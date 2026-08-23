import 'dotenv/config';

function required(name) {
  const value = process.env[name];
  if (!value) throw new Error(`Missing required env var: ${name}`);
  return value;
}

function optional(name, fallback) {
  const value = process.env[name];
  return value === undefined || value === '' ? fallback : value;
}

export const config = {
  discord: {
    token: required('DISCORD_TOKEN'),
    clientId: required('DISCORD_CLIENT_ID'),
    guildId: required('DISCORD_GUILD_ID'),
    shopChannelId: required('DISCORD_SHOP_CHANNEL_ID'),
    adminAlertChannelId: optional('DISCORD_ADMIN_ALERT_CHANNEL_ID', null),
  },
  donutsmp: {
    apiKey: required('DONUTSMP_API_KEY'),
    apiBase: 'https://api.donutsmp.net/v1',
    bankIgn: required('DONUTSMP_BANK_IGN'),
  },
  minecraft: {
    host: optional('MC_HOST', 'donutsmp.net'),
    port: Number(optional('MC_PORT', 25565)),
    auth: optional('MC_AUTH', 'microsoft'),
    username: required('MC_USERNAME'),
    profilesFolder: optional('MC_PROFILES_FOLDER', './data/mc-auth-cache'),
  },
  pricing: {
    usdPerMillion: Number(optional('RATE_USD_PER_MILLION', 0.0185)),
    maxSellMillions: Number(optional('MAX_SELL_MILLIONS', 14180)),
    dailySellCapMillions: Number(optional('DAILY_SELL_CAP_MILLIONS', 2000)),
    orderTimeoutMinutes: Number(optional('ORDER_TIMEOUT_MINUTES', 10)),
  },
  wallet: {
    mnemonic: required('LTC_HOT_WALLET_MNEMONIC'),
    passphrase: optional('LTC_HOT_WALLET_PASSPHRASE', ''),
    esploraApi: optional('LTC_ESPLORA_API', 'https://litecoinspace.org/api'),
  },
  database: {
    path: optional('DATABASE_PATH', './data/autosell.sqlite'),
  },
  log: {
    level: optional('LOG_LEVEL', 'info'),
  },
};
