// Pure game-rule helpers (no I/O) so they are easy to unit test.
import { createHash, createHmac } from 'node:crypto';
import { RULES } from './config.js';

const EARTH_RADIUS_M = 6_371_000;
const rad = (d) => (d * Math.PI) / 180;

/** Great-circle distance in metres between two {lat, lng} points. */
export function distanceM(a, b) {
  const dLat = rad(b.lat - a.lat);
  const dLng = rad(b.lng - a.lng);
  const h =
    Math.sin(dLat / 2) ** 2 + Math.cos(rad(a.lat)) * Math.cos(rad(b.lat)) * Math.sin(dLng / 2) ** 2;
  return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(h)));
}

/** Combined GPS uncertainty of two fixes, capped so bad fixes can't trigger everything. */
export function uncertaintyM(a, b) {
  return Math.min((a.accuracy || 0) + (b.accuracy || 0), RULES.MAX_GPS_UNCERTAINTY_M);
}

/**
 * A thief is only treated as out of the zone when they are out even after giving
 * them the benefit of their GPS error - so nobody is punished for GPS jitter.
 */
export function isOutOfZone(thief, center, radiusM = RULES.BOUNDARY_RADIUS_M) {
  return distanceM(thief, center) - uncertaintyM(thief, center) > radiusM;
}

/** Police "could be" within closeRadius given GPS error. */
export function isClose(thief, police, closeRadiusM) {
  return distanceM(thief, police) - uncertaintyM(thief, police) <= closeRadiusM;
}

/** Distance band shown on a thief's heat meter. */
export function heatLevel(distance) {
  if (distance <= 5) return 'burning';
  if (distance <= 20) return 'hot';
  if (distance <= 50) return 'warm';
  return 'cold';
}

export function validateFeeRs(feeRs) {
  return (
    Number.isInteger(feeRs) && feeRs >= RULES.MIN_FEE_RS && feeRs <= RULES.MAX_FEE_RS
  );
}

/**
 * Split a pot equally between winners, in integer paise.
 * Leftover paise (at most winners-1) go one each to winners in id order, so the
 * whole pot is always paid out and the result is deterministic.
 */
export function splitPot(potPaise, winnerIds, platformFeePercent = 0) {
  if (winnerIds.length === 0) return { payouts: [], platformPaise: potPaise };
  const platformPaise = Math.floor((potPaise * platformFeePercent) / 100);
  const distributable = potPaise - platformPaise;
  const sorted = [...winnerIds].sort();
  const base = Math.floor(distributable / sorted.length);
  let remainder = distributable - base * sorted.length;
  const payouts = sorted.map((userId) => {
    const extra = remainder > 0 ? 1 : 0;
    remainder -= extra;
    return { userId, paise: base + extra };
  });
  return { payouts, platformPaise };
}

/**
 * Work out who won once the game is over.
 * - Police catches every thief (no active thieves left)  -> police takes the pot.
 * - Otherwise (time up / police forfeits)                -> surviving thieves split it.
 */
export function decideWinners({ policeId, thieves, policeForfeited = false }) {
  const survivors = thieves.filter((t) => t.status === 'active').map((t) => t.userId);
  if (!policeForfeited && survivors.length === 0) {
    return { winnerSide: 'police', winnerIds: [policeId] };
  }
  if (survivors.length === 0) return { winnerSide: 'none', winnerIds: [] };
  return { winnerSide: 'thieves', winnerIds: survivors };
}

// ---------------------------------------------------------------------------
// Provably-fair police selection.
// The server commits to sha256(serverSeed) when the room is created, before any
// player has joined. Each player adds their own random clientSeed. The police is
// derived from HMAC(serverSeed, all client seeds), and the serverSeed is revealed
// after the game so anyone can recompute it - the server can't pick the police.
// ---------------------------------------------------------------------------

export const sha256 = (s) => createHash('sha256').update(s).digest('hex');

/** Message that gets HMAC'd: players sorted by id, each with their client seed. */
export function fairnessMessage(players) {
  return [...players]
    .sort((a, b) => (a.userId < b.userId ? -1 : 1))
    .map((p) => `${p.userId}:${p.clientSeed}`)
    .join('|');
}

/**
 * Returns the userId of the police.
 * If the derived player was police in the previous round of this room, the next
 * player in sorted order gets it instead, so nobody is stuck as police twice.
 */
export function pickPolice(serverSeed, players, lastPoliceId = null) {
  const sorted = [...players].sort((a, b) => (a.userId < b.userId ? -1 : 1));
  const digest = createHmac('sha256', serverSeed).update(fairnessMessage(sorted)).digest('hex');
  let index = parseInt(digest.slice(0, 12), 16) % sorted.length;
  if (sorted.length > 1 && sorted[index].userId === lastPoliceId) {
    index = (index + 1) % sorted.length;
  }
  return sorted[index].userId;
}
