import { randomBytes, randomInt, randomUUID } from 'node:crypto';
import { db } from './db.js';
import { config } from './config.js';
import { HttpError } from './errors.js';
import { ensureWallet } from './wallet.js';

const OTP_TTL_MS = 5 * 60_000;
const OTP_RESEND_MS = 30_000;
const OTP_MAX_ATTEMPTS = 5;

export const normalizePhone = (raw) => String(raw || '').replace(/\D/g, '').replace(/^91(?=\d{10}$)/, '');
export const isValidPhone = (p) => /^[6-9]\d{9}$/.test(p);

export function requestOtp(rawPhone) {
  const phone = normalizePhone(rawPhone);
  if (!isValidPhone(phone)) throw new HttpError(400, 'Enter a valid 10-digit Indian mobile number');

  const now = Date.now();
  const existing = db.prepare('SELECT sent_at FROM otps WHERE phone = ?').get(phone);
  if (existing && now - existing.sent_at < OTP_RESEND_MS) {
    throw new HttpError(429, 'Please wait a few seconds before requesting another OTP');
  }

  const code = String(randomInt(0, 1_000_000)).padStart(6, '0');
  db.prepare(
    `INSERT INTO otps (phone, code, expires_at, sent_at, attempts) VALUES (?, ?, ?, ?, 0)
     ON CONFLICT(phone) DO UPDATE SET code = excluded.code, expires_at = excluded.expires_at,
       sent_at = excluded.sent_at, attempts = 0`,
  ).run(phone, code, now + OTP_TTL_MS, now);

  if (config.devMode) {
    console.log(`[dev] OTP for ${phone}: ${code}`);
    return { phone, devOtp: code };
  }
  // Production: plug an SMS provider (MSG91, Twilio, Firebase Auth...) in here.
  throw new HttpError(501, 'SMS provider not configured');
}

export function verifyOtp({ phone: rawPhone, otp, name, ageConfirmed }) {
  const phone = normalizePhone(rawPhone);
  const row = db.prepare('SELECT * FROM otps WHERE phone = ?').get(phone);
  if (!row || row.expires_at < Date.now()) throw new HttpError(400, 'OTP expired, request a new one');
  if (row.attempts >= OTP_MAX_ATTEMPTS) throw new HttpError(429, 'Too many attempts, request a new OTP');
  if (row.code !== String(otp)) {
    db.prepare('UPDATE otps SET attempts = attempts + 1 WHERE phone = ?').run(phone);
    throw new HttpError(400, 'Wrong OTP');
  }
  db.prepare('DELETE FROM otps WHERE phone = ?').run(phone);

  let user = db.prepare('SELECT * FROM users WHERE phone = ?').get(phone);
  if (!user) {
    const cleanName = String(name || '').trim().slice(0, 20);
    if (cleanName.length < 2) throw new HttpError(400, 'Enter your name (2-20 characters)');
    // Cash games involve money, so players must confirm they are adults.
    if (!ageConfirmed) throw new HttpError(400, 'You must be 18 or older to play');
    user = { id: randomUUID(), phone, name: cleanName };
    db.prepare('INSERT INTO users (id, phone, name, created_at) VALUES (?, ?, ?, ?)').run(
      user.id, phone, cleanName, Date.now(),
    );
    ensureWallet(user.id);
  }

  const token = randomBytes(32).toString('hex');
  db.prepare('INSERT INTO sessions (token, user_id, created_at) VALUES (?, ?, ?)').run(
    token, user.id, Date.now(),
  );
  return { token, user: publicUser(getUser(user.id)) };
}

/**
 * Login-free play: the app sends a random per-install device id and gets an account
 * tied to it. Guests get a random name they can change later.
 */
export function guestLogin({ deviceId, name }) {
  if (!config.allowGuests) throw new HttpError(403, 'Guest play is disabled on this server');
  const id = String(deviceId || '').replace(/[^a-zA-Z0-9-]/g, '').slice(0, 64);
  if (id.length < 8) throw new HttpError(400, 'Invalid device id');
  const phone = `guest:${id}`;
  let user = db.prepare('SELECT * FROM users WHERE phone = ?').get(phone);
  if (!user) {
    const cleanName = String(name || '').trim().slice(0, 20) || `Player ${randomInt(1000, 10000)}`;
    user = { id: randomUUID(), phone, name: cleanName };
    db.prepare('INSERT INTO users (id, phone, name, created_at) VALUES (?, ?, ?, ?)').run(
      user.id, phone, cleanName, Date.now(),
    );
    ensureWallet(user.id);
  }
  const token = randomBytes(32).toString('hex');
  db.prepare('INSERT INTO sessions (token, user_id, created_at) VALUES (?, ?, ?)').run(token, user.id, Date.now());
  return { token, user: publicUser(getUser(user.id)) };
}

/** Short code friends can type to add a player (works for guests, who have no phone number). */
export const playerCode = (userId) => userId.replace(/-/g, '').slice(0, 8).toUpperCase();

export function getUser(id) {
  return db.prepare('SELECT * FROM users WHERE id = ?').get(id);
}

export function userFromToken(token) {
  if (!token) return null;
  const s = db.prepare('SELECT user_id FROM sessions WHERE token = ?').get(String(token));
  return s ? getUser(s.user_id) : null;
}

export function logout(token) {
  db.prepare('DELETE FROM sessions WHERE token = ?').run(String(token));
}

export function publicUser(u) {
  return {
    id: u.id,
    name: u.name,
    phoneMasked: u.phone.startsWith('guest:') ? 'Guest' : `******${u.phone.slice(-4)}`,
    code: playerCode(u.id),
    gamesPlayed: u.games_played,
    gamesWon: u.games_won,
    catches: u.catches,
    xp: u.xp,
    totalWonRs: u.total_won_paise / 100,
  };
}

/** Express middleware: requires "Authorization: Bearer <token>". */
export function requireAuth(req, _res, next) {
  const token = (req.headers.authorization || '').replace(/^Bearer\s+/i, '');
  const user = userFromToken(token);
  if (!user) return next(new HttpError(401, 'Please log in again'));
  req.user = user;
  req.token = token;
  next();
}
