import { randomInt } from 'node:crypto';
import { Room } from './Room.js';
import { HttpError } from '../errors.js';

const CODE_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'; // no 0/O/1/I confusion
const FINISHED_ROOM_TTL_MS = 30 * 60_000;

export class RoomManager {
  /** @param {(userId: string, snapshot: object) => void} sendState */
  constructor(sendState) {
    this.sendState = sendState;
    this.rooms = new Map(); // code -> Room
    this.userRoom = new Map(); // userId -> code
    this.pendingFlush = new Set();
  }

  newCode() {
    let code;
    do {
      code = Array.from({ length: 6 }, () => CODE_ALPHABET[randomInt(CODE_ALPHABET.length)]).join('');
    } while (this.rooms.has(code));
    return code;
  }

  roomOf(userId) {
    const code = this.userRoom.get(userId);
    return code ? this.rooms.get(code) : null;
  }

  /** Batch every change made in one synchronous step into a single push per player. */
  scheduleFlush(room) {
    if (this.pendingFlush.has(room)) return;
    this.pendingFlush.add(room);
    queueMicrotask(() => {
      this.pendingFlush.delete(room);
      for (const userId of room.players.keys()) {
        if (this.userRoom.get(userId) === room.code) this.sendState(userId, room.snapshotFor(userId));
      }
    });
  }

  /** Leave any previous room first; refuse if that room is mid-game. */
  detach(userId) {
    const current = this.roomOf(userId);
    if (!current) return;
    if (current.isActive && current.players.get(userId)?.status === 'active') {
      throw new HttpError(409, `You are still in game ${current.code}. Finish or leave it first.`);
    }
    this.leave(userId);
  }

  create(user, opts) {
    this.detach(user.id);
    const code = this.newCode();
    const room = new Room({ code, host: user, ...opts, onChange: (r) => this.scheduleFlush(r) });
    this.rooms.set(code, room);
    this.userRoom.set(user.id, code);
    this.scheduleFlush(room);
    return room;
  }

  join(user, rawCode) {
    const code = String(rawCode || '').trim().toUpperCase();
    const room = this.rooms.get(code);
    if (!room) throw new HttpError(404, 'No room with that code');
    if (this.userRoom.get(user.id) !== code) this.detach(user.id);
    room.addPlayer(user);
    this.userRoom.set(user.id, code);
    this.scheduleFlush(room);
    return room;
  }

  leave(userId) {
    const room = this.roomOf(userId);
    if (!room) return;
    this.userRoom.delete(userId);
    room.removePlayer(userId);
    this.sendState(userId, null);
    if (room.players.size === 0) this.rooms.delete(room.code);
  }

  setOnline(userId, online) {
    const room = this.roomOf(userId);
    const p = room?.players.get(userId);
    if (!p) return;
    p.online = online;
    this.scheduleFlush(room);
  }

  tickAll() {
    const now = Date.now();
    for (const room of this.rooms.values()) {
      try {
        room.tick();
      } catch (err) {
        console.error(`tick failed for room ${room.code}`, err);
      }
      const abandoned = [...room.players.values()].every((p) => !p.online);
      const stale = room.endedAt && now - room.endedAt > FINISHED_ROOM_TTL_MS;
      if (!room.isActive && (stale || (abandoned && room.phase === 'LOBBY'))) {
        for (const id of room.players.keys()) if (this.userRoom.get(id) === room.code) this.userRoom.delete(id);
        this.rooms.delete(room.code);
      }
    }
  }
}
