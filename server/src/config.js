// Central configuration. Every game rule can be overridden through an env var
// (handy for quick test games: GAME_DURATION_SEC=120 HIDE_DURATION_SEC=15 npm start).

const num = (name, fallback) => {
  const v = process.env[name];
  return v === undefined || v === '' ? fallback : Number(v);
};

export const config = {
  port: num('PORT', 4000),
  // Dev mode: OTP is returned in the API response and deposits are credited
  // instantly without a payment gateway. NEVER run production with this on.
  devMode: process.env.NODE_ENV !== 'production',
  // Guest play (no phone/OTP): on in dev mode, or with ALLOW_GUESTS=1. Handy for testing.
  allowGuests: process.env.NODE_ENV !== 'production' || process.env.ALLOW_GUESTS === '1',
  dbPath: process.env.DB_PATH || './data/hideseek.db',
  // Wallet days roll over at midnight India Standard Time.
  dayOffsetMinutes: num('DAY_OFFSET_MINUTES', 330),
};

export const RULES = {
  MIN_PLAYERS: num('MIN_PLAYERS', 2),
  MAX_PLAYERS: num('MAX_PLAYERS', 10),

  MIN_FEE_RS: 10,
  MAX_FEE_RS: 100,
  DAILY_DEPOSIT_LIMIT_RS: 500,
  PLATFORM_FEE_PERCENT: num('PLATFORM_FEE_PERCENT', 0),

  GAME_DURATION_SEC: num('GAME_DURATION_SEC', 20 * 60),
  // Thieves get a head start to hide; police screen is blacked out meanwhile.
  HIDE_DURATION_SEC: num('HIDE_DURATION_SEC', 90),
  // If a player never shakes, the card flips automatically after this.
  AUTO_FLIP_SEC: num('AUTO_FLIP_SEC', 45),

  BOUNDARY_RADIUS_M: num('BOUNDARY_RADIUS_M', 100),
  OUT_OF_RANGE_GRACE_SEC: num('OUT_OF_RANGE_GRACE_SEC', 30),
  // Leaving the zone repeatedly to shake off the police is not allowed.
  MAX_OUT_OF_RANGE_STRIKES: num('MAX_OUT_OF_RANGE_STRIKES', 3),

  CLOSE_RADIUS_M: num('CLOSE_RADIUS_M', 1),
  // Phone GPS is only accurate to a few metres, so "within 1 m" is evaluated
  // as "could be within 1 m given the reported accuracy", capped at this value.
  MAX_GPS_UNCERTAINTY_M: num('MAX_GPS_UNCERTAINTY_M', 5),
  // Bluetooth (BLE) proximity. Phones broadcast a per-game tag and report the signal
  // strength (RSSI, dBm) of the tags they hear. When fresh, BLE overrides GPS for
  // the close-range checks because it works at 1-2 m where GPS cannot.
  BLE_FRESH_SEC: num('BLE_FRESH_SEC', 6),
  BLE_CLOSE_RSSI: num('BLE_CLOSE_RSSI', -62), // about 1 m between pockets/hands
  BLE_FINAL_HUNT_RSSI: num('BLE_FINAL_HUNT_RSSI', -80), // about 10 m
  BLE_BURNING_RSSI: num('BLE_BURNING_RSSI', -72), // about 3-5 m
  // Last minutes of the hunt: police radar widens so endings are tense.
  FINAL_HUNT_SEC: num('FINAL_HUNT_SEC', 120),
  FINAL_HUNT_CLOSE_RADIUS_M: num('FINAL_HUNT_CLOSE_RADIUS_M', 10),
  RADAR_PULSE_EVERY_SEC: num('RADAR_PULSE_EVERY_SEC', 180),

  LOCATION_STALE_SEC: num('LOCATION_STALE_SEC', 20),
  THIEF_OFFLINE_DQ_SEC: num('THIEF_OFFLINE_DQ_SEC', 90),
  POLICE_OFFLINE_VOID_SEC: num('POLICE_OFFLINE_VOID_SEC', 180),

  QR_TOKEN_TTL_SEC: 45,
  QR_TOKEN_ROTATE_SEC: 30,

  XP_POLICE_WIN: 50,
  XP_THIEF_SURVIVE: 30,
  XP_PER_CATCH: 10,
  XP_PARTICIPATION: 5,
};

/** Calendar day (YYYY-MM-DD) in the configured timezone, used for daily limits. */
export function dayKey(ts = Date.now()) {
  return new Date(ts + config.dayOffsetMinutes * 60_000).toISOString().slice(0, 10);
}
