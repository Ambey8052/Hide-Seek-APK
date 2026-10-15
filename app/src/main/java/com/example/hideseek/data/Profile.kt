package com.example.hideseek.data

import android.content.Context
import com.example.hideseek.engine.GameException
import com.example.hideseek.engine.RULES
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID

private val ADJECTIVES = listOf("Sneaky", "Swift", "Silent", "Brave", "Clever", "Lucky", "Shadow", "Quick", "Sly", "Bold")
private val ANIMALS = listOf("Tiger", "Fox", "Cobra", "Eagle", "Panther", "Monkey", "Owl", "Leopard", "Mongoose", "Falcon")
private const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // no 0/O/1/I confusion

/**
 * This phone's player: identity, stats, test wallet and game history, all stored on the
 * device. There is no account and no server - other phones only see the id and name.
 *
 * The wallet is play money for testing (deposits are instant, nothing is charged). Each
 * phone applies its own entry fee, winnings and refunds from the host's game results.
 */
class Profile(context: Context) {
    private val prefs = context.getSharedPreferences("hideseek", Context.MODE_PRIVATE)

    val id: String = prefs.getString(KEY_ID, null)
        ?: UUID.randomUUID().toString().also { prefs.edit().putString(KEY_ID, it).apply() }

    private val _user = MutableStateFlow(loadUser())
    val user: StateFlow<User> = _user

    private val _wallet = MutableStateFlow(loadWallet())
    val wallet: StateFlow<Wallet> = _wallet

    private val _history = MutableStateFlow(loadHistory())
    val history: StateFlow<List<GameHistory>> = _history

    val name get() = _user.value.name

    // ---------------------------------------------------------------- profile

    private fun loadUser(): User {
        val o = prefs.getString(KEY_USER, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (o != null) return User.parse(o)
        val r = java.util.Random()
        val user = User(
            id = id,
            name = ADJECTIVES[r.nextInt(ADJECTIVES.size)] + " " + ANIMALS[r.nextInt(ANIMALS.size)],
            phoneMasked = "",
            code = (1..8).map { CODE_ALPHABET[r.nextInt(CODE_ALPHABET.length)] }.joinToString(""),
            gamesPlayed = 0, gamesWon = 0, catches = 0, xp = 0, totalWonRs = 0.0,
        )
        saveUser(user)
        return user
    }

    private fun saveUser(u: User) {
        prefs.edit().putString(
            KEY_USER,
            JSONObject().put("id", u.id).put("name", u.name).put("code", u.code).put("gamesPlayed", u.gamesPlayed)
                .put("gamesWon", u.gamesWon).put("catches", u.catches).put("xp", u.xp).put("totalWonRs", u.totalWonRs)
                .toString(),
        ).apply()
        _user.value = u
    }

    fun updateName(name: String) {
        val clean = name.trim().take(20)
        if (clean.length < 2) throw GameException("Name must be at least 2 characters")
        saveUser(_user.value.copy(name = clean))
    }

    // ---------------------------------------------------------------- wallet

    private fun txns(): List<Txn> = prefs.getString(KEY_TXNS, null)
        ?.let { runCatching { JSONArray(it) }.getOrNull() }
        .mapObjects { Txn(it.optLong("id"), it.optString("type"), it.optDouble("amountRs"), it.str("note"), it.optLong("at")) }

    private fun loadWallet(): Wallet {
        val all = txns()
        val today = startOfToday()
        val depositedToday = all.filter { it.type == "DEPOSIT" && it.at >= today }.sumOf { it.amountRs }
        val limit = RULES.DAILY_DEPOSIT_LIMIT_RS.toDouble()
        return Wallet(
            balanceRs = Math.round(all.sumOf { it.amountRs } * 100) / 100.0,
            depositedTodayRs = depositedToday,
            dailyDepositLimitRs = limit,
            canDepositTodayRs = (limit - depositedToday).coerceAtLeast(0.0),
            freeGameAvailable = true, // offline test build: free games are unlimited
            transactions = all.sortedByDescending { it.at }.take(50),
        )
    }

    private fun addTxn(type: String, amountRs: Double, note: String?) {
        val arr = prefs.getString(KEY_TXNS, null)?.let { runCatching { JSONArray(it) }.getOrNull() } ?: JSONArray()
        val now = System.currentTimeMillis()
        arr.put(JSONObject().put("id", now).put("type", type).put("amountRs", amountRs).put("note", note).put("at", now))
        // Keep the newest 200 entries, folding older ones into one balance line.
        if (arr.length() > 200) {
            val drop = arr.length() - 199
            var carried = 0.0
            val kept = JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (i < drop) carried += o.optDouble("amountRs") else kept.put(o)
            }
            val first = JSONObject().put("id", 0).put("type", "CARRIED").put("amountRs", carried).put("note", "Earlier history").put("at", 0)
            prefs.edit().putString(KEY_TXNS, JSONArray().put(first).also { a -> for (i in 0 until kept.length()) a.put(kept.get(i)) }.toString()).apply()
        } else {
            prefs.edit().putString(KEY_TXNS, arr.toString()).apply()
        }
        _wallet.value = loadWallet()
    }

    fun deposit(amountRs: Double) {
        val w = _wallet.value
        if (amountRs <= 0 || amountRs != Math.floor(amountRs)) throw GameException("Enter a whole amount in ₹")
        if (amountRs > w.canDepositTodayRs) throw GameException("Daily limit: you can add ${w.canDepositTodayRs.toInt()} more today")
        addTxn("DEPOSIT", amountRs, "Test money")
    }

    fun withdraw(amountRs: Double) {
        if (amountRs <= 0) throw GameException("Enter an amount")
        if (amountRs > _wallet.value.balanceRs) throw GameException("Not enough balance")
        addTxn("WITHDRAWAL", -amountRs, null)
    }

    // ---------------------------------------------------------------- games

    private val settled get() = prefs.getStringSet(KEY_SETTLED, emptySet())!!

    /** Takes the entry fee once per game. */
    fun chargeEntry(gameId: String, feeRs: Double, code: String) {
        val key = "fee:$gameId"
        if (key in settled) return
        markSettled(key)
        addTxn("ENTRY_FEE", -feeRs, "Room $code")
    }

    /**
     * Applies a finished game once: winnings / refund, stats, XP and history.
     * Money only moves if this phone actually paid the entry fee for that game.
     */
    fun settleGame(room: Room) {
        val me = room.me ?: return
        val r = room.result ?: return
        val key = "end:${room.gameId}"
        if (key in settled) return
        markSettled(key)
        val isPolice = me.userId == room.policeId
        val role = if (isPolice) "police" else "thief"

        val entryPaid = "fee:${room.gameId}" in settled
        val payout = r.payouts.firstOrNull { it.userId == me.userId }?.rs ?: 0.0
        if (entryPaid && payout > 0) addTxn("WINNINGS", payout, "Room ${room.code}")
        if (entryPaid && r.refunded) addTxn("REFUND", room.feeRs, "Room ${room.code}")

        val won = me.userId in r.winnerIds
        if (room.phase == Phase.ENDED) {
            val xp = RULES.XP_PARTICIPATION + me.catches * RULES.XP_PER_CATCH +
                (if (won) if (isPolice) RULES.XP_POLICE_WIN else RULES.XP_THIEF_SURVIVE else 0)
            val u = _user.value
            saveUser(
                u.copy(
                    gamesPlayed = u.gamesPlayed + 1,
                    gamesWon = u.gamesWon + if (won) 1 else 0,
                    catches = u.catches + me.catches,
                    xp = u.xp + xp,
                    totalWonRs = u.totalWonRs + payout,
                ),
            )
        }

        val entry = JSONObject()
            .put("id", room.gameId).put("code", room.code).put("feeRs", room.feeRs).put("isFree", room.isFree)
            .put("status", room.phase).put("winnerSide", r.winnerSide).put("role", role).put("myStatus", me.status)
            .put("payoutRs", payout).put("startedAt", System.currentTimeMillis())
            .put("players", JSONArray(room.players.map {
                JSONObject().put("id", it.userId).put("name", it.name).put("won", it.userId in r.winnerIds)
                    .put("catches", if (it.userId == room.policeId) r.policeCatches else 0)
            }))
        val arr = prefs.getString(KEY_HISTORY, null)?.let { runCatching { JSONArray(it) }.getOrNull() } ?: JSONArray()
        val list = mutableListOf(entry)
        for (i in 0 until minOf(arr.length(), 99)) list += arr.getJSONObject(i)
        prefs.edit().putString(KEY_HISTORY, JSONArray(list).toString()).apply()
        _history.value = loadHistory()
    }

    /** The game was cancelled without a result (host closed it / vanished): give the entry back. */
    fun refundCancelled(room: Room) {
        if ("fee:${room.gameId}" !in settled) return
        val key = "end:${room.gameId}"
        if (key in settled) return
        markSettled(key)
        addTxn("REFUND", room.feeRs, "Room ${room.code} cancelled")
    }

    private fun loadHistory(): List<GameHistory> = prefs.getString(KEY_HISTORY, null)
        ?.let { runCatching { JSONArray(it) }.getOrNull() }
        .mapObjects(GameHistory::parse)

    /** Everyone this phone has played with, ranked by wins together. Includes me. */
    fun ranking(): List<RankRow> {
        val arr = prefs.getString(KEY_HISTORY, null)?.let { runCatching { JSONArray(it) }.getOrNull() } ?: JSONArray()
        data class Acc(var name: String, var played: Int = 0, var won: Int = 0, var catches: Int = 0)
        val acc = LinkedHashMap<String, Acc>()
        for (i in 0 until arr.length()) {
            val g = arr.getJSONObject(i)
            if (g.optString("status") != Phase.ENDED) continue
            g.optJSONArray("players").mapObjects { p ->
                val a = acc.getOrPut(p.getString("id")) { Acc(p.optString("name")) }
                a.played += 1
                if (p.optBoolean("won")) a.won += 1
                a.catches += p.optInt("catches")
            }
        }
        val me = _user.value
        acc[id] = Acc(me.name, me.gamesPlayed, me.gamesWon, me.catches)
        return acc.entries
            .map { (pid, a) ->
                val xp = if (pid == id) me.xp else a.played * RULES.XP_PARTICIPATION + a.won * RULES.XP_THIEF_SURVIVE + a.catches * RULES.XP_PER_CATCH
                Triple(pid, a, xp)
            }
            .sortedWith(compareByDescending<Triple<String, Acc, Int>> { it.second.won }.thenByDescending { it.third })
            .mapIndexed { i, (pid, a, xp) ->
                RankRow(i + 1, pid, a.name, xp, a.played, a.won, a.catches, if (pid == id) me.totalWonRs else 0.0, pid == id)
            }
    }

    private fun markSettled(key: String) {
        val set = settled.toMutableSet()
        set += key
        if (set.size > 400) set.remove(set.first())
        prefs.edit().putStringSet(KEY_SETTLED, set).apply()
    }

    private fun startOfToday() = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private companion object {
        const val KEY_ID = "player_id"
        const val KEY_USER = "player"
        const val KEY_TXNS = "wallet_txns"
        const val KEY_HISTORY = "game_history"
        const val KEY_SETTLED = "settled_games"
    }
}
