#!/usr/bin/env node
// Phase 5, spike A2: read a Classic Play Integrity token LOCALLY with the app's own self-managed
// response keys (the customer's-own path, phase-5-attestation.md 2.3). No Google call.
//
//   PI_KEYS_ENC=~/Downloads/api_keys.enc PI_PRIVATE_KEY=…/play-integrity-private.pem node decrypt-integrity.mjs report.txt
//   PI_DECRYPTION_KEY_FILE=… PI_VERIFICATION_KEY_FILE=… node decrypt-integrity.mjs report.txt
//   node decrypt-integrity.mjs --self-test
//
// The first form is the dashboard's flow: Play Console encrypts the app's keys to our RSA-2048
// public key (RSA-OAEP, SHA-1) as api_keys.enc, holding "DECRYPTION_KEY=<b64>" and
// "VERIFICATION_KEY=<b64>" (AES key; DER public EC key). The token is a JWE (A256KW + A256GCM)
// whose payload is a JWS (ES256). Nothing from the key files is printed. No dependencies.

import { readFileSync } from 'node:fs';
import crypto from 'node:crypto';

const b64u = s => Buffer.from(s, 'base64url');

/** Decrypts and verifies; returns the verdict JSON. Throws with a short reason. */
export function readToken(token, decryptionKey, verificationKeyDer) {
  const [h, ek, iv, ct, tag] = token.split('.');
  if (tag === undefined) throw new Error('not a compact JWE (5 parts)');
  const header = JSON.parse(b64u(h).toString());
  if (header.alg !== 'A256KW' || header.enc !== 'A256GCM') throw new Error(`unexpected JWE header ${JSON.stringify(header)}`);
  // A256KW (RFC 3394) unwraps the content key with the first 32 bytes of the decryption key.
  const kek = decryptionKey.subarray(0, 32);
  const unwrap = crypto.createDecipheriv('id-aes256-wrap', kek, Buffer.from('A6A6A6A6A6A6A6A6', 'hex'));
  const cek = Buffer.concat([unwrap.update(b64u(ek)), unwrap.final()]);
  const gcm = crypto.createDecipheriv('aes-256-gcm', cek, b64u(iv));
  gcm.setAAD(Buffer.from(h, 'ascii'));
  gcm.setAuthTag(b64u(tag));
  const jws = Buffer.concat([gcm.update(b64u(ct)), gcm.final()]).toString();
  const [jh, jp, js] = jws.split('.');
  const jwsHeader = JSON.parse(b64u(jh).toString());
  if (jwsHeader.alg !== 'ES256') throw new Error(`unexpected JWS alg ${jwsHeader.alg}`);
  const key = crypto.createPublicKey({ key: verificationKeyDer, format: 'der', type: 'spki' });
  const ok = crypto.verify('sha256', Buffer.from(`${jh}.${jp}`), { key, dsaEncoding: 'ieee-p1363' }, b64u(js));
  if (!ok) throw new Error('JWS signature does not verify');
  return JSON.parse(b64u(jp).toString());
}

function selfTest() {
  // Build a token the way Google does, with throwaway keys, and read it back.
  const aes = crypto.randomBytes(32);
  const { privateKey, publicKey } = crypto.generateKeyPairSync('ec', { namedCurve: 'P-256' });
  const payload = { requestDetails: { requestPackageName: 'x', nonce: 'abc=' }, deviceIntegrity: { deviceRecognitionVerdict: ['MEETS_DEVICE_INTEGRITY'] } };
  const jh = Buffer.from(JSON.stringify({ alg: 'ES256' })).toString('base64url');
  const jp = Buffer.from(JSON.stringify(payload)).toString('base64url');
  const sig = crypto.sign('sha256', Buffer.from(`${jh}.${jp}`), { key: privateKey, dsaEncoding: 'ieee-p1363' }).toString('base64url');
  const jws = `${jh}.${jp}.${sig}`;
  const h = Buffer.from(JSON.stringify({ alg: 'A256KW', enc: 'A256GCM' })).toString('base64url');
  const cek = crypto.randomBytes(32), iv = crypto.randomBytes(12);
  const wrap = crypto.createCipheriv('id-aes256-wrap', aes, Buffer.from('A6A6A6A6A6A6A6A6', 'hex'));
  const ek = Buffer.concat([wrap.update(cek), wrap.final()]).toString('base64url');
  const gcm = crypto.createCipheriv('aes-256-gcm', cek, iv); gcm.setAAD(Buffer.from(h, 'ascii'));
  const ct = Buffer.concat([gcm.update(jws), gcm.final()]).toString('base64url');
  const token = `${h}.${ek}.${iv.toString('base64url')}.${ct}.${gcm.getAuthTag().toString('base64url')}`;
  const der = publicKey.export({ format: 'der', type: 'spki' });
  const out = readToken(token, aes, der);
  if (JSON.stringify(out) !== JSON.stringify(payload)) throw new Error('round trip differs');
  // A wrong verification key must fail.
  const other = crypto.generateKeyPairSync('ec', { namedCurve: 'P-256' }).publicKey.export({ format: 'der', type: 'spki' });
  let refused = false; try { readToken(token, aes, other); } catch { refused = true; }
  if (!refused) throw new Error('wrong key accepted');
  console.log('self-test: PASS (decrypt, verify, wrong key refused)');
}

if (process.argv.includes('--self-test')) { selfTest(); process.exit(0); }

const reportPath = process.argv[2];
const enc = process.env.PI_KEYS_ENC, priv = process.env.PI_PRIVATE_KEY;
const dk = process.env.PI_DECRYPTION_KEY_FILE, vk = process.env.PI_VERIFICATION_KEY_FILE;
if (!reportPath || !((enc && priv) || (dk && vk))) {
  console.error('Usage: PI_KEYS_ENC=… PI_PRIVATE_KEY=… (or PI_DECRYPTION_KEY_FILE=… PI_VERIFICATION_KEY_FILE=…) node decrypt-integrity.mjs report.txt  |  --self-test');
  process.exit(2);
}
const report = {};
for (const line of readFileSync(reportPath, 'utf8').split(/\r?\n/)) {
  const i = line.indexOf('='); if (i > 0) report[line.slice(0, i).trim()] = line.slice(i + 1).trim();
}
if (report.type !== 'classic' || !report.token) { console.error('Need a Classic report with a token= line.'); process.exit(2); }
const keyText = f => Buffer.from(readFileSync(f, 'utf8').trim(), 'base64');
let decKey, verKey;
if (enc) {
  // As the server will do with the file the customer uploads.
  const text = crypto.privateDecrypt({ key: readFileSync(priv), padding: crypto.constants.RSA_PKCS1_OAEP_PADDING }, readFileSync(enc)).toString();
  const kv = Object.fromEntries(text.split(/\r?\n/).filter(l => l.includes('=')).map(l => [l.slice(0, l.indexOf('=')).trim(), l.slice(l.indexOf('=') + 1).trim()]));
  if (!kv.DECRYPTION_KEY || !kv.VERIFICATION_KEY) { console.log('api_keys.enc opened, but without DECRYPTION_KEY / VERIFICATION_KEY lines:', Object.keys(kv).join(', ')); process.exit(1); }
  decKey = Buffer.from(kv.DECRYPTION_KEY, 'base64'); verKey = Buffer.from(kv.VERIFICATION_KEY, 'base64');
  console.log(`api_keys.enc opened: decryption key ${decKey.length} bytes, verification key ${verKey.length} bytes`);
} else { decKey = keyText(dk); verKey = keyText(vk); }
let p;
try { p = readToken(report.token, decKey, verKey); }
catch (e) { console.log('READ FAILED:', e.message); process.exit(1); }
console.log('Verdict (read locally):'); console.log(JSON.stringify(p, null, 2));

const rd = p.requestDetails ?? {}, ai = p.appIntegrity ?? {}, di = p.deviceIntegrity ?? {};
const unpad = v => (v ?? '').replace(/=+$/, '');
const dv = di.deviceRecognitionVerdict ?? [];
const ageS = rd.timestampMillis ? Math.round((Date.now() - Number(rd.timestampMillis)) / 1000) : null;
const expectedNonce = crypto.createHash('sha256').update(report.bound ?? '').digest('base64url');
const checks = [
  ['requestPackageName = package', rd.requestPackageName === report.package, rd.requestPackageName],
  ['nonce = base64url(SHA-256(bound))', unpad(rd.nonce) === expectedNonce && unpad(report.nonce) === expectedNonce, rd.nonce],
  ['token age (s)', ageS !== null, ageS],
  ['appIntegrity.packageName = package', ai.packageName === report.package, ai.packageName],
  ['certificate (Play signing key, so it differs from the upload key)', (ai.certificateSha256Digest ?? []).length > 0, (ai.certificateSha256Digest ?? []).join(',')],
  ['PLAY_RECOGNIZED', ai.appRecognitionVerdict === 'PLAY_RECOGNIZED', ai.appRecognitionVerdict],
  ['MEETS_DEVICE_INTEGRITY', dv.includes('MEETS_DEVICE_INTEGRITY'), dv.join(',') || '(none)'],
];
console.log('\nChecks:');
for (const [name, ok, val] of checks) console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${val !== undefined && val !== '' ? '  (' + val + ')' : ''}`);
