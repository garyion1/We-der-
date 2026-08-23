import fetch from 'node-fetch';
import { config } from '../config.js';
import { createLogger } from '../utils/logger.js';

const log = createLogger('donutapi');

// DonutSMP's public API allows 250 requests/min per key. We throttle well
// under that so this bot never gets rate-limited out from under a live order.
const MIN_INTERVAL_MS = 60_000 / 200;
let lastCallAt = 0;

async function throttle() {
  const wait = lastCallAt + MIN_INTERVAL_MS - Date.now();
  if (wait > 0) await new Promise((r) => setTimeout(r, wait));
  lastCallAt = Date.now();
}

async function apiGet(pathname) {
  await throttle();
  const res = await fetch(`${config.donutsmp.apiBase}${pathname}`, {
    headers: {
      Authorization: `Bearer ${config.donutsmp.apiKey}`,
      Accept: 'application/json',
    },
  });
  if (!res.ok) {
    const body = await res.text().catch(() => '');
    throw new Error(`DonutSMP API ${pathname} -> ${res.status}: ${body.slice(0, 300)}`);
  }
  return res.json();
}

/**
 * The exact response shape isn't pinned down until we've seen a live
 * response with a real API key, so this tries the field names DonutSMP
 * (and the community tools built on it) are known to use, in order.
 * If none match, it throws with the raw payload logged so the field name
 * can be added here in one place.
 */
function extractBalance(payload) {
  const candidates = [
    payload?.result?.money,
    payload?.result?.balance,
    payload?.data?.money,
    payload?.data?.balance,
    payload?.money,
    payload?.balance,
    payload?.data?.stats?.money,
  ];
  for (const c of candidates) {
    if (typeof c === 'number') return Math.round(c);
    if (typeof c === 'string' && c.trim() !== '' && !Number.isNaN(Number(c))) return Math.round(Number(c));
  }
  log.error('Could not find a balance field in DonutSMP API response', payload);
  throw new Error('DonutSMP API response did not contain a recognizable balance field — see logs and update extractBalance()');
}

/** Look up a player's current in-game money balance. */
export async function getPlayerBalance(ign) {
  const payload = await apiGet(`/player/${encodeURIComponent(ign)}`);
  return extractBalance(payload);
}

/** Convenience: current balance of the bank/bot account itself. */
export async function getBankBalance() {
  return getPlayerBalance(config.donutsmp.bankIgn);
}
