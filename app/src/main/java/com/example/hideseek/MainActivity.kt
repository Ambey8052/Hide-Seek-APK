package com.example.hideseek

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.hideseek.data.Invite
import com.example.hideseek.device.formatRs
import com.example.hideseek.ui.room.RoomScreen
import com.example.hideseek.ui.screens.FriendsScreen
import com.example.hideseek.ui.screens.HomeScreen
import com.example.hideseek.ui.screens.HowToPlayScreen
import com.example.hideseek.ui.screens.LeaderboardScreen
import com.example.hideseek.ui.screens.WalletScreen
import com.example.hideseek.ui.ErrorText
import com.example.hideseek.ui.HsButton
import com.example.hideseek.ui.HsScreen
import com.example.hideseek.ui.ServerSettingsCard
import com.example.hideseek.ui.theme.HideSeekTheme
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.hideseek.ui.theme.Hs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HideSeekTheme { HideSeekRoot() }
        }
    }
}

enum class Screen { Splash, Home, Room, Wallet, Friends, Leaderboard, HowToPlay }

/** Minimal back stack; the game only has a handful of screens. */
class Nav {
    var stack by mutableStateOf(listOf(Screen.Splash))
        private set
    val current get() = stack.last()
    fun push(s: Screen) { if (current != s) stack = stack + s }
    fun reset(s: Screen) { stack = listOf(s) }
    fun pop() { if (stack.size > 1) stack = stack.dropLast(1) }
}

@Composable
fun HideSeekRoot() {
    val context = LocalContext.current
    val session = context.app.session
    val game = context.app.game
    val nav = remember { Nav() }
    val token by session.token.collectAsState()
    val scope = rememberCoroutineScope()
    var invite by remember { mutableStateOf<Invite?>(null) }

    // Token gone (server reset, server address changed) -> splash signs in again as a guest.
    LaunchedEffect(token) {
        if (token == null && nav.current != Screen.Splash) nav.reset(Screen.Splash)
    }

    LaunchedEffect(Unit) {
        game.invites.collect { invite = it }
    }

    BackHandler(enabled = nav.stack.size > 1 && nav.current != Screen.Room) { nav.pop() }

    when (nav.current) {
        Screen.Splash -> Splash(nav)
        Screen.Home -> HomeScreen(nav)
        Screen.Room -> RoomScreen(nav)
        Screen.Wallet -> WalletScreen(onBack = nav::pop)
        Screen.Friends -> FriendsScreen(onBack = nav::pop)
        Screen.Leaderboard -> LeaderboardScreen(onBack = nav::pop)
        Screen.HowToPlay -> HowToPlayScreen(onBack = nav::pop)
    }

    invite?.let { inv ->
        val terms = if (inv.isFree) "Free game" else "Entry ${formatRs(inv.feeRs)}"
        AlertDialog(
            onDismissRequest = { invite = null },
            title = { Text("${inv.fromName} invited you!") },
            text = { Text("Room ${inv.code} · $terms · ${inv.players} player(s)") },
            confirmButton = {
                TextButton(onClick = {
                    invite = null
                    scope.launch {
                        try {
                            game.emit("room:join", JSONObject().put("code", inv.code))
                            nav.push(Screen.Room)
                        } catch (e: Exception) {
                            Toast.makeText(context, e.message ?: "Could not join", Toast.LENGTH_LONG).show()
                        }
                    }
                }) { Text("Join") }
            },
            dismissButton = { TextButton(onClick = { invite = null }) { Text("Not now") } },
        )
    }
}

/**
 * No login screen: sign in silently as this device's guest player, then go to an
 * in-progress room (reconnect after an app restart) or home.
 */
@Composable
private fun Splash(nav: Nav) {
    val app = LocalContext.current.app
    val session = app.session
    var attempt by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(attempt) {
        error = null
        try {
            if (session.token.value != null) session.refreshUser() // signs out if the server forgot us
            if (session.token.value == null) session.guestLogin()
        } catch (e: Exception) {
            error = e.message ?: "Could not connect"
            return@LaunchedEffect
        }
        withTimeoutOrNull(3_000) { app.game.roomLoaded.first { it } }
        nav.reset(Screen.Home)
        if (app.game.room.value != null) nav.push(Screen.Room)
    }

    HsScreen {
        Column(
            Modifier.fillMaxWidth().padding(top = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Image(painterResource(R.drawable.logo), contentDescription = null, modifier = Modifier.size(160.dp))
            Text("CHOR SIPAHI", color = Hs.Primary, fontSize = 32.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp)
            Text("Real-world hide & seek with your friends", color = Hs.TextDim, fontSize = 15.sp)
        }
        if (error == null) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Hs.Primary)
            }
        } else {
            ErrorText(error)
            HsButton("Retry", onClick = { attempt++ })
            ServerSettingsCard(onSaved = { attempt++ })
        }
    }
}
