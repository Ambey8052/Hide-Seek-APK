package com.example.hideseek.ui.room

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.hideseek.device.buzz
import com.example.hideseek.device.qrBitmap
import com.example.hideseek.ui.HsButton
import com.example.hideseek.ui.HsScreen
import com.example.hideseek.ui.Label
import com.example.hideseek.ui.Variant
import com.example.hideseek.ui.theme.Hs
import kotlinx.coroutines.launch

private const val HOLD_MS = 1_500

@Composable
fun ThiefHunt(ctx: RoomCtx) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var stealth by remember { mutableStateOf(false) }
    var showQr by remember { mutableStateOf(false) }
    var catchError by remember { mutableStateOf<String?>(null) }
    val me = ctx.me
    val room = ctx.room
    val thieves = room.thieves
    val active = thieves.count { it.status == "active" }
    val left = (room.huntEndsAt ?: ctx.now) - ctx.now
    val finalHunt = left <= room.rules.finalHuntSec * 1000L
    val dim = stealth
    val text = if (dim) Hs.Stealth else Hs.Text

    // One short buzz when the out-of-zone warning starts (thieves are otherwise silent).
    val out = me.outOfRangeDeadline != null
    LaunchedEffect(out) { if (out) context.buzz(400) }

    HsScreen(background = if (dim) Color.Black else Hs.Bg) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Label("Thieves free", color = if (dim) Hs.Stealth else Hs.TextDim)
                Text("$active/${thieves.size}", color = text, fontSize = 28.sp, fontWeight = FontWeight.Black)
            }
            BigTimer(left, small = true, color = if (dim) Hs.Stealth else if (finalHunt) Hs.Danger else Hs.Text)
            Text(
                if (stealth) "🌙 Stealth ON" else "🌙 Stealth",
                color = text,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 12.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .border(1.dp, if (dim) Hs.Stealth else Hs.Border, RoundedCornerShape(50))
                    .clickable { stealth = !stealth }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }

        Text(
            "Hiding from 👮 ${room.police?.name ?: ""} · stay within ${room.rules.boundaryM} m" +
                if (finalHunt) " · FINAL 2 MINUTES!" else "",
            color = if (dim) Hs.Stealth else Hs.TextDim,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        OutOfRangeBanner(ctx)
        GreenDot(me.danger)
        if (room.heatMeter) HeatMeter(me.heat, dim)

        val token = me.qrToken
        if (showQr && token != null) {
            val bitmap = remember(token) { qrBitmap(QR_PREFIX + token) }
            Column(
                Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(14.dp)).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Image(bitmap, contentDescription = "Catch code", modifier = Modifier.size(220.dp))
                Text("Police scans this to catch you · code changes every 30 s", color = Color(0xFF333333), fontSize = 12.sp,
                    textAlign = TextAlign.Center)
            }
        }
        HsButton(
            if (showQr) "Hide catch code" else "Show catch code (when caught)",
            onClick = { showQr = !showQr },
            variant = Variant.Secondary,
            small = true,
            modifier = Modifier.fillMaxWidth(),
        )

        HoldToCatch(dim) {
            scope.launch {
                try {
                    ctx.game.emit("catch:tap")
                } catch (e: Exception) {
                    catchError = e.message
                }
            }
        }
        catchError?.let { Text(it, color = Hs.Danger, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }

        EventFeed(room.events, dim, limit = 3)
        GpsNotice(ctx.gps)
        BleNotice(ctx.ble)
        GpsBadge(ctx.gps, dim)
        BleBadge(ctx, dim)
    }
}

/** Big button the POLICE presses and holds on the thief's phone to confirm a catch. */
@Composable
private fun HoldToCatch(dim: Boolean, onCaught: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }
    val caught by rememberUpdatedState(onCaught)
    val shape = RoundedCornerShape(22.dp)

    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 110.dp)
            .clip(shape)
            .border(2.dp, if (dim) Hs.Stealth else Hs.Thief, shape)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    context.buzz(30)
                    val job = scope.launch {
                        progress.animateTo(1f, tween(HOLD_MS, easing = LinearEasing))
                        context.buzz(200)
                        caught()
                    }
                    tryAwaitRelease()
                    if (progress.value < 1f) {
                        job.cancel()
                        scope.launch { progress.animateTo(0f, tween(150)) }
                    } else {
                        scope.launch { progress.snapTo(0f) }
                    }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(progress.value)
                .background(Hs.Thief),
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
            Text("🚨 CAUGHT", color = if (dim) Hs.Stealth else Hs.Text, fontSize = 30.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
            Text("Police: press & hold to catch this thief", color = if (dim) Hs.Stealth else Hs.TextDim, fontSize = 13.sp)
        }
    }
}
