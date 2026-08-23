const SUFFIXES = { k: 1_000, m: 1_000_000, b: 1_000_000_000 };

/**
 * Parses a user-typed in-game money amount, accepting shorthand like
 * "5m", "1.2b", "500k", or plain "5000000" / "5,000,000".
 * @returns {number|null} whole-dollar integer, or null if unparseable
 */
export function parseIngameAmount(input) {
  if (!input) return null;
  const trimmed = input.trim().toLowerCase().replace(/,/g, '').replace(/^\$/, '');
  const match = trimmed.match(/^(\d+(?:\.\d+)?)([kmb])?$/);
  if (!match) return null;
  const [, numStr, suffix] = match;
  const num = Number(numStr);
  if (!Number.isFinite(num) || num <= 0) return null;
  const value = suffix ? num * SUFFIXES[suffix] : num;
  return Math.round(value);
}

/** Formats a whole-dollar amount back to shorthand for display, e.g. 14180000000 -> "14.18b". */
export function formatIngameAmount(amount) {
  if (amount >= SUFFIXES.b) return `${trimZeros(amount / SUFFIXES.b)}b`;
  if (amount >= SUFFIXES.m) return `${trimZeros(amount / SUFFIXES.m)}m`;
  if (amount >= SUFFIXES.k) return `${trimZeros(amount / SUFFIXES.k)}k`;
  return amount.toLocaleString();
}

function trimZeros(n) {
  return Number(n.toFixed(2)).toString();
}
