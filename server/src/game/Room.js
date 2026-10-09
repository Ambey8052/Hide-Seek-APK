// One game room and its state machine:
//
//   LOBBY ──deal──▶ DEALING ──all cards flipped──▶ HIDING ──head start over──▶ HUNTING ──▶ ENDED
//                                                                                   └────▶ VOID (refund)
//
// The room never talks to sockets directly: it calls `onChange()` whenever state
// changes and the RoomManager pushes a personalised snapshot to every player.
import { randomBytes, randomUUID } from 'node:crypto';
import { RULES } from '../config.js';
import { HttpError } from '../errors.js';
import {
  distanceM, isOutOfZone, isClose, heatLevel, validateFeeRs, splitPot, decideWinners, pickPolice, sha256,
} from '../rules.js';
import { getBalancePaise, collectEntryFees, refundEntryFees, payWinnings, toPaise, toRs } from '../wallet.js';
import {
  usersWithoutFreeGame, markFreeGameUsed, recordGameStart, recordGameEnd, recordGameVoid,
} from '../gameStore.js';

const ACTIVE_PHASES = new Set(['DEALING', 'HIDING', 'HUNTING']);
const SEC = 1000;

export class Room {
  constructor({ code, host, feeRs = 10, isFree = false, settings = {}, onChange = () => {}, now = Date.now }) {
    this.code = code;
    this.hostId = host.id;
    this.onChange = () => onChange(this);
    this.now = now;
    this.players = new Map();
    this.lastPoliceId = null;
    this.settings = { boundaryMode: 'police', heatMeter: true };
    this.events = [];
    this.eventSeq = 0;
    this.applySettings({ feeRs, isFree, ...settings });
    this.resetRound();
    this.addPlayer(host);
  }

  // ---------------------------------------------------------------- lobby

  resetRound() {
    this.id = randomUUID();
    this.phase = 'LOBBY';
    // Committed now, revealed after the game - see rules.js pickPolice().
    this.serverSeed = randomBytes(32).toString('hex');
    this.seedHash = sha256(this.serverSeed);
    this.policeId = null;
    this.potPaise = 0;
    this.feesCollected = false;
    this.flipDeadline = this.hideEndsAt = this.huntEndsAt = this.nextPulseAt = null;
    this.arenaCenter = null;
    this.result = null;
    this.endedAt = null;
  }

  get isActive() {
    return ACTIVE_PHASES.has(this.phase);
  }

  applySettings({ feeRs, isFree, boundaryMode, heatMeter }) {
    if (isFree !== undefined) this.isFree = !!isFree;
    if (!this.isFree && feeRs !== undefined) {
      if (!validateFeeRs(Number(feeRs))) {
        throw new HttpError(400, `Entry fee must be ₹${RULES.MIN_FEE_RS}-₹${RULES.MAX_FEE_RS}`);
      }
      this.feePaise = toPaise(feeRs);
    }
    if (this.isFree) this.feePaise = 0;
    if (boundaryMode === 'police' || boundaryMode === 'arena') this.settings.boundaryMode = boundaryMode;
    if (heatMeter !== undefined) this.settings.heatMeter = !!heatMeter;
  }

  newPlayer(user) {
    return {
      userId: user.id,
      name: user.name,
      ready: false,
      clientSeed: null,
      online: true,
      role: null,
      flipped: false,
      status: 'active', // active | caught | dq
      dqReason: null,
      loc: null,
      lastLocAt: null,
      outSince: null,
      strikes: 0,
      danger: false,
      heat: null,
      closeCount: 0,
      pulse: null,
      catches: 0,
      qr: null,
      prevQr: null,
      bleTag: null, // 8 hex chars this phone broadcasts over Bluetooth
      ble: new Map(), // tag heard -> { rssi (smoothed dBm), at }
      proxSource: null, // 'ble' | 'gps' - what the last proximity check used
    };
  }

  /** Clear all per-round state of a player, keeping identity plus `keep`. */
  resetPlayer(p, keep = {}) {
    Object.assign(p, this.newPlayer({ id: p.userId, name: p.name }), keep);
  }

  addPlayer(user) {
    const existing = this.players.get(user.id);
    if (existing) {
      existing.online = true;
      return existing;
    }
    if (this.phase !== 'LOBBY') throw new HttpError(409, 'This game has already started');
    if (this.players.size >= RULES.MAX_PLAYERS) throw new HttpError(409, `Room is full (max ${RULES.MAX_PLAYERS})`);
    const p = this.newPlayer(user);
    this.players.set(user.id, p);
    this.log(`${user.name} joined`, 'info');
    this.onChange();
    return p;
  }

  /** Player leaves voluntarily. Mid-game this counts as forfeiting. */
  removePlayer(userId) {
    const p = this.players.get(userId);
    if (!p) return;
    if (this.isActive) {
      if (userId === this.policeId) {
        this.log(`Police ${p.name} left the game`, 'dq');
        this.endGame('Police left the game', { policeForfeited: true });
      } else if (p.status === 'active') {
        this.disqualify(p, 'Left the game');
      }
      p.online = false;
      this.onChange();
      return;
    }
    this.players.delete(userId);
    if (this.hostId === userId && this.players.size) this.hostId = this.players.keys().next().value;
    this.log(`${p.name} left`, 'info');
    this.onChange();
  }

  kick(byUserId, userId) {
    this.assertHost(byUserId);
    if (this.phase !== 'LOBBY') throw new HttpError(409, 'Can only remove players in the lobby');
    if (userId === byUserId) throw new HttpError(400, "You can't remove yourself");
    this.removePlayer(userId);
  }

  updateSettings(byUserId, settings) {
    this.assertHost(byUserId);
    if (this.phase !== 'LOBBY') throw new HttpError(409, 'Game already started');
    this.applySettings(settings);
    // Money terms changed, so everyone has to agree again.
    for (const p of this.players.values()) p.ready = false;
    this.onChange();
  }

  setReady(userId, ready, clientSeed) {
    const p = this.mustGet(userId);
    if (this.phase !== 'LOBBY') throw new HttpError(409, 'Game already started');
    if (ready) {
      if (this.isFree) {
        if (usersWithoutFreeGame([userId]).length) {
          throw new HttpError(400, "You've already used today's free game. Join a cash game or play tomorrow.");
        }
      } else if (getBalancePaise(userId) < this.feePaise) {
        throw new HttpError(400, `Add money to your wallet: entry is ₹${toRs(this.feePaise)}`);
      }
      p.clientSeed = String(clientSeed || randomBytes(16).toString('hex')).slice(0, 64);
    }
    p.ready = !!ready;
    this.onChange();
  }

  /** Host starts the game: collect entry fees, secretly pick the police, deal cards. */
  deal(byUserId) {
    this.assertHost(byUserId);
    if (this.phase !== 'LOBBY') throw new HttpError(409, 'Game already started');
    const players = [...this.players.values()];
    if (players.length < RULES.MIN_PLAYERS) throw new HttpError(400, `Need at least ${RULES.MIN_PLAYERS} players`);
    const notReady = players.filter((p) => !p.ready || !p.online);
    if (notReady.length) throw new HttpError(400, `Waiting for: ${notReady.map((p) => p.name).join(', ')}`);

    const ids = players.map((p) => p.userId);
    if (this.isFree) {
      const blocked = usersWithoutFreeGame(ids);
      if (blocked.length) throw new HttpError(400, `${this.names(blocked)} already used today's free game`);
      markFreeGameUsed(ids);
    } else {
      const { ok, short } = collectEntryFees(this.id, ids, this.feePaise);
      if (!ok) {
        for (const id of short) this.players.get(id).ready = false;
        this.onChange();
        throw new HttpError(400, `Not enough wallet balance: ${this.names(short)}`);
      }
      this.feesCollected = true;
      this.potPaise = this.feePaise * ids.length;
    }

    this.policeId = pickPolice(this.serverSeed, players, this.lastPoliceId);
    const tags = new Set();
    for (const p of players) {
      this.resetPlayer(p, { clientSeed: p.clientSeed, ready: true, online: p.online });
      p.role = p.userId === this.policeId ? 'police' : 'thief';
      do p.bleTag = randomBytes(4).toString('hex'); while (tags.has(p.bleTag));
      tags.add(p.bleTag);
    }
    recordGameStart(this);

    this.phase = 'DEALING';
    this.flipDeadline = this.now() + RULES.AUTO_FLIP_SEC * SEC;
    this.log('Cards dealt! Shake your phone to flip your card', 'info');
    this.onChange();
  }

  // ---------------------------------------------------------------- game

  /** Player shook their phone: reveal their card to them only. */
  flip(userId) {
    const p = this.mustGet(userId);
    if (this.phase !== 'DEALING' || p.flipped) return;
    p.flipped = true;
    if ([...this.players.values()].every((x) => x.flipped)) this.startHiding();
    this.onChange();
  }

  startHiding() {
    const now = this.now();
    for (const p of this.players.values()) {
      p.flipped = true;
      p.lastLocAt = now; // grace period before "lost GPS" checks kick in
    }
    this.phase = 'HIDING';
    this.hideEndsAt = now + RULES.HIDE_DURATION_SEC * SEC;
    const police = this.players.get(this.policeId);
    this.log(`${police.name} is the Police! Thieves, go hide within ${RULES.BOUNDARY_RADIUS_M} m`, 'info');
  }

  startHunting() {
    const now = this.now();
    this.phase = 'HUNTING';
    this.huntEndsAt = now + RULES.GAME_DURATION_SEC * SEC;
    this.nextPulseAt = now + RULES.RADAR_PULSE_EVERY_SEC * SEC;
    const police = this.players.get(this.policeId);
    if (police.loc) this.arenaCenter = { ...police.loc };
    this.log('Ready or not - the hunt has begun!', 'info');
  }

  updateLocation(userId, { lat, lng, accuracy, mocked }) {
    const p = this.players.get(userId);
    if (!p || !this.isActive || p.status !== 'active') return;
    if (!Number.isFinite(lat) || !Number.isFinite(lng) || Math.abs(lat) > 90 || Math.abs(lng) > 180) return;
    if (mocked) {
      // Fake-GPS apps would make the whole game meaningless.
      if (userId === this.policeId) {
        this.log(`Police ${p.name} was using fake GPS`, 'dq');
        p.status = 'dq';
        p.dqReason = 'Fake GPS detected';
        this.endGame('Police used fake GPS', { policeForfeited: true });
      } else {
        this.disqualify(p, 'Fake GPS detected');
      }
      this.onChange();
      return;
    }
    const acc = accuracy == null || !Number.isFinite(Number(accuracy)) ? 50 : Number(accuracy); // unknown = poor
    p.loc = { lat, lng, accuracy: Math.max(0, Math.min(acc, 500)) };
    p.lastLocAt = this.now();
  }

  /**
   * Bluetooth readings from one phone: [{ tag, rssi }] for every game tag it hears.
   * Smoothed per tag, because single RSSI samples jump around by several dB.
   */
  updateProximity(userId, seen) {
    const p = this.players.get(userId);
    if (!p || !this.isActive || p.status !== 'active' || !Array.isArray(seen)) return;
    const now = this.now();
    const known = new Set([...this.players.values()].map((x) => x.bleTag).filter(Boolean));
    for (const s of seen.slice(0, 20)) {
      const tag = String(s?.tag || '').toLowerCase();
      const rssi = Number(s?.rssi);
      if (!known.has(tag) || tag === p.bleTag || !Number.isFinite(rssi) || rssi > 0 || rssi < -127) continue;
      const prev = p.ble.get(tag);
      const fresh = prev && now - prev.at < RULES.BLE_FRESH_SEC * SEC;
      p.ble.set(tag, { rssi: fresh ? prev.rssi * 0.6 + rssi * 0.4 : rssi, at: now });
    }
  }

  /** Strongest fresh Bluetooth signal between two players (either direction), or null. */
  bleRssi(a, b, now = this.now()) {
    const fresh = (r) => (r && now - r.at < RULES.BLE_FRESH_SEC * SEC ? r.rssi : null);
    const vals = [fresh(a.ble.get(b.bleTag)), fresh(b.ble.get(a.bleTag))].filter((v) => v !== null);
    return vals.length ? Math.max(...vals) : null;
  }

  /** Police tapped the "CAUGHT" button on the thief's own phone. */
  catchByTap(thiefId) {
    const thief = this.mustGet(thiefId);
    this.assertCatchable(thief);
    this.markCaught(thief, 'tap');
  }

  /** Police scanned the rotating QR code shown on the thief's phone. */
  catchByScan(policeId, token) {
    if (policeId !== this.policeId) throw new HttpError(403, 'Only the police can catch');
    const now = this.now();
    const ttl = RULES.QR_TOKEN_TTL_SEC * SEC;
    const thief = [...this.players.values()].find(
      (p) =>
        (p.qr?.token === token && now - p.qr.issuedAt < ttl) ||
        (p.prevQr?.token === token && now - p.prevQr.issuedAt < ttl),
    );
    if (!thief) throw new HttpError(400, 'Invalid or expired code - ask the thief to show a fresh one');
    this.assertCatchable(thief);
    this.markCaught(thief, 'scan');
    return thief.name;
  }

  assertCatchable(thief) {
    if (this.phase !== 'HUNTING') throw new HttpError(409, 'Catching is only allowed during the hunt');
    if (thief.role !== 'thief' || thief.status !== 'active') throw new HttpError(409, 'Player is not an active thief');
  }

  markCaught(thief, how) {
    thief.status = 'caught';
    thief.danger = false;
    thief.qr = thief.prevQr = null;
    const police = this.players.get(this.policeId);
    police.catches += 1;
    this.log(`${police.name} caught ${thief.name}!`, 'catch', { userId: thief.userId, how });
    this.checkAllCaught();
    this.onChange();
  }

  disqualify(p, reason) {
    if (p.status !== 'active') return;
    p.status = 'dq';
    p.dqReason = reason;
    p.danger = false;
    p.outSince = null;
    p.qr = p.prevQr = null;
    this.log(`${p.name} is out: ${reason}`, 'dq', { userId: p.userId });
    this.checkAllCaught();
  }

  activeThieves() {
    return [...this.players.values()].filter((p) => p.role === 'thief' && p.status === 'active');
  }

  checkAllCaught() {
    if ((this.phase === 'HIDING' || this.phase === 'HUNTING') && this.activeThieves().length === 0) {
      this.endGame('Every thief is caught or out - police wins');
    }
  }

  /** Called once per second by the RoomManager. */
  tick() {
    if (!this.isActive) return;
    const now = this.now();

    if (this.phase === 'DEALING') {
      if (now >= this.flipDeadline) this.startHiding();
      this.onChange();
      return;
    }

    const police = this.players.get(this.policeId);
    if (now - police.lastLocAt > RULES.POLICE_OFFLINE_VOID_SEC * SEC) {
      this.voidGame('Police lost connection - everyone is refunded');
      return;
    }

    if (this.phase === 'HIDING' && now >= this.hideEndsAt) this.startHunting();
    const hunting = this.phase === 'HUNTING';
    const remainingSec = hunting ? (this.huntEndsAt - now) / SEC : Infinity;
    const policeFresh = police.loc && now - police.lastLocAt < RULES.LOCATION_STALE_SEC * SEC;
    const center =
      this.settings.boundaryMode === 'arena' && this.arenaCenter ? this.arenaCenter : policeFresh ? police.loc : null;
    const policeRadius = remainingSec <= RULES.FINAL_HUNT_SEC ? RULES.FINAL_HUNT_CLOSE_RADIUS_M : RULES.CLOSE_RADIUS_M;

    let closeCount = 0;
    for (const t of this.activeThieves()) {
      if (now - t.lastLocAt > RULES.THIEF_OFFLINE_DQ_SEC * SEC) {
        this.disqualify(t, 'Lost connection / GPS for too long');
        continue;
      }
      const thiefFresh = t.loc && now - t.lastLocAt < RULES.LOCATION_STALE_SEC * SEC;

      // 100 m zone with a 30 s warning to come back.
      if (thiefFresh && center) {
        if (isOutOfZone(t.loc, center)) {
          if (!t.outSince) {
            t.outSince = now;
            t.strikes += 1;
            if (t.strikes > RULES.MAX_OUT_OF_RANGE_STRIKES) {
              this.disqualify(t, 'Left the zone too many times');
              continue;
            }
          }
        } else {
          t.outSince = null;
        }
      }
      if (t.outSince && now - t.outSince > RULES.OUT_OF_RANGE_GRACE_SEC * SEC) {
        this.disqualify(t, `Stayed outside the ${RULES.BOUNDARY_RADIUS_M} m zone`);
        continue;
      }

      // Proximity: police phone vibrates, thief sees the green dot.
      // Bluetooth is trusted when fresh; otherwise fall back to GPS.
      const ble = hunting ? this.bleRssi(t, police, now) : null;
      const gpsOk = hunting && thiefFresh && policeFresh;
      if (ble !== null) {
        t.proxSource = 'ble';
        t.danger = ble >= RULES.BLE_CLOSE_RSSI;
        const gpsHeat = gpsOk ? heatLevel(distanceM(t.loc, police.loc)) : 'warm';
        t.heat = this.settings.heatMeter ? (ble >= RULES.BLE_BURNING_RSSI ? 'burning' : gpsHeat === 'burning' ? 'hot' : gpsHeat) : null;
        if (ble >= (policeRadius > RULES.CLOSE_RADIUS_M ? RULES.BLE_FINAL_HUNT_RSSI : RULES.BLE_CLOSE_RSSI)) closeCount += 1;
      } else if (gpsOk) {
        t.proxSource = 'gps';
        t.danger = isClose(t.loc, police.loc, RULES.CLOSE_RADIUS_M);
        t.heat = this.settings.heatMeter ? heatLevel(distanceM(t.loc, police.loc)) : null;
        if (isClose(t.loc, police.loc, policeRadius)) closeCount += 1;
      } else {
        t.danger = false;
      }

      // Rotate the catch QR so a screenshot can't be reused.
      if (!t.qr || now - t.qr.issuedAt > RULES.QR_TOKEN_ROTATE_SEC * SEC) {
        t.prevQr = t.qr;
        t.qr = { token: randomBytes(12).toString('hex'), issuedAt: now };
      }
    }
    if (!this.isActive) return this.onChange(); // a disqualification above ended the game
    police.closeCount = closeCount;

    if (hunting && now >= this.nextPulseAt) {
      this.nextPulseAt = now + RULES.RADAR_PULSE_EVERY_SEC * SEC;
      police.pulse = this.radarPulse(police, now);
      if (police.pulse) this.log('Radar pulse sent to the Police', 'pulse');
    }

    if (this.isActive && this.activeThieves().length === 0) {
      this.endGame('Every thief is caught or out - police wins');
    } else if (this.phase === 'HUNTING' && now >= this.huntEndsAt) {
      this.endGame("Time's up! The thieves escaped");
    }
    this.onChange();
  }

  /** How many thieves are in each distance ring around the police - no directions. */
  radarPulse(police, now) {
    if (!police.loc) return null;
    const rings = [0, 0, 0, 0]; // <=25 m, <=50 m, <=100 m, further
    for (const t of this.activeThieves()) {
      if (!t.loc) continue;
      const d = distanceM(t.loc, police.loc);
      rings[d <= 25 ? 0 : d <= 50 ? 1 : d <= 100 ? 2 : 3] += 1;
    }
    return { at: now, rings };
  }

  endGame(reason, { policeForfeited = false } = {}) {
    if (!this.isActive) return;
    const thieves = [...this.players.values()].filter((p) => p.role === 'thief');
    const { winnerSide, winnerIds } = decideWinners({ policeId: this.policeId, thieves, policeForfeited });

    let payouts = [];
    let platformPaise = 0;
    if (!this.isFree && this.potPaise > 0) {
      if (winnerIds.length) {
        ({ payouts, platformPaise } = splitPot(this.potPaise, winnerIds, RULES.PLATFORM_FEE_PERCENT));
        payWinnings(this.id, payouts);
      } else {
        // Nobody left to win (e.g. police forfeited after every thief was out): refund all.
        refundEntryFees(this.id, [...this.players.keys()], this.feePaise, 'No winner - refund');
      }
    }

    this.phase = 'ENDED';
    this.endedAt = this.now();
    for (const p of this.players.values()) {
      p.danger = false;
      p.outSince = null;
      p.qr = p.prevQr = null;
    }
    const publicResult = {
      reason,
      winnerSide,
      winners: winnerIds.map((id) => ({ userId: id, name: this.players.get(id).name })),
      payouts: payouts.map((p) => ({ userId: p.userId, name: this.players.get(p.userId).name, rs: toRs(p.paise) })),
      potRs: toRs(this.potPaise),
      platformFeeRs: toRs(platformPaise),
      isFree: this.isFree,
    };
    this.result = publicResult;
    recordGameEnd(this, { winnerSide, winnerIds, payouts, public: publicResult });
    this.log(reason, 'end');
    this.onChange();
  }

  voidGame(reason) {
    if (!this.isActive) return;
    if (this.feesCollected) refundEntryFees(this.id, [...this.players.keys()], this.feePaise);
    recordGameVoid(this, reason);
    this.phase = 'VOID';
    this.endedAt = this.now();
    this.result = { reason, winnerSide: 'none', winners: [], payouts: [], potRs: toRs(this.potPaise), refunded: this.feesCollected, isFree: this.isFree };
    this.log(reason, 'end');
    this.onChange();
  }

  /** Same group, new round. The previous police can't be police again straight away. */
  rematch(byUserId) {
    this.assertHost(byUserId);
    if (this.phase !== 'ENDED' && this.phase !== 'VOID') throw new HttpError(409, 'Game is still running');
    this.lastPoliceId = this.policeId;
    for (const [id, p] of this.players) {
      if (!p.online) this.players.delete(id);
      else this.resetPlayer(p, { online: true });
    }
    if (!this.players.has(this.hostId)) this.hostId = this.players.keys().next().value;
    this.resetRound();
    // Today's free game is used up, so rematches default to a cash game.
    if (this.isFree) this.applySettings({ isFree: false, feeRs: RULES.MIN_FEE_RS });
    this.log('New round! Get ready', 'info');
    this.onChange();
  }

  // ---------------------------------------------------------------- views

  snapshotFor(userId) {
    const now = this.now();
    const me = this.players.get(userId);
    const revealed = ['HIDING', 'HUNTING', 'ENDED', 'VOID'].includes(this.phase);
    const finished = this.phase === 'ENDED' || this.phase === 'VOID';
    const isPolice = me && userId === this.policeId;

    return {
      code: this.code,
      gameId: this.id,
      hostId: this.hostId,
      phase: this.phase,
      feeRs: toRs(this.feePaise),
      isFree: this.isFree,
      potRs: toRs(this.potPaise),
      settings: this.settings,
      rules: {
        minPlayers: RULES.MIN_PLAYERS,
        maxPlayers: RULES.MAX_PLAYERS,
        minFeeRs: RULES.MIN_FEE_RS,
        maxFeeRs: RULES.MAX_FEE_RS,
        boundaryM: RULES.BOUNDARY_RADIUS_M,
        graceSec: RULES.OUT_OF_RANGE_GRACE_SEC,
        maxStrikes: RULES.MAX_OUT_OF_RANGE_STRIKES,
        gameSec: RULES.GAME_DURATION_SEC,
        hideSec: RULES.HIDE_DURATION_SEC,
        finalHuntSec: RULES.FINAL_HUNT_SEC,
      },
      seedHash: this.seedHash,
      serverSeed: finished ? this.serverSeed : null,
      serverNow: now,
      flipDeadline: this.flipDeadline,
      hideEndsAt: this.hideEndsAt,
      huntEndsAt: this.huntEndsAt,
      policeId: revealed ? this.policeId : null,
      players: [...this.players.values()].map((p) => ({
        userId: p.userId,
        name: p.name,
        isHost: p.userId === this.hostId,
        online: p.online,
        ready: p.ready,
        flipped: p.flipped,
        status: p.status,
        dqReason: p.dqReason,
      })),
      me: me && {
        userId,
        role: me.flipped ? me.role : null,
        status: me.status,
        dqReason: me.dqReason,
        strikes: me.strikes,
        outOfRange: me.outSince ? { deadline: me.outSince + RULES.OUT_OF_RANGE_GRACE_SEC * SEC } : null,
        danger: me.danger,
        heat: me.heat,
        qrToken: me.qr?.token || null,
        closeCount: isPolice ? me.closeCount : 0,
        pulse: isPolice ? me.pulse : null,
        nextPulseAt: isPolice ? this.nextPulseAt : null,
        catches: me.catches,
        gpsAccuracy: me.loc?.accuracy ?? null,
        bleTag: this.isActive ? me.bleTag : null,
        proxSource: isPolice
          ? this.activeThieves().some((t) => t.proxSource === 'ble') ? 'ble' : 'gps'
          : me.proxSource,
        gpsFresh: !!me.lastLocAt && now - me.lastLocAt < RULES.LOCATION_STALE_SEC * SEC && !!me.loc,
      },
      events: this.events.slice(-20),
      result: this.result,
    };
  }

  // ---------------------------------------------------------------- utils

  log(text, kind, extra = {}) {
    this.events.push({ id: ++this.eventSeq, at: this.now(), text, kind, ...extra });
    if (this.events.length > 50) this.events.shift();
  }

  names(ids) {
    return ids.map((id) => this.players.get(id)?.name || 'someone').join(', ');
  }

  mustGet(userId) {
    const p = this.players.get(userId);
    if (!p) throw new HttpError(404, 'You are not in this room');
    return p;
  }

  assertHost(userId) {
    if (userId !== this.hostId) throw new HttpError(403, 'Only the host can do that');
  }
}
