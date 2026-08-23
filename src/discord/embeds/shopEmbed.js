import { EmbedBuilder } from 'discord.js';
import { config } from '../../config.js';
import { formatIngameAmount } from '../../utils/amount.js';
import { getHotWalletBalanceSats } from '../../wallet/payout.js';
import { getLtcUsdPrice } from '../../pricing/ltcPrice.js';

export async function buildShopEmbed() {
  const rate = config.pricing.usdPerMillion;
  let walletLine = 'unavailable';
  try {
    const [sats, ltcPrice] = await Promise.all([getHotWalletBalanceSats(), getLtcUsdPrice()]);
    const ltc = sats / 1e8;
    const usd = ltc * ltcPrice;
    walletLine = `$${usd.toFixed(2)}  ·  ${ltc.toFixed(4)} LTC`;
  } catch {
    // leave as unavailable — shown rather than throwing, so the embed still posts
  }

  return new EmbedBuilder()
    .setColor(0xf7931a)
    .setTitle('🪙 DonutSMP Autobuy — Sell for Litecoin')
    .setDescription(
      'Sell your in-game money and get paid instantly in Litecoin.\n\n' +
        '**How it works**\n' +
        '1. ⚙️ **Settings** — link your IGN + LTC address *(one-time)*\n' +
        '2. 💰 **Sell Money** — enter how much\n' +
        '3. Pick 🌐 **Public** or 🤵 **Anonymous** for the sale\n' +
        '4. 💸 Get **paid in Litecoin**, straight to your wallet'
    )
    .addFields(
      { name: '💱 Current Rate', value: `$${rate}/M   ($${(rate * 1000).toFixed(2)}/B)` },
      { name: '🏦 Wallet Balance', value: walletLine },
      { name: '💰 Max Money Sell', value: formatIngameAmount(config.pricing.maxSellMillions * 1_000_000) }
    )
    .setFooter({ text: 'Instant LTC payouts • secure one-time linking • /stats • /leaderboard' })
    .setTimestamp();
}
