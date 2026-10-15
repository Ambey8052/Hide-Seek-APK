package com.example.hideseek.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.hideseek.app
import com.example.hideseek.device.formatRs
import com.example.hideseek.ui.Body
import com.example.hideseek.ui.Chip
import com.example.hideseek.ui.HRow
import com.example.hideseek.ui.HsCard
import com.example.hideseek.ui.HsPage
import com.example.hideseek.ui.theme.Hs

private val MEDALS = listOf("🥇", "🥈", "🥉")

@Composable
fun LeaderboardScreen(onBack: () -> Unit) {
    val profile = LocalContext.current.app.profile
    var tab by remember { mutableStateOf("ranking") }
    val games by profile.history.collectAsState()
    val board = remember(games) { profile.ranking() }

    HsPage("Leaderboard & History", onBack) {
        HRow {
            Chip("🏆 Players you've met", tab == "ranking", { tab = "ranking" })
            Chip("🕑 My games", tab == "games", { tab = "games" })
        }

        HsCard {
            if (tab == "ranking") {
                board.forEachIndexed { i, u ->
                    if (i > 0) HorizontalDivider(color = Hs.Border)
                    HRow(
                        Modifier.fillMaxWidth().background(if (u.isMe) Hs.SurfaceAlt else Color.Transparent).padding(vertical = 6.dp),
                        gap = 12,
                    ) {
                        Text(MEDALS.getOrElse(u.rank - 1) { "${u.rank}" }, color = Hs.Text, fontSize = 22.sp,
                            fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center, modifier = Modifier.width(36.dp))
                        Column(Modifier.weight(1f)) {
                            Body(u.name + if (u.isMe) " (you)" else "", bold = true)
                            Text("${u.gamesWon}/${u.gamesPlayed} won · ${u.catches} catches · ${formatRs(u.totalWonRs)} won",
                                color = Hs.TextFaint, fontSize = 12.sp)
                        }
                        Text("${u.xp} XP", color = Hs.Primary, fontWeight = FontWeight.Black)
                    }
                }
            } else {
                if (games.isEmpty()) Body("No games yet. Go play!", dim = true)
                games.forEachIndexed { i, g ->
                    if (i > 0) HorizontalDivider(color = Hs.Border)
                    val mySide = if (g.role == "police") "police" else "thieves"
                    val won = g.payoutRs > 0 || (g.winnerSide == mySide && (g.role == "police" || g.myStatus == "active"))
                    val outcome = when {
                        g.status == "VOID" -> "Cancelled (refunded)"
                        won -> "Won"
                        else -> "Lost"
                    }
                    HRow(Modifier.fillMaxWidth().padding(vertical = 6.dp), gap = 12) {
                        Text(if (g.role == "police") "👮" else "🦹", fontSize = 22.sp, modifier = Modifier.width(36.dp), textAlign = TextAlign.Center)
                        Column(Modifier.weight(1f)) {
                            Body("$outcome · ${if (g.isFree) "Free" else "${formatRs(g.feeRs)} entry"}", bold = true)
                            Text("${formatTime(g.startedAt)} · Room ${g.code}", color = Hs.TextFaint, fontSize = 12.sp)
                        }
                        if (g.payoutRs > 0) Text("+${formatRs(g.payoutRs)}", color = Hs.Green, fontWeight = FontWeight.Black)
                    }
                }
            }
        }
    }
}
