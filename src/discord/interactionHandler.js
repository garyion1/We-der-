import crypto from 'node:crypto';
import { EmbedBuilder } from 'discord.js';
import { config } from '../config.js';
import * as db from '../db/index.js';
import { createLogger } from '../utils/logger.js';
import { isValidLtcAddress } from '../wallet/keys.js';
import { parseIngameAmount, formatIngameAmount } from '../utils/amount.js';
import { settingsModal, sellAmountModal } from './modals.js';
import { visibilityButtons, shopButtons } from './components.js';
import { buildShopEmbed } from './embeds/shopEmbed.js';
import { ValidationError } from '../orders/orderEngine.js';

const log = createLogger('discord-interactions');

function genVerifyCode() {
  return crypto.randomBytes(4).toString('hex').toUpperCase();
}

export function registerInteractionHandlers(client, { orderEngine, bankBot }) {
  client.on('interactionCreate', async (interaction) => {
    try {
      if (interaction.isButton()) {
        await handleButton(interaction, { orderEngine, bankBot });
      } else if (interaction.isModalSubmit()) {
        await handleModal(interaction, { bankBot });
      } else if (interaction.isChatInputCommand()) {
        await handleCommand(interaction);
      }
    } catch (err) {
      log.error('Interaction handling error', err);
      const payload = { content: 'Something went wrong handling that — please try again.', ephemeral: true };
      if (interaction.deferred || interaction.replied) {
        await interaction.followUp(payload).catch(() => {});
      } else {
        await interaction.reply(payload).catch(() => {});
      }
    }
  });
}

async function handleButton(interaction, { orderEngine, bankBot }) {
  if (interaction.customId === 'settings_open') {
    return interaction.showModal(settingsModal());
  }

  if (interaction.customId === 'sell_money_open') {
    const user = db.getUserByDiscordId(interaction.user.id);
    if (!user || !user.verified_at) {
      return interaction.reply({
        content: 'Link and verify your IGN + LTC address in **Settings** first.',
        ephemeral: true,
      });
    }
    return interaction.showModal(sellAmountModal());
  }

  if (interaction.customId.startsWith('sell_pick:')) {
    const [, visibility, amountStr] = interaction.customId.split(':');
    const amountIngame = Number(amountStr);
    await interaction.deferReply({ ephemeral: true });
    try {
      const order = await orderEngine.createOrder({ discordId: interaction.user.id, amountIngame, visibility });
      const embed = new EmbedBuilder()
        .setColor(0x2ecc71)
        .setTitle('💰 Sell order opened')
        .setDescription(
          `Pay **exactly $${order.amount_ingame.toLocaleString()}** (${formatIngameAmount(order.amount_ingame)}) ` +
            `in-game to **${config.donutsmp.bankIgn}** within **${config.pricing.orderTimeoutMinutes} minutes**.\n\n` +
            `You'll receive **${order.ltc_amount.toFixed(8)} LTC** (~$${order.usd_amount.toFixed(2)}) once the payment is confirmed.`
        )
        .setFooter({ text: `Order #${order.id} • ${visibility === 'public' ? 'Public' : 'Anonymous'} sale` });
      await interaction.editReply({ embeds: [embed] });
    } catch (err) {
      if (err instanceof ValidationError) {
        await interaction.editReply({ content: err.message });
      } else {
        throw err;
      }
    }
  }
}

async function handleModal(interaction, { bankBot }) {
  if (interaction.customId === 'settings_modal') {
    const ign = interaction.fields.getTextInputValue('ign').trim();
    const ltcAddress = interaction.fields.getTextInputValue('ltc_address').trim();

    if (!/^[A-Za-z0-9_]{3,16}$/.test(ign)) {
      return interaction.reply({ content: 'That doesn’t look like a valid Minecraft username.', ephemeral: true });
    }
    if (!isValidLtcAddress(ltcAddress)) {
      return interaction.reply({ content: 'That doesn’t look like a valid Litecoin address.', ephemeral: true });
    }

    const existing = db.getUserByIgn(ign);
    if (existing && existing.discord_id !== interaction.user.id) {
      return interaction.reply({ content: 'That IGN is already linked to a different Discord account.', ephemeral: true });
    }

    const code = genVerifyCode();
    db.upsertUser({ discordId: interaction.user.id, ign, ltcAddress, verifyCode: code, verifiedAt: null });

    return interaction.reply({
      content:
        `Almost done! In-game on DonutSMP, whisper **${config.donutsmp.bankIgn}** this one-time code to prove you own **${ign}**:\n\n` +
        `\`${code}\`\n\n` +
        `(e.g. \`/msg ${config.donutsmp.bankIgn} ${code}\`) — you'll get a reply in-game once it's verified.`,
      ephemeral: true,
    });
  }

  if (interaction.customId === 'sell_amount_modal') {
    const raw = interaction.fields.getTextInputValue('amount');
    const amount = parseIngameAmount(raw);
    if (!amount) {
      return interaction.reply({ content: `Couldn't parse "${raw}" — try something like \`5m\` or \`1200000\`.`, ephemeral: true });
    }
    return interaction.reply({
      content: `Selling **${formatIngameAmount(amount)}** ($${amount.toLocaleString()}) — pick how the sale should be listed:`,
      components: [visibilityButtons(amount)],
      ephemeral: true,
    });
  }
}

async function handleCommand(interaction) {
  if (interaction.commandName === 'stats') {
    const totals = db.getStatsTotals();
    const embed = new EmbedBuilder()
      .setColor(0x3498db)
      .setTitle('📊 DonutSMP Autosell Stats')
      .addFields(
        { name: 'Completed orders', value: String(totals.orders), inline: true },
        { name: 'Total money sold', value: formatIngameAmount(totals.total_ingame), inline: true },
        { name: 'Total LTC paid out', value: `${totals.total_ltc.toFixed(4)} LTC`, inline: true }
      );
    return interaction.reply({ embeds: [embed] });
  }

  if (interaction.commandName === 'leaderboard') {
    const rows = db.getLeaderboard(10);
    const description = rows.length
      ? rows
          .map((r, i) => `**${i + 1}.** ${r.ign} — ${formatIngameAmount(r.total_ingame)} (${r.total_ltc.toFixed(4)} LTC, ${r.orders} orders)`)
          .join('\n')
      : 'No completed sales yet.';
    const embed = new EmbedBuilder().setColor(0xf1c40f).setTitle('🏆 Top Sellers').setDescription(description);
    return interaction.reply({ embeds: [embed] });
  }

  if (interaction.commandName === 'shop' && interaction.options.getSubcommand() === 'post') {
    await interaction.deferReply({ ephemeral: true });
    const embed = await buildShopEmbed();
    const message = await interaction.channel.send({ embeds: [embed], components: [shopButtons()] });
    db.setState('shop_channel_id', interaction.channel.id);
    db.setState('shop_message_id', message.id);
    await interaction.editReply({ content: 'Shop embed posted.' });
  }
}
