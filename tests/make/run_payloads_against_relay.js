// Local stand-in for the isolated Make test scenario (docs/11): every synthetic payload goes through the SAME Code
// module the scenario will use (build masterboard.v1 body + HMAC signature), is POSTed to a relay, and the outcome is
// compared with the expected one. Prints codes, statuses and file names only; never PDFs or data values.
// Usage: RELAY_HMAC_SECRET=... node tests/make/run_payloads_against_relay.js http://127.0.0.1:18203
'use strict';
const fs = require('fs');
const path = require('path');
const http = require('http');
const root = path.resolve(__dirname, '..', '..');
const code = fs.readFileSync(path.join(root, 'make/test-scenario/code-module-sign.js'), 'utf8');
const sign = input => new Function('input', code)(input);
const base = new URL(process.argv[2] || 'http://127.0.0.1:18203');
const secret = process.env.RELAY_HMAC_SECRET;
if (!secret) { console.error('RELAY_HMAC_SECRET is required'); process.exit(2); }

const expected = {
  T1: { http: 200, status: 'complete', generated: ['BNZ_BOARDER_FORM'] },
  T2: { http: 200, status: 'complete', generated: ['GHL_NP_INDIVIDUAL_FORM', 'GHL_NP_TRUST_FORM'] },
  T3: { http: 200, status: 'complete', generated: ['GHL_NP_INDIVIDUAL_FORM', 'GHL_NP_TRUST_FORM'], noPdf: true },
  T4: { http: 422, failed: ['GHL_NP_INDIVIDUAL_FORM', 'GHL_NP_TRUST_FORM'] }, // trust record: no individual's name either
  T5: { http: 422, code: 'UNKNOWN_BANK' },
  T10: { http: 200, status: 'complete', generated: ['BNZ_BOARDER_FORM', 'GHL_NP_INDIVIDUAL_FORM', 'GHL_NP_TRUST_FORM'] },
  T12: { http: 200, status: 'partial', generated: ['GHL_NP_INDIVIDUAL_FORM'], failed: ['GHL_NP_TRUST_FORM'] },
  T11: { http: 200, status: 'partial', generated: ['BNZ_BOARDER_FORM', 'GHL_NP_INDIVIDUAL_FORM'], failed: ['GHL_NP_TRUST_FORM'] },
};

function post(body, headers) {
  return new Promise((resolve, reject) => {
    const req = http.request({ hostname: base.hostname, port: base.port, path: '/v1/masterboard/documents', method: 'POST',
      headers: Object.assign({ 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) }, headers) },
    res => { const chunks = []; res.on('data', c => chunks.push(c)); res.on('end', () => resolve({ status: res.statusCode,
      body: Buffer.concat(chunks).toString('utf8') })); });
    req.on('error', reject);
    req.end(body);
  });
}

(async () => {
  let failures = 0;
  for (const tc of Object.keys(expected)) {
    const payload = JSON.parse(fs.readFileSync(path.join(root, 'make/test-scenario/payloads', tc + '.json'), 'utf8'));
    const ts = String(Math.floor(Date.now() / 1000));
    const s = sign({ payload, executionId: 'localrun' + tc, today: '2026-10-28', timestamp: ts, secret });
    const r = await post(s.body, { 'X-RSFA-Timestamp': s.timestamp, 'X-RSFA-Signature': s.signature });
    const out = JSON.parse(r.body || '{}');
    const docs = out.documents || [];
    const gen = docs.filter(d => d.status === 'generated').map(d => d.templateId).sort();
    const failed = docs.filter(d => d.status === 'failed').map(d => d.templateId).sort();
    const e = expected[tc];
    const problems = [];
    if (r.status !== e.http) problems.push(`http ${r.status} != ${e.http}`);
    if (e.status && out.status !== e.status) problems.push(`status ${out.status} != ${e.status}`);
    if (e.code && out.code !== e.code) problems.push(`code ${out.code} != ${e.code}`);
    if (e.generated && JSON.stringify(gen) !== JSON.stringify([...e.generated].sort())) problems.push(`generated ${gen}`);
    if (e.failed && JSON.stringify(failed) !== JSON.stringify([...e.failed].sort())) problems.push(`failed ${failed}`);
    if (e.noPdf && docs.some(d => d.pdfBase64)) problems.push('PDF returned on a dry run');
    if (r.status === 200 && docs.some(d => d.status === 'generated' && !e.noPdf && !d.pdfBase64)) problems.push('missing pdfBase64');
    const names = docs.filter(d => d.fileName).map(d => d.fileName);
    if (new Set(names).size !== names.length) problems.push('duplicate file names');
    console.log(`${problems.length ? 'FAIL' : 'ok  '} ${tc} http=${r.status} status=${out.status || out.code} generated=[${gen}]`
      + ` failed=[${failed}] notMigrated=${(out.notMigrated || []).length} ${problems.join('; ')}`);
    failures += problems.length ? 1 : 0;
  }
  // T6/T7/T9-style relay checks: replay of the exact signed request, wrong secret, stale timestamp
  const p1 = JSON.parse(fs.readFileSync(path.join(root, 'make/test-scenario/payloads/T1.json'), 'utf8'));
  const ts = String(Math.floor(Date.now() / 1000));
  const s = sign({ payload: p1, executionId: 'localreplay', today: '2026-10-28', timestamp: ts, secret });
  const h = { 'X-RSFA-Timestamp': s.timestamp, 'X-RSFA-Signature': s.signature };
  const first = await post(s.body, h);
  const again = await post(s.body, h);
  const wrong = sign({ payload: p1, executionId: 'localwrong', today: '2026-10-28', timestamp: ts, secret: 'x'.repeat(40) });
  const w = await post(wrong.body, { 'X-RSFA-Timestamp': wrong.timestamp, 'X-RSFA-Signature': wrong.signature });
  const oldTs = String(Math.floor(Date.now() / 1000) - 600);
  const st = sign({ payload: p1, executionId: 'localstale', today: '2026-10-28', timestamp: oldTs, secret });
  const stale = await post(st.body, { 'X-RSFA-Timestamp': st.timestamp, 'X-RSFA-Signature': st.signature });
  const checks = [[first.status, 200, 'signed request accepted'], [again.status, 409, 'exact replay refused (REPLAYED_REQUEST)'],
    [w.status, 401, 'wrong secret refused (INVALID_SIGNATURE)'], [stale.status, 401, 'timestamp 10 min old refused (STALE_TIMESTAMP)']];
  for (const [got, want, what] of checks) {
    console.log(`${got === want ? 'ok  ' : 'FAIL'} ${what}: http=${got}`);
    failures += got === want ? 0 : 1;
  }
  console.log(failures ? `PAYLOAD RUN FAILED (${failures})` : 'PAYLOAD RUN PASSED');
  process.exit(failures ? 1 : 0);
})().catch(e => { console.error('error:', e.code || e.message); process.exit(1); });
