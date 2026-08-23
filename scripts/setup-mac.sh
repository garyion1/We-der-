#!/usr/bin/env bash
# One-shot setup for running the DonutSMP autosell bot on a Mac.
# Run this from the repo root: ./scripts/setup-mac.sh
set -euo pipefail
cd "$(dirname "$0")/.."

echo "== DonutSMP Autosell setup =="

if ! xcode-select -p >/dev/null 2>&1; then
  echo "Xcode Command Line Tools are required (needed to build better-sqlite3)."
  echo "Running: xcode-select --install"
  xcode-select --install || true
  echo "Finish that install in the popup, then re-run this script."
  exit 1
fi

if ! command -v node >/dev/null 2>&1; then
  echo "Node.js not found. Install it first, e.g.: brew install node"
  exit 1
fi
echo "Node: $(node -v)"

echo "Installing dependencies..."
npm install

if [ ! -f .env ]; then
  cp .env.example .env
  echo "Created .env from .env.example."
fi

# shellcheck disable=SC1091
set -a; source .env; set +a

prompt_if_empty() {
  local var_name="$1" prompt_text="$2" secret="${3:-}"
  local current="${!var_name:-}"
  if [ -n "$current" ]; then
    echo "$var_name already set, skipping."
    return
  fi
  local value
  if [ "$secret" = "secret" ]; then
    read -r -s -p "$prompt_text: " value; echo
  else
    read -r -p "$prompt_text: " value
  fi
  if grep -q "^${var_name}=" .env; then
    # escape & and | for sed's replacement side
    local escaped
    escaped=$(printf '%s' "$value" | sed -e 's/[&|]/\\&/g')
    sed -i '' "s|^${var_name}=.*|${var_name}=${escaped}|" .env
  else
    printf '%s=%s\n' "$var_name" "$value" >> .env
  fi
  export "$var_name=$value"
}

echo
echo "-- Discord --"
prompt_if_empty DISCORD_TOKEN "Bot token (Developer Portal > Bot)" secret
prompt_if_empty DISCORD_CLIENT_ID "Application/Client ID"
prompt_if_empty DISCORD_GUILD_ID "Server (guild) ID"
prompt_if_empty DISCORD_SHOP_CHANNEL_ID "Shop channel ID"

echo
echo "-- DonutSMP --"
prompt_if_empty DONUTSMP_API_KEY "API key (run /api in-game to get one)" secret
prompt_if_empty DONUTSMP_BANK_IGN "Bank account's in-game username"

echo
echo "-- Minecraft --"
prompt_if_empty MC_USERNAME "Bank account's Minecraft username"

echo
echo "-- LTC hot wallet --"
if [ -z "${LTC_HOT_WALLET_MNEMONIC:-}" ]; then
  echo "Generating a fresh hot wallet seed phrase..."
  MNEMONIC=$(node -e "console.log(require('bip39').generateMnemonic())")
  if grep -q '^LTC_HOT_WALLET_MNEMONIC=' .env; then
    sed -i '' "s|^LTC_HOT_WALLET_MNEMONIC=.*|LTC_HOT_WALLET_MNEMONIC=${MNEMONIC}|" .env
  else
    printf 'LTC_HOT_WALLET_MNEMONIC=%s\n' "$MNEMONIC" >> .env
  fi
  echo
  echo "  >>> WRITE THIS DOWN AND STORE IT SAFELY (never share it) <<<"
  echo "  $MNEMONIC"
  echo
  read -r -p "Press Enter once you've saved it somewhere safe... "
else
  echo "LTC_HOT_WALLET_MNEMONIC already set, skipping."
fi

echo
echo "Registering Discord slash commands..."
npm run deploy-commands

echo
echo "Setup done. Next:"
echo "  1. Run 'npm start' — first run prints a Microsoft device-code login URL"
echo "     for the Minecraft bank account. Open it and enter the code."
echo "  2. Fund the hot wallet address it logs on startup."
echo "  3. In Discord, run /shop post in your shop channel."
echo
read -r -p "Start it now with 'npm start'? [y/N] " start_now
if [[ "$start_now" =~ ^[Yy]$ ]]; then
  npm start
else
  echo "Run 'npm start' whenever you're ready (or set up pm2 — see README)."
fi
