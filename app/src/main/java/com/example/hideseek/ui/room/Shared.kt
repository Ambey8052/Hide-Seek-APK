package com.example.hideseek.ui.room

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.hideseek.data.GameEvent
import com.example.hideseek.data.Player
import com.example.hideseek.device.BleStatus
import com.example.hideseek.device.GpsState
import com.example.hideseek.device.GpsStatus
import com.example.hideseek.device.formatClock
import com.example.hideseek.ui.Body
import com.example.hideseek.ui.ChipRow
import com.example.hideseek.ui.HsButton
import com.example.hideseek.ui.HsCard
import com.example.hideseek.ui.Label
import com.example.hideseek.ui.Variant
import com.example.hideseek.ui.theme.Hs

@Composable
fun BigTimer(ms: Long, label: String? = null, color: Color = Hs.Text, small: Boolean = false, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        label?.let { Label(it) }
        Text(formatClock(ms), color = color, fontSize = if (small) 40.sp else 64.sp, fontWeight = FontWeight.Black)
    }
}

/** Shown to a thief who has stepped outside the zone. */
@Composable
fun OutOfRangeBanner(ctx: RoomCtx) {
    val deadline = ctx.me.outOfRangeDeadline ?: return
    Column(
        Modifier.fillMaxWidth().background(Hs.Danger, RoundedCornerShape(14.dp)).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("⚠ OUT OF ZONE", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black)
        Text("Get back within ${ctx.room.rules.boundaryM} m of the police!", color = Color.White, fontSize = 14.sp)
        Text(formatClock(deadline - ctx.now), color = Color.White, fontSize = 48.sp, fontWeight = FontWeight.Black)
        Text(
            "Warning ${ctx.me.strikes}/${ctx.room.rules.maxStrikes} · you'll be out when the timer hits zero",
            color = Color.White, fontSize = 13.sp, textAlign = TextAlign.Center,
        )
    }
}

private val HEAT_ORDER = listOf("cold", "warm", "hot", "burning")
private val HEAT_LABEL = mapOf(
    "cold" to "Police is far",
    "warm" to "Police within 50 m",
    "hot" to "Police within 20 m",
    "burning" to "Police within 5 m!",
)

@Composable
fun HeatMeter(heat: String?, dim: Boolean) {
    val level = HEAT_ORDER.indexOf(heat)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HEAT_ORDER.forEachIndexed { i, _ ->
                val c = if (i <= level) Hs.heat[heat] ?: Hs.SurfaceAlt else Hs.SurfaceAlt
                Box(Modifier.weight(1f).height(14.dp).background(if (dim) c.copy(alpha = 0.5f) else c, RoundedCornerShape(7.dp)))
            }
        }
        Text(
            HEAT_LABEL[heat] ?: "Waiting for GPS…",
            color = if (dim) Hs.Stealth else Hs.TextDim,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** The thief's "police within 1 m" green dot. */
@Composable
fun GreenDot(active: Boolean) {
    if (!active) return
    val t = rememberInfiniteTransition(label = "dot")
    val scale by t.animateFloat(1f, 1.25f, infiniteRepeatable(tween(350), RepeatMode.Reverse), label = "scale")
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.padding(16.dp).size(120.dp).scale(scale).background(Hs.Green, CircleShape))
        Text("POLICE IS WITHIN 1 m", color = Hs.Green, fontWeight = FontWeight.Black, fontSize = 16.sp)
    }
}

@Composable
fun GpsNotice(gps: GpsState) {
    val context = LocalContext.current
    when (gps.status) {
        GpsStatus.DENIED -> HsCard(borderColor = Hs.Danger) {
            Label("Location permission needed", color = Hs.Danger)
            Body("The game can't work without your location. Allow it in settings, or you'll be disqualified.")
            HsButton("Open settings", onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                )
            }, variant = Variant.Danger, small = true)
        }
        GpsStatus.OFF -> HsCard(borderColor = Hs.Danger) {
            Body("Turn on location (GPS) on your phone to keep playing.")
            HsButton("Location settings", onClick = {
                context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }, variant = Variant.Danger, small = true)
        }
        else -> Unit
    }
}

@Composable
fun GpsBadge(gps: GpsState, dim: Boolean = false) {
    val text = when (gps.status) {
        GpsStatus.OK -> "GPS ±${(gps.accuracy ?: 0f).toInt()} m"
        GpsStatus.STARTING -> "GPS starting…"
        else -> "GPS off"
    }
    val good = gps.status == GpsStatus.OK && (gps.accuracy ?: 99f) <= 15f
    Text(
        "📡 $text",
        color = if (dim) Hs.Stealth else if (good) Hs.Green else Hs.Warning,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

private val KIND_ICON = mapOf("catch" to "🚨", "dq" to "❌", "pulse" to "📡", "info" to "•", "end" to "🏁")

@Composable
fun EventFeed(events: List<GameEvent>, dim: Boolean = false, limit: Int = 5) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        events.takeLast(limit).reversed().forEach { e ->
            Text("${KIND_ICON[e.kind] ?: "•"} ${e.text}", color = if (dim) Hs.Stealth else Hs.TextDim, fontSize = 13.sp)
        }
    }
}

@Composable
fun PlayerStatusList(players: List<Player>, policeId: String?, dim: Boolean = false) {
    ChipRow {
        players.forEach { p ->
            val icon = when {
                p.userId == policeId -> "👮"
                p.status == "caught" -> "🔒"
                p.status == "dq" -> "❌"
                else -> "🦹"
            }
            Text(
                "$icon ${p.name}${if (!p.online) " (offline)" else ""}",
                color = if (dim) Hs.Stealth else if (p.status == "active") Hs.Text else Hs.TextFaint,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .border(1.dp, if (dim) Hs.Stealth else Hs.Border, RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}

@Composable
fun BleNotice(ble: BleStatus) {
    val context = LocalContext.current
    when (ble) {
        BleStatus.OFF -> HsCard(borderColor = Hs.Warning) {
            Body("Turn on Bluetooth for 1-metre accuracy. Without it the game falls back to GPS (±5 m).")
            HsButton("Bluetooth settings", onClick = {
                context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            }, variant = Variant.Secondary, small = true)
        }
        BleStatus.DENIED -> HsCard(borderColor = Hs.Warning) {
            Body("Allow \"Nearby devices\" so phones can sense each other over Bluetooth (much more accurate than GPS).")
            HsButton("Open settings", onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                )
            }, variant = Variant.Secondary, small = true)
        }
        else -> Unit
    }
}

@Composable
fun BleBadge(ctx: RoomCtx, dim: Boolean = false) {
    val (text, good) = when (ctx.ble) {
        BleStatus.ON -> if (ctx.me.proxSource == "ble") "Bluetooth: locked on (±1–2 m)" to true else "Bluetooth: searching…" to true
        BleStatus.OFF -> "Bluetooth off — GPS only" to false
        BleStatus.DENIED -> "Bluetooth not allowed — GPS only" to false
        BleStatus.UNSUPPORTED -> "No Bluetooth LE — GPS only" to false
        BleStatus.IDLE -> return
    }
    Text(
        "📶 $text",
        color = if (dim) Hs.Stealth else if (good) Hs.Green else Hs.Warning,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}
