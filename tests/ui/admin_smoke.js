// Browser test of the local administration area (D-038), end to end, with the FICTIONAL "Example Bank" form only.
// Usage (server started with ENGINE_ADMIN=local, a fresh ADMIN_WORKSPACE and a Monday schema export):
//   java -jar engine/target/end-forms-engine.jar example-form --out <out-dir>/example-bank-form.pdf
//   node tests/ui/admin_smoke.js http://127.0.0.1:8095/ <out-dir>
// Uses the machine's Playwright (not a project dependency). Writes numbered screenshots to <out-dir>.
'use strict';
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const base = (process.argv[2] || 'http://127.0.0.1:8095/').replace(/\/$/, '');
const out = process.argv[3] || '.local/output/admin-smoke';
fs.mkdirSync(out, { recursive: true });
const pdf = path.join(out, 'example-bank-form.pdf');
if (!fs.existsSync(pdf)) { console.error('missing ' + pdf + ' (run the example-form command first)'); process.exit(2); }
const notPdf = path.join(out, 'not-a-form.pdf');
fs.writeFileSync(notPdf, 'This is not a PDF.');

let n = 0;
const ok = (cond, msg) => { if (!cond) throw new Error('FAILED: ' + msg); n++; console.log('ok  ', msg); };
const shot = (page, name) => page.screenshot({ path: path.join(out, name + '.png'), fullPage: false });

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  const errors = [];
  // The browser logs every 4xx as a console error; resource failures are checked precisely below instead.
  page.on('console', m => { if (m.type() === 'error' && !m.text().startsWith('Failed to load resource')) errors.push(m.text()); });
  const failed = [];
  page.on('response', r => { if (r.status() >= 400) failed.push(r.request().method() + ' ' + new URL(r.url()).pathname + ' ' + r.status()); });
  page.on('pageerror', e => errors.push(String(e)));
  const external = [];
  page.on('request', r => { if (!r.url().startsWith(base)) external.push(r.url()); });

  // 1. library
  await page.goto(base + '/admin/');
  await page.waitForSelector('[data-testid=library] tbody tr');
  const deployed = await page.locator('[data-testid=library] .chip[data-status=deployed]').count();
  const approved = await page.locator('[data-testid=library] .chip[data-status=approved]').count();
  ok(true, `library lists templates (${await page.locator('[data-testid=library] tbody tr').count()} rows, ${deployed} deployed, ${approved} approved)`);
  const repoRows = await page.locator('tr[data-kind=manifest]').count();
  if (repoRows) {
    ok(deployed === 1, 'exactly one deployed template (BNZ Boarder), from the recorded deployment');
    ok(approved === 0, 'no locally tested form is labelled approved');
  }
  await shot(page, '01-library');

  // 2. upload: refused, then accepted
  await page.click('a[href="#/upload"]');
  await page.setInputFiles('[data-testid=file]', notPdf);
  await page.selectOption('[data-testid=bank]', 'Other');
  await page.fill('[data-testid=template-name]', 'Not a form');
  await page.click('[data-testid=upload]');
  await page.waitForSelector('[data-testid=report][data-accepted=false]');
  ok(await page.locator('.findings li[data-code=NOT_A_PDF]').count() === 1, 'a non-PDF is refused with NOT_A_PDF and nothing is stored');
  await shot(page, '02-upload-refused');
  await page.setInputFiles('[data-testid=file]', pdf);
  await page.fill('[data-testid=template-name]', 'Example Bank Customer Questionnaire');
  await page.fill('[data-testid=template-version]', '2026-10');
  await page.click('[data-testid=upload]');
  await page.waitForSelector('[data-testid=report][data-accepted=true]');
  ok(true, 'the fictional blank form is accepted');
  await shot(page, '03-upload-accepted');

  // 3. create draft
  await page.click('[data-testid=create-draft]');
  await page.fill('[data-testid=draft-template-id]', 'EXAMPLE_BANK_QUESTIONNAIRE');
  await page.click('[data-testid=modal-ok]');
  await page.waitForSelector('[data-testid=page] img');
  await page.waitForFunction(() => document.querySelector('[data-testid=page] img').complete);
  ok(await page.locator('.box').count() >= 13, 'field boxes are drawn over the page');
  ok(await page.locator('.box.protected').count() === 1, 'the signature field is marked as never filled');

  // 4. inspect a field and map it manually
  await page.click('.box[data-field=CustName]');
  await page.waitForSelector('[data-testid=inspector][data-field=CustName]');
  ok((await page.textContent('[data-testid=inspector]')).includes('Customer full name'), 'inspector shows the printed label');
  await page.click('[data-testid=add-mapping]');
  await page.fill('[data-testid=column-search]', 'Applicant 1 Full Name');
  await page.click('[data-testid=column-list] div[data-column]');
  ok((await page.textContent('[data-testid=inspector]')).includes('Applicant 1 Full Name'), 'a Monday column is chosen by its stable ID');
  await shot(page, '04-inspector-mapping');

  // 5. protected and declaration fields
  await page.click('.box[data-field=CustSignature]');
  ok((await page.textContent('[data-testid=inspector]')).includes('never filled'), 'signature: cannot be mapped');
  await page.click('.box[data-field=DeclTick]');
  ok((await page.textContent('[data-testid=inspector]')).includes('declaration'), 'declaration box: warning shown');
  await shot(page, '05-declaration-warning');

  // 6. mock suggestions
  await page.click('[data-tab=suggestions]');
  await page.click('[data-testid=suggest]');
  await page.waitForSelector('[data-testid=mock-banner]');
  ok((await page.textContent('[data-testid=mock-banner]')).includes('not an AI model'), 'suggestions are labelled MOCK, not AI');
  ok(await page.locator('[data-testid=proposal][data-field=CustSignature]').count() === 0, 'no suggestion for the signature');
  ok(await page.locator('[data-testid=proposal][data-field=DeclTick]').count() === 0, 'no suggestion for the declaration');
  await shot(page, '06-suggestions-mock');
  for (const field of ['PostalAddr', 'LoanAmt', 'RepayFreq']) {
    const card = page.locator(`[data-testid=proposal][data-field=${field}] [data-testid=accept]`);
    if (await card.count()) await card.click();
  }
  ok(await page.locator('[data-testid=save]:not([disabled])').count() === 1, 'accepting suggestions only edits the unsaved draft');

  // 7. save and check problems
  await page.click('[data-testid=save]');
  await page.waitForSelector('[data-testid=save][disabled]');
  ok((await page.textContent('h1 + .muted, .mono.muted')).includes('version 2'), 'saved as version 2');
  await page.click('[data-tab=problems]');
  await shot(page, '07-saved-problems');

  // 8. synthetic test generation
  await page.click('[data-tab=test]');
  await page.click('[data-testid=synthetic]');
  await page.click('[data-testid=generate]');
  await page.waitForSelector('[data-testid=test-result]');
  ok(await page.getAttribute('[data-testid=test-result]', 'data-ok') === 'true', 'test PDF generated from fictional values');
  await page.waitForSelector('[data-testid=compare] img');
  await page.waitForFunction(() => [...document.querySelectorAll('[data-testid=compare] img')].every(i => i.complete && i.naturalWidth > 0));
  ok(await page.locator('[data-testid=compare] img').count() === 2, 'blank template and generated page shown side by side');
  const href = await page.getAttribute('[data-testid=open-pdf]', 'href');
  const res = await page.request.get(base + href);
  ok(res.status() === 200 && (await res.body()).subarray(0, 5).toString() === '%PDF-', 'the generated PDF downloads');
  await page.locator('[data-testid=compare]').scrollIntoViewIfNeeded();
  await shot(page, '08-test-compare');

  // 9. review and approval record
  await page.click('[data-testid=ready]');
  await page.waitForSelector('[data-testid=approve]');
  await page.click('[data-testid=approve]');
  await page.click('[data-testid=modal-ok]');
  ok(await page.locator('h1 .chip[data-status=ready-for-review]').count() === 1, 'approval needs the confirmation box (not recorded)');
  await page.fill('[data-testid=approved-by]', 'Test Reviewer (synthetic)');
  await page.fill('[data-testid=approval-ref]', 'TEST-ADMIN-SMOKE');
  await page.check('[data-testid=approval-confirm]');
  await page.click('[data-testid=modal-ok]');
  await page.waitForSelector('h1 .chip[data-status=approved]');
  ok(true, 'approval recorded for this version (local record, not a deployment)');
  ok(await page.locator('button[title*="Publishing is not available"][disabled]').count() === 1, 'publish is not available');
  await page.click('[data-tab=history]');
  ok(await page.locator('[data-testid=audit] li').count() >= 5, 'audit trail lists create, save, test and status changes');
  await shot(page, '09-approved-history');

  // 10. library shows the draft
  await page.click('a[href="#/library"]');
  await page.waitForSelector('tr[data-kind=draft]');
  ok(await page.locator('tr[data-kind=draft] .chip[data-status=approved]').count() >= 1, 'library shows the locally approved draft, "not deployed"');
  await shot(page, '10-library-after');

  ok(external.length === 0, 'no request left the local server (no CDN, no AI provider)');
  ok(failed.length === 1 && failed[0] === 'POST /admin/api/templates 422',
    'the only failed request is the refused upload' + (failed.length ? ' (' + failed.join(' | ') + ')' : ''));
  ok(errors.length === 0, 'no browser console errors' + (errors.length ? ': ' + errors.join(' | ') : ''));
  await browser.close();
  console.log(`ADMIN SMOKE TEST PASSED (${n} checks)`);
})().catch(async e => { console.error(e.message); process.exit(1); });
