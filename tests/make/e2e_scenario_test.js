// LOCAL end-to-end test of the isolated Make scenario "RSFA TEST – End Forms Engine → SharePoint (BNZ Boarder)"
// (make/e2e-scenario/, docs/15). Nothing leaves this machine. Real processes:
//   engine  java -jar … serve (ENGINE_AUTH=NONE, loopback)            ← stands for the private Cloud Run engine
//   IAM stand-in (this file): accepts only the relay's identity token  ← Cloud Run IAM (403 otherwise); can inject faults
//   relay   java -jar … serve (ENGINE_ROLE=relay, memory replay store) ← the relay Cloud Run service
// In-process fakes (this file): Monday GetItemV2 output (fictional items), Make data store, SharePoint uploadAFile.
// The scenario runner executes the blueprint's modules in order, with the SAME Code module file Make will run.
// Prints codes, statuses and file names only; never field values or PDFs.
// Usage: node tests/make/e2e_scenario_test.js            (needs the jar and .local/generated/templates)
'use strict';
const fs = require('fs');
const path = require('path');
const http = require('http');
const crypto = require('crypto');
const { spawn, spawnSync } = require('child_process');

const root = path.resolve(__dirname, '..', '..');
const jar = path.join(root, 'engine/target/end-forms-engine.jar');
const templates = path.join(root, '.local/generated/templates');
const out = path.join(root, '.local/output/make-e2e');
if (!fs.existsSync(jar) || !fs.existsSync(templates)) {
  console.log('SKIPPED: needs engine/target/end-forms-engine.jar and .local/generated/templates');
  process.exit(0);
}
fs.rmSync(out, { recursive: true, force: true });
fs.mkdirSync(out, { recursive: true });
const code = fs.readFileSync(path.join(root, 'make/test-scenario/code-module-sign.js'), 'utf8');
const codeModule = input => new Function('input', code)(input); // what Make's code:ExecuteCode runs
const items = JSON.parse(fs.readFileSync(path.join(__dirname, 'e2e/monday-items.fictional.json'), 'utf8'));
const SECRET = crypto.randomBytes(32).toString('hex');
const ID_TOKEN = 'test-relay-identity-' + crypto.randomBytes(8).toString('hex');
const PORTS = { engine: 18311, iam: 18312, relay: 18313 };
const APPROVED = '9900000001,9900000002,9900000003,9900000004,9900000005';
const SP = { site: 'test-site', drive: 'test-drive', folder: 'End Forms Engine TEST' };

let n = 0;
const ok = (cond, msg) => { if (!cond) throw new Error('FAILED: ' + msg); n++; console.log('ok  ', msg); };
const procs = [];
const logs = { engine: '', relay: '' };

// ---------------------------------------------------------------- processes
function start(name, env) {
  const p = spawn('java', ['-jar', jar, 'serve'], { env: Object.assign({}, process.env, env), stdio: ['ignore', 'pipe', 'pipe'] });
  p.stdout.on('data', d => { logs[name] += d; });
  p.stderr.on('data', d => { logs[name] += d; });
  procs.push(p);
}
const fault = { mode: 'none', calls: 0 };
function iamStandIn() { // Cloud Run IAM: only the relay's identity reaches the engine; faults for the negative tests
  return http.createServer((req, res) => {
    if (req.headers.authorization !== 'Bearer ' + ID_TOKEN) { res.writeHead(403); res.end('forbidden'); return; }
    fault.calls++;
    if (fault.mode === 'error500') { req.resume(); res.writeHead(500, { 'Content-Type': 'text/plain' }); res.end('engine fault'); return; }
    if (fault.mode === 'hang') { req.resume(); setTimeout(() => { try { res.writeHead(504); res.end(); } catch (e) { /* closed */ } }, 6000); return; }
    const headers = Object.assign({}, req.headers);
    delete headers.authorization;
    const up = http.request({ host: '127.0.0.1', port: PORTS.engine, path: req.url, method: req.method, headers }, r => {
      res.writeHead(r.statusCode, r.headers); r.pipe(res);
    });
    up.on('error', () => { res.writeHead(502); res.end(); });
    req.pipe(up);
  }).listen(PORTS.iam, '127.0.0.1');
}
async function waitFor(port) {
  for (let i = 0; i < 80; i++) {
    const okk = await new Promise(r => http.get({ host: '127.0.0.1', port, path: '/health' }, x => { x.resume(); r(true); }).on('error', () => r(false)));
    if (okk) return;
    await new Promise(r => setTimeout(r, 250));
  }
  throw new Error('service on ' + port + ' did not start');
}

// ---------------------------------------------------------------- fakes: Monday, data store, SharePoint
const monday = { getItem: id => { const it = items[id]; if (!it) throw new Error('monday: item not found'); return it; } };
const dataStore = new Map(); // key -> record (codes, ids and file names only)
const sharepoint = {
  files: new Map(), failNext: 0, uploads: 0,
  upload({ site, drive, folder, filename, data, conflictBehavior }) {
    this.uploads++;
    if (site !== SP.site || drive !== SP.drive || folder !== SP.folder) throw new Error('sharepoint: outside the test folder');
    if (this.failNext > 0) { this.failNext--; const e = new Error('sharepoint: 503 serviceNotAvailable'); e.code = 'SP_UPLOAD_FAILED'; throw e; }
    const p = folder + '/' + filename;
    if (this.files.has(p) && conflictBehavior !== 'replace') { const e = new Error('nameAlreadyExists'); e.code = 'SP_CONFLICT'; throw e; }
    const prev = this.files.get(p);
    this.files.set(p, { data, versions: prev ? prev.versions + 1 : 1 });
    return { id: 'sp-' + crypto.createHash('sha256').update(p).digest('hex').slice(0, 12), webUrl: 'https://sharepoint.test/' + encodeURI(p) };
  },
};

// ---------------------------------------------------------------- HTTP (Make http:MakeRequest v4)
function post(body, headers, timeoutMs) {
  return new Promise(resolve => {
    const req = http.request({ host: '127.0.0.1', port: PORTS.relay, path: '/v1/masterboard/documents', method: 'POST',
      headers: Object.assign({ 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) }, headers) }, r => {
      let b = '';
      r.on('data', d => { b += d; });
      r.on('end', () => { let data = null; try { data = JSON.parse(b); } catch (e) { /* not JSON */ } resolve({ statusCode: r.statusCode, data }); });
    });
    req.setTimeout(timeoutMs, () => { req.destroy(); });
    req.on('error', () => resolve({ statusCode: 599, data: null })); // module 10 error handler: Resume with 599
    req.end(body);
  });
}

// ---------------------------------------------------------------- the scenario, module by module (make/e2e-scenario)
async function runScenario(inp, opts = {}) {
  const today = inp.today || '2026-10-29';                                  // 2 SetVariables
  const runId = 'run' + crypto.randomBytes(4).toString('hex');
  const ts = () => String(Math.floor(Date.now() / 1000) + (opts.clockSkew || 0));
  const signIn = () => {                                                     // 4 / 9 code:ExecuteCode
    if (inp.mode === 'monday') {
      // filter F1 (before module 3): the item must be on the approved test list, so no other item is ever read
      if (!APPROVED.split(',').includes(String(inp.itemId))) return { refused: 'ITEM_NOT_APPROVED' };
      const it = monday.getItem(inp.itemId);                                 // 3 monday:GetItemV2 (monday route only)
      const cols = {};
      for (const [id, v] of Object.entries(it.mappable_column_values)) cols[id] = v.text;
      return codeModule(Object.assign({ mode: 'monday', itemId: inp.itemId, boardId: it.board.id, approvedItemIds: APPROVED,
        executionId: runId, today, timestamp: ts(), secret: opts.secret || SECRET }, cols));
    }
    return codeModule({ payload: JSON.stringify(inp.payload), executionId: runId, today, timestamp: ts(), secret: opts.secret || SECRET });
  };
  const first = signIn();
  if (first.refused) {                                                       // 5 filter: refused → 6 datastore record
    const key = 'refused-' + inp.itemId;
    dataStore.set(key, { status: 'refused', code: first.refused, itemId: inp.itemId, runId });
    return dataStore.get(key);
  }
  const key = first.idempotencyKey;
  const existing = dataStore.get(key);                                       // 7 datastore:GetRecord
  if (existing && existing.status === 'filed') return Object.assign({}, existing, { status: 'duplicate', engineCalled: false });
  for (let attempt = 1; attempt <= 3; attempt++) {                            // 8 BasicRepeater (3)
    const rec = dataStore.get(key);                                           // 9 GetRecord + filter: stop when final
    if (rec && ['filed', 'failed', 'failed-after-retries', 'upload-failed'].includes(rec.status) && rec.runId === runId) break;
    if (attempt > 1) await new Promise(r => setTimeout(r, opts.backoffMs || 1100)); // 10 FunctionSleep (60 s, 120 s in Make; ≥ 1 s so a re-signed retry never reuses a signature)
    const signed = attempt === 1 ? first : signIn();                          // 11 Code: re-signed, same body
    const res = opts.replayFirst && attempt === 1 ? (await post(signed.body, { 'X-RSFA-Timestamp': signed.timestamp, 'X-RSFA-Signature': signed.signature }, 15000), await post(signed.body, { 'X-RSFA-Timestamp': signed.timestamp, 'X-RSFA-Signature': signed.signature }, 15000))
      : await post(signed.body, { 'X-RSFA-Timestamp': signed.timestamp, 'X-RSFA-Signature': signed.signature }, 15000); // 12 HTTP
    const base = { itemId: signed.itemId, idempotencyKey: key, correlationId: signed.correlationId, runId, attempt,
      httpStatus: res.statusCode, otherBanks: signed.otherBanks };
    if (res.statusCode === 200 && res.data && ['complete', 'partial'].includes(res.data.status)) { // 13 route A
      const filed = [];
      try {
        for (const d of res.data.documents.filter(x => x.status === 'generated' && x.pdfBase64)) { // 14 iterator + filter
          const pdf = Buffer.from(d.pdfBase64, 'base64');                     // toBinary(pdfBase64; base64)
          if (pdf.subarray(0, 5).toString() !== '%PDF-') throw Object.assign(new Error('not a PDF'), { code: 'NOT_A_PDF' });
          const up = sharepoint.upload({ site: SP.site, drive: SP.drive, folder: SP.folder, filename: d.fileName, data: pdf,
            conflictBehavior: 'replace' });                                   // 15 microsoft-sharepoint:uploadAFile
          filed.push({ fileName: d.fileName, templateId: d.templateId, sharepointItemId: up.id, webUrl: up.webUrl, bytes: pdf.length });
        }
      } catch (e) {                                                           // 15 error handler → 16 record
        dataStore.set(key, Object.assign(base, { status: 'upload-failed', code: e.code || 'SP_UPLOAD_FAILED' }));
        return dataStore.get(key);
      }
      dataStore.set(key, Object.assign(base, { status: filed.length ? 'filed' : 'no-document', engineStatus: res.data.status,
        requestDigest: res.data.requestDigest, files: filed, notMigrated: (res.data.notMigrated || []).length }));
      return dataStore.get(key);                                              // 17 datastore:AddRecord (replace)
    }
    const code = res.data && res.data.code;
    if ([400, 401, 404, 409, 413, 422].includes(res.statusCode) || (res.data && res.data.retryable === false)) { // route B
      dataStore.set(key, Object.assign(base, { status: 'failed', code: code || ('HTTP_' + res.statusCode) }));
      return dataStore.get(key);
    }
    dataStore.set(key, Object.assign(base, { status: attempt === 3 ? 'failed-after-retries' : 'retrying', code: code || ('HTTP_' + res.statusCode) })); // route C
  }
  return dataStore.get(key);
}

function verifyPdf(file, expect) {
  const e = path.join(out, path.basename(file) + '.expect.json');
  fs.writeFileSync(e, JSON.stringify(expect));
  const r = spawnSync('python3', [path.join(root, 'deploy/verify_pdf.py'), file, '--expect', e], { encoding: 'utf8' });
  return JSON.parse(r.stdout);
}

// ---------------------------------------------------------------- tests
(async () => {
  start('engine', { ENGINE_AUTH: 'NONE', PORT: String(PORTS.engine), ENGINE_BIND: '127.0.0.1',
    ENGINE_MAPPINGS_DIR: path.join(root, 'mappings'), ENGINE_TEMPLATE_DIRS: templates });
  iamStandIn();
  start('relay', { ENGINE_ROLE: 'relay', PORT: String(PORTS.relay), ENGINE_BIND: '127.0.0.1',
    RELAY_TARGET_URL: 'http://127.0.0.1:' + PORTS.iam, RELAY_HMAC_SECRETS: SECRET, RELAY_TEST_ID_TOKEN: ID_TOKEN,
    RELAY_REPLAY_STORE: 'memory', RELAY_UPSTREAM_TIMEOUT_SECONDS: '3', RELAY_RATE_PER_MINUTE: '120' });
  await waitFor(PORTS.engine);
  await waitFor(PORTS.relay);
  const T1 = JSON.parse(fs.readFileSync(path.join(root, 'make/test-scenario/payloads/T1.json'), 'utf8'));
  const T5 = JSON.parse(fs.readFileSync(path.join(root, 'make/test-scenario/payloads/T5.json'), 'utf8'));

  // 1. synthetic payload, dated like the verified fixture: identical bytes to the verified BNZ Boarder PDF
  let r = await runScenario({ mode: 'synthetic', payload: T1, today: '2026-10-27', itemId: T1.itemId });
  ok(r.status === 'filed' && r.files.length === 1 && r.files[0].templateId === 'BNZ_BOARDER_FORM', 'synthetic T1 → relay → engine → SharePoint test folder: filed');
  ok(!/[A-Za-z]{3,} [A-Za-z]{3,}/.test(r.files[0].fileName), 'file name carries ids and a digest only: ' + r.files[0].fileName);
  let pdf = sharepoint.files.get(SP.folder + '/' + r.files[0].fileName).data;
  fs.writeFileSync(path.join(out, 'T1.pdf'), pdf);
  const sha = crypto.createHash('sha256').update(pdf).digest('hex');
  ok(sha.startsWith('2ae7bfacf48b8ee8'), 'stored PDF is byte-identical to the verified BNZ Boarder output (2ae7bfac…)');
  ok(verifyPdf(path.join(out, 'T1.pdf'), {}).ok, 'stored PDF: 7 fields + 2 name overlays correct; nothing ticked or signed');

  // 2. Monday mode (fictional item, columns by ID, BNZ + ASB selected → BNZ only), today 2026-10-29
  r = await runScenario({ mode: 'monday', itemId: '9900000001' });
  ok(r.status === 'filed' && r.files.length === 1, 'Monday-mode item (columns by ID) → filed');
  ok(r.otherBanks === 'ASB', 'other selected banks are reported, not sent (BNZ-only POC)');
  pdf = sharepoint.files.get(SP.folder + '/' + r.files[0].fileName).data;
  fs.writeFileSync(path.join(out, 'monday-9900000001.pdf'), pdf);
  const v = verifyPdf(path.join(out, 'monday-9900000001.pdf'), { fields: { 'Text 2': '12 Example Street, Testville', 'Text 3': 'Auckland',
    'Text 4': '0999', 'Text 5': '2', 'Text 6': '250.00', 'Date 1': '291026', 'Date 2': '291026' }, overlays: ['Jane Example', 'John Sample'] });
  ok(v.ok, 'Monday-mode PDF: address split into street / city / postcode, values and dates correct, no ticks or signatures');

  // 3. duplicate processing: same item, same data → no engine call, no upload
  const callsBefore = fault.calls, uploadsBefore = sharepoint.uploads;
  r = await runScenario({ mode: 'monday', itemId: '9900000001' });
  ok(r.status === 'duplicate' && fault.calls === callsBefore && sharepoint.uploads === uploadsBefore, 'duplicate run: skipped before the engine and SharePoint');

  // 4. refusals before any engine call
  for (const [id, codeExp, what] of [['9900000002', 'MISSING_REQUIRED_DATA', 'missing required data'],
    ['9900000003', 'ADDRESS_UNPARSEABLE', 'unparseable address (no guessing)'], ['9900000004', 'BNZ_NOT_SELECTED', 'BNZ not selected'],
    ['1234567890', 'ITEM_NOT_APPROVED', 'item not on the approved test list']]) {
    const c = fault.calls;
    r = await runScenario({ mode: 'monday', itemId: id }).catch(e => ({ status: 'error', code: e.message }));
    ok(r.status === 'refused' && r.code === codeExp && fault.calls === c, what + ' → refused ' + codeExp + ', engine not called');
  }

  // 5. authentication, freshness, replay
  r = await runScenario({ mode: 'synthetic', payload: Object.assign({}, T1, { itemId: '1000000091' }) }, { secret: crypto.randomBytes(32).toString('hex') });
  ok(r.status === 'failed' && r.httpStatus === 401 && r.code === 'INVALID_SIGNATURE', 'wrong secret → 401 INVALID_SIGNATURE, not retried');
  r = await runScenario({ mode: 'synthetic', payload: Object.assign({}, T1, { itemId: '1000000092' }) }, { clockSkew: -900 });
  ok(r.status === 'failed' && r.code === 'STALE_TIMESTAMP', 'expired timestamp → 401 STALE_TIMESTAMP');
  r = await runScenario({ mode: 'synthetic', payload: Object.assign({}, T1, { itemId: '1000000093' }) }, { replayFirst: true });
  ok(r.status === 'failed' && r.httpStatus === 409 && r.code === 'REPLAYED_REQUEST', 'identical signed request sent twice → 409 REPLAYED_REQUEST');

  // 6. unknown bank
  r = await runScenario({ mode: 'synthetic', payload: T5 });
  ok(r.status === 'failed' && r.httpStatus === 422 && r.code === 'UNKNOWN_BANK', 'unknown bank → 422 UNKNOWN_BANK, nothing filed');

  // 7. engine error and timeout → bounded retries, then a later successful run (partial completion → retry)
  fault.mode = 'error500';
  r = await runScenario({ mode: 'monday', itemId: '9900000005' });
  ok(r.status === 'failed-after-retries' && r.attempt === 3, 'engine 500 → 3 re-signed attempts → failed-after-retries (' + r.code + ')');
  fault.mode = 'hang';
  r = await runScenario({ mode: 'synthetic', payload: Object.assign({}, T1, { itemId: '1000000094' }) });
  ok(r.status === 'failed-after-retries' && r.code === 'UPSTREAM_TIMEOUT', 'engine timeout → 504 UPSTREAM_TIMEOUT ×3 → failed-after-retries');
  fault.mode = 'none';
  r = await runScenario({ mode: 'monday', itemId: '9900000005' });
  ok(r.status === 'filed', 'same item after the engine recovers → filed (no manual clean-up)');

  // 8. SharePoint failure after a successful generation → upload-failed; the next run files it
  sharepoint.failNext = 1;
  const t2 = Object.assign({}, T1, { itemId: '1000000095' });
  r = await runScenario({ mode: 'synthetic', payload: t2 });
  ok(r.status === 'upload-failed' && r.code === 'SP_UPLOAD_FAILED', 'SharePoint upload failure → upload-failed, nothing recorded as filed');
  r = await runScenario({ mode: 'synthetic', payload: t2 });
  ok(r.status === 'filed' && sharepoint.files.get(SP.folder + '/' + r.files[0].fileName).versions === 1, 'retry after upload failure → filed once (one file version)');

  // 9. logs: codes and ids only
  for (const s of ['Jane Example', 'John Sample', 'Example Street', 'Testville', SECRET, ID_TOKEN]) {
    ok(!logs.relay.includes(s) && !logs.engine.includes(s), 'relay and engine logs do not contain a test value or secret');
  }
  ok(JSON.stringify([...dataStore.values()]).match(/Jane|Example Street|Testville/) === null, 'run records hold no field values');
  fs.writeFileSync(path.join(out, 'run-records.json'), JSON.stringify([...dataStore.values()], null, 1));
  console.log(`MAKE E2E SCENARIO TEST PASSED (${n} checks)`);
})().catch(e => { console.error(e.message); process.exitCode = 1; }).finally(() => { procs.forEach(p => p.kill()); setTimeout(() => process.exit(), 300); });
