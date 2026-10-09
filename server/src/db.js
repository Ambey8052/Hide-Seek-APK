import { DatabaseSync } from 'node:sqlite';
import { mkdirSync } from 'node:fs';
import { dirname } from 'node:path';
import { config } from './config.js';

if (config.dbPath !== ':memory:') mkdirSync(dirname(config.dbPath), { recursive: true });

export const db = new DatabaseSync(config.dbPath);
db.exec('PRAGMA journal_mode = WAL; PRAGMA foreign_keys = ON;');

db.exec(`
CREATE TABLE IF NOT EXISTS users (
  id            TEXT PRIMARY KEY,
  phone         TEXT NOT NULL UNIQUE,
  name          TEXT NOT NULL,
  created_at    INTEGER NOT NULL,
  free_game_day TEXT,
  games_played  INTEGER NOT NULL DEFAULT 0,
  games_won     INTEGER NOT NULL DEFAULT 0,
  catches       INTEGER NOT NULL DEFAULT 0,
  xp            INTEGER NOT NULL DEFAULT 0,
  total_won_paise INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS sessions (
  token      TEXT PRIMARY KEY,
  user_id    TEXT NOT NULL REFERENCES users(id),
  created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS otps (
  phone      TEXT PRIMARY KEY,
  code       TEXT NOT NULL,
  expires_at INTEGER NOT NULL,
  sent_at    INTEGER NOT NULL,
  attempts   INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS wallets (
  user_id       TEXT PRIMARY KEY REFERENCES users(id),
  balance_paise INTEGER NOT NULL DEFAULT 0 CHECK (balance_paise >= 0)
);

-- Append-only money ledger. amount_paise is signed (+credit / -debit).
CREATE TABLE IF NOT EXISTS transactions (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id       TEXT NOT NULL REFERENCES users(id),
  type          TEXT NOT NULL,
  amount_paise  INTEGER NOT NULL,
  balance_after INTEGER NOT NULL,
  game_id       TEXT,
  note          TEXT,
  day           TEXT NOT NULL,
  created_at    INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_txn_user ON transactions(user_id, created_at);

CREATE TABLE IF NOT EXISTS friends (
  user_id    TEXT NOT NULL REFERENCES users(id),
  friend_id  TEXT NOT NULL REFERENCES users(id),
  created_at INTEGER NOT NULL,
  PRIMARY KEY (user_id, friend_id)
);

CREATE TABLE IF NOT EXISTS games (
  id           TEXT PRIMARY KEY,
  code         TEXT NOT NULL,
  host_id      TEXT NOT NULL,
  fee_paise    INTEGER NOT NULL,
  is_free      INTEGER NOT NULL,
  status       TEXT NOT NULL,          -- ACTIVE | ENDED | VOID
  police_id    TEXT,
  winner_side  TEXT,                   -- police | thieves | none
  pot_paise    INTEGER NOT NULL DEFAULT 0,
  server_seed  TEXT,
  seed_hash    TEXT,
  fairness_json TEXT,
  result_json  TEXT,
  started_at   INTEGER,
  ended_at     INTEGER
);

CREATE TABLE IF NOT EXISTS game_players (
  game_id      TEXT NOT NULL REFERENCES games(id),
  user_id      TEXT NOT NULL REFERENCES users(id),
  role         TEXT,
  status       TEXT,
  payout_paise INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (game_id, user_id)
);
`);

/** Run fn inside a SQLite transaction; rolls back if it throws. */
export function tx(fn) {
  db.exec('BEGIN IMMEDIATE');
  try {
    const out = fn();
    db.exec('COMMIT');
    return out;
  } catch (err) {
    db.exec('ROLLBACK');
    throw err;
  }
}
