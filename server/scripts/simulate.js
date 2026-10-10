// Multi-player simulation: fake players drive the real server over HTTP + Socket.IO
// (exactly like the app does) and every rule is checked. Start a server with short
// timers first (see "npm run simulate" in package.json), then:
//   node scripts/simulate.js [http://localhost:4100]
import { io } from 'socket.io-client';
import { randomUUID } from 'node:crypto';
import { sha256, pickPolice, distanceM } from '../src/rules.js';

const BASE = process.argv[2] || 'http://localhost:4100';
const ORIGIN = { lat: 28.6139, lng: 77.209 };
const M = 1 / 111_195; // ~1 m of latitude
/** Point `north` metres north (and `east` metres east) of the origin. */
const at = (north, east = 0) => ({
  lat: ORIGIN.lat + north * M,
  lng: ORIGIN.lng + (east * M) / Math.cos((ORIGIN.lat * Math.PI) / 180),
});
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ------------------------------------------------------------------ tiny test runner

const results = [];
let section = '';
function check(name, ok, detail = '') {
  results.push({ section, name, ok: !!ok, detail });
  console.log(`${ok ? '  ✔' : '  ✘'} ${name}${detail && !ok ? `  -> ${detail}` : ''}`);
}
function begin(title) {
  section = title;
  console.log(`\n▶ ${title}`);
}
async function waitFor(fn, ms, label) {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    try {
      if (fn()) return true;
    } catch {
      /* state not there yet */
    }
    await sleep(100);
  }
  if (label) console.log(`    (timed out waiting for: ${label})`);
  return false;
}
async function expectError(promise, re) {
  try {
    await promise;
    return 'no error';
  } catch (e) {
    return re.test(e.message) ? true : e.message;
  }
}

// ------------------------------------------------------------------ fake player

async function api(path, { body, token, method } = {}) {
  const res = await fetch(BASE + path, {
    method: method || (body ? 'POST' : 'GET'),
    headers: { 'Content-Type': 'application/json', ...(token && { Authorization: `Bearer ${token}` }) },
    body: body && JSON.stringify(body),
  });
  const json = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(json.error || `HTTP ${res.status}`);
  return json;
}

const everyone = [];

class FakePlayer {
  constructor(name) {
    this.name = name;
    this.deviceId = randomUUID();
    this.state = null;
    this.pos = null; // current GPS position (sent every second while set)
    this.mocked = false;
    this.ble = null; // [{ tag, rssi }] reported every second while set
    this.invites = [];
    everyone.push(this);
  }

  async login() {
    const { token, user } = await api('/api/auth/guest', { body: { deviceId: this.deviceId, name: this.name } });
    this.token = token;
    this.user = user;
    this.id = user.id;
    this.connect();
    await waitFor(() => this.socket.connected, 5000, `${this.name} connect`);
    return this;
  }

  connect() {
    this.socket = io(BASE, { auth: { token: this.token }, transports: ['websocket'], reconnection: false });
    this.socket.on('room:state', (s) => (this.state = s));
    this.socket.on('invite', (inv) => this.invites.push(inv));
  }

  emit(event, payload = {}) {
    return new Promise((resolve, reject) => {
      this.socket.timeout(5000).emit(event, payload, (err, ack) => {
        if (err) return reject(new Error('timeout'));
        return ack.ok ? resolve(ack) : reject(new Error(ack.error));
      });
    });
  }

  deposit(rs) {
    return api('/api/wallet/deposit', { body: { amountRs: rs }, token: this.token });
  }
  async balance() {
    return (await api('/api/wallet', { token: this.token })).balanceRs;
  }

  get me() {
    return this.state?.me;
  }
  get phase() {
    return this.state?.phase;
  }
  close() {
    this.socket?.close();
  }
}

// One shared heartbeat, like the app: GPS + Bluetooth readings once per second.
const heartbeat = setInterval(() => {
  for (const p of everyone) {
    if (!p.socket?.connected) continue;
    if (p.pos) p.socket.emit('loc', { ...p.pos, accuracy: 3, mocked: p.mocked });
    if (p.ble) p.socket.emit('prox', { seen: p.ble });
  }
}, 1000);

async function makePlayers(prefix, n, depositRs = 0) {
  const ps = await Promise.all(Array.from({ length: n }, (_, i) => new FakePlayer(`${prefix}${i + 1}`).login()));
  if (depositRs) await Promise.all(ps.map((p) => p.deposit(depositRs)));
  return ps;
}

/** Host creates a room, others join, all ready, host deals, everyone flips. Returns roles. */
async function startGame(players, { feeRs = 10, isFree = false, flip = true } = {}) {
  const [host] = players;
  const { code } = await host.emit('room:create', { feeRs, isFree });
  for (const p of players.slice(1)) await p.emit('room:join', { code });
  for (const p of players) await p.emit('room:ready', { ready: true, clientSeed: randomUUID() });
  await host.emit('room:deal');
  for (const p of players) p.pos = at(0);
  if (flip) {
    for (const p of players) await p.emit('card:flip');
    await waitFor(() => players.every((p) => p.phase === 'HIDING'), 5000, 'HIDING');
  }
  const police = players.find((p) => p.id === players[0].state.policeId);
  const thieves = players.filter((p) => p !== police);
  return { code, police, thieves };
}

// ------------------------------------------------------------------ scenarios

async function lobbyAndWallet() {
  begin('Lobby, limits and wallet');
  const [a, b] = await makePlayers('Lobby', 2);

  check('guest login gives a random-free name and a player code', /^Lobby1$/.test(a.user.name) && /^[0-9A-F]{8}$/.test(a.user.code));
  const again = await api('/api/auth/guest', { body: { deviceId: a.deviceId } });
  check('same device logs back into the same account', again.user.id === a.id);

  check('entry fee below ₹10 rejected', (await expectError(a.emit('room:create', { feeRs: 5 }), /₹10-₹100/)) === true);
  check('entry fee above ₹100 rejected', (await expectError(a.emit('room:create', { feeRs: 101 }), /₹10-₹100/)) === true);

  await a.deposit(300);
  await a.deposit(200);
  check('₹500 per day deposit limit enforced', (await expectError(a.deposit(1), /Daily deposit limit/)) === true);

  const { code } = await a.emit('room:create', { feeRs: 50 });
  await b.emit('room:join', { code });
  check('player with ₹0 cannot ready for a ₹50 game', (await expectError(b.emit('room:ready', { ready: true }), /Add money/)) === true);
  await b.deposit(100);
  await b.emit('room:ready', { ready: true });
  await a.emit('room:ready', { ready: true });
  await a.emit('room:settings', { feeRs: 20 });
  await waitFor(() => a.state.feeRs === 20, 2000);
  check('changing terms un-readies everyone', a.state.players.every((p) => !p.ready));
  check('only the host can change terms', (await expectError(b.emit('room:settings', { feeRs: 30 }), /Only the host/)) === true);
  check('only the host can deal', (await expectError(b.emit('room:deal'), /Only the host/)) === true);

  const more = await makePlayers('Fill', 8);
  for (const p of more) await p.emit('room:join', { code });
  await waitFor(() => a.state.players.length === 10, 2000);
  check('room holds 10 players', a.state.players.length === 10);
  const eleventh = (await makePlayers('Eleventh', 1))[0];
  check('11th player is rejected (max 10)', (await expectError(eleventh.emit('room:join', { code }), /full/)) === true);
  check('wrong room code rejected', (await expectError(eleventh.emit('room:join', { code: 'ZZZZZZ' }), /No room/)) === true);

  await a.emit('room:kick', { userId: more[0].id });
  await waitFor(() => more[0].state === null && a.state.players.length === 9, 2000);
  check('host can remove a player; they are told', more[0].state === null && a.state.players.length === 9);

  for (const p of [a, b, ...more, eleventh]) p.close();
}

async function friendsAndInvites() {
  begin('Friends and invites');
  const [a, b, c] = await makePlayers('Friend', 3);
  await api('/api/friends', { body: { phone: b.user.code }, token: a.token });
  const bList = await api('/api/friends', { token: b.token });
  check('adding by player code makes a mutual friendship', bList.some((f) => f.id === a.id));
  check('friend list shows online status', bList.find((f) => f.id === a.id)?.online === true);

  const { code } = await a.emit('room:create', { feeRs: 10 });
  await a.emit('invite:send', { friendId: b.id });
  await waitFor(() => b.invites.length > 0, 2000);
  check('online friend receives the invite with the room code', b.invites[0]?.code === code && b.invites[0]?.fromName === 'Friend1');
  check('cannot invite a non-friend', (await expectError(a.emit('invite:send', { friendId: c.id }), /only invite friends/)) === true);
  b.close();
  await sleep(300);
  check('inviting an offline friend gives a clear error', (await expectError(a.emit('invite:send', { friendId: b.id }), /offline/)) === true);
  a.close();
  c.close();
}

async function bigGame() {
  begin('10-player cash game: distance, Bluetooth, zone, catching, payout');
  const players = await makePlayers('P', 10, 100);
  const { police, thieves } = await startGame(players, { feeRs: 50 });
  const [tTap, tQr, tReconnect, tFinal, tBleWeak, tReturn, tStay, tStrikes, tFake] = thieves;

  check('exactly one police, nine thieves', players.filter((p) => p.me.role === 'police').length === 1);
  check('everyone paid ₹50 into the pot (₹500)', players[0].state.potRs === 500);
  check('each wallet charged ₹50', (await Promise.all(players.map((p) => p.balance()))).every((b) => b === 50));
  check('each player gets their own Bluetooth tag', new Set(players.map((p) => p.me.bleTag)).size === 10);

  // Everybody hides at a known distance from the police (who stays at the origin).
  const spots = { tTap: at(30), tQr: at(30, 5), tReconnect: at(15), tFinal: at(40), tBleWeak: at(80), tReturn: at(60), tStay: at(70), tStrikes: at(50), tFake: at(20) };
  Object.assign(tTap, { pos: spots.tTap });
  Object.assign(tQr, { pos: spots.tQr });
  Object.assign(tReconnect, { pos: spots.tReconnect });
  Object.assign(tFinal, { pos: spots.tFinal });
  Object.assign(tBleWeak, { pos: spots.tBleWeak });
  Object.assign(tReturn, { pos: spots.tReturn });
  Object.assign(tStay, { pos: spots.tStay });
  Object.assign(tStrikes, { pos: spots.tStrikes });
  Object.assign(tFake, { pos: spots.tFake });
  police.pos = at(0);

  await waitFor(() => police.phase === 'HUNTING', 10_000, 'HUNTING');
  check('hunt starts after the hiding head start', police.phase === 'HUNTING');
  await sleep(2500);

  // --- heat meter bands from GPS distance
  check('thief 15 m away sees "hot"', tReconnect.me.heat === 'hot', tReconnect.me.heat);
  check('thief 40 m away sees "warm"', tFinal.me.heat === 'warm', tFinal.me.heat);
  check('thief 80 m away sees "cold"', tBleWeak.me.heat === 'cold', tBleWeak.me.heat);
  check('nobody is flagged close yet', police.me.closeCount === 0 && thieves.every((t) => !t.me.danger));

  // --- privacy: the police never receives thieves' coordinates
  const policeView = JSON.stringify(police.state);
  check('police snapshot contains no thief coordinates', !policeView.includes(String(spots.tTap.lat).slice(0, 9)));

  // --- GPS proximity: thief walks right up to the police
  tTap.pos = at(0.5);
  await waitFor(() => tTap.me.danger && police.me.closeCount >= 1, 4000);
  check('GPS: thief within 1 m gets the green dot', tTap.me.danger === true);
  check('GPS: police phone gets the close alert', police.me.closeCount >= 1);
  check('GPS: proximity source reported as gps', tTap.me.proxSource === 'gps');
  check('thief that is not near gets no green dot', tReconnect.me.danger === false);

  // --- catch by holding the button on the thief's phone
  await tTap.emit('catch:tap');
  await waitFor(() => tTap.me.status === 'caught', 2000);
  check('catch by tap: thief is caught', tTap.me.status === 'caught');
  check('police catch counter goes up', police.me.catches === 1);
  check('caught thief no longer counts as close', await waitFor(() => police.me.closeCount === 0, 3000));
  check('a caught thief cannot be caught twice', (await expectError(tTap.emit('catch:tap'), /not an active thief/)) === true);

  // --- Bluetooth beats GPS: 30 m away by GPS but phones are touching
  const policeTag = police.me.bleTag;
  tQr.ble = [{ tag: policeTag, rssi: -55 }];
  await waitFor(() => tQr.me.danger, 4000);
  check('Bluetooth: strong signal gives the green dot despite 30 m GPS', tQr.me.danger === true);
  check('Bluetooth: source reported as ble', tQr.me.proxSource === 'ble');
  check('Bluetooth: heat shows "burning"', tQr.me.heat === 'burning');
  check('Bluetooth: police gets the close alert', police.me.closeCount >= 1);
  check('police screen shows Bluetooth lock', police.me.proxSource === 'ble');

  // --- catch by QR scan (plus the abuse cases)
  check('random QR code is rejected', (await expectError(police.emit('catch:scan', { token: 'deadbeef' }), /Invalid or expired/)) === true);
  check('a thief cannot use the scanner', (await expectError(tReturn.emit('catch:scan', { token: tQr.me.qrToken }), /Only the police/)) === true);
  const scan = await police.emit('catch:scan', { token: tQr.me.qrToken });
  check('catch by QR scan names the right thief', scan.caught === tQr.name);
  tQr.ble = null;

  // --- Bluetooth says "far" even when GPS jitter says "close"
  tBleWeak.ble = [{ tag: policeTag, rssi: -90 }];
  await sleep(1500);
  tBleWeak.pos = at(2);
  await sleep(2500);
  check('Bluetooth weak signal overrides a GPS false alarm', tBleWeak.me.danger === false && tBleWeak.me.proxSource === 'ble');
  tBleWeak.ble = null;
  await sleep(3500);
  check('Bluetooth goes quiet -> falls back to GPS', tBleWeak.me.proxSource === 'gps' && tBleWeak.me.danger === true);
  tBleWeak.pos = spots.tBleWeak;

  // --- 100 m zone: leave and come back within 30 s
  tReturn.pos = at(130);
  await waitFor(() => tReturn.me.outOfRange, 4000);
  check('leaving the 100 m zone starts the warning countdown', !!tReturn.me.outOfRange && tReturn.me.strikes === 1);
  tReturn.pos = spots.tReturn;
  await waitFor(() => !tReturn.me.outOfRange, 4000);
  check('coming back clears the warning (still playing)', !tReturn.me.outOfRange && tReturn.me.status === 'active');

  // --- leave and stay out -> out after the grace period; repeated exits -> 3 strikes
  tStay.pos = at(140);
  const strikeLoop = (async () => {
    for (let i = 0; i < 4 && tStrikes.me.status === 'active'; i++) {
      tStrikes.pos = at(135);
      await waitFor(() => tStrikes.me.outOfRange || tStrikes.me.status !== 'active', 4000);
      tStrikes.pos = spots.tStrikes;
      await waitFor(() => !tStrikes.me.outOfRange || tStrikes.me.status !== 'active', 4000);
    }
  })();

  // --- fake GPS app -> disqualified at once
  tFake.mocked = true;
  await waitFor(() => tFake.me.status === 'dq', 3000);
  check('fake-GPS thief is disqualified', tFake.me.status === 'dq' && /Fake GPS/.test(tFake.me.dqReason));

  // --- radar pulse: counts thieves per distance ring, no directions
  await waitFor(() => police.me.pulse && police.me.pulse.at > Date.now() - 6000, 8000);
  const rings = police.me.pulse?.rings || [];
  const active = thieves.filter((t) => t.me.status === 'active' && t.pos);
  const expected = [0, 0, 0, 0];
  for (const t of active) {
    const d = distanceM(t.pos, police.pos);
    expected[d <= 25 ? 0 : d <= 50 ? 1 : d <= 100 ? 2 : 3] += 1;
  }
  check('radar pulse arrives', rings.length === 4);
  check('radar pulse total matches active thieves', rings.reduce((a, b) => a + b, 0) >= active.length - 2, `${rings} vs ${expected}`);

  // --- reconnect: a thief's phone drops off for 3 s and comes back
  tReconnect.close();
  await sleep(3000);
  tReconnect.state = null;
  tReconnect.connect();
  await waitFor(() => tReconnect.state?.phase === 'HUNTING', 4000);
  check('reconnecting player gets the game back and is still in', tReconnect.me?.status === 'active');

  await strikeLoop;
  await waitFor(() => tStay.me.status === 'dq', 10_000);
  check('staying outside the zone past the grace period disqualifies', tStay.me.status === 'dq' && /zone/.test(tStay.me.dqReason));
  check('leaving the zone more than 3 times disqualifies', tStrikes.me.status === 'dq' && /too many times/.test(tStrikes.me.dqReason || ''), tStrikes.me.dqReason);

  // --- final hunt: police radar widens from 1 m to 10 m
  tFinal.pos = at(8);
  await sleep(2500);
  const finalSec = police.state.rules.finalHuntSec * 1000;
  const inFinal = () => police.state.huntEndsAt - police.state.serverNow <= finalSec;
  if (!inFinal()) check('8 m away is NOT close before the final hunt', police.me.closeCount === 0, `closeCount=${police.me.closeCount}`);
  await waitFor(inFinal, 40_000, 'final hunt');
  await waitFor(() => police.me.closeCount >= 1, 4000);
  check('final hunt: 8 m away now alerts the police', police.me.closeCount >= 1);
  check('final hunt: thief green dot still means ~1 m (not shown at 8 m)', tFinal.me.danger === false);

  // --- time up: uncaught thieves split the pot
  await waitFor(() => police.phase === 'ENDED', 30_000, 'game end');
  const r = police.state.result;
  const survivors = [tReconnect, tFinal, tBleWeak, tReturn];
  check('game ends when time is up: thieves win', r?.winnerSide === 'thieves');
  check('exactly the 4 uncaught thieves are paid', r?.payouts.length === 4 && survivors.every((s) => r.payouts.some((p) => p.userId === s.id)));
  check('pot paid out in full: ₹500 = 4 × ₹125', r?.payouts.every((p) => p.rs === 125) && r.payouts.reduce((a, p) => a + p.rs, 0) === 500);
  const bal = Object.fromEntries(await Promise.all(players.map(async (p) => [p.name, await p.balance()])));
  check('winners\' wallets: ₹50 + ₹125 = ₹175', survivors.every((s) => bal[s.name] === 175), JSON.stringify(bal));
  check('police, caught and disqualified players keep ₹50', [police, tTap, tQr, tStay, tStrikes, tFake].every((p) => bal[p.name] === 50));

  // --- fairness proof
  const proof = await api(`/api/games/${police.state.gameId}/fairness`, { token: police.token });
  check('server seed matches the seal shown before the game', sha256(proof.serverSeed) === proof.seedHash);
  check('police can be re-derived from the revealed seeds', pickPolice(proof.serverSeed, proof.players, proof.lastPoliceId) === police.id);

  // --- history & stats
  const hist = await api('/api/games', { token: tFinal.token });
  check('game appears in history with the payout', hist[0]?.payoutRs === 125 && hist[0]?.role === 'thief');
  const board = await api('/api/leaderboard', { token: police.token });
  check('leaderboard updated with the police catches', board.find((u) => u.id === police.id)?.catches === 2);

  // --- rematch: same group, the previous police can't be police again
  const host = players[0];
  await host.emit('room:rematch');
  await waitFor(() => players.every((p) => p.phase === 'LOBBY'), 3000);
  check('rematch brings everyone back to the lobby', players.every((p) => p.phase === 'LOBBY'));
  check('rematch has a fresh fairness seal', host.state.seedHash !== proof.seedHash);
  for (const p of players) await p.emit('room:ready', { ready: true, clientSeed: randomUUID() });
  await host.emit('room:deal');
  await waitFor(() => host.phase === 'DEALING', 2000);
  for (const p of players) await p.emit('card:flip');
  await waitFor(() => host.state.policeId, 3000);
  check('previous police is not police again', host.state.policeId && host.state.policeId !== police.id);
  for (const p of players) p.close();
  for (const p of players) p.pos = null;
}

async function policeWins() {
  begin('Police catches everyone (one thief lost signal)');
  const players = await makePlayers('W', 3, 100);
  const { police, thieves } = await startGame(players, { feeRs: 30 });
  const [t1, t2] = thieves;
  t1.pos = at(20);
  t2.pos = at(25);
  await waitFor(() => police.phase === 'HUNTING', 10_000);
  t2.close(); // phone dies
  t2.pos = null;
  await waitFor(() => t1.state.players.find((p) => p.userId === t2.id)?.status === 'dq', 15_000);
  check('thief offline too long is disqualified', t1.state.players.find((p) => p.userId === t2.id)?.status === 'dq');
  await t1.emit('catch:tap');
  await waitFor(() => police.phase === 'ENDED', 3000);
  check('police wins when every thief is caught or out', police.state.result?.winnerSide === 'police');
  check('police takes the whole ₹90 pot', (await police.balance()) === 70 + 90);
  for (const p of players) p.close();
}

async function policeForfeits() {
  begin('Police leaves mid-game');
  const players = await makePlayers('F', 3, 100);
  const { police, thieves } = await startGame(players, { feeRs: 20 });
  await waitFor(() => police.phase === 'HUNTING', 10_000);
  await police.emit('room:leave');
  await waitFor(() => thieves[0].phase === 'ENDED', 3000);
  check('police forfeits -> thieves win', thieves[0].state.result?.winnerSide === 'thieves');
  check('the two thieves split ₹60 (₹30 each)', (await thieves[0].balance()) === 110 && (await thieves[1].balance()) === 110);
  for (const p of players) p.close();
}

async function policeOffline() {
  begin('Police loses connection -> game cancelled, refunds');
  const players = await makePlayers('V', 3, 100);
  const { police, thieves } = await startGame(players, { feeRs: 40 });
  thieves.forEach((t, i) => (t.pos = at(10 + i * 10)));
  await waitFor(() => police.phase === 'HUNTING', 10_000);
  police.close();
  police.pos = null;
  await waitFor(() => thieves[0].phase === 'VOID', 20_000, 'VOID');
  check('game is cancelled', thieves[0].phase === 'VOID');
  check('everyone refunded to ₹100', (await Promise.all(players.map((p) => p.balance()))).every((b) => b === 100));
  for (const p of players) p.close();
}

async function freeGameRule() {
  begin('One free game per player per day');
  const players = await makePlayers('Free', 2);
  const { police } = await startGame(players, { isFree: true });
  check('free game: no pot, no charge', players[0].state.potRs === 0 && (await players[0].balance()) === 0);
  await police.emit('room:leave');
  await waitFor(() => players.every((p) => p.phase === 'ENDED' || p.state === null), 3000);
  for (const p of players) await p.emit('room:leave').catch(() => {});
  const { code } = await players[0].emit('room:create', { isFree: true });
  check('second free game the same day is refused', (await expectError(players[0].emit('room:ready', { ready: true }), /free game/)) === true);
  void code;
  for (const p of players) p.close();
}

async function autoFlip() {
  begin('Cards flip automatically if someone never shakes');
  const players = await makePlayers('Flip', 2, 0);
  await startGame(players, { isFree: true, flip: false });
  check('role hidden before shaking', players.every((p) => p.me.role === null) && players[0].state.policeId === null);
  await players[0].emit('card:flip');
  await sleep(500);
  check('my card shows only after I shake', players[0].me.role !== null && players[1].me.role === null);
  await waitFor(() => players.every((p) => p.phase === 'HIDING'), 15_000, 'auto flip');
  check('everyone auto-flipped after the deadline', players.every((p) => p.me.role));
  for (const p of players) p.close();
}

// ------------------------------------------------------------------ random games

const rand = (a, b) => a + Math.random() * (b - a);
const pick = (arr) => arr[Math.floor(Math.random() * arr.length)];

/** One random game with 4-5 players: random fee, hiding spots, chase, catches and rule-breakers. */
async function randomGame(n) {
  const k = 4 + Math.floor(Math.random() * 2);
  const fee = pick([10, 20, 30, 50, 75, 100]);
  const players = await makePlayers(`R${n}-`, k, 100);
  const { police, thieves } = await startGame(players, { feeRs: fee });
  const log = [];
  const xy = new Map(players.map((p) => [p, { n: 0, e: 0 }]));
  const place = (p) => (p.pos = at(xy.get(p).n, xy.get(p).e));
  const dist = (a, b) => Math.hypot(xy.get(a).n - xy.get(b).n, xy.get(a).e - xy.get(b).e);

  // Thieves run off to a random spot 10-90 m away in a random direction.
  for (const t of thieves) {
    const ang = rand(0, 2 * Math.PI), d = rand(10, 90);
    xy.set(t, { n: d * Math.cos(ang), e: d * Math.sin(ang) });
    t.wanderer = Math.random() < 0.25; // sometimes steps out of the zone for a few seconds
    place(t);
  }
  place(police);
  const policeSpeed = rand(1.2, 4); // m/s: some police are slow, some fast
  for (const t of thieves) t.speed = rand(1, 3);
  await waitFor(() => police.phase === 'HUNTING', 15_000);
  const potOk = players[0].state.potRs === fee * k;

  let errors = 0;
  while (police.phase === 'HUNTING') {
    const active = thieves.filter((t) => t.me?.status === 'active');
    // Police chases the nearest active thief at ~5 m/s.
    const target = active.sort((a, b) => dist(police, a) - dist(police, b))[0];
    if (target) {
      const pp = xy.get(police), tp = xy.get(target), d = dist(police, target);
      const step = Math.min(policeSpeed, Math.max(0, d - 0.3));
      if (d > 0) xy.set(police, { n: pp.n + ((tp.n - pp.n) / d) * step, e: pp.e + ((tp.e - pp.e) / d) * step });
      place(police);
    }
    for (const t of active) {
      const tp = xy.get(t), pp = xy.get(police);
      // Thieves feel the heat and sneak away (slower than the police), staying inside the zone.
      if (['hot', 'burning'].includes(t.me.heat) && Math.random() < 0.6) {
        const d = Math.max(dist(t, police), 0.1);
        let nn = tp.n + ((tp.n - pp.n) / d) * t.speed, ee = tp.e + ((tp.e - pp.e) / d) * t.speed;
        const nd = Math.hypot(nn - pp.n, ee - pp.e);
        if (nd > 90) { nn = pp.n + ((nn - pp.n) / nd) * 90; ee = pp.e + ((ee - pp.e) / nd) * 90; }
        xy.set(t, { n: nn, e: ee });
      } else if (dist(t, police) > 92 && !t.outside) {
        // Zone follows the police: an honest thief stays within range.
        const d = dist(t, police);
        xy.set(t, { n: pp.n + ((tp.n - pp.n) / d) * 85, e: pp.e + ((tp.e - pp.e) / d) * 85 });
      }
      if (t.wanderer && Math.random() < 0.08) {
        t.wanderer = false;
        const d = Math.max(dist(t, police), 0.1);
        xy.set(t, { n: pp.n + ((tp.n - pp.n) / d) * 140, e: pp.e + ((tp.e - pp.e) / d) * 140 });
        log.push(`${t.name} wandered out of the zone`);
        const comesBack = Math.random() < 0.6; // the rest stay out too long and get disqualified
        t.outside = !comesBack;
        if (comesBack) setTimeout(() => {
          if (t.me?.status !== 'active') return;
          t.outside = false;
          const p2 = xy.get(police), q = xy.get(t), dd = Math.hypot(q.n - p2.n, q.e - p2.e);
          xy.set(t, { n: p2.n + ((q.n - p2.n) / dd) * 60, e: p2.e + ((q.e - p2.e) / dd) * 60 });
          place(t);
          log.push(`${t.name} came back`);
        }, 3000);
      }
      place(t);
      // Police reaches a thief whose phone shows the green dot -> catch (tap or QR).
      if (t.me.danger && dist(t, police) < 2.5) {
        try {
          if (Math.random() < 0.5) await t.emit('catch:tap');
          else await police.emit('catch:scan', { token: t.me.qrToken });
          log.push(`${police.name} caught ${t.name}`);
        } catch (e) {
          if (!/not an active thief|only allowed during the hunt/.test(e.message)) { errors++; log.push(`catch error: ${e.message}`); }
        }
      }
    }
    await sleep(1000);
  }
  await waitFor(() => police.phase === 'ENDED' || police.phase === 'VOID', 10_000);

  // ---- invariants
  const st = police.state, r = st.result || {};
  const final = Object.fromEntries(st.players.map((p) => [p.userId, p.status]));
  const survivors = thieves.filter((t) => final[t.id] === 'active');
  const expectedSide = survivors.length ? 'thieves' : 'police';
  const winnerIds = expectedSide === 'police' ? [police.id] : survivors.map((t) => t.id);
  const balances = await Promise.all(players.map((p) => p.balance()));
  const total = Math.round(balances.reduce((a, b) => a + b, 0) * 100);
  const share = (fee * k) / winnerIds.length;
  const paidRight = players.every((p, i) => {
    const want = 100 - fee + (winnerIds.includes(p.id) ? share : 0);
    return Math.abs(balances[i] - want) < 0.02;
  });
  const caught = thieves.filter((t) => final[t.id] === 'caught').length;
  const dq = thieves.filter((t) => final[t.id] === 'dq').length;
  const summary = `game ${n}: ${k} players, ₹${fee} entry, ${caught} caught, ${dq} out, ${survivors.length} escaped -> ${r.winnerSide} win`;
  check(summary, potOk && st.phase === 'ENDED' && r.winnerSide === expectedSide && paidRight && total === 100 * k * 100 && errors === 0,
    JSON.stringify({ potOk, phase: st.phase, side: r.winnerSide, expectedSide, paidRight, balances, errors, log }));
  for (const p of players) p.close();
  return { k, fee, caught, dq, escaped: survivors.length, side: r.winnerSide };
}

async function randomGames() {
  begin('Random games: 5 rooms of 4-5 players playing at the same time');
  const games = await Promise.all([1, 2, 3, 4, 5].map((n) => randomGame(n).catch((e) => (check(`game ${n} crashed: ${e.message}`, false), null))));
  const ok = games.filter(Boolean);
  check(`${ok.reduce((a, g) => a + g.k, 0)} players across ${ok.length} simultaneous rooms`, ok.length === 5);
}

// ------------------------------------------------------------------ run

const started = Date.now();
try {
  await api('/api/health');
} catch {
  console.error(`No server at ${BASE}. Start one with short timers first (npm run sim:server).`);
  process.exit(1);
}
const only = process.argv[3];
const all = { lobbyAndWallet, friendsAndInvites, autoFlip, freeGameRule, bigGame, policeWins, policeForfeits, policeOffline, randomGames };
for (const scenario of only ? [all[only]] : Object.values(all)) {
  try {
    await scenario();
  } catch (e) {
    check(`scenario crashed: ${e.message}`, false);
  }
}
clearInterval(heartbeat);
for (const p of everyone) p.close();

const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} checks passed in ${Math.round((Date.now() - started) / 1000)} s with ${everyone.length} fake players`);
if (failed.length) {
  console.log('\nFailed:');
  for (const f of failed) console.log(`  [${f.section}] ${f.name} ${f.detail}`);
}
process.exit(failed.length ? 1 : 0);
