package com.example.hideseek.ui.room

import android.widget.Toast
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.hideseek.device.formatClock
import com.example.hideseek.device.stopVibration
import com.example.hideseek.device.vibratePattern
import com.example.hideseek.ui.Body
import com.example.hideseek.ui.HsButton
import com.example.hideseek.ui.HsCard
import com.example.hideseek.ui.HsScreen
import com.example.hideseek.ui.Label
import com.example.hideseek.ui.Variant
import com.example.hideseek.ui.theme.Hs
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

const val QR_PREFIX = "HS:"

@Composable
fun PoliceHunt(ctx: RoomCtx) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val me = ctx.me
    val thieves = ctx.room.thieves
    val active = thieves.count { it.status == "active" }
    val left = (ctx.room.huntEndsAt ?: ctx.now) - ctx.now
    val finalHunt = left <= ctx.room.rules.finalHuntSec * 1000L

    // Keep buzzing while any thief is within range.
    LaunchedEffect(me.closeCount > 0) {
        while (me.closeCount > 0) {
            context.vibratePattern(0, 500, 150, 500)
            delay(2_000)
        }
    }
    DisposableEffect(Unit) { onDispose { context.stopVibration() } }

    val scanner = androidx.activity.compose.rememberLauncherForActivityResult(ScanContract()) { result ->
        val data = result.contents ?: return@rememberLauncherForActivityResult
        if (!data.startsWith(QR_PREFIX)) {
            Toast.makeText(context, "That's not a Hide & Seek catch code", Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            try {
                val ack = ctx.game.emit("catch:scan", JSONObject().put("token", data.removePrefix(QR_PREFIX)))
                context.vibratePattern(0, 80, 60, 80)
                Toast.makeText(context, "Caught ${ack.optString("caught")}!", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(context, e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    HsScreen {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Label("Thieves left")
                Text("$active/${thieves.size}", color = Hs.Text, fontSize = 28.sp, fontWeight = FontWeight.Black)
            }
            BigTimer(left, small = true, color = if (finalHunt) Hs.Danger else Hs.Text)
            Column(horizontalAlignment = Alignment.End) {
                Label("Caught")
                Text("${me.catches}", color = Hs.Text, fontSize = 28.sp, fontWeight = FontWeight.Black)
            }
        }
        if (finalHunt) {
            Text("🔥 FINAL HUNT — radar range boosted to 10 m", color = Hs.Danger, fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }

        Radar(
            alert = me.closeCount > 0,
            label = when {
                me.closeCount > 0 -> "${me.closeCount} THIEF${if (me.closeCount > 1) "S" else ""} RIGHT HERE!"
                finalHunt -> "Final hunt · 10 m range"
                me.proxSource == "ble" -> "Bluetooth radar on"
                else -> "Scanning…"
            },
        )

        HsButton("📷 Scan thief's catch code", onClick = {
            scanner.launch(
                ScanOptions()
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt("Point at the catch code on the thief's phone")
                    .setBeepEnabled(false)
                    .setOrientationLocked(true),
            )
        }, variant = Variant.Police)
        Body("Or hold the CAUGHT button on the thief's own phone.", dim = true, size = 13, center = true, modifier = Modifier.fillMaxWidth())

        HsCard {
            Label("Radar pulse")
            val pulse = me.pulse
            if (pulse != null && pulse.rings.size >= 3) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("≤ 25 m", "≤ 50 m", "≤ 100 m").forEachIndexed { i, label ->
                        Column(
                            Modifier.weight(1f).background(Hs.SurfaceAlt, RoundedCornerShape(8.dp)).padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text("${pulse.rings[i]}", color = Hs.Police, fontSize = 26.sp, fontWeight = FontWeight.Black)
                            Text(label, color = Hs.TextDim, fontSize = 12.sp)
                        }
                    }
                }
            } else {
                Body("No pulse yet.", dim = true)
            }
            me.nextPulseAt?.let { Body("Next pulse in ${formatClock(it - ctx.now)}", dim = true) }
        }

        PlayerStatusList(ctx.room.players, ctx.room.policeId)
        EventFeed(ctx.room.events)
        GpsNotice(ctx.gps)
        BleNotice(ctx.ble)
        GpsBadge(ctx.gps)
        BleBadge(ctx)
    }
}

/** Police radar: rotating sweep, glows red when a thief is within range. */
@Composable
fun Radar(alert: Boolean, label: String) {
    val tint = if (alert) Hs.Danger else Hs.Green
    val transition = rememberInfiniteTransition(label = "radar")
    val angle by transition.animateFloat(
        0f, 360f, infiniteRepeatable(tween(if (alert) 900 else 2600, easing = LinearEasing)), label = "sweep",
    )
    val glow by transition.animateFloat(0.15f, 0.6f, infiniteRepeatable(tween(450), RepeatMode.Reverse), label = "glow")

    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(Modifier.size(280.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2 - 4f
                if (alert) drawCircle(Hs.Danger.copy(alpha = glow), radius = r)
                listOf(1f, 0.66f, 0.33f).forEach {
                    drawCircle(tint.copy(alpha = 0.45f), radius = r * it, style = Stroke(width = 3f))
                }
                drawLine(tint.copy(alpha = 0.2f), Offset(center.x, 4f), Offset(center.x, size.height - 4f))
                drawLine(tint.copy(alpha = 0.2f), Offset(4f, center.y), Offset(size.width - 4f, center.y))
                rotate(angle) {
                    drawArc(
                        brush = Brush.sweepGradient(
                            0f to Color.Transparent, 0.75f to Color.Transparent, 1f to tint.copy(alpha = 0.5f),
                            center = center,
                        ),
                        startAngle = -90f,
                        sweepAngle = 90f,
                        useCenter = true,
                        topLeft = Offset(center.x - r, center.y - r),
                        size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("👮", fontSize = 40.sp)
                Text(label, color = tint, fontSize = 15.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 50.dp))
            }
        }
    }
}
