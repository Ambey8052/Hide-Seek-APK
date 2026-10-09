// Live smoke test against a running server: two fake phones log in, top up,
// create/join a room, deal, shake, and report GPS.
// Usage: node scripts/smoke.js [http://localhost:4000]
import { io } from 'socket.io-client';

const BASE = process.argv[2] || 'http://localhost:4000';
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

async function login(phone, name) {
  const { devOtp } = await api('/api/auth/otp', { phone });
  const { token, user } = await api('/api/auth/verify', { phone, otp: devOtp, name, ageConfirmed: true });
  await api('/api/wallet/deposit', { amountRs: 100 }, token);
  const socket = io(BASE, { auth: { token }, transports: ['websocket'] });
  const state = { latest: null };
  socket.on('room:state', (s) => (state.latest = s));
  await new Promise((r) => socket.on('connect', r));
  const emit = (ev, payload = {}) =>
    new Promise((resolve, reject) =>
      socket.emit(ev, payload, (ack) => (ack.ok ? resolve(ack) : reject(new Error(`${ev}: ${ack.error}`)))),
    );
  return { token, user, socket, state, emit };
}

const wait = (ms) => new Promise((r) => setTimeout(r, ms));
const rand = () => String(6000000000 + Math.floor(Math.random() * 3e9));

const a = await login(rand(), 'Asha');
const b = await login(rand(), 'Bunty');

const { code } = await a.emit('room:create', { feeRs: 20 });
// The app navigates to the room screen right after the ack, so state must already be there.
if (a.state.latest?.code !== code) throw new Error('room state arrived after the create ack');
await b.emit('room:join', { code });
if (b.state.latest?.code !== code) throw new Error('room state arrived after the join ack');
await a.emit('room:ready', { ready: true, clientSeed: 'aaa' });
await b.emit('room:ready', { ready: true, clientSeed: 'bbb' });
await a.emit('room:deal');
await wait(100);
console.log('phase after deal:', a.state.latest.phase, '| pot ₹', a.state.latest.potRs);
await a.emit('card:flip');
await b.emit('card:flip');
await wait(100);
const s = a.state.latest;
console.log('phase after flips:', s.phase, '| my role:', s.me.role, '| police:', s.players.find((p) => p.userId === s.policeId).name);
await a.emit('loc', { lat: 28.6139, lng: 77.209, accuracy: 5 });
await b.emit('loc', { lat: 28.6141, lng: 77.209, accuracy: 5 });
const wallet = await api('/api/wallet', null, a.token);
console.log('Asha wallet ₹', wallet.balanceRs, '| last txn:', wallet.transactions[0].type, wallet.transactions[0].amountRs);
await a.emit('room:leave');
await wait(100);
console.log('after police/thief leaves:', b.state.latest.phase, '-', b.state.latest.result?.reason);
console.log('Bunty wallet ₹', (await api('/api/wallet', null, b.token)).balanceRs);
a.socket.close();
b.socket.close();
console.log('SMOKE OK');
