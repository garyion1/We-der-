import fetch from 'node-fetch';
import { createLogger } from '../utils/logger.js';

const log = createLogger('ltc-price');

const CACHE_MS = 30_000;
let cached = null;
let cachedAt = 0;

/** Current LTC/USD spot price, cached for 30s so quoting orders doesn't hammer the API. */
export async function getLtcUsdPrice() {
  if (cached && Date.now() - cachedAt < CACHE_MS) return cached;

  const res = await fetch('https://api.coingecko.com/api/v3/simple/price?ids=litecoin&vs_currencies=usd');
  if (!res.ok) {
    if (cached) {
      log.warn(`Price feed request failed (${res.status}), reusing last known price`);
      return cached;
    }
    throw new Error(`Failed to fetch LTC/USD price: ${res.status}`);
  }
  const json = await res.json();
  const price = json?.litecoin?.usd;
  if (typeof price !== 'number') throw new Error('LTC/USD price feed returned unexpected payload');

  cached = price;
  cachedAt = Date.now();
  return price;
}
