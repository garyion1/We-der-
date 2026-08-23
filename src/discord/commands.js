import { SlashCommandBuilder, PermissionFlagsBits } from 'discord.js';

export const commands = [
  new SlashCommandBuilder().setName('stats').setDescription('Show overall autosell stats').toJSON(),
  new SlashCommandBuilder().setName('leaderboard').setDescription('Show the top sellers').toJSON(),
  new SlashCommandBuilder()
    .setName('shop')
    .setDescription('Admin: manage the autosell shop embed')
    .setDefaultMemberPermissions(PermissionFlagsBits.ManageGuild)
    .addSubcommand((sub) => sub.setName('post').setDescription('Post (or repost) the shop embed in this channel'))
    .toJSON(),
];
