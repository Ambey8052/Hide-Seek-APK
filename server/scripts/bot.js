// A scripted second player, so you can try a whole game with only one phone.
// It logs in, tops up its wallet, joins your room, readies up, shakes (flips) when
// the cards are dealt and keeps reporting a GPS position near the given point.
//
// Usage: node scripts/bot.js <ROOM_CODE> [lat] [lng] [serverUrl]
//   e.g. node scripts/bot.js K7PQ2M 28.6140 77.2090
import { io } from 'socket.io-client';

const [code, lat = '28.6140', lng = '77.2090', BASE = 'http://localhost:4000'] = process.argv.slice(2);
if (!code) {
  console.log('Usage: node scripts/bot.js <ROOM_CODE> [lat] [lng] [serverUrl]');
  process.exit(1);
}

const api = async (path, body, token) => {
  const res = await fetch(BASE + path, {
    method: body ? 'POST' : 'GET',
    headers: { 'Content-Type': 'application/json', ...(token && { Authorization: `Bearer ${token}` }) },
    body: body && JSON.stringify(body),
  });
  const json = await res.json();
  if (!res.ok) throw new Error(`${path}: ${json.error}`);
  return json;
};

const phone = String(6000000000 + Math.floor(Math.random() * 3e9));
const { devOtp } = await api('/api/auth/otp', { phone });
const { token } = await api('/api/auth/verify', { phone, otp: devOtp, name: 'Bot', ageConfirmed: true });
await api('/api/wallet/deposit', { amountRs: 200 }, token);

const socket = io(BASE, { auth: { token }, transports: ['websocket'] });
const emit = (ev, payload = {}) =>
  new Promise((resolve) => socket.emit(ev, payload, (ack) => {
    if (!ack.ok) console.log(`[bot] ${ev}: ${ack.error}`);
    resolve(ack);
  }));

let lastPhase = null;
let joined = false;
let readyFor = null;
socket.on('room:state', async (s) => {
  if (!s) {
    if (!joined) return; // initial "not in a room" push on connect
    console.log('[bot] left the room, bye');
    process.exit(0);
  }
  if (s.phase !== lastPhase) {
    console.log(`[bot] phase ${s.phase}${s.me?.role ? ` · I am ${s.me.role}` : ''}`);
    if (s.result) console.log(`[bot] result: ${s.result.reason}`);
    lastPhase = s.phase;
  }
  // Ready up once per round (and again if the host changes the terms).
  const me = s.players.find((p) => p.userId === s.me?.userId);
  if (s.phase === 'LOBBY' && me && !me.ready && readyFor !== `${s.gameId}:${s.feeRs}:${s.isFree}`) {
    readyFor = `${s.gameId}:${s.feeRs}:${s.isFree}`;
    await emit('room:ready', { ready: true, clientSeed: Math.random().toString(16).slice(2) });
  }
  if (s.phase === 'DEALING' && !me?.flipped) setTimeout(() => emit('card:flip'), 1500);
});

await new Promise((r) => socket.on('connect', r));
const ack = await emit('room:join', { code });
if (!ack.ok) process.exit(1);
joined = true;
console.log(`[bot] joined room ${code}`);

// Report a slightly jittering position every 2 s.
setInterval(() => {
  const j = () => (Math.random() - 0.5) * 0.00002;
  socket.emit('loc', { lat: Number(lat) + j(), lng: Number(lng) + j(), accuracy: 4, mocked: false });
}, 2000);
