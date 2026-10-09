// End-to-end simulation of whole games against an in-memory database,
// with a fake clock so 20 minutes run instantly.
import { test, before } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';

process.env.DB_PATH = ':memory:';
let db, Room, wallet, RULES, recoverInterruptedGames;

before(async () => {
  ({ db } = await import('../src/db.js'));
  ({ Room } = await import('../src/game/Room.js'));
  wallet = await import('../src/wallet.js');
  ({ RULES } = await import('../src/config.js'));
  ({ recoverInterruptedGames } = await import('../src/gameStore.js'));
});

const M = 1 / 111_195;
const fix = (north, east = 0) => ({ lat: 28.6139 + north * M, lng: 77.209 + east * M, accuracy: 0 });

function makeUser(name, balanceRs = 0) {
  const id = randomUUID();
  db.prepare('INSERT INTO users (id, phone, name, created_at) VALUES (?, ?, ?, ?)').run(
    id, String(6000000000 + Math.floor(Math.random() * 1e9)), name, Date.now(),
  );
  wallet.ensureWallet(id);
  if (balanceRs) wallet.deposit(id, balanceRs);
  return { id, name };
}

function setupGame({ feeRs = 50, players = 4, isFree = false } = {}) {
  let now = 1_000_000;
  const clock = { now: () => now, advance: (sec) => (now += sec * 1000) };
  const users = Array.from({ length: players }, (_, i) => makeUser(`P${i}`, isFree ? 0 : 100));
  const room = new Room({ code: 'TEST01', host: users[0], feeRs, isFree, now: clock.now });
  users.slice(1).forEach((u) => room.addPlayer(u));
  users.forEach((u) => room.setReady(u.id, true, `seed-${u.id}`));
  room.deal(users[0].id);
  const police = users.find((u) => u.id === room.policeId);
  const thieves = users.filter((u) => u.id !== room.policeId);
  return { room, users, police, thieves, clock };
}

/** Everyone reports GPS, then the clock advances one second and the room ticks. */
function step(room, clock, positions, seconds = 1) {
  for (let i = 0; i < seconds; i++) {
    for (const [id, pos] of Object.entries(positions)) room.updateLocation(id, pos);
    clock.advance(1);
    room.tick();
  }
}

function startHunt(g) {
  g.users.forEach((u) => g.room.flip(u.id));
  assert.equal(g.room.phase, 'HIDING');
  const pos = Object.fromEntries(g.users.map((u, i) => [u.id, u.id === g.police.id ? fix(0) : fix(40, i * 5)]));
  step(g.room, g.clock, pos, RULES.HIDE_DURATION_SEC + 1);
  assert.equal(g.room.phase, 'HUNTING');
  return pos;
}

test('entry fees are held in the pot and police wins it all by catching everyone', () => {
  const g = setupGame({ feeRs: 50, players: 4 });
  assert.equal(g.room.potPaise, 20_000);
  g.users.forEach((u) => assert.equal(wallet.getBalancePaise(u.id), 5_000));

  // Role is hidden until the player shakes.
  assert.equal(g.room.snapshotFor(g.police.id).me.role, null);
  assert.equal(g.room.snapshotFor(g.police.id).policeId, null);
  startHunt(g);
  assert.equal(g.room.snapshotFor(g.police.id).me.role, 'police');

  // Catch two by tapping the thief's phone, one by scanning the QR.
  g.room.catchByTap(g.thieves[0].id);
  g.room.catchByTap(g.thieves[1].id);
  const token = g.room.snapshotFor(g.thieves[2].id).me.qrToken;
  assert.ok(token);
  assert.equal(g.room.catchByScan(g.police.id, token), g.thieves[2].name);

  assert.equal(g.room.phase, 'ENDED');
  assert.equal(g.room.result.winnerSide, 'police');
  assert.equal(wallet.getBalancePaise(g.police.id), 5_000 + 20_000);
  g.thieves.forEach((t) => assert.equal(wallet.getBalancePaise(t.id), 5_000));
  assert.ok(g.room.snapshotFor(g.police.id).serverSeed, 'seed revealed after the game');
});

test("time up: surviving thieves split the pot, caught thieves get nothing", () => {
  const g = setupGame({ feeRs: 10, players: 4 });
  const pos = startHunt(g);
  g.room.catchByTap(g.thieves[0].id);
  step(g.room, g.clock, pos, RULES.GAME_DURATION_SEC + 1);

  assert.equal(g.room.phase, 'ENDED');
  assert.equal(g.room.result.winnerSide, 'thieves');
  // pot 40 rs split by 2 survivors
  assert.equal(wallet.getBalancePaise(g.thieves[1].id), 9_000 + 2_000);
  assert.equal(wallet.getBalancePaise(g.thieves[2].id), 9_000 + 2_000);
  assert.equal(wallet.getBalancePaise(g.thieves[0].id), 9_000);
  assert.equal(wallet.getBalancePaise(g.police.id), 9_000);
});

test('leaving the 100 m zone: 30 s warning, then disqualified', () => {
  const g = setupGame({ players: 3 });
  const pos = startHunt(g);
  const runner = g.thieves[0];

  step(g.room, g.clock, { ...pos, [runner.id]: fix(130) }, 5);
  const warn = g.room.snapshotFor(runner.id).me.outOfRange;
  assert.ok(warn, 'warning shown');

  // Comes back in time -> warning cleared.
  step(g.room, g.clock, pos, 2);
  assert.equal(g.room.snapshotFor(runner.id).me.outOfRange, null);

  // Goes out again and stays out.
  step(g.room, g.clock, { ...pos, [runner.id]: fix(130) }, RULES.OUT_OF_RANGE_GRACE_SEC + 2);
  assert.equal(g.room.players.get(runner.id).status, 'dq');
  assert.equal(g.room.phase, 'HUNTING', 'the other thief is still playing');
});

test('zone follows the police: police walking away drags the zone along', () => {
  const g = setupGame({ players: 2 });
  const pos = startHunt(g);
  const thief = g.thieves[0];
  step(g.room, g.clock, { ...pos, [g.police.id]: fix(-90) }, 3); // thief at 40 m north, police 90 m south
  assert.ok(g.room.snapshotFor(thief.id).me.outOfRange);
});

test('proximity: police vibrates and thief sees the green dot within 1 m', () => {
  const g = setupGame({ players: 3 });
  const pos = startHunt(g);
  const target = g.thieves[0];
  const nextToTarget = { ...pos[target.id], lat: pos[target.id].lat + 0.5 * M }; // 0.5 m away
  step(g.room, g.clock, { ...pos, [g.police.id]: nextToTarget }, 2);
  assert.equal(g.room.snapshotFor(target.id).me.danger, true);
  assert.equal(g.room.snapshotFor(g.thieves[1].id).me.danger, false);
  assert.equal(g.room.snapshotFor(g.police.id).me.closeCount, 1);
  assert.equal(g.room.snapshotFor(target.id).me.heat, 'burning');
});

test('fake GPS disqualifies a thief', () => {
  const g = setupGame({ players: 3 });
  startHunt(g);
  g.room.updateLocation(g.thieves[0].id, { ...fix(10), mocked: true });
  assert.equal(g.room.players.get(g.thieves[0].id).status, 'dq');
});

test('police losing connection voids the game and refunds everyone', () => {
  const g = setupGame({ feeRs: 20, players: 3 });
  const pos = startHunt(g);
  delete pos[g.police.id];
  step(g.room, g.clock, pos, RULES.POLICE_OFFLINE_VOID_SEC + 2);
  assert.equal(g.room.phase, 'VOID');
  g.users.forEach((u) => assert.equal(wallet.getBalancePaise(u.id), 10_000));
});

test('free game: one per day per player', () => {
  const g = setupGame({ players: 2, isFree: true });
  assert.equal(g.room.potPaise, 0);
  const again = new Room({ code: 'TEST02', host: g.users[0], isFree: true });
  assert.throws(() => again.setReady(g.users[0].id, true), /free game/);
});

test('cannot start without enough balance; nobody is charged', () => {
  const rich = makeUser('Rich', 100);
  const poor = makeUser('Poor', 100);
  const room = new Room({ code: 'TEST03', host: rich, feeRs: 100 });
  room.addPlayer(poor);
  room.setReady(rich.id, true);
  room.setReady(poor.id, true);
  wallet.requestWithdrawal(poor.id, 50); // spends money after readying
  assert.throws(() => room.deal(rich.id), /Not enough wallet balance: Poor/);
  assert.equal(wallet.getBalancePaise(rich.id), 10_000);
  assert.equal(room.phase, 'LOBBY');
});

test('daily deposit limit of 500 rs', () => {
  const u = makeUser('Saver');
  wallet.deposit(u.id, 300);
  wallet.deposit(u.id, 200);
  assert.throws(() => wallet.deposit(u.id, 1), /Daily deposit limit/);
});

test('max 10 players', () => {
  const host = makeUser('Host');
  const room = new Room({ code: 'TEST04', host, isFree: true });
  for (let i = 0; i < 9; i++) room.addPlayer(makeUser(`X${i}`));
  assert.throws(() => room.addPlayer(makeUser('Eleventh')), /full/);
});

test('rematch never makes the same person police twice in a row', () => {
  const g = setupGame({ feeRs: 10, players: 3 });
  const first = g.room.policeId;
  startHunt(g);
  g.room.endGame('test');
  g.room.rematch(g.users[0].id);
  g.users.forEach((u) => g.room.setReady(u.id, true, 'again'));
  g.room.deal(g.users[0].id);
  assert.notEqual(g.room.policeId, first);
});

test('server restart refunds games that were in progress', () => {
  const g = setupGame({ feeRs: 30, players: 2 });
  assert.ok(recoverInterruptedGames() >= 1); // earlier tests also left games running
  g.users.forEach((u) => assert.equal(wallet.getBalancePaise(u.id), 10_000));
});

test('bluetooth: each player gets a unique tag only they are told', () => {
  const g = setupGame({ players: 4 });
  const tags = g.users.map((u) => g.room.snapshotFor(u.id).me.bleTag);
  assert.ok(tags.every((t) => /^[0-9a-f]{8}$/.test(t)));
  assert.equal(new Set(tags).size, 4);
});

test('bluetooth overrides GPS when fresh, and falls back to GPS when stale', () => {
  const g = setupGame({ players: 2 });
  const pos = startHunt(g); // GPS puts the thief ~40 m from the police
  const thief = g.thieves[0];
  const policeTag = g.room.snapshotFor(g.police.id).me.bleTag;
  const thiefTag = g.room.snapshotFor(thief.id).me.bleTag;

  // Strong signal: phones are right next to each other even though GPS says 40 m.
  g.room.updateProximity(thief.id, [{ tag: policeTag, rssi: -55 }]);
  step(g.room, g.clock, pos, 1);
  let me = g.room.snapshotFor(thief.id).me;
  assert.equal(me.danger, true);
  assert.equal(me.proxSource, 'ble');
  assert.equal(me.heat, 'burning');
  assert.equal(g.room.snapshotFor(g.police.id).me.closeCount, 1);

  // Weak signal from the police's side: not close, even if GPS jitter said so.
  g.room.updateProximity(thief.id, []);
  step(g.room, g.clock, { ...pos, [g.police.id]: pos[thief.id] }, 1); // GPS: same spot
  g.room.players.get(thief.id).ble.clear();
  g.room.updateProximity(g.police.id, [{ tag: thiefTag, rssi: -90 }]);
  step(g.room, g.clock, { ...pos, [g.police.id]: pos[thief.id] }, 1);
  me = g.room.snapshotFor(thief.id).me;
  assert.equal(me.danger, false, 'BLE says far, so no false alarm from GPS');

  // Bluetooth goes quiet -> GPS takes over again.
  step(g.room, g.clock, { ...pos, [g.police.id]: pos[thief.id] }, 8);
  me = g.room.snapshotFor(thief.id).me;
  assert.equal(me.proxSource, 'gps');
  assert.equal(me.danger, true);
});

test('bluetooth ignores unknown tags, own tag and junk values', () => {
  const g = setupGame({ players: 2 });
  startHunt(g);
  const thief = g.thieves[0];
  const ownTag = g.room.snapshotFor(thief.id).me.bleTag;
  g.room.updateProximity(thief.id, [
    { tag: 'deadbeef', rssi: -40 },
    { tag: ownTag, rssi: -30 },
    { tag: g.room.snapshotFor(g.police.id).me.bleTag, rssi: 'loud' },
  ]);
  assert.equal(g.room.players.get(thief.id).ble.size, 0);
});
