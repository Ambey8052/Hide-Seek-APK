package com.example.hideseek

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.hideseek.ui.HsScreen
import com.example.hideseek.ui.room.RoomScreen
import com.example.hideseek.ui.screens.HomeScreen
import com.example.hideseek.ui.screens.HowToPlayScreen
import com.example.hideseek.ui.screens.LeaderboardScreen
import com.example.hideseek.ui.screens.WalletScreen
import com.example.hideseek.ui.theme.HideSeekTheme
import com.example.hideseek.ui.theme.Hs
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HideSeekTheme { HideSeekRoot() }
        }
    }
}

enum class Screen { Splash, Home, Room, Wallet, Leaderboard, HowToPlay }

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
    val game = context.app.game
    val nav = remember { Nav() }

    // "The host closed the room", "You were removed", ...
    LaunchedEffect(Unit) {
        game.notices.collect { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }

    BackHandler(enabled = nav.stack.size > 1 && nav.current != Screen.Room) { nav.pop() }

    when (nav.current) {
        Screen.Splash -> Splash(nav)
        Screen.Home -> HomeScreen(nav)
        Screen.Room -> RoomScreen(nav)
        Screen.Wallet -> WalletScreen(onBack = nav::pop)
        Screen.Leaderboard -> LeaderboardScreen(onBack = nav::pop)
        Screen.HowToPlay -> HowToPlayScreen(onBack = nav::pop)
    }
}

/** Everything is on the phone, so the splash is just a short logo moment. */
@Composable
private fun Splash(nav: Nav) {
    val app = LocalContext.current.app
    LaunchedEffect(Unit) {
        delay(700)
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
            Text("Offline · Bluetooth & Wi-Fi", color = Hs.TextFaint, fontSize = 13.sp)
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Hs.Primary)
        }
    }
}
