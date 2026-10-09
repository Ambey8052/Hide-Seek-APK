import { test, before } from 'node:test';
import assert from 'node:assert/strict';

process.env.DB_PATH = ':memory:';
let auth, friends;
before(async () => {
  auth = await import('../src/auth.js');
  friends = await import('../src/friends.js');
});

test('guest login: same device gets the same account, new device a new one', () => {
  const a = auth.guestLogin({ deviceId: 'device-aaaa-1111' });
  const again = auth.guestLogin({ deviceId: 'device-aaaa-1111' });
  const b = auth.guestLogin({ deviceId: 'device-bbbb-2222', name: 'Rahul' });
  assert.equal(a.user.id, again.user.id);
  assert.notEqual(a.token, again.token);
  assert.notEqual(a.user.id, b.user.id);
  assert.match(a.user.name, /^Player \d{4}$/);
  assert.equal(b.user.name, 'Rahul');
  assert.equal(a.user.phoneMasked, 'Guest');
  assert.equal(auth.userFromToken(again.token).id, a.user.id);
  assert.throws(() => auth.guestLogin({ deviceId: 'x' }), /Invalid device id/);
});

test('guests can add each other as friends by player code', () => {
  const a = auth.guestLogin({ deviceId: 'device-cccc-3333' }).user;
  const b = auth.guestLogin({ deviceId: 'device-dddd-4444' }).user;
  assert.match(b.code, /^[0-9A-F]{8}$/);
  friends.addFriendByPhone(a.id, b.code.toLowerCase());
  assert.equal(friends.areFriends(a.id, b.id), true);
  assert.equal(friends.areFriends(b.id, a.id), true);
  assert.throws(() => friends.addFriendByPhone(a.id, 'ABCDEF12'), /No player with that code/);
});
