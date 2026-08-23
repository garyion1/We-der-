import { ModalBuilder, TextInputBuilder, TextInputStyle, ActionRowBuilder } from 'discord.js';

export function settingsModal() {
  const modal = new ModalBuilder().setCustomId('settings_modal').setTitle('Link your IGN + LTC address');

  const ign = new TextInputBuilder()
    .setCustomId('ign')
    .setLabel('DonutSMP in-game name')
    .setStyle(TextInputStyle.Short)
    .setMinLength(3)
    .setMaxLength(16)
    .setRequired(true);

  const address = new TextInputBuilder()
    .setCustomId('ltc_address')
    .setLabel('Your Litecoin (LTC) address')
    .setStyle(TextInputStyle.Short)
    .setMinLength(20)
    .setMaxLength(64)
    .setRequired(true);

  modal.addComponents(new ActionRowBuilder().addComponents(ign), new ActionRowBuilder().addComponents(address));
  return modal;
}

export function sellAmountModal() {
  const modal = new ModalBuilder().setCustomId('sell_amount_modal').setTitle('Sell in-game money');

  const amount = new TextInputBuilder()
    .setCustomId('amount')
    .setLabel('How much to sell? (e.g. 5m, 1.2b, 500000)')
    .setStyle(TextInputStyle.Short)
    .setMaxLength(20)
    .setRequired(true);

  modal.addComponents(new ActionRowBuilder().addComponents(amount));
  return modal;
}
