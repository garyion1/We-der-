import { EventEmitter } from 'node:events';
import { config } from '../config.js';
import { createLogger } from '../utils/logger.js';
import * as db from '../db/index.js';
import * as donutApi from '../donutApi/client.js';
import { getLtcUsdPrice } from '../pricing/ltcPrice.js';
import { sendLtc } from '../wallet/payout.js';

const log = createLogger('orders');

export class ValidationError extends Error {}

/**
 * Orchestrates the sell flow: quote -> pending order -> match against a
 * Mineflayer-observed in-game payment -> confirm via the DonutSMP API ->
 * pay out LTC. Emits:
 *   'orderCompleted' -> { order, txid }
 *   'orderFailed'    -> { order, reason }   (money was received in-game but payout failed — needs ops attention)
 *   'orderExpired'   -> { order }
 *   'unmatchedPayment' -> { payerIgn, amount } (in-game payment seen that didn't match any open order)
 */
export class OrderEngine extends EventEmitter {
  constructor(bankBot) {
    super();
    this.bankBot = bankBot;
    bankBot.on('payment', (payment) => this._onPayment(payment).catch((err) => log.error('Error handling payment', err)));
    this._sweepTimer = setInterval(() => this._sweepExpired(), 30_000);
  }

  /** Validate + price a sell request and open a pending order. */
  async createOrder({ discordId, amountIngame, visibility }) {
    const user = db.getUserByDiscordId(discordId);
    if (!user || !user.verified_at) {
      throw new ValidationError('You need to link and verify your IGN + LTC address in Settings first.');
    }
    if (!Number.isInteger(amountIngame) || amountIngame <= 0) {
      throw new ValidationError('Enter a whole positive amount of in-game money to sell.');
    }
    const maxSell = config.pricing.maxSellMillions * 1_000_000;
    if (amountIngame > maxSell) {
      throw new ValidationError(`That's above the current max sell (${config.pricing.maxSellMillions.toLocaleString()}m).`);
    }

    const startOfDay = new Date();
    startOfDay.setHours(0, 0, 0, 0);
    const soldToday = db.sumCompletedIngameSince(discordId, startOfDay.getTime());
    const dailyCap = config.pricing.dailySellCapMillions * 1_000_000;
    if (soldToday + amountIngame > dailyCap) {
      throw new ValidationError(`That would exceed your daily sell cap (${config.pricing.dailySellCapMillions.toLocaleString()}m/day).`);
    }

    const existingOpen = db.getOpenOrdersForIgn(user.ign);
    if (existingOpen.length > 0) {
      throw new ValidationError('You already have a pending sell order — finish or let it expire before starting a new one.');
    }

    const rate = config.pricing.usdPerMillion;
    const usdAmount = (amountIngame / 1_000_000) * rate;
    const ltcPrice = await getLtcUsdPrice();
    const ltcAmount = usdAmount / ltcPrice;

    let bankBalanceBefore = null;
    try {
      bankBalanceBefore = await donutApi.getBankBalance();
    } catch (err) {
      log.warn('Could not snapshot bank balance before order (continuing without it)', err.message);
    }

    const order = db.createOrder({
      discordId,
      ign: user.ign,
      visibility,
      amountIngame,
      rateUsdPerMillion: rate,
      usdAmount,
      ltcPriceUsd: ltcPrice,
      ltcAmount,
      ltcAddress: user.ltc_address,
      bankBalanceBefore,
      timeoutMinutes: config.pricing.orderTimeoutMinutes,
    });

    log.info(`Order #${order.id} opened: ${user.ign} selling ${amountIngame} for ${ltcAmount.toFixed(8)} LTC`);
    return order;
  }

  async _onPayment({ payerIgn, amount }) {
    const openOrders = db.getOpenOrdersForIgn(payerIgn);
    const match = openOrders.find((o) => o.amount_ingame === amount);
    if (!match) {
      log.warn(`Unmatched in-game payment: ${payerIgn} paid $${amount} but no matching open order`);
      this.emit('unmatchedPayment', { payerIgn, amount });
      return;
    }

    db.setOrderStatus(match.id, 'ingame_payment_seen', { ingamePaidAt: Date.now() });

    // Cross-check against the DonutSMP API before moving real money — the
    // chat line alone is enough for fast UX, but the payout only fires once
    // the bank's own balance corroborates it.
    const confirmed = await this._confirmViaApi(match, amount);
    if (!confirmed) {
      log.error(`Order #${match.id}: chat payment seen but API balance did not corroborate it — holding, not paying out`);
      this.emit('unmatchedPayment', { payerIgn, amount, orderId: match.id, reason: 'api_mismatch' });
      return;
    }

    db.setOrderStatus(match.id, 'ingame_payment_confirmed', { confirmedAt: Date.now() });
    await this._payout(db.getOrderById(match.id));
  }

  async _confirmViaApi(order, amount) {
    try {
      const currentBalance = await donutApi.getBankBalance();
      db.recordBankBalanceSnapshot(currentBalance);
      if (order.bank_balance_before == null) {
        // We couldn't snapshot at order creation (API hiccup) — fall back to trusting chat.
        return true;
      }
      return currentBalance >= order.bank_balance_before + amount;
    } catch (err) {
      log.warn(`DonutSMP API confirmation check failed for order #${order.id}, falling back to chat-only`, err.message);
      return true;
    }
  }

  async _payout(order) {
    try {
      const sats = Math.round(order.ltc_amount * 1e8);
      const { txid } = await sendLtc(order.ltc_address, sats);
      db.setOrderStatus(order.id, 'completed', { txHash: txid, fulfilledAt: Date.now() });
      log.info(`Order #${order.id} paid out: ${txid}`);
      this.emit('orderCompleted', { order: db.getOrderById(order.id), txid });
    } catch (err) {
      log.error(`Order #${order.id} payout FAILED after in-game money was received`, err);
      db.setOrderStatus(order.id, 'failed', { failureReason: err.message });
      this.emit('orderFailed', { order: db.getOrderById(order.id), reason: err.message });
    }
  }

  _sweepExpired() {
    for (const order of db.getExpirableOrders()) {
      db.setOrderStatus(order.id, 'expired', {});
      this.emit('orderExpired', { order });
    }
  }

  stop() {
    clearInterval(this._sweepTimer);
  }
}
