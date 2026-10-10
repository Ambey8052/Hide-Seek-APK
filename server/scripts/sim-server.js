// Starts a separate test server for scripts/simulate.js: port 4100, a throw-away
// database and sped-up timers (60 s hunt instead of 20 min, etc.).
import { tmpdir } from 'node:os';
import { join } from 'node:path';

Object.assign(process.env, {
  PORT: '4100',
  DB_PATH: join(tmpdir(), `hideseek-sim-${Date.now()}.db`),
  GAME_DURATION_SEC: '60',
  HIDE_DURATION_SEC: '3',
  AUTO_FLIP_SEC: '8',
  OUT_OF_RANGE_GRACE_SEC: '5',
  RADAR_PULSE_EVERY_SEC: '5',
  FINAL_HUNT_SEC: '15',
  THIEF_OFFLINE_DQ_SEC: '6',
  POLICE_OFFLINE_VOID_SEC: '8',
  LOCATION_STALE_SEC: '4',
  BLE_FRESH_SEC: '3',
});
await import('../src/index.js');
