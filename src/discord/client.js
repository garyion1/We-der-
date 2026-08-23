import { Client, GatewayIntentBits } from 'discord.js';
import { config } from '../config.js';

export function createDiscordClient() {
  return new Client({ intents: [GatewayIntentBits.Guilds] });
}

export async function loginDiscord(client) {
  await client.login(config.discord.token);
}
