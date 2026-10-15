package com.example.hideseek.engine

import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID

// One game room and its state machine, run on the host phone:
//
//   LOBBY ──deal──▶ DEALING ──all cards flipped──▶ HIDING ──head start over──▶ HUNTING ──▶ ENDED
//                                                                                   └────▶ VOID
//
// Ported from the old Node server (server/src/game/Room.js). The room never talks to the
// network: it calls onChange() and the host pushes a personalised snapshot to every phone.
// Wallets live on each phone, so the engine only computes the pot and payouts; every phone
// applies its own entry fee and winnings (see GameClient.settle).

class GameException(message: String) : Exception(message)

private const val SEC = 1000L
private val ACTIVE_PHASES = setOf("DEALING", "HIDING", "HUNTING")
private val random = SecureRandom()

fun randomHex(bytes: Int) = ByteArray(bytes).also(random::nextBytes).hex()

data class Identity(val id: String, val name: String)

private class Qr(val token: String, val issuedAt: Long)
private class BleReading(val rssi: Double, val at: Long)
private class EngineEvent(val id: Long, val at: Long, val text: String, val kind: String, val userId: String?)

private class EnginePlayer(val userId: String, var name: String) {
    var ready = false
    var clientSeed: String? = null
    var online = true
    var role: String? = null
    var flipped = false
    var status = "active" // active | caught | dq
    var dqReason: String? = null
    var loc: Loc? = null
    var lastLocAt: Long? = null
    var outSince: Long? = null
    var strikes = 0
    var danger = false
    var heat: String? = null
    var closeCount = 0
    var pulse: JSONObject? = null
    var catches = 0
    var qr: Qr? = null
    var prevQr: Qr? = null
    var bleTag: String? = null // 8 hex chars this phone broadcasts over Bluetooth
    val ble = HashMap<String, BleReading>() // tag heard -> smoothed RSSI
    var proxSource: String? = null // ble | gps

    /** Fresh per-round state, keeping identity. */
    fun reset(keepReady: Boolean) = EnginePlayer(userId, name).also {
        it.online = online
        if (keepReady) {
            it.ready = ready
            it.clientSeed = clientSeed
        }
    }
}

class RoomEngine(
    val code: String,
    host: Identity,
    feeRs: Double,
    isFree: Boolean,
    private val onChange: () -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    var hostId = host.id
        private set
    private val players = LinkedHashMap<String, EnginePlayer>()
    private var lastPoliceId: String? = null
    private var boundaryMode = "police"
    private var heatMeter = true
    private var quick = false
    private val timings get() = if (quick) Timings.QUICK else Timings.NORMAL
    private val events = ArrayList<EngineEvent>()
    private var eventSeq = 0L
    var isFree = isFree
        private set
    private var feePaise = 0L

    private var id = ""
    var phase = "LOBBY"
        private set
    private var serverSeed = ""
    private var seedHash = ""
    private var policeId: String? = null
    private var potPaise = 0L
    private var flipDeadline: Long? = null
    private var hideEndsAt: Long? = null
    private var huntEndsAt: Long? = null
    private var nextPulseAt: Long? = null
    private var arenaCenter: Loc? = null
    private var result: JSONObject? = null

    init {
        applySettings(JSONObject().put("feeRs", feeRs).put("isFree", isFree))
        resetRound()
        addPlayer(host)
    }

    val isActive get() = phase in ACTIVE_PHASES
    val playerIds: List<String> get() = players.keys.toList()
    val playerCount get() = players.size
    fun has(userId: String) = userId in players

    // ---------------------------------------------------------------- lobby

    private fun resetRound() {
        id = UUID.randomUUID().toString()
        phase = "LOBBY"
        serverSeed = randomHex(32)
        seedHash = sha256(serverSeed)
        policeId = null
        potPaise = 0
        flipDeadline = null
        hideEndsAt = null
        huntEndsAt = null
        nextPulseAt = null
        arenaCenter = null
        result = null
    }

    private fun applySettings(s: JSONObject) {
        if (s.has("isFree")) isFree = s.optBoolean("isFree")
        if (!isFree && s.has("feeRs")) {
            val fee = s.optDouble("feeRs")
            if (!validateFeeRs(fee)) throw GameException("Entry fee must be ₹${RULES.MIN_FEE_RS}-₹${RULES.MAX_FEE_RS}")
            feePaise = (fee * 100).toLong()
        }
        if (isFree) feePaise = 0
        when (s.optString("boundaryMode")) {
            "police", "arena" -> boundaryMode = s.optString("boundaryMode")
        }
        if (s.has("heatMeter")) heatMeter = s.optBoolean("heatMeter")
        if (s.has("quick")) quick = s.optBoolean("quick")
    }

    /** A phone joined (or re-joined after losing the connection). */
    fun addPlayer(user: Identity) {
        players[user.id]?.let {
            it.online = true
            onChange()
            return
        }
        if (phase != "LOBBY") throw GameException("This game has already started")
        if (players.size >= RULES.MAX_PLAYERS) throw GameException("Room is full (max ${RULES.MAX_PLAYERS})")
        players[user.id] = EnginePlayer(user.id, user.name.take(20).ifBlank { "Player" })
        log("${user.name} joined", "info")
        onChange()
    }

    fun setOnline(userId: String, online: Boolean) {
        val p = players[userId] ?: return
        p.online = online
        if (!online && phase == "LOBBY") p.ready = false
        onChange()
    }

    /** Player leaves voluntarily. Mid-game this counts as forfeiting. */
    fun removePlayer(userId: String) {
        val p = players[userId] ?: return
        if (isActive) {
            if (userId == policeId) {
                log("Police ${p.name} left the game", "dq")
                endGame("Police left the game", policeForfeited = true)
            } else if (p.status == "active") {
                disqualify(p, "Left the game")
            }
            p.online = false
            onChange()
            return
        }
        players.remove(userId)
        if (hostId == userId && players.isNotEmpty()) hostId = players.keys.first()
        log("${p.name} left", "info")
        onChange()
    }

    fun kick(byUserId: String, userId: String) {
        assertHost(byUserId)
        if (phase != "LOBBY") throw GameException("Can only remove players in the lobby")
        if (userId == byUserId) throw GameException("You can't remove yourself")
        removePlayer(userId)
    }

    fun updateSettings(byUserId: String, settings: JSONObject) {
        assertHost(byUserId)
        if (phase != "LOBBY") throw GameException("Game already started")
        applySettings(settings)
        // Terms changed, so everyone has to agree again.
        players.values.forEach { it.ready = false }
        onChange()
    }

    fun setReady(userId: String, ready: Boolean, clientSeed: String?) {
        val p = mustGet(userId)
        if (phase != "LOBBY") throw GameException("Game already started")
        if (ready) p.clientSeed = (clientSeed?.takeIf { it.isNotBlank() } ?: randomHex(16)).take(64)
        p.ready = ready
        onChange()
    }

    /** Host starts the game: secretly pick the police and deal the cards. */
    fun deal(byUserId: String) {
        assertHost(byUserId)
        if (phase != "LOBBY") throw GameException("Game already started")
        val list = players.values.toList()
        if (list.size < RULES.MIN_PLAYERS) throw GameException("Need at least ${RULES.MIN_PLAYERS} players")
        val notReady = list.filter { !it.ready || !it.online }
        if (notReady.isNotEmpty()) throw GameException("Waiting for: ${notReady.joinToString { it.name }}")

        potPaise = feePaise * list.size
        val police = pickPolice(serverSeed, list.map { it.userId to it.clientSeed }, lastPoliceId)
        policeId = police
        val tags = HashSet<String>()
        for (old in list) {
            val p = old.reset(keepReady = true)
            p.role = if (p.userId == police) "police" else "thief"
            var tag: String
            do tag = randomHex(4) while (!tags.add(tag))
            p.bleTag = tag
            players[p.userId] = p
        }
        phase = "DEALING"
        flipDeadline = now() + RULES.AUTO_FLIP_SEC * SEC
        log("Cards dealt! Shake your phone to flip your card", "info")
        onChange()
    }

    // ---------------------------------------------------------------- game

    /** Player shook their phone: reveal their card to them only. */
    fun flip(userId: String) {
        val p = mustGet(userId)
        if (phase != "DEALING" || p.flipped) return
        p.flipped = true
        if (players.values.all { it.flipped }) startHiding()
        onChange()
    }

    private fun startHiding() {
        val t = now()
        players.values.forEach {
            it.flipped = true
            it.lastLocAt = t // grace period before "lost GPS" checks kick in
        }
        phase = "HIDING"
        hideEndsAt = t + timings.hideSec * SEC
        log("${police().name} is the Police! Thieves, go hide within ${RULES.BOUNDARY_RADIUS_M} m", "info")
    }

    private fun startHunting() {
        val t = now()
        phase = "HUNTING"
        huntEndsAt = t + timings.gameSec * SEC
        nextPulseAt = t + timings.radarPulseEverySec * SEC
        police().loc?.let { arenaCenter = it }
        log("Ready or not - the hunt has begun!", "info")
    }

    fun updateLocation(userId: String, fix: JSONObject) {
        val p = players[userId] ?: return
        if (!isActive || p.status != "active") return
        val lat = fix.optDouble("lat", Double.NaN)
        val lng = fix.optDouble("lng", Double.NaN)
        if (!lat.isFinite() || !lng.isFinite() || kotlin.math.abs(lat) > 90 || kotlin.math.abs(lng) > 180) return
        if (fix.optBoolean("mocked")) {
            // Fake-GPS apps would make the whole game meaningless.
            if (userId == policeId) {
                log("Police ${p.name} was using fake GPS", "dq")
                p.status = "dq"
                p.dqReason = "Fake GPS detected"
                endGame("Police used fake GPS", policeForfeited = true)
            } else {
                disqualify(p, "Fake GPS detected")
            }
            onChange()
            return
        }
        val acc = fix.optDouble("accuracy", 50.0).takeIf { it.isFinite() } ?: 50.0 // unknown = poor
        p.loc = Loc(lat, lng, acc.coerceIn(0.0, 500.0))
        p.lastLocAt = now()
    }

    /** Bluetooth readings from one phone: [{ tag, rssi }], smoothed per tag. */
    fun updateProximity(userId: String, seen: JSONArray?) {
        val p = players[userId] ?: return
        if (!isActive || p.status != "active" || seen == null) return
        val t = now()
        val known = players.values.mapNotNull { it.bleTag }.toSet()
        for (i in 0 until minOf(seen.length(), 20)) {
            val s = seen.optJSONObject(i) ?: continue
            val tag = s.optString("tag").lowercase()
            val rssi = s.optDouble("rssi", Double.NaN)
            if (tag !in known || tag == p.bleTag || !rssi.isFinite() || rssi > 0 || rssi < -127) continue
            val prev = p.ble[tag]
            val fresh = prev != null && t - prev.at < RULES.BLE_FRESH_SEC * SEC
            p.ble[tag] = BleReading(if (fresh) prev!!.rssi * 0.6 + rssi * 0.4 else rssi, t)
        }
    }

    /** Strongest fresh Bluetooth signal between two players (either direction), or null. */
    private fun bleRssi(a: EnginePlayer, b: EnginePlayer, t: Long): Double? {
        fun fresh(r: BleReading?) = r?.takeIf { t - it.at < RULES.BLE_FRESH_SEC * SEC }?.rssi
        return listOfNotNull(fresh(a.ble[b.bleTag]), fresh(b.ble[a.bleTag])).maxOrNull()
    }

    /** Police tapped the "CAUGHT" button on the thief's own phone. */
    fun catchByTap(thiefId: String) {
        val thief = mustGet(thiefId)
        assertCatchable(thief)
        markCaught(thief, "tap")
    }

    /** Police scanned the rotating QR code shown on the thief's phone. Returns the thief's name. */
    fun catchByScan(byUserId: String, token: String): String {
        if (byUserId != policeId) throw GameException("Only the police can catch")
        val t = now()
        val ttl = RULES.QR_TOKEN_TTL_SEC * SEC
        val thief = players.values.firstOrNull { p ->
            (p.qr?.token == token && t - p.qr!!.issuedAt < ttl) || (p.prevQr?.token == token && t - p.prevQr!!.issuedAt < ttl)
        } ?: throw GameException("Invalid or expired code - ask the thief to show a fresh one")
        assertCatchable(thief)
        markCaught(thief, "scan")
        return thief.name
    }

    private fun assertCatchable(thief: EnginePlayer) {
        if (phase != "HUNTING") throw GameException("Catching is only allowed during the hunt")
        if (thief.role != "thief" || thief.status != "active") throw GameException("Player is not an active thief")
    }

    private fun markCaught(thief: EnginePlayer, how: String) {
        thief.status = "caught"
        thief.danger = false
        thief.qr = null
        thief.prevQr = null
        val police = police()
        police.catches += 1
        log("${police.name} caught ${thief.name}!", "catch", thief.userId)
        checkAllCaught()
        onChange()
    }

    private fun disqualify(p: EnginePlayer, reason: String) {
        if (p.status != "active") return
        p.status = "dq"
        p.dqReason = reason
        p.danger = false
        p.outSince = null
        p.qr = null
        p.prevQr = null
        log("${p.name} is out: $reason", "dq", p.userId)
        checkAllCaught()
    }

    private fun activeThieves() = players.values.filter { it.role == "thief" && it.status == "active" }

    private fun checkAllCaught() {
        if ((phase == "HIDING" || phase == "HUNTING") && activeThieves().isEmpty()) {
            endGame("Every thief is caught or out - police wins")
        }
    }

    /** Called once per second by the host. */
    fun tick() {
        if (!isActive) return
        val t = now()

        if (phase == "DEALING") {
            if (t >= flipDeadline!!) startHiding()
            onChange()
            return
        }

        val police = police()
        if (t - (police.lastLocAt ?: t) > RULES.POLICE_OFFLINE_VOID_SEC * SEC) {
            voidGame("Police lost connection - game cancelled, everyone is refunded")
            return
        }

        if (phase == "HIDING" && t >= hideEndsAt!!) startHunting()
        val hunting = phase == "HUNTING"
        val remainingSec = if (hunting) (huntEndsAt!! - t) / SEC.toDouble() else Double.POSITIVE_INFINITY
        val policeLoc = police.loc
        val policeFresh = policeLoc != null && t - (police.lastLocAt ?: 0) < RULES.LOCATION_STALE_SEC * SEC
        val center = if (boundaryMode == "arena" && arenaCenter != null) arenaCenter else if (policeFresh) policeLoc else null
        val finalHunt = remainingSec <= timings.finalHuntSec
        val policeRadius = if (finalHunt) RULES.FINAL_HUNT_CLOSE_RADIUS_M else RULES.CLOSE_RADIUS_M

        var closeCount = 0
        for (th in activeThieves()) {
            if (t - (th.lastLocAt ?: t) > RULES.THIEF_OFFLINE_DQ_SEC * SEC) {
                disqualify(th, "Lost connection / GPS for too long")
                continue
            }
            val thiefLoc = th.loc
            val thiefFresh = thiefLoc != null && t - (th.lastLocAt ?: 0) < RULES.LOCATION_STALE_SEC * SEC

            // 100 m zone with a 30 s warning to come back.
            if (thiefFresh && center != null) {
                if (isOutOfZone(thiefLoc!!, center)) {
                    if (th.outSince == null) {
                        th.outSince = t
                        th.strikes += 1
                        if (th.strikes > RULES.MAX_OUT_OF_RANGE_STRIKES) {
                            disqualify(th, "Left the zone too many times")
                            continue
                        }
                    }
                } else {
                    th.outSince = null
                }
            }
            if (th.outSince != null && t - th.outSince!! > RULES.OUT_OF_RANGE_GRACE_SEC * SEC) {
                disqualify(th, "Stayed outside the ${RULES.BOUNDARY_RADIUS_M} m zone")
                continue
            }

            // Proximity: police phone vibrates, thief sees the green dot.
            // Bluetooth is trusted when fresh; otherwise fall back to GPS.
            val ble = if (hunting) bleRssi(th, police, t) else null
            val gpsOk = hunting && thiefFresh && policeFresh
            if (ble != null) {
                th.proxSource = "ble"
                th.danger = ble >= RULES.BLE_CLOSE_RSSI
                val gpsHeat = if (gpsOk) heatLevel(distanceM(thiefLoc!!, policeLoc!!)) else "warm"
                th.heat = if (!heatMeter) null else if (ble >= RULES.BLE_BURNING_RSSI) "burning" else if (gpsHeat == "burning") "hot" else gpsHeat
                if (ble >= (if (finalHunt) RULES.BLE_FINAL_HUNT_RSSI else RULES.BLE_CLOSE_RSSI)) closeCount += 1
            } else if (gpsOk) {
                th.proxSource = "gps"
                th.danger = isClose(thiefLoc!!, policeLoc!!, RULES.CLOSE_RADIUS_M)
                th.heat = if (heatMeter) heatLevel(distanceM(thiefLoc, policeLoc)) else null
                if (isClose(thiefLoc, policeLoc, policeRadius)) closeCount += 1
            } else {
                th.danger = false
            }

            // Rotate the catch QR so a screenshot can't be reused.
            val qr = th.qr
            if (qr == null || t - qr.issuedAt > RULES.QR_TOKEN_ROTATE_SEC * SEC) {
                th.prevQr = qr
                th.qr = Qr(randomHex(12), t)
            }
        }
        if (!isActive) return onChange() // a disqualification above ended the game
        police.closeCount = closeCount

        if (hunting && t >= nextPulseAt!!) {
            nextPulseAt = t + timings.radarPulseEverySec * SEC
            police.pulse = radarPulse(police, t)
            if (police.pulse != null) log("Radar pulse sent to the Police", "pulse")
        }

        if (isActive && activeThieves().isEmpty()) {
            endGame("Every thief is caught or out - police wins")
        } else if (phase == "HUNTING" && t >= huntEndsAt!!) {
            endGame("Time's up! The thieves escaped")
        }
        onChange()
    }

    /** How many thieves are in each distance ring around the police - no directions. */
    private fun radarPulse(police: EnginePlayer, t: Long): JSONObject? {
        val pl = police.loc ?: return null
        val rings = IntArray(4) // <=25 m, <=50 m, <=100 m, further
        for (th in activeThieves()) {
            val d = distanceM(th.loc ?: continue, pl)
            rings[if (d <= 25) 0 else if (d <= 50) 1 else if (d <= 100) 2 else 3] += 1
        }
        return JSONObject().put("at", t).put("rings", JSONArray(rings.toList()))
    }

    private fun endGame(reason: String, policeForfeited: Boolean = false) {
        if (!isActive) return
        val survivors = players.values.filter { it.role == "thief" && it.status == "active" }.map { it.userId }
        val (winnerSide, winnerIds) = decideWinners(policeId!!, survivors, policeForfeited)

        var payouts = emptyList<PayoutShare>()
        var platformPaise = 0L
        var refunded = false
        if (!isFree && potPaise > 0) {
            if (winnerIds.isNotEmpty()) {
                val (p, platform) = splitPot(potPaise, winnerIds, RULES.PLATFORM_FEE_PERCENT)
                payouts = p
                platformPaise = platform
            } else {
                refunded = true // nobody left to win: everyone gets their entry back
            }
        }

        phase = "ENDED"
        players.values.forEach {
            it.danger = false
            it.outSince = null
            it.qr = null
            it.prevQr = null
        }
        result = JSONObject()
            .put("reason", reason)
            .put("winnerSide", winnerSide)
            .put("winners", JSONArray(winnerIds.map { JSONObject().put("userId", it).put("name", players[it]?.name) }))
            .put("payouts", JSONArray(payouts.map {
                JSONObject().put("userId", it.userId).put("name", players[it.userId]?.name).put("rs", it.paise / 100.0)
            }))
            .put("potRs", potPaise / 100.0)
            .put("platformFeeRs", platformPaise / 100.0)
            .put("isFree", isFree)
            .put("refunded", refunded)
            .put("policeCatches", police().catches)
        log(reason, "end")
        onChange()
    }

    private fun voidGame(reason: String) {
        if (!isActive) return
        phase = "VOID"
        result = JSONObject()
            .put("reason", reason)
            .put("winnerSide", "none")
            .put("winners", JSONArray())
            .put("payouts", JSONArray())
            .put("potRs", potPaise / 100.0)
            .put("refunded", potPaise > 0)
            .put("isFree", isFree)
        log(reason, "end")
        onChange()
    }

    /** Same group, new round. The previous police can't be police again straight away. */
    fun rematch(byUserId: String) {
        assertHost(byUserId)
        if (phase != "ENDED" && phase != "VOID") throw GameException("Game is still running")
        lastPoliceId = policeId
        for (id in players.keys.toList()) {
            val p = players.getValue(id)
            if (!p.online) players.remove(id) else players[id] = p.reset(keepReady = false)
        }
        if (hostId !in players && players.isNotEmpty()) hostId = players.keys.first()
        resetRound()
        log("New round! Get ready", "info")
        onChange()
    }

    // ---------------------------------------------------------------- views

    /** What one player is allowed to see. Same JSON shape the old server sent. */
    fun snapshotFor(userId: String): JSONObject {
        val t = now()
        val me = players[userId]
        val revealed = phase in setOf("HIDING", "HUNTING", "ENDED", "VOID")
        val finished = phase == "ENDED" || phase == "VOID"
        val isPolice = me != null && userId == policeId
        val tm = timings

        val o = JSONObject()
            .put("code", code)
            .put("gameId", id)
            .put("hostId", hostId)
            .put("phase", phase)
            .put("feeRs", feePaise / 100.0)
            .put("isFree", isFree)
            .put("potRs", potPaise / 100.0)
            .put("settings", JSONObject().put("boundaryMode", boundaryMode).put("heatMeter", heatMeter).put("quick", quick))
            .put(
                "rules", JSONObject()
                    .put("minPlayers", RULES.MIN_PLAYERS)
                    .put("maxPlayers", RULES.MAX_PLAYERS)
                    .put("minFeeRs", RULES.MIN_FEE_RS)
                    .put("maxFeeRs", RULES.MAX_FEE_RS)
                    .put("boundaryM", RULES.BOUNDARY_RADIUS_M)
                    .put("graceSec", RULES.OUT_OF_RANGE_GRACE_SEC)
                    .put("maxStrikes", RULES.MAX_OUT_OF_RANGE_STRIKES)
                    .put("gameSec", tm.gameSec)
                    .put("hideSec", tm.hideSec)
                    .put("finalHuntSec", tm.finalHuntSec),
            )
            .put("seedHash", seedHash)
            .put("serverSeed", if (finished) serverSeed else JSONObject.NULL)
            .put("serverNow", t)
            .put("flipDeadline", flipDeadline ?: JSONObject.NULL)
            .put("hideEndsAt", hideEndsAt ?: JSONObject.NULL)
            .put("huntEndsAt", huntEndsAt ?: JSONObject.NULL)
            .put("policeId", if (revealed) policeId ?: JSONObject.NULL else JSONObject.NULL)
            .put("players", JSONArray(players.values.map { p ->
                JSONObject()
                    .put("userId", p.userId)
                    .put("name", p.name)
                    .put("isHost", p.userId == hostId)
                    .put("online", p.online)
                    .put("ready", p.ready)
                    .put("flipped", p.flipped)
                    .put("status", p.status)
                    .put("dqReason", p.dqReason ?: JSONObject.NULL)
            }))
            .put("events", JSONArray(events.takeLast(20).map { e ->
                JSONObject().put("id", e.id).put("at", e.at).put("text", e.text).put("kind", e.kind)
                    .put("userId", e.userId ?: JSONObject.NULL)
            }))
            .put("result", result ?: JSONObject.NULL)

        if (me == null) return o.put("me", JSONObject.NULL)
        val policeProx = if (activeThieves().any { it.proxSource == "ble" }) "ble" else "gps"
        return o.put(
            "me", JSONObject()
                .put("userId", userId)
                .put("role", if (me.flipped) me.role ?: JSONObject.NULL else JSONObject.NULL)
                .put("status", me.status)
                .put("dqReason", me.dqReason ?: JSONObject.NULL)
                .put("strikes", me.strikes)
                .put("outOfRange", me.outSince?.let { JSONObject().put("deadline", it + RULES.OUT_OF_RANGE_GRACE_SEC * SEC) } ?: JSONObject.NULL)
                .put("danger", me.danger)
                .put("heat", me.heat ?: JSONObject.NULL)
                .put("qrToken", me.qr?.token ?: JSONObject.NULL)
                .put("closeCount", if (isPolice) me.closeCount else 0)
                .put("pulse", if (isPolice) me.pulse ?: JSONObject.NULL else JSONObject.NULL)
                .put("nextPulseAt", if (isPolice) nextPulseAt ?: JSONObject.NULL else JSONObject.NULL)
                .put("catches", me.catches)
                .put("bleTag", if (isActive) me.bleTag ?: JSONObject.NULL else JSONObject.NULL)
                .put("proxSource", if (isPolice) policeProx else me.proxSource ?: JSONObject.NULL),
        )
    }

    // ---------------------------------------------------------------- utils

    private fun log(text: String, kind: String, userId: String? = null) {
        events += EngineEvent(++eventSeq, now(), text, kind, userId)
        if (events.size > 50) events.removeAt(0)
    }

    private fun police() = players.getValue(policeId!!)

    private fun mustGet(userId: String) = players[userId] ?: throw GameException("You are not in this room")

    private fun assertHost(userId: String) {
        if (userId != hostId) throw GameException("Only the host can do that")
    }
}
