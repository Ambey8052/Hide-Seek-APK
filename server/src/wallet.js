// Wallet + ledger. All amounts are integer paise. Every balance change writes a
// transaction row with the resulting balance, so the ledger is fully auditable.
import { db, tx } from './db.js';
import { RULES, dayKey } from './config.js';
import { HttpError } from './errors.js';

export const toPaise = (rs) => Math.round(Number(rs) * 100);
export const toRs = (paise) => paise / 100;

export function ensureWallet(userId) {
  db.prepare('INSERT OR IGNORE INTO wallets (user_id, balance_paise) VALUES (?, 0)').run(userId);
}

export function getBalancePaise(userId) {
  ensureWallet(userId);
  return db.prepare('SELECT balance_paise FROM wallets WHERE user_id = ?').get(userId).balance_paise;
}

/** Must be called inside tx(). Throws if the balance would go negative. */
function applyChange(userId, amountPaise, type, { gameId = null, note = null } = {}) {
  ensureWallet(userId);
  const balance = getBalancePaise(userId);
  const next = balance + amountPaise;
  if (next < 0) throw new HttpError(400, 'Insufficient wallet balance');
  db.prepare('UPDATE wallets SET balance_paise = ? WHERE user_id = ?').run(next, userId);
  const now = Date.now();
  db.prepare(
    `INSERT INTO transactions (user_id, type, amount_paise, balance_after, game_id, note, day, created_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
  ).run(userId, type, amountPaise, next, gameId, note, dayKey(now), now);
  return next;
}

export function depositedTodayPaise(userId) {
  const row = db
    .prepare(`SELECT COALESCE(SUM(amount_paise), 0) AS total FROM transactions
              WHERE user_id = ? AND type = 'DEPOSIT' AND day = ?`)
    .get(userId, dayKey());
  return row.total;
}

export function deposit(userId, amountRs, note = 'Wallet top-up') {
  const amount = toPaise(amountRs);
  if (!Number.isFinite(amount) || amount < 100) throw new HttpError(400, 'Minimum deposit is ₹1');
  const limit = toPaise(RULES.DAILY_DEPOSIT_LIMIT_RS);
  return tx(() => {
    const already = depositedTodayPaise(userId);
    if (already + amount > limit) {
      throw new HttpError(
        400,
        `Daily deposit limit is ₹${RULES.DAILY_DEPOSIT_LIMIT_RS}. You can add ₹${toRs(limit - already)} more today.`,
      );
    }
    return applyChange(userId, amount, 'DEPOSIT', { note });
  });
}

export function requestWithdrawal(userId, amountRs) {
  const amount = toPaise(amountRs);
  if (!Number.isFinite(amount) || amount < 100) throw new HttpError(400, 'Minimum withdrawal is ₹1');
  // Money leaves the wallet immediately; an ops/payout job settles it to the bank/UPI.
  return tx(() => applyChange(userId, -amount, 'WITHDRAWAL', { note: 'Pending bank/UPI payout' }));
}

/**
 * Take the entry fee from every player at once - all or nothing.
 * Returns the list of userIds who can't afford it (nothing is charged in that case).
 */
export function collectEntryFees(gameId, userIds, feePaise) {
  const short = userIds.filter((id) => getBalancePaise(id) < feePaise);
  if (short.length) return { ok: false, short };
  tx(() => {
    for (const id of userIds) applyChange(id, -feePaise, 'ENTRY_FEE', { gameId, note: 'Game entry (held in pot)' });
  });
  return { ok: true, short: [] };
}

export function refundEntryFees(gameId, userIds, feePaise, note = 'Game cancelled - refund') {
  if (feePaise <= 0) return;
  tx(() => {
    for (const id of userIds) applyChange(id, feePaise, 'REFUND', { gameId, note });
  });
}

export function payWinnings(gameId, payouts) {
  tx(() => {
    for (const p of payouts) {
      if (p.paise > 0) applyChange(p.userId, p.paise, 'WINNINGS', { gameId, note: 'Game winnings' });
    }
  });
}

export function history(userId, limit = 50) {
  return db
    .prepare(`SELECT id, type, amount_paise, balance_after, game_id, note, created_at
              FROM transactions WHERE user_id = ? ORDER BY id DESC LIMIT ?`)
    .all(userId, limit)
    .map((t) => ({
      id: t.id,
      type: t.type,
      amountRs: toRs(t.amount_paise),
      balanceAfterRs: toRs(t.balance_after),
      gameId: t.game_id,
      note: t.note,
      at: t.created_at,
    }));
}

export function walletSummary(user) {
  const deposited = depositedTodayPaise(user.id);
  return {
    balanceRs: toRs(getBalancePaise(user.id)),
    depositedTodayRs: toRs(deposited),
    dailyDepositLimitRs: RULES.DAILY_DEPOSIT_LIMIT_RS,
    canDepositTodayRs: toRs(Math.max(0, toPaise(RULES.DAILY_DEPOSIT_LIMIT_RS) - deposited)),
    freeGameAvailable: user.free_game_day !== dayKey(),
    transactions: history(user.id),
  };
}
