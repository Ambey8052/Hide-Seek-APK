package com.example.hideseek.ui.room

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.hideseek.Nav
import com.example.hideseek.Screen
import com.example.hideseek.app
import com.example.hideseek.data.GameClient
import com.example.hideseek.data.GameEvent
import com.example.hideseek.data.Phase
import com.example.hideseek.data.Room
import com.example.hideseek.device.BleStatus
import com.example.hideseek.device.GpsState
import com.example.hideseek.device.rememberBleProximity
import com.example.hideseek.device.KeepScreenOn
import com.example.hideseek.device.buzz
import com.example.hideseek.device.rememberLocationReporter
import com.example.hideseek.device.rememberServerNow
import com.example.hideseek.ui.theme.Hs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Everything a phase view needs. */
class RoomCtx(
    val room: Room,
    val game: GameClient,
    val now: Long,
    val gps: GpsState,
    val ble: BleStatus,
    val onLeave: () -> Unit,
    val openFriends: () -> Unit,
) {
    val me get() = room.me!!
    val userId get() = me.userId
    val isHost get() = room.hostId == userId
}

private val TOAST_KINDS = setOf("catch", "dq", "pulse")

@Composable
fun RoomScreen(nav: Nav) {
    val app = LocalContext.current.app
    val game = app.game
    val room by game.room.collectAsState()
    val roomLoaded by game.roomLoaded.collectAsState()
    val scope = rememberCoroutineScope()
    val now = rememberServerNow(game)
    var confirmLeave by remember { mutableStateOf(false) }

    val r = room
    val inGame = r?.inGame == true
    val gps = rememberLocationReporter(inGame && r?.me?.isActive == true, game)
    val ble = rememberBleProximity(inGame && r?.me?.isActive == true, r?.me?.bleTag, game)

    // Kicked, left, or the room expired.
    LaunchedEffect(room == null, roomLoaded) {
        if (roomLoaded && room == null) nav.reset(Screen.Home)
    }

    fun leaveNow() {
        scope.launch {
            runCatching { game.emit("room:leave") }
            nav.reset(Screen.Home)
        }
    }

    val onLeave = { if (r == null || r.finished) leaveNow() else confirmLeave = true }
    BackHandler { onLeave() }

    if (r?.me == null) return
    if (inGame) KeepScreenOn()

    val ctx = RoomCtx(r, game, now, gps, ble, onLeave, openFriends = { nav.push(Screen.Friends) })
    Box(Modifier.fillMaxSize()) {
        when (r.phase) {
            Phase.LOBBY -> Lobby(ctx)
            Phase.DEALING -> Deal(ctx)
            Phase.HIDING -> if (ctx.me.isActive) Hiding(ctx) else Spectate(ctx)
            Phase.HUNTING -> when {
                !ctx.me.isActive -> Spectate(ctx)
                ctx.me.isPolice -> PoliceHunt(ctx)
                else -> ThiefHunt(ctx)
            }
            else -> if (r.result != null) Result(ctx)
        }
        EventToast(r)
    }

    if (confirmLeave) {
        val forfeit = inGame && r.me.isActive
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(if (forfeit) "Leave the game?" else "Leave the room?") },
            text = {
                if (forfeit) {
                    Text(
                        if (r.me.isPolice) "You will forfeit and the remaining thieves win the pot."
                        else "You'll be out of the game and lose your entry fee.",
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    leaveNow()
                }) { Text("Leave", color = Hs.Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Stay") } },
        )
    }
}

/** Slide-in banner for catches, disqualifications and radar pulses. */
@Composable
private fun BoxScopeToast(event: GameEvent?, visible: Boolean) {
    AnimatedVisibility(
        visible = visible && event != null,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
    ) {
        val e = event ?: return@AnimatedVisibility
        val bg = when (e.kind) {
            "catch" -> Hs.Thief
            "pulse" -> Hs.Police
            else -> Hs.SurfaceAlt
        }
        Text(
            e.text,
            color = Color.White,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(16.dp).background(bg, RoundedCornerShape(14.dp)).padding(12.dp),
        )
    }
}

@Composable
private fun EventToast(room: Room) {
    val context = LocalContext.current
    var lastSeen by remember { mutableStateOf<Long?>(null) }
    var shown by remember { mutableStateOf<GameEvent?>(null) }
    var visible by remember { mutableStateOf(false) }
    val newestId = room.events.lastOrNull()?.id ?: 0L

    LaunchedEffect(newestId) {
        val seen = lastSeen
        lastSeen = newestId
        // First load or a new round: don't replay history.
        if (seen == null || newestId < seen) return@LaunchedEffect
        val fresh = room.events.lastOrNull { it.id > seen && it.kind in TOAST_KINDS } ?: return@LaunchedEffect
        shown = fresh
        visible = true
        if (fresh.kind == "catch") context.buzz(120)
        delay(3_000)
        visible = false
    }

    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
        BoxScopeToast(shown, visible)
    }
}
