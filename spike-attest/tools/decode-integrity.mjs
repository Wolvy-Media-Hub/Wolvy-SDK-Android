#!/usr/bin/env node
// Phase 5, spike A: decode a Play Integrity token from the spike app's report with the Cloud
// project's service account, and check it the way the design's app_attest.php would.
//
//   GOOGLE_SA_KEY=/path/outside/the/repos/key.json node decode-integrity.mjs report.txt [--as <package>]
//
// report.txt is the text from the app's "Copy report" (WOLVY-ATTEST-REPORT … END). The key file is
// read here only to sign Google's OAuth request; nothing from it is printed. No dependencies.

import { readFileSync } from 'node:fs';
import { createSign } from 'node:crypto';

const args = process.argv.slice(2);
const reportPath = args.find(a => !a.startsWith('--'));
const asIdx = args.indexOf('--as');
const keyPath = process.env.GOOGLE_SA_KEY;
if (!reportPath || !keyPath) {
  console.error('Usage: GOOGLE_SA_KEY=/path/key.json node decode-integrity.mjs report.txt [--as <package>]');
  process.exit(2);
}

// --- The report ---
const report = {};
for (const line of readFileSync(reportPath, 'utf8').split(/\r?\n/)) {
  const i = line.indexOf('=');
  if (i > 0) report[line.slice(0, i).trim()] = line.slice(i + 1).trim();
}
if (!report.token) { console.error('No token= line in the report (status: ' + (report.status ?? '?') + ').'); process.exit(2); }
const pkg = asIdx >= 0 ? args[asIdx + 1] : report.package;

// --- OAuth access token from the service account (RS256 JWT bearer grant) ---
const sa = JSON.parse(readFileSync(keyPath, 'utf8'));
const b64url = b => Buffer.from(b).toString('base64url');
const now = Math.floor(Date.now() / 1000);
const unsigned = b64url(JSON.stringify({ alg: 'RS256', typ: 'JWT' })) + '.' + b64url(JSON.stringify({
  iss: sa.client_email, scope: 'https://www.googleapis.com/auth/playintegrity',
  aud: 'https://oauth2.googleapis.com/token', iat: now, exp: now + 600,
}));
const jwt = unsigned + '.' + createSign('RSA-SHA256').update(unsigned).sign(sa.private_key, 'base64url');
const tokRes = await fetch('https://oauth2.googleapis.com/token', {
  method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
  body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion: jwt }),
});
const tok = await tokRes.json();
if (!tok.access_token) { console.error('OAuth failed:', tokRes.status, tok.error, tok.error_description); process.exit(1); }

// --- Decode ---
console.log(`Service account project: ${sa.project_id}`);
console.log(`Report: ${report.type} request, project ${report.project}, package ${report.package}, ${report.device}`);
console.log(`Decoding as package: ${pkg}\n`);
const res = await fetch(`https://playintegrity.googleapis.com/v1/${encodeURIComponent(pkg)}:decodeIntegrityToken`, {
  method: 'POST', headers: { Authorization: `Bearer ${tok.access_token}`, 'Content-Type': 'application/json' },
  body: JSON.stringify({ integrity_token: report.token }),
});
const body = await res.json();
if (!res.ok) {
  console.log(`DECODE FAILED: HTTP ${res.status}`);
  console.log(JSON.stringify(body.error ?? body, null, 2));
  process.exit(1);
}
const p = body.tokenPayloadExternal ?? {};
console.log('Verdict:');
console.log(JSON.stringify(p, null, 2));

// --- The design's checks (phase-5-attestation.md, 2.3) ---
const rd = p.requestDetails ?? {}, ai = p.appIntegrity ?? {}, di = p.deviceIntegrity ?? {};
const expected = report.type === 'standard' ? report.request_hash : report.nonce;
// Google returns a Classic nonce with base64 padding added; compare without it.
const unpad = v => (v ?? '').replace(/=+$/, '');
const got = report.type === 'standard' ? rd.requestHash : rd.nonce;
const certs = (report.cert_sha256_b64url ?? '').split(',').filter(Boolean);
const ageS = rd.timestampMillis ? Math.round((Date.now() - Number(rd.timestampMillis)) / 1000) : null;
const dv = di.deviceRecognitionVerdict ?? [];
const checks = [
  ['requestPackageName = package', rd.requestPackageName === report.package, rd.requestPackageName],
  [report.type === 'standard' ? 'requestHash = request_hash' : 'nonce = nonce', unpad(got) === unpad(expected), got],
  ['token age (s)', ageS !== null, ageS],
  ['appIntegrity filled (not UNEVALUATED)', !!ai.appRecognitionVerdict && ai.appRecognitionVerdict !== 'UNEVALUATED', ai.appRecognitionVerdict],
  ['appIntegrity.packageName = package', ai.packageName === report.package, ai.packageName],
  ['certificateSha256Digest has the app cert', (ai.certificateSha256Digest ?? []).some(c => certs.includes(c)), (ai.certificateSha256Digest ?? []).join(',')],
  ['versionCode = version_code', String(ai.versionCode ?? '') === String(report.version_code ?? ''), ai.versionCode],
  ['PLAY_RECOGNIZED', ai.appRecognitionVerdict === 'PLAY_RECOGNIZED', ai.appRecognitionVerdict],
  ['MEETS_DEVICE_INTEGRITY', dv.includes('MEETS_DEVICE_INTEGRITY'), dv.join(',') || '(none)'],
  ['MEETS_STRONG_INTEGRITY', dv.includes('MEETS_STRONG_INTEGRITY'), ''],
];
console.log('\nChecks:');
for (const [name, ok, val] of checks) console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${val !== '' && val !== undefined ? '  (' + val + ')' : ''}`);
