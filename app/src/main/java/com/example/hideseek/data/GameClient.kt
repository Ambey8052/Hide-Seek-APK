package com.example.hideseek.data

import io.socket.client.Ack
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.engineio.client.transports.WebSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import kotlin.coroutines.resume

class GameException(message: String) : Exception(message)

/**
 * Real-time connection to the game server. Holds the current room snapshot, which the
 * server pushes on every change (and once per second during a game).
 */
class GameClient(private val session: Session, scope: CoroutineScope) {
    private val _room = MutableStateFlow<Room?>(null)
    val room: StateFlow<Room?> = _room

    /** True once the server has told us whether we're in a room (after connecting). */
    private val _roomLoaded = MutableStateFlow(false)
    val roomLoaded: StateFlow<Boolean> = _roomLoaded

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private val _invites = MutableSharedFlow<Invite>(extraBufferCapacity = 4)
    val invites: SharedFlow<Invite> = _invites

    private var socket: Socket? = null
    @Volatile private var clockOffset = 0L

    init {
        scope.launch {
            combine(session.token, session.serverUrl) { t, u -> t to u }
                .distinctUntilChanged()
                .collect { (token, url) -> connect(token, url) }
        }
    }

    /** Current time on the server's clock, so every phone shows the same countdown. */
    fun serverNow() = System.currentTimeMillis() + clockOffset

    private fun connect(token: String?, url: String) {
        socket?.let {
            it.off()
            it.close()
        }
        socket = null
        _room.value = null
        _roomLoaded.value = false
        _connected.value = false
        if (token == null) return

        val opts = IO.Options.builder()
            .setAuth(mapOf("token" to token))
            .setTransports(arrayOf(WebSocket.NAME))
            .setReconnectionDelayMax(3000)
            .build()
        val s = try {
            IO.socket(url, opts)
        } catch (e: Exception) {
            return
        }
        s.on(Socket.EVENT_CONNECT) { _connected.value = true }
        s.on(Socket.EVENT_DISCONNECT) { _connected.value = false }
        s.on(Socket.EVENT_CONNECT_ERROR) { args ->
            if (args.firstOrNull()?.toString()?.contains("unauthorized") == true) session.signOut()
        }
        s.on("room:state") { args ->
            val room = (args.firstOrNull() as? JSONObject)?.let {
                runCatching { Room.parse(it) }.getOrNull()
            }
            if (room != null) clockOffset = room.serverNow - System.currentTimeMillis()
            _room.value = room
            _roomLoaded.value = true
        }
        s.on("invite") { args ->
            (args.firstOrNull() as? JSONObject)?.let { _invites.tryEmit(Invite.parse(it)) }
        }
        socket = s
        s.connect()
    }

    /** Emit an event and wait for the server's { ok, error } acknowledgement. */
    suspend fun emit(event: String, payload: JSONObject = JSONObject()): JSONObject {
        val s = socket?.takeIf { it.connected() } ?: throw GameException("Not connected to the server")
        val ack: JSONObject = try {
            withTimeout(8_000) {
                suspendCancellableCoroutine<JSONObject> { cont ->
                    s.emit(event, arrayOf<Any>(payload), Ack { args ->
                        if (cont.isActive) cont.resume(args.firstOrNull() as? JSONObject ?: JSONObject())
                    })
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw GameException("Server did not respond")
        }
        if (!ack.optBoolean("ok")) throw GameException(ack.optString("error").ifBlank { "Something went wrong" })
        return ack
    }

    /** Fire-and-forget (GPS fixes). */
    fun send(event: String, payload: JSONObject) {
        socket?.takeIf { it.connected() }?.emit(event, payload)
    }
}
