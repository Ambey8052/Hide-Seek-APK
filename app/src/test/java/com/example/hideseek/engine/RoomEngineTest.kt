package com.example.hideseek.engine

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plays whole games through the host engine with a fake clock - no phones needed. */
class RoomEngineTest {
    private var clock = 1_000_000L

    private val base = Loc(28.6139, 77.2090) // everyone starts at the same spot
    private fun north(m: Double) = Loc(base.lat + m / 111_320.0, base.lng, 3.0)

    private fun room(feeRs: Double = 10.0, isFree: Boolean = false, n: Int = 3): RoomEngine {
        val r = RoomEngine("ABCDEF", Identity("p0", "Host"), feeRs, isFree, onChange = {}, now = { clock })
        for (i in 1 until n) r.addPlayer(Identity("p$i", "Player $i"))
        return r
    }

    private fun RoomEngine.readyAll() = playerIds.forEach { setReady(it, true, "seed-$it") }
    private fun RoomEngine.me(id: String) = snapshotFor(id).getJSONObject("me")
    private fun RoomEngine.policeId() = playerIds.first { me(it).optString("role") == "police" }
    private fun RoomEngine.loc(id: String, l: Loc) =
        updateLocation(id, JSONObject().put("lat", l.lat).put("lng", l.lng).put("accuracy", l.accuracy))

    private fun advance(sec: Int, r: RoomEngine, step: (RoomEngine) -> Unit = {}) {
        repeat(sec) {
            clock += 1000
            step(r)
            r.tick()
        }
    }

    /** Deal, flip every card, and run the hiding phase with everyone at [base]. */
    private fun startHunt(r: RoomEngine): String {
        r.readyAll()
        r.deal("p0")
        assertEquals("DEALING", r.phase)
        r.playerIds.forEach { r.flip(it) }
        assertEquals("HIDING", r.phase)
        val police = r.policeId()
        advance(Timings.NORMAL.hideSec + 1, r) { e -> e.playerIds.forEach { e.loc(it, base) } }
        assertEquals("HUNTING", r.phase)
        return police
    }

    @Test
    fun policeCatchesEveryoneAndTakesThePot() {
        val r = room(feeRs = 20.0)
        val police = startHunt(r)
        val thieves = r.playerIds - police

        // First thief: police scans their QR code.
        val token = r.me(thieves[0]).getString("qrToken")
        assertThrows(GameException::class.java) { r.catchByScan(police, "not-a-real-code") }
        assertThrows(GameException::class.java) { r.catchByScan(thieves[1], token) } // only the police catches
        r.catchByScan(police, token)
        assertEquals("caught", r.me(thieves[0]).getString("status"))

        // Second thief: police holds CAUGHT on the thief's phone.
        r.catchByTap(thieves[1])
        assertEquals("ENDED", r.phase)

        val result = r.snapshotFor(police).getJSONObject("result")
        assertEquals("police", result.getString("winnerSide"))
        assertEquals(60.0, result.getDouble("potRs"), 0.0)
        val payouts = result.getJSONArray("payouts")
        assertEquals(1, payouts.length())
        assertEquals(police, payouts.getJSONObject(0).getString("userId"))
        assertEquals(60.0, payouts.getJSONObject(0).getDouble("rs"), 0.0)
        assertEquals(2, result.getInt("policeCatches"))
        // The seed is revealed after the game and matches the seal shown before it.
        val snap = r.snapshotFor(police)
        assertEquals(snap.getString("seedHash"), sha256(snap.getString("serverSeed")))
    }

    @Test
    fun thievesSplitThePotWhenTimeRunsOut() {
        val r = room(feeRs = 10.0, n = 4)
        startHunt(r)
        advance(Timings.NORMAL.gameSec + 1, r) { e -> e.playerIds.forEach { e.loc(it, base) } }
        assertEquals("ENDED", r.phase)
        val result = r.snapshotFor("p0").getJSONObject("result")
        assertEquals("thieves", result.getString("winnerSide"))
        val payouts = result.getJSONArray("payouts")
        assertEquals(3, payouts.length())
        // ₹40 pot / 3 thieves = 1334 + 1333 + 1333 paise: the whole pot is paid out.
        val total = (0 until payouts.length()).sumOf { (payouts.getJSONObject(it).getDouble("rs") * 100).toLong() }
        assertEquals(4000L, total)
    }

    @Test
    fun thiefOutsideTheZoneTooLongIsDisqualified() {
        val r = room(isFree = true, n = 3)
        val police = startHunt(r)
        val (runner, stayer) = r.playerIds - police
        advance(RULES.OUT_OF_RANGE_GRACE_SEC - 5, r) { e ->
            e.loc(police, base); e.loc(stayer, base); e.loc(runner, north(150.0))
        }
        assertTrue(r.me(runner).has("outOfRange") && !r.me(runner).isNull("outOfRange"))
        assertEquals("active", r.me(runner).getString("status"))
        advance(10, r) { e -> e.loc(police, base); e.loc(stayer, base); e.loc(runner, north(150.0)) }
        assertEquals("dq", r.me(runner).getString("status"))
        assertEquals("HUNTING", r.phase) // one thief still hiding
    }

    @Test
    fun bluetoothProximityWarnsThiefAndBuzzesPolice() {
        val r = room(isFree = true, n = 2)
        val police = startHunt(r)
        val thief = (r.playerIds - police).single()
        val thiefTag = r.me(thief).getString("bleTag")
        // GPS says 40 m apart, but Bluetooth hears the thief right next to the police.
        r.loc(police, base)
        r.loc(thief, north(40.0))
        r.updateProximity(police, JSONArray().put(JSONObject().put("tag", thiefTag).put("rssi", -55)))
        clock += 1000
        r.tick()
        assertTrue(r.me(thief).getBoolean("danger"))
        assertEquals("ble", r.me(thief).getString("proxSource"))
        assertEquals(1, r.me(police).getInt("closeCount"))

        // Stale Bluetooth readings expire and GPS takes over again.
        advance(RULES.BLE_FRESH_SEC + 1, r) { e -> e.loc(police, base); e.loc(thief, north(40.0)) }
        assertFalse(r.me(thief).getBoolean("danger"))
        assertEquals("gps", r.me(thief).getString("proxSource"))
    }

    @Test
    fun cardsStayHiddenUntilFlipped() {
        val r = room(isFree = true)
        r.readyAll()
        r.deal("p0")
        assertTrue(r.me("p1").isNull("role"))
        assertTrue(r.snapshotFor("p1").isNull("policeId"))
        r.flip("p1")
        assertFalse(r.me("p1").isNull("role"))
        assertTrue(r.me("p2").isNull("role"))
    }

    @Test
    fun lobbyRules() {
        val r = room(isFree = true, n = 2)
        assertThrows(GameException::class.java) { r.deal("p0") } // nobody ready
        r.readyAll()
        assertThrows(GameException::class.java) { r.deal("p1") } // only the host deals
        r.updateSettings("p0", JSONObject().put("quick", true))
        assertFalse(r.snapshotFor("p0").getJSONArray("players").getJSONObject(1).getBoolean("ready")) // terms changed
        assertEquals(Timings.QUICK.gameSec, r.snapshotFor("p0").getJSONObject("rules").getInt("gameSec"))
        r.readyAll()
        r.deal("p0")
        // Strangers can't join a running game, but a player who lost the link can come back.
        assertThrows(GameException::class.java) { r.addPlayer(Identity("p9", "Late")) }
        r.setOnline("p1", false)
        r.addPlayer(Identity("p1", "Player 1"))
        assertTrue(r.snapshotFor("p0").getJSONArray("players").getJSONObject(1).getBoolean("online"))
    }

    @Test
    fun policeLeavingForfeitsToTheThieves() {
        val r = room(feeRs = 10.0, n = 3)
        val police = startHunt(r)
        r.removePlayer(police)
        val result = r.snapshotFor("p0").getJSONObject("result")
        assertEquals("thieves", result.getString("winnerSide"))
        assertEquals(2, result.getJSONArray("payouts").length())
    }

    @Test
    fun rematchNeverRepeatsThePolice() {
        val r = room(isFree = true, n = 2)
        var last: String? = null
        repeat(6) {
            val police = startHunt(r)
            if (last != null) assertNotEquals(last, police)
            last = police
            r.removePlayer(police) // quick way to end the round
            r.addPlayer(Identity(police, "back")) // came back online
            r.rematch(r.hostId)
            assertEquals("LOBBY", r.phase)
            assertNull(r.snapshotFor("p0").opt("result").takeIf { it != JSONObject.NULL })
        }
    }

    @Test
    fun potSplitAndFairnessHelpers() {
        val (payouts, platform) = splitPot(1000, listOf("b", "a", "c"))
        assertEquals(listOf(334L, 333L, 333L), payouts.map { it.paise })
        assertEquals("a", payouts[0].userId)
        assertEquals(0L, platform)
        val seeds = listOf("x" to "1", "y" to "2", "z" to "3")
        assertEquals(pickPolice("seed", seeds, null), pickPolice("seed", seeds.reversed(), null))
    }
}
