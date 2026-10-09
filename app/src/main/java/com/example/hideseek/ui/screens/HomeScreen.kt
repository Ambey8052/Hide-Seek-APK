package com.example.hideseek.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.ui.res.painterResource
import com.example.hideseek.Nav
import com.example.hideseek.R
import com.example.hideseek.ui.ServerSettingsCard
import com.example.hideseek.Screen
import com.example.hideseek.app
import com.example.hideseek.data.Wallet
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
import com.example.hideseek.ui.HsTextField
import com.example.hideseek.ui.Label
import com.example.hideseek.ui.Title
import com.example.hideseek.ui.theme.Hs
import kotlinx.coroutines.launch
import org.json.JSONObject

val FEES = listOf(10, 20, 30, 50, 75, 100)

@Composable
fun HomeScreen(nav: Nav) {
    val app = LocalContext.current.app
    val user by app.session.user.collectAsState()
    val room by app.game.room.collectAsState()
    val connected by app.game.connected.collectAsState()
    val scope = rememberCoroutineScope()
    var wallet by remember { mutableStateOf<Wallet?>(null) }
    var free by remember { mutableStateOf(false) }
    var fee by remember { mutableStateOf(20) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showServer by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf<String?>(null) }
    var freeUsedDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            wallet = Wallet.parse(app.session.get("/api/wallet") as JSONObject)
            app.session.refreshUser()
        } catch (e: Exception) {
            error = e.message
        }
    }

    fun act(key: String, event: String, payload: JSONObject) = scope.launch {
        busy = key
        error = null
        try {
            app.game.emit(event, payload)
            nav.push(Screen.Room)
        } catch (e: Exception) {
            error = e.message
        } finally {
            busy = null
        }
    }

    HsScreen {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            HRow(gap = 12) {
                Image(painterResource(R.drawable.logo), contentDescription = null, modifier = Modifier.size(52.dp))
                Column(Modifier.clickable { editName = "" }) {
                    Body("Namaste,", dim = true)
                    Title((user?.name ?: "Player") + " ✎")
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "LVL ${user?.level ?: 1}",
                    color = Hs.PrimaryText,
                    fontWeight = FontWeight.Black,
                    fontSize = 12.sp,
                    modifier = Modifier.background(Hs.Primary, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
                )
                HRow(gap = 6) {
                    Dot(if (connected) Hs.Green else Hs.Danger, 8)
                    Text(if (connected) "Online" else "Connecting…", color = Hs.TextDim, fontSize = 12.sp)
                }
            }
        }

        room?.let { r ->
            Text(
                "You're in room ${r.code} · tap to return",
                color = Hs.PrimaryText,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().background(Hs.Primary, RoundedCornerShape(14.dp))
                    .clickable { nav.push(Screen.Room) }.padding(12.dp),
            )
        }

        HsCard(Modifier.clickable { nav.push(Screen.Wallet) }) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Label("Wallet")
                    Text(wallet?.let { formatRs(it.balanceRs) } ?: "—", color = Hs.Text, fontSize = 30.sp, fontWeight = FontWeight.Black)
                    wallet?.let {
                        Text(
                            if (it.freeGameAvailable) "🎁 1 free game available today" else "Free game used today",
                            color = if (it.freeGameAvailable) Hs.Green else Hs.TextFaint,
                            fontSize = 13.sp,
                        )
                    }
                }
                Text("+ Add", color = Hs.Primary, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
            }
        }

        HsCard {
            Label("Create a game")
            HRow {
                Chip("💰 Cash game", !free, { free = false })
                Chip("🎁 Free game", free, {
                    if (wallet?.freeGameAvailable == true) free = true else freeUsedDialog = true
                })
            }
            if (!free) {
                Body("Entry fee per player (everyone pays the same)", dim = true)
                ChipRow { FEES.forEach { f -> Chip("₹$f", fee == f, { fee = f }) } }
            } else {
                Body("No entry fee, no cash prize — play for XP and bragging rights. Uses today's free game for every player.", dim = true)
            }
            HsButton("Create room", onClick = {
                act("create", "room:create", JSONObject().put("feeRs", fee).put("isFree", free))
            }, loading = busy == "create", enabled = connected)
        }

        HsCard {
            Label("Join friends")
            HRow {
                HsTextField(
                    code, { code = it.uppercase().filter(Char::isLetterOrDigit) }, "ROOM CODE", Modifier.weight(1f), maxLength = 6,
                    textStyle = TextStyle(fontSize = 18.sp, letterSpacing = 4.sp, fontWeight = FontWeight.ExtraBold),
                )
                HsButton("Join", onClick = { act("join", "room:join", JSONObject().put("code", code)) },
                    modifier = Modifier.width(96.dp), loading = busy == "join", enabled = code.length == 6 && connected, small = true)
            }
        }
        ErrorText(error)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile("👥", "Friends", Modifier.weight(1f)) { nav.push(Screen.Friends) }
            Tile("🏆", "Leaderboard", Modifier.weight(1f)) { nav.push(Screen.Leaderboard) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile("📖", "How to play", Modifier.weight(1f)) { nav.push(Screen.HowToPlay) }
            Tile("⚙", "Server", Modifier.weight(1f)) { showServer = !showServer }
        }
        if (showServer) ServerSettingsCard()
        user?.let {
            Body(
                "Played ${it.gamesPlayed} · Won ${it.gamesWon} · Catches ${it.catches} · ${it.xp} XP · Player code ${it.code}",
                dim = true, size = 12, center = true, modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    editName?.let { draft ->
        AlertDialog(
            onDismissRequest = { editName = null },
            title = { Text("Your name") },
            text = { HsTextField(draft, { editName = it }, user?.name ?: "What friends call you", maxLength = 20) },
            confirmButton = {
                TextButton(onClick = {
                    val name = draft.trim()
                    editName = null
                    scope.launch {
                        try {
                            app.session.updateName(name)
                        } catch (e: Exception) {
                            error = e.message
                        }
                    }
                }, enabled = draft.trim().length >= 2) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editName = null }) { Text("Cancel") } },
        )
    }
    if (freeUsedDialog) {
        AlertDialog(
            onDismissRequest = { freeUsedDialog = false },
            title = { Text("Free game used") },
            text = { Text("Everyone gets 1 free game per day. Try a cash game or come back tomorrow.") },
            confirmButton = { TextButton(onClick = { freeUsedDialog = false }) { Text("OK") } },
        )
    }
}

@Composable
private fun Tile(emoji: String, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .background(Hs.Surface, RoundedCornerShape(14.dp))
            .border(1.dp, Hs.Border, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(emoji, fontSize = 28.sp)
        Text(label, color = Hs.Text, fontWeight = FontWeight.Bold)
    }
}
