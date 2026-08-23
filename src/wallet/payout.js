import * as bitcoin from 'bitcoinjs-lib';
import { LITECOIN } from './network.js';
import { getHotWalletAddress, getHotWalletSigner, isValidLtcAddress } from './keys.js';
import { getUtxos, getFeeRateSatsPerVb, broadcastTx, getAddressBalanceSats } from './esplora.js';
import { createLogger } from '../utils/logger.js';

const log = createLogger('wallet');

const DUST_SATS = 546;
// Rough P2WPKH-in / P2WPKH-out vbyte weights, good enough for fee estimation.
const VBYTES_PER_INPUT = 68;
const VBYTES_PER_OUTPUT = 31;
const VBYTES_OVERHEAD = 10;

export { isValidLtcAddress };

export async function getHotWalletBalanceSats() {
  return getAddressBalanceSats(getHotWalletAddress());
}

function selectUtxos(utxos, targetSats, feeRateSatsPerVb) {
  const sorted = [...utxos].sort((a, b) => b.value - a.value);
  const selected = [];
  let total = 0;
  for (const utxo of sorted) {
    selected.push(utxo);
    total += utxo.value;
    const estVBytes = VBYTES_OVERHEAD + selected.length * VBYTES_PER_INPUT + 2 * VBYTES_PER_OUTPUT;
    const estFee = estVBytes * feeRateSatsPerVb;
    if (total >= targetSats + estFee) {
      return { selected, total, fee: estFee };
    }
  }
  return null;
}

/**
 * Sends `amountSats` of LTC from the hot wallet to `toAddress`.
 * Throws on insufficient hot-wallet balance or invalid address — callers
 * (the order engine) should catch this, mark the order failed, and alert
 * an operator rather than silently retrying a money-moving operation.
 */
export async function sendLtc(toAddress, amountSats) {
  if (!isValidLtcAddress(toAddress)) {
    throw new Error(`Refusing to send: invalid LTC address ${toAddress}`);
  }
  if (!Number.isInteger(amountSats) || amountSats <= DUST_SATS) {
    throw new Error(`Refusing to send: amount ${amountSats} sats is invalid or below dust limit`);
  }

  const hotAddress = getHotWalletAddress();
  const signer = getHotWalletSigner();
  const [utxos, feeRate] = await Promise.all([getUtxos(hotAddress), getFeeRateSatsPerVb()]);

  const picked = selectUtxos(utxos, amountSats, feeRate);
  if (!picked) {
    throw new Error(
      `Insufficient hot wallet balance: need ~${amountSats} sats + fee, have ${utxos.reduce((s, u) => s + u.value, 0)} sats across ${utxos.length} UTXOs. Top up ${hotAddress}.`
    );
  }

  const { p2wpkh } = bitcoin.payments;
  const hotScript = p2wpkh({ pubkey: signer.publicKey, network: LITECOIN }).output;

  const psbt = new bitcoin.Psbt({ network: LITECOIN });
  for (const utxo of picked.selected) {
    psbt.addInput({
      hash: utxo.txid,
      index: utxo.vout,
      witnessUtxo: { script: hotScript, value: utxo.value },
    });
  }

  psbt.addOutput({ address: toAddress, value: amountSats });

  const change = picked.total - amountSats - picked.fee;
  if (change > DUST_SATS) {
    psbt.addOutput({ address: hotAddress, value: change });
  }

  psbt.signAllInputs(signer);
  psbt.finalizeAllInputs();

  const txHex = psbt.extractTransaction().toHex();
  const txid = await broadcastTx(txHex);
  log.info(`Sent ${amountSats} sats to ${toAddress} — txid ${txid}`);
  return { txid, feeSats: picked.fee };
}

export { getHotWalletAddress };
