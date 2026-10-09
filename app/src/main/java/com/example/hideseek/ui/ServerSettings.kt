package com.example.hideseek.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import com.example.hideseek.app
import com.example.hideseek.data.DEFAULT_SERVER_URL

/**
 * Edit the game server address. A sign-in belongs to one server, so saving a new
 * address signs out; the splash screen then signs in again as a guest there.
 */
@Composable
fun ServerSettingsCard(onSaved: () -> Unit = {}) {
    val session = LocalContext.current.app.session
    val serverUrl by session.serverUrl.collectAsState()
    var draft by remember(serverUrl) { mutableStateOf(serverUrl) }

    HsCard {
        Label("Game server address")
        Body("The PC running the server, on the same Wi-Fi as this phone.", dim = true, size = 13)
        HsTextField(draft, { draft = it.trim() }, DEFAULT_SERVER_URL, keyboardType = KeyboardType.Uri)
        HsButton("Save & reconnect", onClick = {
            if (draft != serverUrl) {
                session.setServerUrl(draft)
                session.signOut()
            }
            onSaved()
        }, variant = Variant.Secondary, small = true, enabled = draft.startsWith("http"))
    }
}
