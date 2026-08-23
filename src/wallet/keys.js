import * as bip39 from 'bip39';
import { BIP32Factory } from 'bip32';
import * as ecc from 'tiny-secp256k1';
import { ECPairFactory } from 'ecpair';
import * as bitcoin from 'bitcoinjs-lib';
import { config } from '../config.js';
import { LITECOIN } from './network.js';

bitcoin.initEccLib(ecc);
const bip32 = BIP32Factory(ecc);
const ECPair = ECPairFactory(ecc);

// BIP84 (native segwit), Litecoin coin type 2, single account/chain — this
// is a purpose-built HOT WALLET (see .env.example), not your main savings,
// so we don't bother with address-gap-limit rotation: one derived address
// is used as the standing "top up this address" hot wallet address.
const DERIVATION_PATH = "m/84'/2'/0'/0/0";

let _root = null;
function root() {
  if (_root) return _root;
  if (!bip39.validateMnemonic(config.wallet.mnemonic)) {
    throw new Error('LTC_HOT_WALLET_MNEMONIC is not a valid BIP39 mnemonic');
  }
  const seed = bip39.mnemonicToSeedSync(config.wallet.mnemonic, config.wallet.passphrase || undefined);
  _root = bip32.fromSeed(seed, LITECOIN);
  return _root;
}

function hotWalletNode() {
  return root().derivePath(DERIVATION_PATH);
}

export function getHotWalletAddress() {
  const node = hotWalletNode();
  const { address } = bitcoin.payments.p2wpkh({ pubkey: node.publicKey, network: LITECOIN });
  return address;
}

export function getHotWalletSigner() {
  const node = hotWalletNode();
  return ECPair.fromPrivateKey(node.privateKey, { network: LITECOIN });
}

export function isValidLtcAddress(address) {
  try {
    bitcoin.address.toOutputScript(address, LITECOIN);
    return true;
  } catch {
    return false;
  }
}
