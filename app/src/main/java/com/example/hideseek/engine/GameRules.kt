package com.example.hideseek.engine

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// Game rules and pure rule helpers, ported from the old Node server (server/src/config.js
// and rules.js). The host phone runs the game, so these live in the app now.

/** Timings that can be shortened for quick test games. Everything else is in [RULES]. */
data class Timings(val gameSec: Int, val hideSec: Int, val finalHuntSec: Int, val radarPulseEverySec: Int) {
    companion object {
        val NORMAL = Timings(gameSec = 20 * 60, hideSec = 90, finalHuntSec = 120, radarPulseEverySec = 180)
        /** "Quick test" lobby option: try the whole flow in a few minutes. */
        val QUICK = Timings(gameSec = 3 * 60, hideSec = 20, finalHuntSec = 60, radarPulseEverySec = 45)
    }
}

object RULES {
    const val MIN_PLAYERS = 2
    const val MAX_PLAYERS = 10

    const val MIN_FEE_RS = 10
    const val MAX_FEE_RS = 100
    const val DAILY_DEPOSIT_LIMIT_RS = 500
    const val PLATFORM_FEE_PERCENT = 0

    // If a player never shakes, the card flips automatically after this.
    const val AUTO_FLIP_SEC = 45

    const val BOUNDARY_RADIUS_M = 100
    const val OUT_OF_RANGE_GRACE_SEC = 30
    // Leaving the zone repeatedly to shake off the police is not allowed.
    const val MAX_OUT_OF_RANGE_STRIKES = 3

    const val CLOSE_RADIUS_M = 1.0
    // Phone GPS is only accurate to a few metres, so "within 1 m" is evaluated
    // as "could be within 1 m given the reported accuracy", capped at this value.
    const val MAX_GPS_UNCERTAINTY_M = 5.0
    // Bluetooth (BLE) proximity: phones broadcast a per-game tag and report the signal
    // strength (RSSI, dBm) of the tags they hear. When fresh, BLE overrides GPS for
    // the close-range checks because it works at 1-2 m where GPS cannot.
    const val BLE_FRESH_SEC = 6
    const val BLE_CLOSE_RSSI = -62.0 // about 1 m between pockets/hands
    const val BLE_FINAL_HUNT_RSSI = -80.0 // about 10 m
    const val BLE_BURNING_RSSI = -72.0 // about 3-5 m
    // Last minutes of the hunt: police radar widens so endings are tense.
    const val FINAL_HUNT_CLOSE_RADIUS_M = 10.0

    const val LOCATION_STALE_SEC = 20
    const val THIEF_OFFLINE_DQ_SEC = 90
    const val POLICE_OFFLINE_VOID_SEC = 180

    const val QR_TOKEN_TTL_SEC = 45
    const val QR_TOKEN_ROTATE_SEC = 30

    const val XP_POLICE_WIN = 50
    const val XP_THIEF_SURVIVE = 30
    const val XP_PER_CATCH = 10
    const val XP_PARTICIPATION = 5
}

data class Loc(val lat: Double, val lng: Double, val accuracy: Double = 0.0)

private const val EARTH_RADIUS_M = 6_371_000.0
private fun rad(d: Double) = d * Math.PI / 180

/** Great-circle distance in metres. */
fun distanceM(a: Loc, b: Loc): Double {
    val dLat = rad(b.lat - a.lat)
    val dLng = rad(b.lng - a.lng)
    val s1 = sin(dLat / 2)
    val s2 = sin(dLng / 2)
    val h = s1 * s1 + cos(rad(a.lat)) * cos(rad(b.lat)) * s2 * s2
    return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(h)))
}

/** Combined GPS uncertainty of two fixes, capped so bad fixes can't trigger everything. */
fun uncertaintyM(a: Loc, b: Loc) = min(a.accuracy + b.accuracy, RULES.MAX_GPS_UNCERTAINTY_M)

/** Out of the zone only when out even after giving the benefit of GPS error. */
fun isOutOfZone(thief: Loc, center: Loc, radiusM: Double = RULES.BOUNDARY_RADIUS_M.toDouble()) =
    distanceM(thief, center) - uncertaintyM(thief, center) > radiusM

/** Police "could be" within closeRadius given GPS error. */
fun isClose(thief: Loc, police: Loc, closeRadiusM: Double) =
    distanceM(thief, police) - uncertaintyM(thief, police) <= closeRadiusM

/** Distance band shown on a thief's heat meter. */
fun heatLevel(distance: Double) = when {
    distance <= 5 -> "burning"
    distance <= 20 -> "hot"
    distance <= 50 -> "warm"
    else -> "cold"
}

fun validateFeeRs(feeRs: Double) = feeRs == floor(feeRs) && feeRs >= RULES.MIN_FEE_RS && feeRs <= RULES.MAX_FEE_RS

data class PayoutShare(val userId: String, val paise: Long)

/**
 * Split a pot equally between winners, in integer paise. Leftover paise go one each
 * to winners in id order, so the whole pot is always paid out deterministically.
 */
fun splitPot(potPaise: Long, winnerIds: List<String>, platformFeePercent: Int = 0): Pair<List<PayoutShare>, Long> {
    if (winnerIds.isEmpty()) return emptyList<PayoutShare>() to potPaise
    val platform = potPaise * platformFeePercent / 100
    val distributable = potPaise - platform
    val sorted = winnerIds.sorted()
    val base = distributable / sorted.size
    var remainder = distributable - base * sorted.size
    val payouts = sorted.map { id ->
        val extra = if (remainder > 0) 1 else 0
        remainder -= extra
        PayoutShare(id, base + extra)
    }
    return payouts to platform
}

/**
 * - Police catches every thief (no active thieves left) -> police takes the pot.
 * - Otherwise (time up / police forfeits)               -> surviving thieves split it.
 */
fun decideWinners(policeId: String, survivors: List<String>, policeForfeited: Boolean): Pair<String, List<String>> = when {
    !policeForfeited && survivors.isEmpty() -> "police" to listOf(policeId)
    survivors.isEmpty() -> "none" to emptyList()
    else -> "thieves" to survivors
}

// ---------------------------------------------------------------------------
// Provably-fair police selection. The host commits to sha256(seed) when the round
// starts, every player adds their own random clientSeed, and the police is derived
// from HMAC(seed, all client seeds). The seed is revealed after the game.
// ---------------------------------------------------------------------------

fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

fun sha256(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).hex()

fun hmacSha256(key: String, msg: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key.toByteArray(), "HmacSHA256"))
    return mac.doFinal(msg.toByteArray()).hex()
}

/** Players sorted by id, each with their client seed. */
fun fairnessMessage(seeds: List<Pair<String, String?>>) =
    seeds.sortedBy { it.first }.joinToString("|") { "${it.first}:${it.second}" }

/** userId of the police; never the same player twice in a row. */
fun pickPolice(serverSeed: String, seeds: List<Pair<String, String?>>, lastPoliceId: String?): String {
    val sorted = seeds.sortedBy { it.first }
    val digest = hmacSha256(serverSeed, fairnessMessage(sorted))
    var index = (digest.substring(0, 12).toLong(16) % sorted.size).toInt()
    if (sorted.size > 1 && sorted[index].first == lastPoliceId) index = (index + 1) % sorted.size
    return sorted[index].first
}
