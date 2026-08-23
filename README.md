# DonutSMP Autosell

A Discord bot that lets DonutSMP players sell their in-game money for Litecoin, paid out automatically. A dedicated Minecraft "bank" account (via [mineflayer](https://github.com/PrismarineJS/mineflayer)) receives the in-game payment, the DonutSMP public API corroborates it, and a self-custodied LTC hot wallet sends the payout.

Skeleton spawner selling is intentionally out of scope — money only.

## How it works

1. **Settings** — user links their IGN + LTC address, then whispers a one-time code to the bank account in-game to prove they own that IGN.
2. **Sell Money** — user enters an amount and picks Public/Anonymous.
3. Bot tells them to pay that exact amount to the bank account in-game, within a timeout window.
4. The Mineflayer worker sees the payment in chat; the DonutSMP API cross-checks the bank account's balance actually went up by that amount before anything is paid out.
5. The bot sends LTC from its hot wallet to the user's address and DMs them the tx id.

## Architecture

```
src/
  config.js              env-based config, fails fast on missing required vars
  index.js                entry point — wires everything together
  db/                      SQLite (better-sqlite3): users, orders, balance snapshots
  donutApi/client.js       wraps api.donutsmp.net/v1 (key via in-game /api command)
  pricing/ltcPrice.js      LTC/USD spot price (CoinGecko), cached 30s
  mineflayer/              bank account: connection, chat/whisper parsing, anti-afk
  orders/orderEngine.js    quote -> pending order -> match payment -> confirm -> payout
  wallet/                  BIP39/BIP32 hot wallet, tx signing (bitcoinjs-lib), esplora broadcast
  discord/                 client, slash commands, buttons/modals, embeds
```

## Wallet model — read this before funding anything

The bot **cannot** automate an Exodus wallet directly (no public sending API), so it uses a
**hot/cold split**:

- `LTC_HOT_WALLET_MNEMONIC` is a **brand new, dedicated** BIP39 seed (generate one with
  `node -e "console.log(require('bip39').generateMnemonic())"` after `npm install`, or via a
  wallet tool) — **do not reuse your personal Exodus seed here**.
- The bot derives one address from it (`m/84'/2'/0'/0/0`, native segwit `ltc1...`) and only ever
  holds a small **rolling operating balance** there — whatever `sats` are needed for near-term
  payouts.
- You top that address up manually (e.g. by sending LTC from Exodus to it) as the balance runs
  low. The Discord embed's "Wallet Balance" field shows the current hot wallet balance so you know
  when to refill.
- If the server is ever compromised, the blast radius is whatever's sitting in the hot wallet —
  never your main funds.

This is a genuine hot wallet: the private key material is derivable from the mnemonic in
`.env`. Treat that file (and wherever `.env` lives in production) like a password — restrict file
permissions, don't commit it, and don't log it.

## Before you can actually run this live, you'll need

1. **A Discord application** — bot token, client ID, and the guild ID you're deploying commands to.
2. **A DonutSMP API key** — run `/api` in-game on DonutSMP to get one.
3. **The bank Minecraft account** already set up for Mineflayer to log into (you said this is ready) — `MC_USERNAME` must match the account, `MC_AUTH=microsoft` will print a device-code login URL to the console on first run and cache the token after that.
4. **DonutSMP's exact chat wording**, confirmed live. `src/mineflayer/chatParser.js` currently guesses several common economy-plugin phrasings for pay confirmations and whispers (`PATTERNS` / `WHISPER_PATTERNS`) — once you see real messages in-game, add/adjust the regex there. Everything downstream just consumes the parsed `{ payerIgn, amount }` / `{ senderIgn, text }`, so this is a one-file change.
5. **The DonutSMP API's actual response shape** for a player lookup. `src/donutApi/client.js`'s `extractBalance()` tries several plausible field names (`result.money`, `data.balance`, etc.) and throws with the raw payload logged if none match — check the logs on first real order and add the correct field name there if needed.
6. **A funded hot wallet** (see above).

## Setup

```bash
npm install
cp .env.example .env    # fill in every value described above
npm run deploy-commands # registers /stats, /leaderboard, /shop post
npm start
```

Then in the target channel, run `/shop post` (requires Manage Server) to publish the embed with
the Sell Money / Settings buttons shown in the design — it self-refreshes every 5 minutes so the
rate/wallet balance stay current.

## What's been verified vs. what needs a live pass

Verified with automated smoke tests during development (amount parsing, chat/whisper regex
matching, LTC address derivation/validation, SQLite round-trips) — no live credentials were
available in this environment to test:

- Actual Discord gateway connection / slash command registration
- Actual DonutSMP server connection via Mineflayer, and the real chat/whisper wording
- The real DonutSMP API response shape
- A real signed LTC transaction broadcast

Budget time for a live test pass covering: linking + in-game verification whisper, a full sell
order end-to-end with a small real amount, an intentionally-wrong payment amount (should stay
unmatched, alert admins, not pay out), and an expired order.

## Operational notes

- **Fraud guards in place**: one open order per IGN at a time, exact-amount matching, per-user
  daily sell cap (`DAILY_SELL_CAP_MILLIONS`), max single-sell cap, order expiry, in-game payment
  confirmed against the DonutSMP API before any LTC moves, idempotent payout (an order can only
  reach `completed` once).
- **A failed payout after in-game money was received** (e.g. hot wallet underfunded) marks the
  order `failed`, DMs the user that it'll be resolved manually, and alerts
  `DISCORD_ADMIN_ALERT_CHANNEL_ID` if configured — check `orders.status = 'failed'` in the DB for
  anything needing a manual payout.
- **Regulatory/ToS considerations worth being aware of**, not blockers but worth knowing: running
  an automated real-money-for-game-currency exchange can touch money-transmission rules depending
  on your jurisdiction and volume, and DonutSMP's own rules on bot/automation accounts may apply
  to the Mineflayer bank account — worth a read before going live at any real scale.
