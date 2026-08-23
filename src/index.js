import { config } from './config.js';
import { createLogger } from './utils/logger.js';
import * as db from './db/index.js';
import { BankBot } from './mineflayer/bot.js';
import { OrderEngine } from './orders/orderEngine.js';
import { createDiscordClient, loginDiscord } from './discord/client.js';
import { registerInteractionHandlers } from './discord/interactionHandler.js';
import { buildShopEmbed } from './discord/embeds/shopEmbed.js';
import { formatIngameAmount } from './utils/amount.js';

const log = createLogger('main');

const SHOP_REFRESH_INTERVAL_MS = 5 * 60_000;

async function alertAdmins(client, content) {
  if (!config.discord.adminAlertChannelId) {
    log.warn(`[admin alert, no channel configured] ${content}`);
    return;
  }
  try {
    const channel = await client.channels.fetch(config.discord.adminAlertChannelId);
    await channel.send({ content: `⚠️ ${content}` });
  } catch (err) {
    log.error('Failed to send admin alert', err);
  }
}

async function dmUser(client, discordId, content) {
  try {
    const user = await client.users.fetch(discordId);
    await user.send(content);
  } catch (err) {
    log.warn(`Could not DM user ${discordId}`, err.message);
  }
}

async function main() {
  const bankBot = new BankBot();
  const orderEngine = new OrderEngine(bankBot);
  const client = createDiscordClient();

  registerInteractionHandlers(client, { orderEngine, bankBot });

  // IGN + LTC address ownership verification: user whispers a one-time
  // code to the bank account in-game, mirrored to Discord.
  bankBot.on('whisper', ({ senderIgn, text }) => {
    const user = db.getUserByIgn(senderIgn);
    if (!user || user.verified_at || !user.verify_code) return;
    if (text.trim().toUpperCase() !== user.verify_code) return;

    db.markUserVerified(user.discord_id);
    bankBot.whisper(senderIgn, 'Verified! You can now use Sell Money on Discord.');
    dmUser(client, user.discord_id, `✅ **${user.ign}** is verified — you can now use **Sell Money** in the shop.`);
    log.info(`Verified ${user.ign} (${user.discord_id})`);
  });

  orderEngine.on('orderCompleted', async ({ order, txid }) => {
    await dmUser(
      client,
      order.discord_id,
      `💸 Payment sent! **${order.ltc_amount.toFixed(8)} LTC** for order #${order.id} is on its way to your wallet.\n` +
        `Tx: \`${txid}\``
    );
    if (order.visibility === 'public' && config.discord.shopChannelId) {
      try {
        const channel = await client.channels.fetch(config.discord.shopChannelId);
        await channel.send(
          `💸 **${order.ign}** just sold ${formatIngameAmount(order.amount_ingame)} for ${order.ltc_amount.toFixed(4)} LTC!`
        );
      } catch (err) {
        log.warn('Could not post public sale announcement', err.message);
      }
    }
  });

  orderEngine.on('orderFailed', async ({ order, reason }) => {
    await dmUser(
      client,
      order.discord_id,
      `⚠️ Your in-game payment for order #${order.id} was received, but the LTC payout failed. ` +
        `Our team has been notified and will resolve this manually.`
    );
    await alertAdmins(
      client,
      `Order #${order.id} (${order.ign}) — in-game payment received but payout FAILED: ${reason}. Needs manual payout.`
    );
  });

  orderEngine.on('orderExpired', async ({ order }) => {
    await dmUser(client, order.discord_id, `⏱️ Order #${order.id} expired before payment was received — no worries, just start a new one.`);
  });

  orderEngine.on('unmatchedPayment', async ({ payerIgn, amount, reason }) => {
    await alertAdmins(
      client,
      `Unmatched in-game payment: **${payerIgn}** paid $${amount.toLocaleString()} but it didn't match an open order` +
        (reason ? ` (${reason})` : '') +
        `. Check the bank account balance/logs.`
    );
  });

  client.once('ready', async () => {
    log.info(`Discord logged in as ${client.user.tag}`);
    setInterval(() => refreshShopEmbed(client).catch((err) => log.warn('Shop embed refresh failed', err.message)), SHOP_REFRESH_INTERVAL_MS);
  });

  await loginDiscord(client);

  const shutdown = async () => {
    log.info('Shutting down...');
    orderEngine.stop();
    client.destroy();
    db.db.close();
    process.exit(0);
  };
  process.on('SIGINT', shutdown);
  process.on('SIGTERM', shutdown);
}

async function refreshShopEmbed(client) {
  const channelId = db.getState('shop_channel_id');
  const messageId = db.getState('shop_message_id');
  if (!channelId || !messageId) return;
  const channel = await client.channels.fetch(channelId);
  const message = await channel.messages.fetch(messageId);
  const embed = await buildShopEmbed();
  await message.edit({ embeds: [embed] });
}

main().catch((err) => {
  log.error('Fatal startup error', err);
  process.exit(1);
});
