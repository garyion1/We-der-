import { ActionRowBuilder, ButtonBuilder, ButtonStyle } from 'discord.js';

export function shopButtons() {
  return new ActionRowBuilder().addComponents(
    new ButtonBuilder().setCustomId('sell_money_open').setLabel('Sell Money').setEmoji('💰').setStyle(ButtonStyle.Success),
    new ButtonBuilder().setCustomId('settings_open').setLabel('Settings').setEmoji('⚙️').setStyle(ButtonStyle.Secondary)
  );
}

export function visibilityButtons(amount) {
  return new ActionRowBuilder().addComponents(
    new ButtonBuilder().setCustomId(`sell_pick:public:${amount}`).setLabel('Public').setEmoji('🌐').setStyle(ButtonStyle.Primary),
    new ButtonBuilder().setCustomId(`sell_pick:anonymous:${amount}`).setLabel('Anonymous').setEmoji('🤵').setStyle(ButtonStyle.Secondary)
  );
}
