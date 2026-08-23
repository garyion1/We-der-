import { REST, Routes } from 'discord.js';
import { config } from '../config.js';
import { commands } from './commands.js';
import { createLogger } from '../utils/logger.js';

const log = createLogger('deploy-commands');

const rest = new REST().setToken(config.discord.token);

try {
  log.info(`Registering ${commands.length} guild command(s)...`);
  await rest.put(Routes.applicationGuildCommands(config.discord.clientId, config.discord.guildId), { body: commands });
  log.info('Done.');
} catch (err) {
  log.error('Failed to register commands', err);
  process.exit(1);
}
