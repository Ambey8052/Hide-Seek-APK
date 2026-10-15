package com.example.hideseek.data

import org.json.JSONArray
import org.json.JSONObject

// Mirrors of the JSON the host phone sends (engine/RoomEngine.snapshotFor).
// Android's JSONObject.optString() turns JSON null into the string "null", so nullable
// fields go through the helpers below.

fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key)
fun JSONObject.long(key: String): Long? = if (isNull(key)) null else optLong(key)
fun JSONObject.dbl(key: String): Double? = if (isNull(key)) null else optDouble(key)
fun JSONObject.obj(key: String): JSONObject? = if (isNull(key)) null else optJSONObject(key)

inline fun <T> JSONArray?.mapObjects(f: (JSONObject) -> T): List<T> =
    if (this == null) emptyList() else List(length()) { f(getJSONObject(it)) }

data class User(
    val id: String,
    val name: String,
    val phoneMasked: String,
    val code: String,           // short player code friends can type to add you
    val gamesPlayed: Int,
    val gamesWon: Int,
    val catches: Int,
    val xp: Int,
    val totalWonRs: Double,
) {
    val level get() = xp / 100 + 1

    companion object {
        fun parse(o: JSONObject) = User(
            id = o.getString("id"),
            name = o.optString("name"),
            phoneMasked = o.optString("phoneMasked"),
            code = o.optString("code"),
            gamesPlayed = o.optInt("gamesPlayed"),
            gamesWon = o.optInt("gamesWon"),
            catches = o.optInt("catches"),
            xp = o.optInt("xp"),
            totalWonRs = o.optDouble("totalWonRs", 0.0),
        )
    }
}

data class Txn(val id: Long, val type: String, val amountRs: Double, val note: String?, val at: Long)

data class Wallet(
    val balanceRs: Double,
    val depositedTodayRs: Double,
    val dailyDepositLimitRs: Double,
    val canDepositTodayRs: Double,
    val freeGameAvailable: Boolean,
    val transactions: List<Txn>,
) {
    companion object {
        fun parse(o: JSONObject) = Wallet(
            balanceRs = o.optDouble("balanceRs", 0.0),
            depositedTodayRs = o.optDouble("depositedTodayRs", 0.0),
            dailyDepositLimitRs = o.optDouble("dailyDepositLimitRs", 500.0),
            canDepositTodayRs = o.optDouble("canDepositTodayRs", 0.0),
            freeGameAvailable = o.optBoolean("freeGameAvailable"),
            transactions = o.optJSONArray("transactions").mapObjects {
                Txn(it.optLong("id"), it.optString("type"), it.optDouble("amountRs", 0.0), it.str("note"), it.optLong("at"))
            },
        )
    }
}

data class RankRow(
    val rank: Int, val id: String, val name: String, val xp: Int, val gamesPlayed: Int,
    val gamesWon: Int, val catches: Int, val totalWonRs: Double, val isMe: Boolean,
) {
    companion object {
        fun parse(o: JSONObject) = RankRow(
            o.optInt("rank"), o.getString("id"), o.optString("name"), o.optInt("xp"), o.optInt("gamesPlayed"),
            o.optInt("gamesWon"), o.optInt("catches"), o.optDouble("totalWonRs", 0.0), o.optBoolean("isMe"),
        )
    }
}

data class GameHistory(
    val id: String, val code: String, val feeRs: Double, val isFree: Boolean, val status: String,
    val winnerSide: String?, val role: String?, val myStatus: String?, val payoutRs: Double, val startedAt: Long,
) {
    companion object {
        fun parse(o: JSONObject) = GameHistory(
            o.getString("id"), o.optString("code"), o.optDouble("feeRs", 0.0), o.optBoolean("isFree"),
            o.optString("status"), o.str("winnerSide"), o.str("role"), o.str("myStatus"),
            o.optDouble("payoutRs", 0.0), o.optLong("startedAt"),
        )
    }
}

/** A room being advertised by a host phone nearby (Bluetooth / Wi-Fi). */
data class NearbyRoom(val endpointId: String, val code: String, val hostName: String)

// ------------------------------------------------------------------ room snapshot

object Phase {
    const val LOBBY = "LOBBY"
    const val DEALING = "DEALING"
    const val HIDING = "HIDING"
    const val HUNTING = "HUNTING"
    const val ENDED = "ENDED"
    const val VOID = "VOID"
}

data class Rules(
    val minPlayers: Int, val maxPlayers: Int, val boundaryM: Int, val graceSec: Int,
    val maxStrikes: Int, val gameSec: Int, val hideSec: Int, val finalHuntSec: Int,
)

data class Player(
    val userId: String, val name: String, val isHost: Boolean, val online: Boolean, val ready: Boolean,
    val flipped: Boolean, val status: String, val dqReason: String?,
)

data class Pulse(val at: Long, val rings: List<Int>)

data class Me(
    val userId: String,
    val role: String?,          // null until this player's card is flipped
    val status: String,         // active | caught | dq
    val dqReason: String?,
    val strikes: Int,
    val outOfRangeDeadline: Long?,
    val danger: Boolean,        // thief: police within 1 m -> green dot
    val heat: String?,          // cold | warm | hot | burning
    val qrToken: String?,
    val closeCount: Int,        // police: thieves within radar range -> vibrate
    val pulse: Pulse?,
    val nextPulseAt: Long?,
    val catches: Int,
    val bleTag: String?,        // what this phone broadcasts over Bluetooth during a game
    val proxSource: String?,    // ble | gps - what the server used for the last close-range check
) {
    val isPolice get() = role == "police"
    val isActive get() = status == "active"
}

data class GameEvent(val id: Long, val at: Long, val text: String, val kind: String)

data class Payout(val userId: String, val name: String, val rs: Double)

data class GameResult(
    val reason: String,
    val winnerSide: String,
    val winnerIds: List<String>,
    val payouts: List<Payout>,
    val potRs: Double,
    val platformFeeRs: Double,
    val isFree: Boolean,
    val refunded: Boolean,
    val policeCatches: Int,
)

data class Room(
    val code: String,
    val gameId: String,
    val hostId: String,
    val phase: String,
    val feeRs: Double,
    val isFree: Boolean,
    val potRs: Double,
    val boundaryMode: String,
    val heatMeter: Boolean,
    val quick: Boolean,
    val rules: Rules,
    val seedHash: String,
    val serverSeed: String?,
    val serverNow: Long,
    val flipDeadline: Long?,
    val hideEndsAt: Long?,
    val huntEndsAt: Long?,
    val policeId: String?,
    val players: List<Player>,
    val me: Me?,
    val events: List<GameEvent>,
    val result: GameResult?,
) {
    val inGame get() = phase == Phase.DEALING || phase == Phase.HIDING || phase == Phase.HUNTING
    val finished get() = phase == Phase.ENDED || phase == Phase.VOID
    val police get() = players.firstOrNull { it.userId == policeId }
    val thieves get() = players.filter { it.userId != policeId }

    companion object {
        fun parse(o: JSONObject): Room {
            val r = o.getJSONObject("rules")
            val s = o.optJSONObject("settings") ?: JSONObject()
            return Room(
                code = o.getString("code"),
                gameId = o.optString("gameId"),
                hostId = o.getString("hostId"),
                phase = o.getString("phase"),
                feeRs = o.optDouble("feeRs", 0.0),
                isFree = o.optBoolean("isFree"),
                potRs = o.optDouble("potRs", 0.0),
                boundaryMode = s.optString("boundaryMode", "police"),
                heatMeter = s.optBoolean("heatMeter", true),
                quick = s.optBoolean("quick"),
                rules = Rules(
                    r.optInt("minPlayers", 2), r.optInt("maxPlayers", 10), r.optInt("boundaryM", 100),
                    r.optInt("graceSec", 30), r.optInt("maxStrikes", 3), r.optInt("gameSec", 1200),
                    r.optInt("hideSec", 90), r.optInt("finalHuntSec", 120),
                ),
                seedHash = o.optString("seedHash"),
                serverSeed = o.str("serverSeed"),
                serverNow = o.optLong("serverNow"),
                flipDeadline = o.long("flipDeadline"),
                hideEndsAt = o.long("hideEndsAt"),
                huntEndsAt = o.long("huntEndsAt"),
                policeId = o.str("policeId"),
                players = o.optJSONArray("players").mapObjects {
                    Player(
                        it.getString("userId"), it.optString("name"), it.optBoolean("isHost"), it.optBoolean("online"),
                        it.optBoolean("ready"), it.optBoolean("flipped"), it.optString("status", "active"), it.str("dqReason"),
                    )
                },
                me = o.obj("me")?.let { m ->
                    Me(
                        userId = m.getString("userId"),
                        role = m.str("role"),
                        status = m.optString("status", "active"),
                        dqReason = m.str("dqReason"),
                        strikes = m.optInt("strikes"),
                        outOfRangeDeadline = m.obj("outOfRange")?.optLong("deadline"),
                        danger = m.optBoolean("danger"),
                        heat = m.str("heat"),
                        qrToken = m.str("qrToken"),
                        closeCount = m.optInt("closeCount"),
                        pulse = m.obj("pulse")?.let { p ->
                            val rings = p.optJSONArray("rings")
                            Pulse(p.optLong("at"), List(rings?.length() ?: 0) { rings!!.optInt(it) })
                        },
                        nextPulseAt = m.long("nextPulseAt"),
                        catches = m.optInt("catches"),
                        bleTag = m.str("bleTag"),
                        proxSource = m.str("proxSource"),
                    )
                },
                events = o.optJSONArray("events").mapObjects {
                    GameEvent(it.optLong("id"), it.optLong("at"), it.optString("text"), it.optString("kind"))
                },
                result = o.obj("result")?.let { res ->
                    GameResult(
                        reason = res.optString("reason"),
                        winnerSide = res.optString("winnerSide", "none"),
                        winnerIds = res.optJSONArray("winners").mapObjects { it.getString("userId") },
                        payouts = res.optJSONArray("payouts").mapObjects {
                            Payout(it.getString("userId"), it.optString("name"), it.optDouble("rs", 0.0))
                        },
                        potRs = res.optDouble("potRs", 0.0),
                        platformFeeRs = res.optDouble("platformFeeRs", 0.0),
                        isFree = res.optBoolean("isFree"),
                        refunded = res.optBoolean("refunded"),
                        policeCatches = res.optInt("policeCatches"),
                    )
                },
            )
        }
    }
}
