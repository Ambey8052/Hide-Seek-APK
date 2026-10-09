package com.example.hideseek.ui.room

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.hideseek.device.ShakeDetector
import com.example.hideseek.device.buzz
import com.example.hideseek.device.formatClock
import com.example.hideseek.device.vibratePattern
import com.example.hideseek.ui.HsButton
import com.example.hideseek.ui.Variant
import com.example.hideseek.ui.theme.Hs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAP_FALLBACK_AFTER_MS = 8_000L

private data class Face(val emoji: String, val title: String, val subtitle: String, val bg: Color, val accent: Color, val hint: String)

private val FACES = mapOf(
    "police" to Face("👮", "SIPAHI", "POLICE", Hs.PoliceDark, Hs.Police,
        "Find and catch every thief before time runs out. The pot is yours if you catch them all."),
    "thief" to Face("🦹", "CHOR", "THIEF", Hs.ThiefDark, Hs.Thief,
        "Hide within 100 m of the police. Survive until time runs out to share the pot."),
)

/** Phase DEALING: full-screen face-down card, shake to flip. */
@Composable
fun Deal(ctx: RoomCtx) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val role = ctx.me.role
    var sent by remember { mutableStateOf(false) }
    var showTap by remember { mutableStateOf(false) }

    fun flip() {
        if (sent) return
        sent = true
        context.buzz(80)
        scope.launch {
            try {
                ctx.game.emit("card:flip")
            } catch (e: Exception) {
                sent = false
            }
        }
    }

    ShakeDetector(enabled = role == null && !sent) { flip() }
    LaunchedEffect(Unit) {
        delay(TAP_FALLBACK_AFTER_MS)
        showTap = true
    }
    LaunchedEffect(role) {
        if (role != null) context.vibratePattern(0, 60, 80, 160)
    }

    val flipped = ctx.room.players.count { it.flipped }
    Column(Modifier.fillMaxSize().background(Hs.Bg).safeDrawingPadding().padding(12.dp)) {
        FlipCard(role, Modifier.weight(1f))
        Column(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (role != null) {
                Text("Waiting for others to shake… $flipped/${ctx.room.players.size}", color = Hs.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            } else if (showTap) {
                HsButton("Shake not working? Tap to flip", onClick = ::flip, variant = Variant.Secondary, small = true)
            }
            ctx.room.flipDeadline?.let {
                Text("Cards flip automatically in ${formatClock(it - ctx.now)}", color = Hs.TextFaint, fontSize = 12.sp)
            }
        }
    }
}

/** Full-height playing card. Shows its back until [role] arrives, then flips over. */
@Composable
fun FlipCard(role: String?, modifier: Modifier = Modifier) {
    val rotation = remember { Animatable(if (role != null) 180f else 0f) }
    LaunchedEffect(role) {
        if (role != null) rotation.animateTo(180f, tween(900, easing = EaseOutBack))
    }
    // Little shake hint while waiting.
    val wobble by rememberInfiniteTransition(label = "wobble").animateFloat(
        initialValue = 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = 1800
                0f at 0
                4f at 90
                -4f at 270
                0f at 360
            },
            RepeatMode.Restart,
            StartOffset(0),
        ),
        label = "wobbleAngle",
    )

    val face = role?.let { FACES[it] }
    val showFront = rotation.value > 90f && face != null
    Box(
        modifier
            .fillMaxSize()
            .graphicsLayer {
                rotationY = rotation.value
                rotationZ = if (role == null) wobble else 0f
                cameraDistance = 14f * density
            },
    ) {
        if (showFront) {
            // Counter-rotate so the face isn't mirrored.
            CardFace(face!!, Modifier.graphicsLayer { rotationY = 180f })
        } else {
            CardBack()
        }
    }
}

@Composable
private fun CardBack() {
    Box(
        Modifier.fillMaxSize()
            .background(Hs.CardBack, RoundedCornerShape(22.dp))
            .border(3.dp, Hs.Primary, RoundedCornerShape(22.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.SpaceEvenly) {
            repeat(6) { row ->
                Text(
                    if (row % 2 == 0) "★  ?  ★  ?" else "?  ★  ?  ★",
                    color = Hs.Primary.copy(alpha = 0.12f),
                    fontSize = 40.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("📳", fontSize = 72.sp)
            Text("SHAKE YOUR PHONE", color = Hs.Primary, fontSize = 26.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            Text("to flip your card", color = Hs.Text, fontSize = 16.sp)
        }
    }
}

@Composable
private fun CardFace(face: Face, modifier: Modifier) {
    Box(
        modifier.fillMaxSize()
            .background(face.bg, RoundedCornerShape(22.dp))
            .border(3.dp, face.accent, RoundedCornerShape(22.dp))
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(face.emoji, fontSize = 30.sp, modifier = Modifier.align(Alignment.TopStart))
        Text(face.emoji, fontSize = 30.sp, modifier = Modifier.align(Alignment.BottomEnd).graphicsLayer { rotationZ = 180f })
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(face.emoji, fontSize = 110.sp)
            Text(face.title, color = Color.White, fontSize = 56.sp, fontWeight = FontWeight.Black, letterSpacing = 6.sp)
            Text(face.subtitle, color = face.accent, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 8.sp)
            Text(face.hint, color = Color(0xFFE2E8F0), fontSize = 16.sp, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 24.dp), lineHeight = 23.sp)
        }
    }
}
