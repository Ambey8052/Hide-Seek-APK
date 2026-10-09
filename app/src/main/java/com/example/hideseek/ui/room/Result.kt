package com.example.hideseek.ui.room

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.hideseek.data.Phase
import com.example.hideseek.device.formatRs
import com.example.hideseek.device.vibratePattern
import com.example.hideseek.ui.Body
import com.example.hideseek.ui.HRow
import com.example.hideseek.ui.HsButton
import com.example.hideseek.ui.HsCard
import com.example.hideseek.ui.HsScreen
import com.example.hideseek.ui.Label
import com.example.hideseek.ui.Variant
import com.example.hideseek.ui.theme.Hs
import kotlinx.coroutines.launch

/** A thief who was caught or disqualified watches the rest of the game. */
@Composable
fun Spectate(ctx: RoomCtx) {
    val caught = ctx.me.status == "caught"
    val hunting = ctx.room.phase == Phase.HUNTING
    val ends = (if (hunting) ctx.room.huntEndsAt else ctx.room.hideEndsAt) ?: ctx.now
    val center = Modifier.fillMaxWidth()
    HsScreen {
        Text(if (caught) "🔒" else "❌", fontSize = 72.sp, textAlign = TextAlign.Center, modifier = center.padding(top = 24.dp))
        Text(if (caught) "You were caught!" else "You're out", color = Hs.Text, fontSize = 28.sp,
            fontWeight = FontWeight.Black, textAlign = TextAlign.Center, modifier = center)
        if (!caught) ctx.me.dqReason?.let { Body(it, dim = true, center = true, modifier = center) }
        Body("Stay and watch — results appear here when the game ends. Please don't reveal anyone's hiding spot!",
            dim = true, center = true, modifier = center)
        Column(center, horizontalAlignment = Alignment.CenterHorizontally) {
            BigTimer(ends - ctx.now, if (hunting) "Game ends in" else "Hunt starts in", small = true)
        }
        HsCard { PlayerStatusList(ctx.room.players, ctx.room.policeId) }
        EventFeed(ctx.room.events, limit = 8)
    }
}

@Composable
fun Result(ctx: RoomCtx) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val room = ctx.room
    val r = room.result ?: return
    val mine = r.payouts.firstOrNull { it.userId == ctx.userId }
    val iWon = ctx.userId in r.winnerIds
    val voided = room.phase == Phase.VOID

    LaunchedEffect(Unit) {
        if (iWon) context.vibratePattern(0, 80, 80, 80, 80, 300) else context.vibratePattern(0, 300)
    }

    val headline = when {
        voided -> "Game cancelled"
        r.winnerSide == "police" -> "👮 POLICE WINS!"
        r.winnerSide == "thieves" -> "🦹 THIEVES ESCAPE!"
        else -> "No winner"
    }
    val bannerColor = when {
        voided -> Hs.SurfaceAlt
        r.winnerSide == "police" -> Hs.PoliceDark
        else -> Hs.ThiefDark
    }

    HsScreen {
        Column(
            Modifier.fillMaxWidth().background(bannerColor, RoundedCornerShape(22.dp)).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(headline, color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
            Text(r.reason, color = Color(0xFFE2E8F0), fontSize = 15.sp, textAlign = TextAlign.Center)
            if (iWon && !voided) {
                Text("🎉 You won${mine?.let { " ${formatRs(it.rs)}" } ?: ""}!", color = Hs.Primary, fontSize = 20.sp, fontWeight = FontWeight.Black)
            }
            if (voided && r.refunded) Text("Everyone has been refunded in full.", color = Hs.Primary, fontWeight = FontWeight.Bold)
        }

        if (!r.isFree && !voided) {
            HsCard {
                Label("Prize pot ${formatRs(r.potRs)}")
                if (r.payouts.isEmpty()) Body("Entry fees were refunded.", dim = true)
                r.payouts.forEach { p ->
                    HRow(Modifier.fillMaxWidth()) {
                        Body(p.name, bold = p.userId == ctx.userId, modifier = Modifier.weight(1f))
                        Text("+${formatRs(p.rs)}", color = Hs.Green, fontSize = 18.sp, fontWeight = FontWeight.Black)
                    }
                }
                if (r.platformFeeRs > 0) Body("Platform fee ${formatRs(r.platformFeeRs)}", dim = true)
            }
        }
        if (r.isFree && !voided) {
            HsCard { Body("Free game — winners earn bonus XP. Up the stakes with a cash rematch!") }
        }

        HsCard {
            Label("Final standings")
            PlayerStatusList(room.players, room.policeId)
        }

        room.serverSeed?.let { seed ->
            HsCard {
                Label("Fairness proof")
                Text("seal: ${room.seedHash}", color = Hs.TextFaint, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Text("seed: $seed", color = Hs.TextFaint, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Body("sha256(seed) equals the seal shown before the game — the police draw was locked in advance.", dim = true, size = 12)
            }
        }

        if (ctx.isHost) {
            HsButton("🔁 Rematch (same group)", onClick = { scope.launch { runCatching { ctx.game.emit("room:rematch") } } })
        } else {
            Body("Waiting for the host to start a rematch…", dim = true, center = true, modifier = Modifier.fillMaxWidth())
        }
        HsButton("Back to home", onClick = ctx.onLeave, variant = Variant.Ghost)
    }
}
