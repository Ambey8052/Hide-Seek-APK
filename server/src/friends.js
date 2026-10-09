import { db, tx } from './db.js';
import { HttpError } from './errors.js';
import { normalizePhone, isValidPhone, playerCode } from './auth.js';

export function areFriends(a, b) {
  return !!db.prepare('SELECT 1 FROM friends WHERE user_id = ? AND friend_id = ?').get(a, String(b));
}

/** Friendship is mutual: adding someone adds you to their list as well. */
/** Add by 10-digit mobile number or by 8-character player code. */
export function addFriendByPhone(userId, raw) {
  const input = String(raw || '').trim();
  let friend;
  if (/^[0-9a-f]{8}$/i.test(input)) {
    friend = db
      .prepare('SELECT id, name FROM users')
      .all()
      .find((u) => playerCode(u.id) === input.toUpperCase());
    if (!friend) throw new HttpError(404, 'No player with that code');
  } else {
    const phone = normalizePhone(input);
    if (!isValidPhone(phone)) throw new HttpError(400, 'Enter a 10-digit mobile number or an 8-character player code');
    friend = db.prepare('SELECT id, name FROM users WHERE phone = ?').get(phone);
    if (!friend) throw new HttpError(404, 'No player with that number yet - invite them to install the app');
  }
  if (friend.id === userId) throw new HttpError(400, "That's you!");
  const now = Date.now();
  const ins = db.prepare('INSERT OR IGNORE INTO friends (user_id, friend_id, created_at) VALUES (?, ?, ?)');
  tx(() => {
    ins.run(userId, friend.id, now);
    ins.run(friend.id, userId, now);
  });
  return friend;
}

export function removeFriend(userId, friendId) {
  tx(() => {
    db.prepare('DELETE FROM friends WHERE user_id = ? AND friend_id = ?').run(userId, friendId);
    db.prepare('DELETE FROM friends WHERE user_id = ? AND friend_id = ?').run(friendId, userId);
  });
}

export function listFriends(userId, presence) {
  return db
    .prepare(
      `SELECT u.id, u.name, u.xp, u.games_won FROM friends f JOIN users u ON u.id = f.friend_id
       WHERE f.user_id = ? ORDER BY u.name`,
    )
    .all(userId)
    .map((u) => ({ id: u.id, name: u.name, xp: u.xp, gamesWon: u.games_won, online: !!presence.get(u.id) }));
}

/** You + your friends, ranked by XP. */
export function leaderboard(userId) {
  return db
    .prepare(
      `SELECT id, name, xp, games_played, games_won, catches, total_won_paise FROM users
       WHERE id = ? OR id IN (SELECT friend_id FROM friends WHERE user_id = ?)
       ORDER BY xp DESC, games_won DESC LIMIT 50`,
    )
    .all(userId, userId)
    .map((u, i) => ({
      rank: i + 1,
      id: u.id,
      name: u.name,
      xp: u.xp,
      gamesPlayed: u.games_played,
      gamesWon: u.games_won,
      catches: u.catches,
      totalWonRs: u.total_won_paise / 100,
      isMe: u.id === userId,
    }));
}
