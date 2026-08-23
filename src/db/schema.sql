CREATE TABLE IF NOT EXISTS users (
  discord_id     TEXT PRIMARY KEY,
  ign            TEXT NOT NULL,
  ign_lower      TEXT NOT NULL UNIQUE,
  ltc_address    TEXT NOT NULL,
  verify_code    TEXT,
  verified_at    INTEGER,
  created_at     INTEGER NOT NULL,
  updated_at     INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS orders (
  id                     INTEGER PRIMARY KEY AUTOINCREMENT,
  discord_id             TEXT NOT NULL,
  ign                    TEXT NOT NULL,
  ign_lower              TEXT NOT NULL,
  visibility             TEXT NOT NULL CHECK (visibility IN ('public','anonymous')),
  amount_ingame          INTEGER NOT NULL,
  rate_usd_per_million   REAL NOT NULL,
  usd_amount             REAL NOT NULL,
  ltc_price_usd          REAL NOT NULL,
  ltc_amount             REAL NOT NULL,
  status                 TEXT NOT NULL CHECK (status IN (
                           'pending',
                           'ingame_payment_seen',
                           'ingame_payment_confirmed',
                           'payout_sent',
                           'completed',
                           'expired',
                           'failed',
                           'cancelled'
                         )),
  ltc_address            TEXT NOT NULL,
  tx_hash                TEXT,
  bank_balance_before    INTEGER,
  created_at             INTEGER NOT NULL,
  expires_at             INTEGER NOT NULL,
  ingame_paid_at         INTEGER,
  confirmed_at           INTEGER,
  fulfilled_at           INTEGER,
  failure_reason         TEXT,
  FOREIGN KEY (discord_id) REFERENCES users(discord_id)
);

CREATE INDEX IF NOT EXISTS idx_orders_status ON orders(status);
CREATE INDEX IF NOT EXISTS idx_orders_ign_lower_status ON orders(ign_lower, status);
CREATE INDEX IF NOT EXISTS idx_orders_discord_id ON orders(discord_id);

CREATE TABLE IF NOT EXISTS bank_balance_snapshots (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  balance    INTEGER NOT NULL,
  taken_at   INTEGER NOT NULL
);

-- Small generic key/value store (e.g. the posted shop embed's message id).
CREATE TABLE IF NOT EXISTS bot_state (
  key    TEXT PRIMARY KEY,
  value  TEXT NOT NULL
);
