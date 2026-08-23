import Database from 'better-sqlite3';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { config } from '../config.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

fs.mkdirSync(path.dirname(config.database.path), { recursive: true });

export const db = new Database(config.database.path);
db.pragma('journal_mode = WAL');
db.pragma('foreign_keys = ON');

const schema = fs.readFileSync(path.join(__dirname, 'schema.sql'), 'utf8');
db.exec(schema);

const now = () => Date.now();

// ---------- users ----------

const upsertUserStmt = db.prepare(`
  INSERT INTO users (discord_id, ign, ign_lower, ltc_address, verify_code, verified_at, created_at, updated_at)
  VALUES (@discordId, @ign, @ignLower, @ltcAddress, @verifyCode, @verifiedAt, @now, @now)
  ON CONFLICT(discord_id) DO UPDATE SET
    ign = excluded.ign,
    ign_lower = excluded.ign_lower,
    ltc_address = excluded.ltc_address,
    verify_code = excluded.verify_code,
    verified_at = excluded.verified_at,
    updated_at = excluded.updated_at
`);

export function upsertUser({ discordId, ign, ltcAddress, verifyCode = null, verifiedAt = null }) {
  upsertUserStmt.run({
    discordId,
    ign,
    ignLower: ign.toLowerCase(),
    ltcAddress,
    verifyCode,
    verifiedAt,
    now: now(),
  });
}

export function markUserVerified(discordId) {
  db.prepare('UPDATE users SET verified_at = ?, verify_code = NULL, updated_at = ? WHERE discord_id = ?')
    .run(now(), now(), discordId);
}

export function getUserByDiscordId(discordId) {
  return db.prepare('SELECT * FROM users WHERE discord_id = ?').get(discordId);
}

export function getUserByIgn(ign) {
  return db.prepare('SELECT * FROM users WHERE ign_lower = ?').get(ign.toLowerCase());
}

// ---------- orders ----------

const insertOrderStmt = db.prepare(`
  INSERT INTO orders (
    discord_id, ign, ign_lower, visibility, amount_ingame,
    rate_usd_per_million, usd_amount, ltc_price_usd, ltc_amount,
    status, ltc_address, bank_balance_before, created_at, expires_at
  ) VALUES (
    @discordId, @ign, @ignLower, @visibility, @amountIngame,
    @rateUsdPerMillion, @usdAmount, @ltcPriceUsd, @ltcAmount,
    'pending', @ltcAddress, @bankBalanceBefore, @now, @expiresAt
  )
`);

export function createOrder(order) {
  const createdAt = now();
  const info = insertOrderStmt.run({
    ...order,
    ignLower: order.ign.toLowerCase(),
    now: createdAt,
    expiresAt: createdAt + order.timeoutMinutes * 60_000,
  });
  return getOrderById(info.lastInsertRowid);
}

export function getOrderById(id) {
  return db.prepare('SELECT * FROM orders WHERE id = ?').get(id);
}

export function getOpenOrdersForIgn(ign) {
  return db
    .prepare(
      `SELECT * FROM orders
       WHERE ign_lower = ? AND status IN ('pending', 'ingame_payment_seen')
       ORDER BY created_at ASC`
    )
    .all(ign.toLowerCase());
}

export function getExpirableOrders() {
  return db
    .prepare(
      `SELECT * FROM orders
       WHERE status IN ('pending', 'ingame_payment_seen') AND expires_at < ?`
    )
    .all(now());
}

export function setOrderStatus(id, status, extra = {}) {
  const fields = ['status = @status'];
  const params = { id, status, ...extra };
  for (const key of Object.keys(extra)) {
    fields.push(`${toSnake(key)} = @${key}`);
  }
  db.prepare(`UPDATE orders SET ${fields.join(', ')} WHERE id = @id`).run(params);
  return getOrderById(id);
}

function toSnake(camel) {
  return camel.replace(/[A-Z]/g, (m) => `_${m.toLowerCase()}`);
}

export function sumCompletedIngameSince(discordId, sinceTs) {
  const row = db
    .prepare(
      `SELECT COALESCE(SUM(amount_ingame), 0) AS total FROM orders
       WHERE discord_id = ? AND status = 'completed' AND created_at >= ?`
    )
    .get(discordId, sinceTs);
  return row.total;
}

export function getLeaderboard(limit = 10) {
  return db
    .prepare(
      `SELECT discord_id, ign, SUM(amount_ingame) AS total_ingame, SUM(ltc_amount) AS total_ltc, COUNT(*) AS orders
       FROM orders
       WHERE status = 'completed'
       GROUP BY discord_id
       ORDER BY total_ingame DESC
       LIMIT ?`
    )
    .all(limit);
}

export function getStatsTotals() {
  return db
    .prepare(
      `SELECT COUNT(*) AS orders, COALESCE(SUM(amount_ingame),0) AS total_ingame, COALESCE(SUM(ltc_amount),0) AS total_ltc
       FROM orders WHERE status = 'completed'`
    )
    .get();
}

// ---------- bank balance snapshots ----------

export function recordBankBalanceSnapshot(balance) {
  db.prepare('INSERT INTO bank_balance_snapshots (balance, taken_at) VALUES (?, ?)').run(balance, now());
}

export function getLatestBankBalanceSnapshot() {
  return db.prepare('SELECT * FROM bank_balance_snapshots ORDER BY taken_at DESC LIMIT 1').get();
}

// ---------- bot_state kv ----------

export function getState(key) {
  return db.prepare('SELECT value FROM bot_state WHERE key = ?').get(key)?.value ?? null;
}

export function setState(key, value) {
  db.prepare(
    'INSERT INTO bot_state (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value'
  ).run(key, value);
}
