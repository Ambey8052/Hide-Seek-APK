// Persistence for games, per-player results and stats.
import { db, tx } from './db.js';
import { dayKey, RULES } from './config.js';
import { refundEntryFees } from './wallet.js';

export function usersWithoutFreeGame(userIds) {
  const today = dayKey();
  return userIds.filter((id) => {
    const u = db.prepare('SELECT free_game_day FROM users WHERE id = ?').get(id);
    return u?.free_game_day === today;
  });
}

export function markFreeGameUsed(userIds) {
  const today = dayKey();
  const stmt = db.prepare('UPDATE users SET free_game_day = ? WHERE id = ?');
  tx(() => userIds.forEach((id) => stmt.run(today, id)));
}

export function recordGameStart(room) {
  const players = [...room.players.values()];
  tx(() => {
    db.prepare(
      `INSERT INTO games (id, code, host_id, fee_paise, is_free, status, police_id, pot_paise,
                          server_seed, seed_hash, fairness_json, started_at)
       VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?, ?)`,
    ).run(
      room.id, room.code, room.hostId, room.feePaise, room.isFree ? 1 : 0, room.policeId,
      room.potPaise, room.serverSeed, room.seedHash,
      JSON.stringify({
        players: players.map((p) => ({ userId: p.userId, clientSeed: p.clientSeed })),
        lastPoliceId: room.lastPoliceId,
      }),
      Date.now(),
    );
    const ins = db.prepare('INSERT INTO game_players (game_id, user_id, role) VALUES (?, ?, ?)');
    players.forEach((p) => ins.run(room.id, p.userId, p.role));
  });
}

export function recordGameEnd(room, result) {
  const payoutBy = new Map(result.payouts.map((p) => [p.userId, p.paise]));
  const winners = new Set(result.winnerIds);
  tx(() => {
    db.prepare(
      `UPDATE games SET status = 'ENDED', winner_side = ?, result_json = ?, ended_at = ? WHERE id = ?`,
    ).run(result.winnerSide, JSON.stringify(result.public), Date.now(), room.id);

    const upd = db.prepare(
      'UPDATE game_players SET status = ?, payout_paise = ? WHERE game_id = ? AND user_id = ?',
    );
    const stats = db.prepare(
      `UPDATE users SET games_played = games_played + 1, games_won = games_won + ?,
         catches = catches + ?, xp = xp + ?, total_won_paise = total_won_paise + ? WHERE id = ?`,
    );
    for (const p of room.players.values()) {
      const won = winners.has(p.userId);
      const isPolice = p.userId === room.policeId;
      const catches = isPolice ? p.catches : 0;
      let xp = RULES.XP_PARTICIPATION + catches * RULES.XP_PER_CATCH;
      if (won) xp += isPolice ? RULES.XP_POLICE_WIN : RULES.XP_THIEF_SURVIVE;
      upd.run(p.status, payoutBy.get(p.userId) || 0, room.id, p.userId);
      stats.run(won ? 1 : 0, catches, xp, payoutBy.get(p.userId) || 0, p.userId);
    }
  });
}

export function recordGameVoid(room, reason) {
  db.prepare(
    `UPDATE games SET status = 'VOID', winner_side = 'none', result_json = ?, ended_at = ? WHERE id = ?`,
  ).run(JSON.stringify({ reason }), Date.now(), room.id);
}

/**
 * On server start, any game still ACTIVE was interrupted by a crash/restart.
 * Refund everyone so no money is ever stuck in a pot.
 */
export function recoverInterruptedGames() {
  const games = db.prepare(`SELECT * FROM games WHERE status = 'ACTIVE'`).all();
  for (const g of games) {
    const ids = db.prepare('SELECT user_id FROM game_players WHERE game_id = ?').all(g.id).map((r) => r.user_id);
    if (!g.is_free) refundEntryFees(g.id, ids, g.fee_paise, 'Server restarted - refund');
    db.prepare(
      `UPDATE games SET status = 'VOID', winner_side = 'none', result_json = ?, ended_at = ? WHERE id = ?`,
    ).run(JSON.stringify({ reason: 'Server restarted' }), Date.now(), g.id);
  }
  return games.length;
}

export function gameHistory(userId, limit = 30) {
  return db
    .prepare(
      `SELECT g.id, g.code, g.fee_paise, g.is_free, g.status, g.winner_side, g.pot_paise,
              g.started_at, g.ended_at, gp.role, gp.status AS my_status, gp.payout_paise
       FROM game_players gp JOIN games g ON g.id = gp.game_id
       WHERE gp.user_id = ? ORDER BY g.started_at DESC LIMIT ?`,
    )
    .all(userId, limit)
    .map((r) => ({
      id: r.id,
      code: r.code,
      feeRs: r.fee_paise / 100,
      isFree: !!r.is_free,
      status: r.status,
      winnerSide: r.winner_side,
      potRs: r.pot_paise / 100,
      role: r.role,
      myStatus: r.my_status,
      payoutRs: r.payout_paise / 100,
      startedAt: r.started_at,
      endedAt: r.ended_at,
    }));
}

/** Everything needed to independently re-derive who was police. */
export function fairnessProof(gameId) {
  const g = db.prepare('SELECT * FROM games WHERE id = ?').get(gameId);
  if (!g) return null;
  const revealed = g.status !== 'ACTIVE';
  return {
    gameId: g.id,
    seedHash: g.seed_hash,
    serverSeed: revealed ? g.server_seed : null,
    policeId: g.police_id,
    ...JSON.parse(g.fairness_json || '{}'),
    howToVerify:
      'sha256(serverSeed) must equal seedHash. Sort players by userId, join "userId:clientSeed" with "|", ' +
      'HMAC-SHA256 it with serverSeed as key, take the first 12 hex chars as an integer modulo the player ' +
      'count. If that player was lastPoliceId, take the next one.',
  };
}
