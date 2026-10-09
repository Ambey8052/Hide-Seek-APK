import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHmac } from 'node:crypto';
import {
  distanceM, splitPot, decideWinners, pickPolice, fairnessMessage, sha256, isOutOfZone, isClose, validateFeeRs,
} from '../src/rules.js';

// ~1 m of latitude
const M = 1 / 111_195;
const at = (north, accuracy = 0) => ({ lat: 28.6139 + north * M, lng: 77.209, accuracy });

test('distance is accurate at game scale', () => {
  assert.ok(Math.abs(distanceM(at(0), at(100)) - 100) < 0.5);
  assert.ok(Math.abs(distanceM(at(0), at(1)) - 1) < 0.05);
});

test('zone check gives thieves the benefit of GPS error', () => {
  assert.equal(isOutOfZone(at(103, 3), at(0, 2)), false); // 103 m but ±5 m
  assert.equal(isOutOfZone(at(110, 3), at(0, 2)), true);
  assert.equal(isOutOfZone(at(99), at(0)), false);
});

test('close detection uses capped GPS uncertainty', () => {
  assert.equal(isClose(at(0.8), at(0), 1), true);
  assert.equal(isClose(at(4, 2), at(0, 2), 1), true); // 4 m - 4 m uncertainty
  assert.equal(isClose(at(30, 50), at(0, 50), 1), false); // uncertainty capped at 5 m
});

test('fee validation', () => {
  assert.equal(validateFeeRs(10), true);
  assert.equal(validateFeeRs(100), true);
  assert.equal(validateFeeRs(5), false);
  assert.equal(validateFeeRs(101), false);
  assert.equal(validateFeeRs(12.5), false);
});

test('pot split pays out every paisa', () => {
  const { payouts, platformPaise } = splitPot(10_000, ['c', 'a', 'b']);
  assert.equal(platformPaise, 0);
  assert.deepEqual(payouts, [
    { userId: 'a', paise: 3334 },
    { userId: 'b', paise: 3333 },
    { userId: 'c', paise: 3333 },
  ]);
  const withFee = splitPot(10_000, ['a', 'b'], 10);
  assert.equal(withFee.platformPaise, 1000);
  assert.equal(withFee.payouts.reduce((s, p) => s + p.paise, 0), 9000);
});

test('winner rules', () => {
  const thieves = [
    { userId: 't1', status: 'caught' },
    { userId: 't2', status: 'active' },
    { userId: 't3', status: 'dq' },
  ];
  assert.deepEqual(decideWinners({ policeId: 'p', thieves }), { winnerSide: 'thieves', winnerIds: ['t2'] });
  const allCaught = thieves.map((t) => ({ ...t, status: 'caught' }));
  assert.deepEqual(decideWinners({ policeId: 'p', thieves: allCaught }), { winnerSide: 'police', winnerIds: ['p'] });
  assert.deepEqual(decideWinners({ policeId: 'p', thieves: allCaught, policeForfeited: true }), {
    winnerSide: 'none',
    winnerIds: [],
  });
});

test('police pick is reproducible from the revealed seed and never repeats', () => {
  const players = ['u3', 'u1', 'u2', 'u4'].map((userId) => ({ userId, clientSeed: `seed-${userId}` }));
  const seed = 'a'.repeat(64);
  const police = pickPolice(seed, players);

  // Independent re-derivation, exactly as documented to players.
  const sorted = [...players].sort((a, b) => (a.userId < b.userId ? -1 : 1));
  const digest = createHmac('sha256', seed).update(fairnessMessage(players)).digest('hex');
  assert.equal(police, sorted[parseInt(digest.slice(0, 12), 16) % 4].userId);

  assert.notEqual(pickPolice(seed, players, police), police);
  assert.equal(sha256('x').length, 64);

  // Roughly uniform over many seeds.
  const counts = {};
  for (let i = 0; i < 4000; i++) {
    const p = pickPolice(String(i), players);
    counts[p] = (counts[p] || 0) + 1;
  }
  for (const c of Object.values(counts)) assert.ok(c > 850 && c < 1150, `skewed: ${JSON.stringify(counts)}`);
});
