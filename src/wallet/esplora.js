import fetch from 'node-fetch';
import { config } from '../config.js';

const BASE = config.wallet.esploraApi.replace(/\/$/, '');

async function get(pathname) {
  const res = await fetch(`${BASE}${pathname}`);
  if (!res.ok) throw new Error(`Esplora GET ${pathname} -> ${res.status}`);
  return res.json();
}

/** UTXOs for an address, in satoshis. */
export async function getUtxos(address) {
  return get(`/address/${address}/utxo`);
}

/** Confirmed + unconfirmed balance for an address, in satoshis. */
export async function getAddressBalanceSats(address) {
  const info = await get(`/address/${address}`);
  const funded = info.chain_stats.funded_txo_sum + info.mempool_stats.funded_txo_sum;
  const spent = info.chain_stats.spent_txo_sum + info.mempool_stats.spent_txo_sum;
  return funded - spent;
}

/** Raw previous-tx hex, needed by bitcoinjs-lib to build non-witness-utxo fallback data. */
export async function getTxHex(txid) {
  const res = await fetch(`${BASE}/tx/${txid}/hex`);
  if (!res.ok) throw new Error(`Esplora GET /tx/${txid}/hex -> ${res.status}`);
  return res.text();
}

/** sats/vByte fee estimate targeting a ~6 block confirmation. */
export async function getFeeRateSatsPerVb() {
  const estimates = await get('/fee-estimates');
  return Math.ceil(estimates['6'] ?? estimates['3'] ?? estimates['2'] ?? 10);
}

/** Broadcast a raw signed transaction (hex). Returns the txid. */
export async function broadcastTx(txHex) {
  const res = await fetch(`${BASE}/tx`, {
    method: 'POST',
    headers: { 'Content-Type': 'text/plain' },
    body: txHex,
  });
  const body = await res.text();
  if (!res.ok) throw new Error(`Broadcast failed (${res.status}): ${body}`);
  return body.trim();
}
