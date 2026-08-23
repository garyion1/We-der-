import mineflayer from 'mineflayer';
import { EventEmitter } from 'node:events';
import { config } from '../config.js';
import { createLogger } from '../utils/logger.js';
import { parsePaymentMessage, parseWhisperMessage } from './chatParser.js';

const log = createLogger('mineflayer');

const RECONNECT_DELAY_MS = 10_000;
const ANTI_AFK_INTERVAL_MS = 45_000;

/**
 * Wraps a mineflayer bot for the DonutSMP "bank" account. Emits:
 *   'payment' -> { payerIgn, amount, raw }   when a pay-confirmation chat line is parsed
 *   'online'  -> ()                          when (re)connected and spawned
 *   'offline' -> (reason)                    when disconnected
 */
export class BankBot extends EventEmitter {
  constructor() {
    super();
    this.bot = null;
    this.antiAfkTimer = null;
    this._connect();
  }

  _connect() {
    log.info(`Connecting to ${config.minecraft.host}:${config.minecraft.port} as ${config.minecraft.username}`);
    this.bot = mineflayer.createBot({
      host: config.minecraft.host,
      port: config.minecraft.port,
      username: config.minecraft.username,
      auth: config.minecraft.auth,
      profilesFolder: config.minecraft.profilesFolder,
      version: false,
    });

    this.bot.on('spawn', () => {
      log.info('Bank bot spawned in world');
      this._startAntiAfk();
      this.emit('online');
    });

    this.bot.on('message', (jsonMsg) => {
      const text = jsonMsg.toString();
      const parsed = parsePaymentMessage(text);
      if (parsed) {
        log.info(`Detected payment: ${parsed.payerIgn} -> $${parsed.amount}`);
        this.emit('payment', { ...parsed, raw: text });
        return;
      }
      const whisper = parseWhisperMessage(text);
      if (whisper) {
        this.emit('whisper', { ...whisper, raw: text });
      }
    });

    this.bot.on('kicked', (reason) => {
      log.warn('Kicked from server', reason);
      this._scheduleReconnect('kicked');
    });

    this.bot.on('error', (err) => {
      log.error('Mineflayer error', err);
    });

    this.bot.on('end', (reason) => {
      log.warn(`Disconnected: ${reason}`);
      this._stopAntiAfk();
      this.emit('offline', reason);
      this._scheduleReconnect('end');
    });
  }

  _scheduleReconnect() {
    if (this._reconnecting) return;
    this._reconnecting = true;
    setTimeout(() => {
      this._reconnecting = false;
      this._connect();
    }, RECONNECT_DELAY_MS);
  }

  _startAntiAfk() {
    this._stopAntiAfk();
    this.antiAfkTimer = setInterval(() => {
      if (!this.bot?.entity) return;
      const yaw = Math.random() * Math.PI * 2;
      this.bot.look(yaw, 0, true).catch(() => {});
    }, ANTI_AFK_INTERVAL_MS);
  }

  _stopAntiAfk() {
    if (this.antiAfkTimer) clearInterval(this.antiAfkTimer);
    this.antiAfkTimer = null;
  }

  isOnline() {
    return Boolean(this.bot?.entity);
  }

  whisper(ign, message) {
    if (!this.bot?.entity) return;
    this.bot.chat(`/msg ${ign} ${message}`);
  }
}
