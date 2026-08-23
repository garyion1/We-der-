/**
 * DonutSMP's exact in-game pay-confirmation wording hasn't been confirmed
 * against a live server yet, so this tries several common economy-plugin
 * phrasings. Once you see a real payment message in-game, add/replace the
 * pattern here — everything downstream just consumes { payerIgn, amount }.
 *
 * Each pattern must capture the payer's IGN as group "payer" and the
 * amount (digits, commas, optional decimal) as group "amount".
 */
const PATTERNS = [
  /^(?<payer>\w+) paid you \$?(?<amount>[\d,]+(?:\.\d+)?)/i,
  /^You received \$?(?<amount>[\d,]+(?:\.\d+)?) from (?<payer>\w+)/i,
  /^\[Pay\] (?<payer>\w+) has paid you \$?(?<amount>[\d,]+(?:\.\d+)?)/i,
  /^(?<payer>\w+) has paid (?:you )?\$?(?<amount>[\d,]+(?:\.\d+)?) to you/i,
];

/**
 * @param {string} message plain-text chat line (colour codes already stripped by mineflayer)
 * @returns {{ payerIgn: string, amount: number } | null}
 */
export function parsePaymentMessage(message) {
  for (const pattern of PATTERNS) {
    const match = message.match(pattern);
    if (!match?.groups) continue;
    const amount = Number(match.groups.amount.replace(/,/g, ''));
    if (!Number.isFinite(amount) || amount <= 0) continue;
    return { payerIgn: match.groups.payer, amount: Math.round(amount) };
  }
  return null;
}

/**
 * Same caveat as PATTERNS above — DonutSMP's whisper format needs to be
 * confirmed live and adjusted here. Used for the IGN-ownership verification
 * step (user whispers a one-time code to the bank account).
 */
const WHISPER_PATTERNS = [
  /^(?<sender>\w+) whispers?(?: to you)?: ?(?<text>.+)$/i,
  /^\[(?<sender>\w+) -> me\] ?(?<text>.+)$/i,
  /^From (?<sender>\w+): ?(?<text>.+)$/i,
];

/**
 * @param {string} message
 * @returns {{ senderIgn: string, text: string } | null}
 */
export function parseWhisperMessage(message) {
  for (const pattern of WHISPER_PATTERNS) {
    const match = message.match(pattern);
    if (!match?.groups) continue;
    return { senderIgn: match.groups.sender, text: match.groups.text.trim() };
  }
  return null;
}
