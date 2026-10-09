import { Server } from 'socket.io';
import { userFromToken, getUser } from './auth.js';
import { RoomManager } from './game/RoomManager.js';
import { areFriends } from './friends.js';
import { HttpError } from './errors.js';

/** userId -> number of connected sockets (a user may have several). */
export const presence = new Map();

export function attachSockets(httpServer) {
  const io = new Server(httpServer, { cors: { origin: '*' }, pingInterval: 10_000, pingTimeout: 15_000 });
  const manager = new RoomManager((userId, snapshot) => io.to(`user:${userId}`).emit('room:state', snapshot));

  io.use((socket, next) => {
    const user = userFromToken(socket.handshake.auth?.token);
    if (!user) return next(new Error('unauthorized'));
    socket.data.userId = user.id;
    next();
  });

  io.on('connection', (socket) => {
    const userId = socket.data.userId;
    socket.join(`user:${userId}`);
    presence.set(userId, (presence.get(userId) || 0) + 1);
    manager.setOnline(userId, true);
    const existing = manager.roomOf(userId);
    socket.emit('room:state', existing ? existing.snapshotFor(userId) : null);

    // Wrap a handler so thrown errors become { error } acks instead of crashes.
    // Acks are deferred so the room-state push (flushed in a microtask) reaches the
    // client first - e.g. after "room:create" the app already has the room when it navigates.
    const on = (event, handler) =>
      socket.on(event, (payload, ack) => {
        const reply = (res) => typeof ack === 'function' && setImmediate(() => ack(res));
        try {
          const result = handler(payload || {});
          reply({ ok: true, ...(result || {}) });
        } catch (err) {
          if (!(err instanceof HttpError)) console.error(`socket ${event} failed`, err);
          reply({ ok: false, error: err instanceof HttpError ? err.message : 'Something went wrong' });
        }
      });

    const me = () => getUser(userId);
    const room = () => {
      const r = manager.roomOf(userId);
      if (!r) throw new HttpError(404, 'You are not in a room');
      return r;
    };

    on('room:create', ({ feeRs, isFree, settings }) => ({ code: manager.create(me(), { feeRs, isFree, settings }).code }));
    on('room:join', ({ code }) => ({ code: manager.join(me(), code).code }));
    on('room:leave', () => manager.leave(userId));
    on('room:sync', () => {
      const r = manager.roomOf(userId);
      socket.emit('room:state', r ? r.snapshotFor(userId) : null);
    });
    on('room:settings', (settings) => room().updateSettings(userId, settings));
    on('room:ready', ({ ready, clientSeed }) => room().setReady(userId, ready, clientSeed));
    on('room:kick', ({ userId: target }) => {
      room().kick(userId, target);
      manager.userRoom.delete(target);
      io.to(`user:${target}`).emit('room:state', null);
    });
    on('room:deal', () => room().deal(userId));
    on('room:rematch', () => room().rematch(userId));
    on('card:flip', () => room().flip(userId));
    on('loc', (fix) => room().updateLocation(userId, fix));
    on('prox', ({ seen }) => room().updateProximity(userId, seen));
    on('catch:tap', () => room().catchByTap(userId));
    on('catch:scan', ({ token }) => ({ caught: room().catchByScan(userId, String(token || '')) }));

    on('invite:send', ({ friendId }) => {
      const r = room();
      if (r.phase !== 'LOBBY') throw new HttpError(409, 'Game already started');
      if (!areFriends(userId, friendId)) throw new HttpError(403, 'You can only invite friends');
      if (!presence.get(friendId)) throw new HttpError(409, 'Friend is offline - share the room code instead');
      io.to(`user:${friendId}`).emit('invite', {
        code: r.code,
        fromName: me().name,
        feeRs: r.feePaise / 100,
        isFree: r.isFree,
        players: r.players.size,
      });
    });

    socket.on('disconnect', () => {
      const left = (presence.get(userId) || 1) - 1;
      if (left <= 0) {
        presence.delete(userId);
        manager.setOnline(userId, false);
      } else {
        presence.set(userId, left);
      }
    });
  });

  setInterval(() => manager.tickAll(), 1000).unref();
  return { io, manager };
}
