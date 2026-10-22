package com.example.hideseek.ui.screens

import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.hideseek.Nav
import com.example.hideseek.R
import com.example.hideseek.Screen
import com.example.hideseek.app
import com.example.hideseek.data.NearbyRoom
import com.example.hideseek.device.formatRs
import com.example.hideseek.net.NEARBY_PERMISSIONS
import com.example.hideseek.net.missingNearbyPermissions
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
import com.example.hideseek.ui.Variant
import com.example.hideseek.ui.theme.Hs
import kotlinx.coroutines.launch

val FEES = listOf(10, 20, 30, 50, 75, 100)

private fun Context.missingNearbyPermissions() = missingNearbyPermissions(this)

@Composable
fun HomeScreen(nav: Nav) {
    val context = LocalContext.current
    val app = context.app
    val game = app.game
    val user by app.profile.user.collectAsState()
    val wallet by app.profile.wallet.collectAsState()
    val room by game.room.collectAsState()
    val nearby by game.nearby.collectAsState()
    val scanError by game.scanError.collectAsState()
    val scope = rememberCoroutineScope()
    var free by remember { mutableStateOf(true) }
    var fee by remember { mutableStateOf(20) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var editName by remember { mutableStateOf<String?>(null) }
    var granted by remember { mutableStateOf(context.missingNearbyPermissions().isEmpty()) }
    var afterGrant by remember { mutableStateOf<(() -> Unit)?>(null) }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = context.missingNearbyPermissions().isEmpty()
        if (granted) afterGrant?.invoke()
        else error = "Allow \"Nearby devices\" and \"Location\" so phones can find each other over Bluetooth / Wi-Fi."
        afterGrant = null
    }

    /** Run [block] once Bluetooth / Wi-Fi / location permissions are granted. */
    fun withPermissions(block: () -> Unit) {
        if (context.missingNearbyPermissions().isEmpty()) {
            granted = true
            block()
        } else {
            afterGrant = block
            permissions.launch(NEARBY_PERMISSIONS)
        }
    }

    // Look for rooms nearby the whole time Home is on screen.
    DisposableEffect(granted) {
        if (granted) game.startScan()
        onDispose { game.stopScan() }
    }

    fun create() = withPermissions {
        error = null
        if (!free && wallet.balanceRs < fee) {
            error = "Add test money to your wallet first: entry is ₹$fee"
            return@withPermissions
        }
        game.hostRoom(fee.toDouble(), free)
        nav.push(Screen.Room)
    }

    fun join(block: suspend () -> Unit, key: String) = withPermissions {
        scope.launch {
            busy = key
            error = null
            try {
                block()
                nav.push(Screen.Room)
            } catch (e: Exception) {
                error = e.message
            } finally {
                busy = null
            }
        }
    }

    HsScreen {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            HRow(gap = 12) {
                Image(painterResource(R.drawable.logo), contentDescription = null, modifier = Modifier.size(52.dp))
                Column(Modifier.clickable { editName = user.name }) {
                    Body("Namaste,", dim = true)
                    Title(user.name + " ✎")
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "LVL ${user.level}",
                    color = Hs.PrimaryText,
                    fontWeight = FontWeight.Black,
                    fontSize = 12.sp,
                    modifier = Modifier.background(Hs.Primary, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
                )
                HRow(gap = 6) {
                    Dot(Hs.Green, 8)
                    Text("Offline play", color = Hs.TextDim, fontSize = 12.sp)
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

        HsCard {
            Label("Host a game on this phone")
            Body("Your phone becomes the game host. Friends nearby join over Bluetooth / Wi-Fi — no internet needed.", dim = true, size = 13)
            HRow {
                Chip("🎁 Free game", free, { free = true })
                Chip("💰 Test-money game", !free, { free = false })
            }
            if (!free) {
                Body("Entry fee per player, paid from each phone's test wallet (${formatRs(wallet.balanceRs)} here)", dim = true)
                ChipRow { FEES.forEach { f -> Chip("₹$f", fee == f, { fee = f }) } }
            }
            HsButton("Create room", onClick = ::create)
        }

        HsCard {
            Label("Join a game nearby")
            if (!granted) {
                Body("Allow Bluetooth / Wi-Fi access to see games hosted on phones around you.", dim = true, size = 13)
                HsButton("Allow & search", onClick = { withPermissions { } }, variant = Variant.Secondary, small = true)
            } else {
                if (nearby.isEmpty()) {
                    HRow(gap = 10) {
                        CircularProgressIndicator(color = Hs.Primary, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        Body("Searching for games nearby…", dim = true, size = 13)
                    }
                    Body("Keep Bluetooth, Wi-Fi and Location on, and stay within ~30 m of the host.", dim = true, size = 12)
                }
                nearby.forEach { r -> NearbyRoomRow(r, busy == r.endpointId) { join({ game.join(r) }, r.endpointId) } }
                ErrorText(scanError)
            }
            HorizontalDivider(color = Hs.Border)
            HRow {
                HsTextField(
                    code, { code = it.uppercase().filter(Char::isLetterOrDigit) }, "ROOM CODE", Modifier.weight(1f), maxLength = 6,
                    textStyle = TextStyle(fontSize = 18.sp, letterSpacing = 4.sp, fontWeight = FontWeight.ExtraBold),
                )
                HsButton("Join", onClick = { join({ game.join(code) }, "code") },
                    modifier = Modifier.width(96.dp), loading = busy == "code", enabled = code.length == 6, small = true)
            }
        }
        ErrorText(error)

        HsCard(Modifier.clickable { nav.push(Screen.Wallet) }) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Label("Test wallet")
                    Text(formatRs(wallet.balanceRs), color = Hs.Text, fontSize = 26.sp, fontWeight = FontWeight.Black)
                    Text("Play money for testing — no real payments", color = Hs.TextFaint, fontSize = 12.sp)
                }
                Text("+ Add", color = Hs.Primary, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile("🏆", "Leaderboard", Modifier.weight(1f)) { nav.push(Screen.Leaderboard) }
            Tile("📖", "How to play", Modifier.weight(1f)) { nav.push(Screen.HowToPlay) }
        }
        Body(
            "Played ${user.gamesPlayed} · Won ${user.gamesWon} · Catches ${user.catches} · ${user.xp} XP",
            dim = true, size = 12, center = true, modifier = Modifier.fillMaxWidth(),
        )
    }

    editName?.let { draft ->
        AlertDialog(
            onDismissRequest = { editName = null },
            title = { Text("Your name") },
            text = { HsTextField(draft, { editName = it }, "What friends call you", maxLength = 20) },
            confirmButton = {
                TextButton(onClick = {
                    editName = null
                    runCatching { app.profile.updateName(draft) }.onFailure { error = it.message }
                }, enabled = draft.trim().length >= 2) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editName = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun NearbyRoomRow(room: NearbyRoom, loading: Boolean, onJoin: () -> Unit) {
    HRow(
        Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .background(Hs.SurfaceAlt, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        gap = 12,
    ) {
        Text("📡", fontSize = 22.sp)
        Column(Modifier.weight(1f)) {
            Text(room.hostName, color = Hs.Text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text("Room ${room.code}", color = Hs.TextFaint, fontSize = 12.sp, letterSpacing = 1.sp)
        }
        HsButton("Join", onClick = onJoin, modifier = Modifier.width(88.dp), loading = loading, small = true)
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
