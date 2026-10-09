package com.example.hideseek.ui.room

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.hideseek.data.Player
import com.example.hideseek.device.formatRs
import com.example.hideseek.ui.Body
import com.example.hideseek.ui.Chip
import com.example.hideseek.ui.ChipRow
import com.example.hideseek.ui.Dot
import com.example.hideseek.ui.ErrorText
import com.example.hideseek.ui.HRow
import com.example.hideseek.ui.HsButton
import com.example.hideseek.ui.HsCard
import com.example.hideseek.ui.HsScreen
import com.example.hideseek.ui.Label
import com.example.hideseek.ui.Variant
import com.example.hideseek.ui.screens.FEES
import com.example.hideseek.ui.screens.shareText
import com.example.hideseek.ui.theme.Hs
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.security.SecureRandom
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.hideseek.device.BLE_PERMISSIONS

/** Random per-player seed mixed into the police draw (see How to play → Fair play). */
private fun newClientSeed(): String =
    ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

@Composable
fun Lobby(ctx: RoomCtx) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val room = ctx.room
    val me = room.players.firstOrNull { it.userId == ctx.userId }
    val allReady = room.players.all { it.ready && it.online }
    val enough = room.players.size >= room.rules.minPlayers
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var kicking by remember { mutableStateOf<Player?>(null) }

    fun act(key: String, event: String, payload: JSONObject = JSONObject()) = scope.launch {
        busy = key
        error = null
        try {
            ctx.game.emit(event, payload)
        } catch (e: Exception) {
            error = e.message
        } finally {
            busy = null
        }
    }

    fun setting(payload: JSONObject) = act("settings", "room:settings", payload)

    // Ask for location + Bluetooth now, in the lobby, so no system dialog pops up mid-game.
    val needed = (arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION) + BLE_PERMISSIONS)
        .filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    HsScreen {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Label("Room code")
            Text(room.code, color = Hs.Primary, fontSize = 48.sp, fontWeight = FontWeight.Black, letterSpacing = 8.sp)
            HRow {
                HsButton("📤 Share code", onClick = {
                    context.shareText("Join my Chor Sipahi hide & seek game! Open the app and enter room code ${room.code}")
                }, variant = Variant.Secondary, small = true)
                HsButton("👥 Invite friends", onClick = ctx.openFriends, variant = Variant.Secondary, small = true)
            }
        }

        HsCard {
            Label("Game terms")
            Text(
                if (room.isFree) "🎁 Free game" else "💰 ${formatRs(room.feeRs)} per player",
                color = Hs.Text, fontSize = 22.sp, fontWeight = FontWeight.Black,
            )
            if (!room.isFree) {
                Body(
                    "Prize pot: ${formatRs(room.feeRs * room.players.size)} (${room.players.size} × ${formatRs(room.feeRs)})",
                    dim = true,
                )
            }
            Body(
                "${room.rules.gameSec / 60} min hunt · ${room.rules.boundaryM} m zone " +
                    (if (room.boundaryMode == "police") "around the police" else "around the start point") +
                    (if (room.heatMeter) " · thief heat meter on" else ""),
                dim = true,
            )

            if (ctx.isHost) {
                ChipRow {
                    Chip("Cash", !room.isFree, { setting(JSONObject().put("isFree", false).put("feeRs", room.feeRs.toInt().coerceAtLeast(10))) })
                    Chip("Free", room.isFree, { setting(JSONObject().put("isFree", true)) })
                }
                if (!room.isFree) {
                    ChipRow { FEES.forEach { f -> Chip("₹$f", room.feeRs.toInt() == f, { setting(JSONObject().put("feeRs", f)) }) } }
                }
                ChipRow {
                    Chip("Zone follows police", room.boundaryMode == "police", { setting(JSONObject().put("boundaryMode", "police")) })
                    Chip("Fixed arena", room.boundaryMode == "arena", { setting(JSONObject().put("boundaryMode", "arena")) })
                    Chip(
                        if (room.heatMeter) "Heat meter: on" else "Heat meter: off",
                        room.heatMeter,
                        { setting(JSONObject().put("heatMeter", !room.heatMeter)) },
                    )
                }
                Body("Changing terms un-readies everyone so all players agree again.", dim = true, size = 12)
            }
        }

        HsCard {
            Label("Players ${room.players.size}/${room.rules.maxPlayers}")
            room.players.forEach { p ->
                HorizontalDivider(color = Hs.Border)
                HRow(Modifier.fillMaxWidth().heightIn(min = 44.dp), gap = 12) {
                    Dot(if (p.online) Hs.Green else Hs.TextFaint)
                    Text(
                        p.name + (if (p.isHost) " 👑" else "") + (if (p.userId == ctx.userId) " (you)" else ""),
                        color = Hs.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
                    )
                    Text(if (p.ready) "READY" else "waiting", color = if (p.ready) Hs.Green else Hs.TextFaint, fontWeight = FontWeight.ExtraBold)
                    if (ctx.isHost && p.userId != ctx.userId) {
                        Text("✕", color = Hs.TextFaint, fontSize = 18.sp, modifier = Modifier.clickable { kicking = p }.padding(8.dp))
                    }
                }
            }
        }

        ErrorText(error)

        val ready = me?.ready == true
        HsButton(
            when {
                ready -> "✓ I'm in — tap to cancel"
                room.isFree -> "I'm in (free game)"
                else -> "I'm in — pay ${formatRs(room.feeRs)} at start"
            },
            onClick = {
                if (!ready && needed.isNotEmpty()) permissions.launch(needed.toTypedArray())
                act("ready", "room:ready", JSONObject().put("ready", !ready).put("clientSeed", newClientSeed()))
            },
            variant = if (ready) Variant.Secondary else Variant.Primary,
            loading = busy == "ready",
        )
        if (ctx.isHost) {
            HsButton(
                when {
                    !enough -> "Need ${room.rules.minPlayers}+ players"
                    !allReady -> "Waiting for everyone…"
                    else -> "🃏 Deal the cards!"
                },
                onClick = { act("deal", "room:deal") },
                variant = Variant.Police,
                enabled = enough && allReady,
                loading = busy == "deal",
            )
        } else {
            Body("The host deals the cards when everyone is ready.", dim = true, center = true, modifier = Modifier.fillMaxWidth())
        }

        Column(
            Modifier.fillMaxWidth().background(Hs.Surface, RoundedCornerShape(8.dp)).padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("🔏 Fairness seal: ${room.seedHash.take(16)}…", color = Hs.TextFaint, fontSize = 11.sp)
            Text(
                "The police draw is locked to this seal and can be verified after the game.",
                color = Hs.TextFaint, fontSize = 11.sp, textAlign = TextAlign.Center,
            )
        }

        HsButton("Leave room", onClick = ctx.onLeave, variant = Variant.Ghost, small = true, modifier = Modifier.fillMaxWidth())
    }

    kicking?.let { p ->
        AlertDialog(
            onDismissRequest = { kicking = null },
            title = { Text("Remove ${p.name}?") },
            confirmButton = {
                TextButton(onClick = {
                    kicking = null
                    act("kick", "room:kick", JSONObject().put("userId", p.userId))
                }) { Text("Remove", color = Hs.Danger) }
            },
            dismissButton = { TextButton(onClick = { kicking = null }) { Text("Cancel") } },
        )
    }
}
