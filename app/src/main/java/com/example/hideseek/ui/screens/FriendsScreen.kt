package com.example.hideseek.ui.screens

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.hideseek.app
import com.example.hideseek.data.Friend
import com.example.hideseek.data.Phase
import com.example.hideseek.data.asArray
import com.example.hideseek.data.mapObjects
import com.example.hideseek.ui.Body
import com.example.hideseek.ui.Dot
import com.example.hideseek.ui.ErrorText
import com.example.hideseek.ui.HRow
import com.example.hideseek.ui.HsButton
import com.example.hideseek.ui.HsCard
import com.example.hideseek.ui.HsPage
import com.example.hideseek.ui.HsTextField
import com.example.hideseek.ui.Label
import com.example.hideseek.ui.Variant
import com.example.hideseek.ui.theme.Hs
import kotlinx.coroutines.launch
import org.json.JSONObject

fun Context.shareText(text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    startActivity(Intent.createChooser(send, "Share room code"))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FriendsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val room by app.game.room.collectAsState()
    val me by app.session.user.collectAsState()
    val scope = rememberCoroutineScope()
    var friends by remember { mutableStateOf<List<Friend>>(emptyList()) }
    var phone by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val invited = remember { mutableStateMapOf<String, Boolean>() }
    var removing by remember { mutableStateOf<Friend?>(null) }
    val lobby = room?.takeIf { it.phase == Phase.LOBBY }

    fun load(block: suspend () -> Any) = scope.launch {
        try {
            friends = block().asArray().mapObjects(Friend::parse)
            error = null
        } catch (e: Exception) {
            error = e.message
        }
    }

    LaunchedEffect(Unit) { load { app.session.get("/api/friends") } }

    HsPage("Friends", onBack) {
        lobby?.let { r ->
            HsCard(borderColor = Hs.Primary) {
                Body("Inviting to room ${r.code}", bold = true)
                HsButton("Share code on WhatsApp / SMS", onClick = {
                    context.shareText("Join my Chor Sipahi hide & seek game! Open the app and enter room code ${r.code}")
                }, variant = Variant.Secondary, small = true)
            }
        }

        HsCard {
            Label("Add a friend")
            me?.let { Body("Your player code: ${it.code} — share it so friends can add you.", dim = true, size = 13) }
            HRow {
                HsTextField(phone, { phone = it.filter(Char::isLetterOrDigit).uppercase() }, "Player code or mobile no.", Modifier.weight(1f), maxLength = 10)
                HsButton("Add", onClick = {
                    load { app.session.post("/api/friends", JSONObject().put("phone", phone)).also { phone = "" } }
                }, modifier = Modifier.width(90.dp), enabled = phone.length == 8 || phone.length == 10, small = true)
            }
            ErrorText(error)
        }

        HsCard {
            Label("Your friends (${friends.size})")
            if (friends.isEmpty()) Body("Add friends to invite them straight into your games.", dim = true)
            friends.forEach { f ->
                HorizontalDivider(color = Hs.Border)
                HRow(Modifier.fillMaxWidth().padding(vertical = 4.dp), gap = 12) {
                    Dot(if (f.online) Hs.Green else Hs.TextFaint)
                    Column(Modifier.weight(1f).combinedClickable(onClick = {}, onLongClick = { removing = f })) {
                        Text(f.name, color = Hs.Text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text("${f.xp} XP · ${f.gamesWon} wins · ${if (f.online) "online" else "offline"}", color = Hs.TextFaint, fontSize = 12.sp)
                    }
                    if (lobby != null) {
                        val done = invited[f.id] == true
                        HsButton(
                            if (done) "Invited ✓" else "Invite",
                            onClick = {
                                scope.launch {
                                    try {
                                        app.game.emit("invite:send", JSONObject().put("friendId", f.id))
                                        invited[f.id] = true
                                    } catch (e: Exception) {
                                        Toast.makeText(context, e.message, Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                            variant = if (done) Variant.Secondary else Variant.Primary,
                            enabled = f.online && !done,
                            small = true,
                        )
                    }
                }
            }
            if (friends.isNotEmpty()) Text("Long-press a name to remove", color = Hs.TextFaint, fontSize = 12.sp)
        }
    }

    removing?.let { f ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${f.name}?") },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    load { app.session.delete("/api/friends/${f.id}") }
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}
