// End-to-end browser test of the PRIVATE admin service in its production-like local environment (D-041, docs/14 §8):
// the real admin image (2 instances) behind the IAP stand-in, with the fake Cloud Storage API. Fictional data only.
// Prerequisite: bash tests/admin/run_private_env.sh up
// Usage: node tests/admin/private_admin_e2e.js [http://127.0.0.1:8443] [.local/output/private-admin]
'use strict';
const { chromium } = require('playwright');
const { spawnSync } = require('child_process');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const base = (process.argv[2] || 'http://127.0.0.1:8443').replace(/\/$/, '');
const out = process.argv[3] || '.local/output/private-admin';
const GCS = 'http://127.0.0.1:4443';
const ADMIN = 'admin.test@firbot.example.org', APPROVER = 'approver.test@rsfa.example.org';
const VIEWER = 'viewer.test@rsfa.example.org', IAP_ONLY = 'iap-only.test@rsfa.example.org', STRANGER = 'stranger@example.com';
fs.mkdirSync(out, { recursive: true });

let n = 0;
const ok = (cond, msg) => { if (!cond) throw new Error('FAILED: ' + msg); n++; console.log('ok  ', msg); };
const shot = (page, name) => page.screenshot({ path: path.join(out, name + '.png') });
const instances = new Set();

async function session(browser, email) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, acceptDownloads: true });
  const page = await ctx.newPage();
  page.errors = [];
  page.on('pageerror', e => page.errors.push(String(e)));
  page.on('response', r => { const i = r.headers()['x-standin-instance']; if (i) instances.add(i); });
  await page.goto(base + '/admin/');
  if (email) {
    await page.waitForURL(/\/_iap\/signin/);
    await page.fill('[data-testid=iap-email]', email);
    await page.click('[data-testid=iap-signin]');
  }
  return { ctx, page };
}

(async () => {
  const browser = await chromium.launch();

  // 1. anonymous: sent to sign-in; IAP refuses accounts without IAP access; the app refuses IAP-only accounts
  {
    const { ctx, page } = await session(browser, null);
    ok(/\/_iap\/signin/.test(page.url()), 'an anonymous browser is sent to sign-in');
    await shot(page, '01-sign-in');
    await ctx.close();
    const s = await session(browser, STRANGER);
    await s.page.waitForSelector('[data-testid=iap-denied]');
    ok(true, 'a Google account without IAP access is refused by IAP');
    await s.ctx.close();
    const i = await session(browser, IAP_ONLY);
    await i.page.waitForSelector('[data-testid=not-authorised]');
    ok((await i.page.textContent('[data-testid=not-authorised]')).includes('not on the administration allowlist'),
      'an account that passes IAP but is not on the app allowlist sees no data');
    ok(await i.page.locator('[data-testid=library]').count() === 0, '... and no library');
    await shot(i.page, '02-not-on-allowlist');
    await i.ctx.close();
  }

  // 2. administrator: upload a synthetic blank form, map, save, test
  const A = await session(browser, ADMIN);
  const page = A.page;
  await page.waitForSelector('[data-testid=library] tbody tr');
  ok((await page.textContent('[data-testid=user]')) === ADMIN && (await page.textContent('[data-testid=role]')) === 'admin',
    'the signed-in identity and role are shown');
  ok((await page.textContent('[data-testid=env]')).includes('synthetic documents only'), 'environment badge: synthetic only');
  ok(await page.locator('[data-testid=library] .chip[data-status=deployed]').count() === 1, 'library: BNZ Boarder deployed (00005-rih)');
  await shot(page, '03-library');
  await page.click('a[href="#/upload"]');
  const dl = await Promise.all([page.waitForEvent('download'), page.click('[data-testid=example-form]')]);
  const pdf = path.join(out, 'example-bank-form.pdf');
  await dl[0].saveAs(pdf);
  ok(fs.readFileSync(pdf).subarray(0, 5).toString() === '%PDF-', 'the fictional test form downloads from the page');
  await page.setInputFiles('[data-testid=file]', pdf);
  await page.selectOption('[data-testid=bank]', 'Other');
  await page.fill('[data-testid=template-name]', 'Example Bank Customer Questionnaire');
  await page.fill('[data-testid=template-version]', '2026-10');
  ok(await page.locator('[data-testid=upload][disabled]').count() === 1, 'upload stays disabled until "no client data" is confirmed');
  await page.check('[data-testid=confirm-no-client-data]');
  await page.click('[data-testid=upload]');
  await page.waitForSelector('[data-testid=report][data-accepted=true]');
  ok(true, 'the synthetic blank form is accepted into private storage');
  await shot(page, '04-upload-accepted');
  await page.click('[data-testid=create-draft]');
  await page.fill('[data-testid=draft-template-id]', 'EXAMPLE_BANK_QUESTIONNAIRE');
  await page.click('[data-testid=modal-ok]');
  await page.waitForSelector('[data-testid=page] img');
  const draftUrl = page.url();
  await page.click('.box[data-field=CustName]');
  await page.click('[data-testid=add-mapping]');
  await page.fill('[data-testid=column-search]', 'Applicant 1 Full Name');
  await page.click('[data-testid=column-list] div[data-column]');
  await page.click('[data-tab=suggestions]');
  await page.click('[data-testid=suggest]');
  await page.waitForSelector('[data-testid=mock-banner]');
  for (const field of ['PostalAddr', 'LoanAmt', 'RepayFreq']) {
    const b = page.locator(`[data-testid=proposal][data-field=${field}] [data-testid=accept]`);
    if (await b.count()) await b.click();
  }
  await page.click('[data-testid=save]');
  await page.waitForSelector('[data-testid=save][disabled]');
  ok((await page.textContent('.mono.muted')).includes('version 2'), 'mapping saved as version 2');
  await shot(page, '05-mapped-saved');
  await page.click('[data-tab=test]');
  await page.click('[data-testid=synthetic]');
  await page.waitForSelector('[data-testid=generate]');
  ok(await page.locator('[data-testid=test] input[readonly]').count() >= 1, 'free-text test values are generated and read-only');
  await page.click('[data-testid=generate]');
  await page.waitForSelector('[data-testid=test-result]');
  ok(await page.getAttribute('[data-testid=test-result]', 'data-ok') === 'true', 'test PDF generated from fictional values');
  await page.waitForFunction(() => [...document.querySelectorAll('[data-testid=compare] img')].every(i => i.complete && i.naturalWidth > 0));
  const href = await page.getAttribute('[data-testid=open-pdf]', 'href');
  let pdfOk = true;
  for (let i = 0; i < 4; i++) { // several requests: round-robin across both instances
    const r = await page.request.get(base + href);
    pdfOk = pdfOk && r.status() === 200 && (await r.body()).subarray(0, 5).toString() === '%PDF-';
  }
  ok(pdfOk, 'the generated PDF is served by every instance (shared storage)');
  await page.locator('[data-testid=compare]').scrollIntoViewIfNeeded();
  await shot(page, '06-test-compare');
  await page.click('[data-testid=ready]');
  await page.waitForSelector('h1 .chip[data-status=ready-for-review]');
  ok(await page.locator('button[title*="Publishing is not available"][disabled]').count() === 1, 'publication is not available');
  const csrf = await page.evaluate(async () => (await fetch('/admin/api/drafts', { method: 'POST', body: '{}' })).status);
  ok(csrf === 403, 'a write without the page marker (cross-site form) is refused');

  // 3. another browser session (approver) reopens the saved draft and records the approval
  const B = await session(browser, APPROVER);
  await B.page.waitForSelector('tr[data-kind=draft]');
  ok(await B.page.locator('tr[data-kind=draft]').count() === 1, 'the draft is listed in another browser session');
  await B.page.goto(draftUrl);
  await B.page.waitForSelector('[data-testid=page] img');
  ok((await B.page.textContent('.mono.muted')).includes('version 2'), 'the other session reopens version 2');
  ok(await B.page.locator('.box.mapped[data-field=CustName]').count() === 1, '... with the saved mapping');
  ok(await B.page.locator('[data-testid=save]').count() === 0, 'an approver cannot edit the mapping');
  await B.page.click('[data-testid=approve]');
  await B.page.fill('[data-testid=approved-by]', 'Approver Test (synthetic)');
  await B.page.fill('[data-testid=approval-ref]', 'TEST-PRIVATE-E2E');
  await B.page.check('[data-testid=approval-confirm]');
  await B.page.click('[data-testid=modal-ok]');
  await B.page.waitForSelector('h1 .chip[data-status=approved]');
  ok(true, 'approval recorded by the approver (development approval, not a deployment)');
  await B.page.click('[data-tab=history]');
  const history = await B.page.textContent('[data-testid=audit]');
  ok(history.includes(ADMIN) && history.includes(APPROVER), 'the audit trail names the verified identities');
  await shot(B.page, '07-other-session-approved');

  // 4. viewer: read-only
  const C = await session(browser, VIEWER);
  await C.page.waitForSelector('[data-testid=library] tbody tr');
  ok(await C.page.locator('a[href="#/upload"]:visible').count() === 0, 'a viewer has no upload');
  const viewerWrite = await C.page.evaluate(async () => (await fetch('/admin/api/drafts', { method: 'POST', headers: { 'X-RSFA-Admin': '1' }, body: '{}' })).status);
  ok(viewerWrite === 403, 'a viewer write is refused by the server');

  // 5. session expiry: the page says so instead of failing silently
  const signout = await A.ctx.newPage();
  await signout.goto(base + '/_iap/signout');
  await page.click('a[href="#/library"]');
  await page.waitForSelector('#session-banner:not([hidden])');
  ok(true, 'an expired sign-in shows the "reload to sign in again" banner');
  await shot(page, '08-session-expired');

  // 6. bypassing IAP: the instances themselves refuse requests without a valid IAP assertion
  const direct = await fetch('http://127.0.0.1:8091/admin/api/session');
  ok(direct.status === 401 && (await direct.json()).code === 'IAP_MISSING', 'direct request without IAP: 401');
  const { privateKey } = crypto.generateKeyPairSync('ec', { namedCurve: 'P-256' });
  const b64 = (o) => Buffer.from(typeof o === 'string' ? o : JSON.stringify(o)).toString('base64url');
  const now = Math.floor(Date.now() / 1000);
  const h = b64({ alg: 'ES256', kid: 'forged' }), p = b64({ iss: 'https://cloud.google.com/iap', aud: 'x', email: ADMIN, sub: 's', iat: now, exp: now + 600 });
  const forged = h + '.' + p + '.' + crypto.sign('sha256', Buffer.from(h + '.' + p), { key: privateKey, dsaEncoding: 'ieee-p1363' }).toString('base64url');
  const f = await fetch('http://127.0.0.1:8092/admin/api/session', { headers: { 'x-goog-iap-jwt-assertion': forged } });
  ok(f.status === 401, 'direct request with a forged assertion: 401');
  const spoof = await fetch('http://127.0.0.1:8091/admin/api/session', { headers: { 'x-goog-authenticated-user-email': 'accounts.google.com:' + ADMIN } });
  ok(spoof.status === 401, 'the unsigned IAP email header alone is not trusted');

  // 7. storage and logs
  ok(instances.has('8091') && instances.has('8092'), 'both instances served the session');
  const stats = await (await fetch(GCS + '/_fake/stats')).json();
  ok(stats.overwriteAttempts === 0, 'storage: no object was ever overwritten');
  const events = stats.names.filter(x => /^drafts\/d-[0-9a-f]{12}\/events\/\d{6}\.json$/.test(x));
  ok(events.length === 5 && events[4].endsWith('000005.json'), 'storage: one gap-free event log: create, save, test, ready, approved');
  ok(stats.names.every(x => !x.includes('@') && !x.toLowerCase().includes('example-bank')), 'storage: object names carry no emails or file names');
  const logs = ['rsfa-admin-a', 'rsfa-admin-b'].map(c => { // the service logs to stderr: keep both streams
    const r = spawnSync('docker', ['logs', c], { encoding: 'utf8' });
    return r.stdout + r.stderr;
  }).join('\n');
  fs.writeFileSync(path.join(out, 'container-logs.txt'), logs);
  ok(/user=u-[0-9a-f]{8}/.test(logs), 'logs identify users by a short hash');
  for (const s of [ADMIN, APPROVER, VIEWER, IAP_ONLY, 'example-bank-form', 'Jane Example', 'Example Street', 'Example Bank Customer']) {
    ok(!logs.includes(s), 'logs do not contain ' + s);
  }
  ok([A, B, C].every(s => s.page.errors.length === 0), 'no browser errors');
  await browser.close();
  console.log(`PRIVATE ADMIN E2E PASSED (${n} checks)`);
})().catch((e) => { console.error(e.message); process.exit(1); });
