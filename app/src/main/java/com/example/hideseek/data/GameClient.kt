package com.example.hideseek.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.hideseek.engine.GameException
import com.example.hideseek.engine.Identity
import com.example.hideseek.engine.RoomEngine
import com.example.hideseek.net.LinkListener
import com.example.hideseek.net.NearbyLink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.security.SecureRandom

enum class Mode { NONE, HOST, GUEST }

private const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
private const val JOIN_TIMEOUT_MS = 25_000L
private const val ACK_TIMEOUT_MS = 8_000L

/**
 * The game connection, fully offline.
 *
 * - HOST: this phone runs the [RoomEngine], advertises the room over Bluetooth / Wi-Fi and
 *   pushes each player their own snapshot whenever anything changes (and every second).
 * - GUEST: this phone connects to the host and sends it actions; it gets snapshots back.
 *
 * Either way the UI sees the same [room] snapshot and calls [emit] / [send], exactly as it
 * did with the old server.
 *
 * Message format (one JSON object per Nearby payload):
 *   guest -> host  {t:"hello", userId, name}  {t:"req", id, event, payload}  {t:"evt", event, payload}
 *   host -> guest  {t:"state", room}  {t:"ack", id, res}  {t:"closed", reason}  {t:"error", message}
 */
class GameClient(context: Context, private val profile: Profile, private val scope: CoroutineScope) : LinkListener {
    private val link = NearbyLink(context, this)
    private val main = Handler(Looper.getMainLooper())

    private val _mode = MutableStateFlow(Mode.NONE)
    val mode: StateFlow<Mode> = _mode

    private val _room = MutableStateFlow<Room?>(null)
    val room: StateFlow<Room?> = _room

    /** Always true offline: there's nothing to load at start-up. Kept for the room screen. */
    val roomLoaded: StateFlow<Boolean> = MutableStateFlow(true)

    /** Guest: linked to the host. Host: always true while hosting. */
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    /** Rooms advertised by host phones nearby, while scanning. */
    private val _nearby = MutableStateFlow<List<NearbyRoom>>(emptyList())
    val nearby: StateFlow<List<NearbyRoom>> = _nearby

    private val _scanError = MutableStateFlow<String?>(null)
    val scanError: StateFlow<String?> = _scanError

    /** One-off messages for a toast ("Host closed the room"). */
    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val notices: SharedFlow<String> = _notices

    @Volatile private var clockOffset = 0L

    /** Current time on the host's clock, so every phone shows the same countdown. */
    fun serverNow() = System.currentTimeMillis() + clockOffset

    // ---------------------------------------------------------------- host state

    private var engine: RoomEngine? = null
    private var tickJob: Job? = null
    private val endpointOf = HashMap<String, String>() // userId -> endpointId
    private val userOf = HashMap<String, String>() // endpointId -> userId
    private var flushPending = false

    // ---------------------------------------------------------------- guest state

    private var hostEndpoint: String? = null
    private var joinCode: String? = null // room we're in (or joining), for auto-reconnect
    private var joinWaiter: CompletableDeferred<Unit>? = null
    private var reqSeq = 0L
    private val pending = HashMap<Long, CompletableDeferred<JSONObject>>()
    private var scanning = false

    // ================================================================= host

    /** Create a room on this phone and make it visible to nearby phones. */
    fun hostRoom(feeRs: Double, isFree: Boolean) {
        leave()
        val code = (1..6).map { CODE_ALPHABET[SecureRandom().nextInt(CODE_ALPHABET.length)] }.joinToString("")
        val e = RoomEngine(code, Identity(profile.id, profile.name), feeRs, isFree, onChange = ::scheduleFlush)
        engine = e
        _mode.value = Mode.HOST
        _connected.value = true
        clockOffset = 0
        link.stopDiscovery()
        link.startAdvertising(code, profile.name) { msg -> _notices.tryEmit("Can't make the room visible: $msg") }
        tickJob = scope.launch {
            while (isActive) {
                delay(1_000)
                runCatching { e.tick() }
            }
        }
        flushNow()
    }

    /** Batch every change made in one step into a single push per player. */
    private fun scheduleFlush() {
        if (flushPending) return
        flushPending = true
        main.post { if (flushPending) flushNow() }
    }

    private fun flushNow() {
        flushPending = false
        val e = engine ?: return
        for (userId in e.playerIds) {
            val snap = e.snapshotFor(userId)
            if (userId == profile.id) applyState(snap) else endpointOf[userId]?.let { link.send(it, state(snap)) }
        }
    }

    private fun state(snap: JSONObject?) = JSONObject().put("t", "state").put("room", snap ?: JSONObject.NULL)

    /** Runs one action on the engine for [userId]. Returns extra ack fields. */
    private fun handle(userId: String, event: String, p: JSONObject): JSONObject? {
        val e = engine ?: throw GameException("Room is closed")
        when (event) {
            "room:settings" -> e.updateSettings(userId, p)
            "room:ready" -> e.setReady(userId, p.optBoolean("ready"), p.optString("clientSeed"))
            "room:deal" -> e.deal(userId)
            "room:rematch" -> e.rematch(userId)
            "card:flip" -> e.flip(userId)
            "loc" -> e.updateLocation(userId, p)
            "prox" -> e.updateProximity(userId, p.optJSONArray("seen"))
            "catch:tap" -> e.catchByTap(userId)
            "catch:scan" -> return JSONObject().put("caught", e.catchByScan(userId, p.optString("token")))
            "room:kick" -> {
                val target = p.optString("userId")
                e.kick(userId, target)
                dropGuest(target, "The host removed you from the room")
            }
            "room:leave" -> {
                e.removePlayer(userId)
                dropGuest(userId, null)
            }
            else -> throw GameException("Unknown action")
        }
        return null
    }

    /** Tell a guest they're out of the room, then disconnect them. */
    private fun dropGuest(userId: String, reason: String?) {
        val ep = endpointOf.remove(userId) ?: return
        userOf.remove(ep)
        link.send(ep, JSONObject().put("t", "closed").put("reason", reason ?: JSONObject.NULL))
        main.postDelayed({ link.disconnect(ep) }, 500)
    }

    private fun hostMessage(endpointId: String, msg: JSONObject) {
        val e = engine ?: return
        when (msg.optString("t")) {
            "hello" -> {
                val userId = msg.optString("userId")
                if (userId.isBlank() || userId == profile.id) return
                try {
                    e.addPlayer(Identity(userId, msg.optString("name")))
                } catch (err: GameException) {
                    link.send(endpointId, JSONObject().put("t", "error").put("message", err.message))
                    main.postDelayed({ link.disconnect(endpointId) }, 500)
                    return
                }
                // Same player on a new connection (reconnect): forget the old one.
                endpointOf.put(userId, endpointId)?.takeIf { it != endpointId }?.let { old ->
                    userOf.remove(old)
                    link.disconnect(old)
                }
                userOf[endpointId] = userId
                flushNow()
            }
            "req" -> {
                val userId = userOf[endpointId] ?: return
                val res = try {
                    val extra = handle(userId, msg.optString("event"), msg.optJSONObject("payload") ?: JSONObject())
                    (extra ?: JSONObject()).put("ok", true)
                } catch (err: GameException) {
                    JSONObject().put("ok", false).put("error", err.message)
                } catch (err: Exception) {
                    JSONObject().put("ok", false).put("error", "Something went wrong")
                }
                flushNow() // state first, then the ack, so the guest already sees the change
                link.send(endpointId, JSONObject().put("t", "ack").put("id", msg.optLong("id")).put("res", res))
            }
            "evt" -> {
                val userId = userOf[endpointId] ?: return
                runCatching { handle(userId, msg.optString("event"), msg.optJSONObject("payload") ?: JSONObject()) }
            }
        }
    }

    private fun closeHostedRoom() {
        for (ep in userOf.keys.toList()) {
            link.send(ep, JSONObject().put("t", "closed").put("reason", "The host closed the room"))
        }
        tickJob?.cancel()
        tickJob = null
        engine = null
        endpointOf.clear()
        userOf.clear()
        // Give the "closed" messages a moment to go out before dropping the links.
        main.postDelayed({ if (engine == null) link.stopAll() }, 600)
        link.stopAdvertising()
    }

    // ================================================================= guest

    /** Start looking for rooms nearby (Home screen). */
    fun startScan() {
        scanning = true
        _scanError.value = null
        if (_mode.value == Mode.HOST) return
        link.startDiscovery { _scanError.value = it }
    }

    fun stopScan() {
        scanning = false
        // Keep discovering if we're trying to reconnect to our host.
        if (_mode.value == Mode.GUEST && !_connected.value) return
        link.stopDiscovery()
        _nearby.value = emptyList()
    }

    /** Join a room by its code; it must be advertised nearby. */
    suspend fun join(code: String) {
        val clean = code.trim().uppercase()
        val target = _nearby.value.firstOrNull { it.code == clean }
        if (target == null) {
            startScan()
            throw GameException("Room $clean isn't nearby yet. Stay close to the host phone - it will appear in the list below.")
        }
        join(target)
    }

    suspend fun join(target: NearbyRoom) {
        leave()
        _mode.value = Mode.GUEST
        joinCode = target.code
        val waiter = CompletableDeferred<Unit>()
        joinWaiter = waiter
        hostEndpoint = target.endpointId
        link.stopDiscovery() // discovering while connecting slows the connection down
        link.connect(target.endpointId, profile.name)
        try {
            withTimeout(JOIN_TIMEOUT_MS) { waiter.await() }
        } catch (e: TimeoutCancellationException) {
            leave()
            throw GameException("The host didn't answer. Move closer to the host phone and try again.")
        } catch (e: Exception) {
            leave()
            throw e
        } finally {
            joinWaiter = null
        }
    }

    private fun guestMessage(endpointId: String, msg: JSONObject) {
        if (endpointId != hostEndpoint) return
        when (msg.optString("t")) {
            "state" -> {
                val snap = msg.optJSONObject("room")
                if (snap == null) {
                    endGuest(null)
                    return
                }
                applyState(snap)
                joinWaiter?.complete(Unit)
            }
            "ack" -> pending.remove(msg.optLong("id"))?.complete(msg.optJSONObject("res") ?: JSONObject())
            "error" -> {
                val message = msg.optString("message").ifBlank { "Could not join" }
                joinWaiter?.completeExceptionally(GameException(message)) ?: endGuest(message)
            }
            "closed" -> endGuest(msg.optString("reason").takeIf { it.isNotBlank() && it != "null" })
        }
    }

    /** The host ended our membership (left, kicked, room closed). */
    private fun endGuest(reason: String?) {
        if (reason != null) _notices.tryEmit(reason)
        _room.value?.takeIf { it.inGame }?.let(profile::refundCancelled) // host closed a running game
        leave(notifyHost = false)
    }

    private fun reconnect() {
        // Lost the host mid-room: keep showing the last state and look for the host again.
        _connected.value = false
        hostEndpoint = null
        link.startDiscovery { _scanError.value = it }
    }

    // ================================================================= shared

    private fun applyState(snap: JSONObject) {
        val room = runCatching { Room.parse(snap) }.getOrNull() ?: return
        clockOffset = room.serverNow - System.currentTimeMillis()
        _room.value = room
        settle(room)
    }

    /** Each phone keeps its own test wallet and stats in step with the game. */
    private fun settle(room: Room) {
        val me = room.me ?: return
        if (!room.isFree && room.phase in setOf(Phase.DEALING, Phase.HIDING, Phase.HUNTING) && me.userId in room.players.map { it.userId }) {
            profile.chargeEntry(room.gameId, room.feeRs, room.code)
        }
        if (room.finished && room.result != null) profile.settleGame(room)
    }

    /** Send an action and wait for the host's { ok, error } answer. */
    suspend fun emit(event: String, payload: JSONObject = JSONObject()): JSONObject {
        if (event == "room:leave") {
            leave()
            return JSONObject().put("ok", true)
        }
        val room = _room.value
        if (event == "room:ready" && payload.optBoolean("ready") && room != null && !room.isFree) {
            val balance = profile.wallet.value.balanceRs
            if (balance < room.feeRs) throw GameException("Add test money to your wallet: entry is ₹${room.feeRs.toInt()}")
        }
        return when (_mode.value) {
            Mode.HOST -> {
                val extra = handle(profile.id, event, payload)
                flushNow()
                (extra ?: JSONObject()).put("ok", true)
            }
            Mode.GUEST -> {
                val ep = hostEndpoint?.takeIf { _connected.value } ?: throw GameException("Not connected to the host - reconnecting…")
                val id = ++reqSeq
                val waiter = CompletableDeferred<JSONObject>()
                pending[id] = waiter
                link.send(ep, JSONObject().put("t", "req").put("id", id).put("event", event).put("payload", payload))
                val ack = try {
                    withTimeout(ACK_TIMEOUT_MS) { waiter.await() }
                } catch (e: TimeoutCancellationException) {
                    throw GameException("The host did not respond")
                } finally {
                    pending.remove(id)
                }
                if (!ack.optBoolean("ok")) throw GameException(ack.optString("error").ifBlank { "Something went wrong" })
                ack
            }
            Mode.NONE -> throw GameException("You are not in a room")
        }
    }

    /** Fire-and-forget (GPS fixes, Bluetooth readings). */
    fun send(event: String, payload: JSONObject) {
        main.post {
            when (_mode.value) {
                Mode.HOST -> runCatching { handle(profile.id, event, payload) }
                Mode.GUEST -> hostEndpoint?.takeIf { _connected.value }?.let {
                    link.send(it, JSONObject().put("t", "evt").put("event", event).put("payload", payload))
                }
                Mode.NONE -> Unit
            }
        }
    }

    /** Leave the current room (host: closes it for everyone). */
    fun leave(notifyHost: Boolean = true) {
        val running = _room.value?.takeIf { it.inGame }
        when (_mode.value) {
            Mode.HOST -> {
                // Closing the host ends the game for everyone: it's cancelled and refunded.
                running?.let(profile::refundCancelled)
                closeHostedRoom()
            }
            Mode.GUEST -> {
                // The host phone is gone for good: nobody can finish this game.
                if (!_connected.value) running?.let(profile::refundCancelled)
                hostEndpoint?.let { ep ->
                    if (notifyHost && _connected.value) {
                        link.send(ep, JSONObject().put("t", "req").put("id", ++reqSeq).put("event", "room:leave").put("payload", JSONObject()))
                        main.postDelayed({ link.disconnect(ep) }, 400)
                    } else {
                        link.disconnect(ep)
                    }
                }
                pending.values.forEach { it.completeExceptionally(GameException("Left the room")) }
                pending.clear()
            }
            Mode.NONE -> Unit
        }
        _mode.value = Mode.NONE
        _room.value = null
        _connected.value = false
        hostEndpoint = null
        joinCode = null
        clockOffset = 0
        if (scanning) link.startDiscovery { _scanError.value = it } else link.stopDiscovery()
    }

    // ================================================================= LinkListener

    override fun onRoomFound(endpointId: String, code: String, hostName: String) {
        _nearby.value = _nearby.value.filter { it.endpointId != endpointId && it.code != code } + NearbyRoom(endpointId, code, hostName)
        // Reconnect to our room's host after losing the link.
        if (_mode.value == Mode.GUEST && !_connected.value && hostEndpoint == null && code == joinCode) {
            hostEndpoint = endpointId
            link.stopDiscovery()
            link.connect(endpointId, profile.name)
        }
    }

    override fun onRoomLost(endpointId: String) {
        _nearby.value = _nearby.value.filter { it.endpointId != endpointId }
    }

    override fun onConnected(endpointId: String) {
        if (_mode.value == Mode.GUEST && endpointId == hostEndpoint) {
            _connected.value = true
            link.send(endpointId, JSONObject().put("t", "hello").put("userId", profile.id).put("name", profile.name))
        }
        // Host: wait for the guest's "hello" before treating it as a player.
    }

    override fun onDisconnected(endpointId: String) {
        when (_mode.value) {
            Mode.HOST -> {
                val userId = userOf.remove(endpointId) ?: return
                if (endpointOf[userId] == endpointId) endpointOf.remove(userId)
                engine?.setOnline(userId, false)
            }
            Mode.GUEST -> if (endpointId == hostEndpoint) {
                pending.values.forEach { it.completeExceptionally(GameException("Lost the connection to the host")) }
                pending.clear()
                if (_room.value == null) {
                    joinWaiter?.completeExceptionally(GameException("Lost the connection to the host"))
                } else {
                    reconnect()
                }
            }
            Mode.NONE -> Unit
        }
    }

    override fun onConnectFailed(endpointId: String, message: String) {
        if (_mode.value != Mode.GUEST || endpointId != hostEndpoint) return
        val waiter = joinWaiter
        if (waiter != null && _room.value == null) {
            waiter.completeExceptionally(GameException(message))
        } else {
            // Reconnect attempt failed: keep looking.
            main.postDelayed({ if (_mode.value == Mode.GUEST && !_connected.value) reconnect() }, 2_000)
        }
    }

    override fun onMessage(endpointId: String, msg: JSONObject) {
        when (_mode.value) {
            Mode.HOST -> hostMessage(endpointId, msg)
            Mode.GUEST -> guestMessage(endpointId, msg)
            Mode.NONE -> Unit
        }
    }
}
