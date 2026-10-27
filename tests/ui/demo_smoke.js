// Browser smoke test for the demo UI (synthetic data only). Uses the Playwright + Chromium already installed on the
// machine; it is not a project dependency. Usage:
//   node tests/ui/demo_smoke.js <base-url> <out-dir>
// e.g. node tests/ui/demo_smoke.js http://127.0.0.1:8080/ .local/output/ui-smoke
// Steps: open the page, check the engine status, Load Sample Data, edit Applicant 1 and the street, Generate PDF,
// download it, verify it with tests/ui/check_demo_pdf.py (edited values present, no ticks, no signatures), then check
// that an invalid value produces a visible, useful error. Exits non-zero on any failure.
const { chromium } = require('playwright');
const { execFileSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const base = process.argv[2] || 'http://127.0.0.1:8080/';
const out = process.argv[3] || '.local/output/ui-smoke';
// SHA-256 of the PDF for tests/fixtures/bnz-boarder.request.json (docs/02): the UI must produce the same bytes as the CLI.
const FIXTURE_SHA = '2ae7bfacf48b8ee8e63aaf9ce28997650abd2c07b59ddbeade9928ce3269d6f6';
const EDITED_NAME = 'Aroha Tēst-Example';
const EDITED_STREET = '34 Demo Road, Sampleton';

function check(cond, msg) {
  if (!cond) throw new Error('FAILED: ' + msg);
  console.log('ok   ' + msg);
}

(async () => {
  fs.mkdirSync(out, { recursive: true });
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1366, height: 900 }, acceptDownloads: true });
  const consoleErrors = [];
  // The browser logs every non-2xx response; the deliberate 422 in the error-path step is expected.
  page.on('console', (m) => { if (m.type() === 'error' && !/status of 422/.test(m.text())) consoleErrors.push(m.text()); });
  page.on('pageerror', (e) => consoleErrors.push(String(e)));
  try {
    await page.goto(base);
    check((await page.title()) === 'RSFA End Forms Engine', 'page title');
    await page.waitForSelector('#engine-status.ok', { timeout: 10000 });
    check((await page.textContent('#engine-status-text')) === 'Engine online · 1 form ready',
      'engine status counts usable forms offered by the page (1)');
    check((await page.textContent('#template option')).includes('BNZ — Boarder Income Declaration'), 'template selector');
    await page.screenshot({ path: path.join(out, '1-empty.png'), fullPage: true });

    await page.click('#load-sample');
    await page.click('#generate');
    await page.waitForSelector('#download:not([hidden])', { timeout: 30000 });
    check((await page.textContent('#p-sha')) === FIXTURE_SHA, 'unedited sample: identical bytes to the CLI fixture PDF (deterministic)');

    const a1 = page.locator('input[data-key="Applicant 1 Full Name"]');
    check((await a1.inputValue()) === 'Jane Example', 'sample data loaded');
    check((await page.inputValue('#today')) === '2026-10-27', 'sample date loaded');
    await a1.fill(EDITED_NAME);
    await page.locator('input[data-key="1st Property Address Street"]').fill(EDITED_STREET);
    check(await a1.evaluate((e) => e.classList.contains('changed')), 'edited field highlighted');

    await page.click('#generate');
    await page.waitForSelector('#download:not([hidden])', { timeout: 30000 });
    check(/PDF generated/.test(await page.textContent('#message')), 'success message');
    check((await page.textContent('#p-engine')) === 'Deterministic mapping engine — not AI', 'provenance: deterministic, not AI');
    check((await page.textContent('#p-template')) === 'BNZ_BOARDER_FORM@2.0_8-24', 'provenance: template');
    check((await page.textContent('#p-filled')) === '9', 'provenance: 9 fields filled');
    check(/^[0-9a-f]{64}$/.test(await page.textContent('#p-sha')), 'provenance: SHA-256 shown');
    check(!(await page.locator('#pdf-frame').isHidden()), 'preview frame shown');
    await page.waitForTimeout(800);
    await page.screenshot({ path: path.join(out, '2-generated.png'), fullPage: true });

    const [download] = await Promise.all([page.waitForEvent('download'), page.click('#download')]);
    const pdf = path.join(out, 'demo.pdf');
    await download.saveAs(pdf);
    check(/^BNZ_BOARDER_FORM_demo-.*\.pdf$/.test(download.suggestedFilename()), 'download filename');
    const shaShown = await page.textContent('#p-sha');
    const report = JSON.parse(execFileSync('python3', [path.join(__dirname, 'check_demo_pdf.py'), pdf,
      '--expect', EDITED_NAME, '--expect', EDITED_STREET, '--sha', shaShown]).toString());
    console.log(JSON.stringify(report));
    check(report.ok, 'downloaded PDF: edited values present, no ticks, no signatures, SHA matches page');

    // Error path: an emoji cannot be printed by the form font, so the engine must refuse with a useful message.
    await a1.fill('Jane 🙂 Example');
    await page.click('#generate');
    await page.waitForSelector('#message.error', { timeout: 30000 });
    const msg = await page.textContent('#message');
    check(/VALIDATION_FAILED/.test(msg) && /Applicant 1/.test(msg) && /UNENCODABLE_TEXT/.test(msg), 'error names field and code');
    check(!msg.includes('🙂'), 'error does not echo the value');
    check(await page.locator('#download').isHidden(), 'no download offered after an error');
    await page.screenshot({ path: path.join(out, '3-error.png'), fullPage: true });

    // Mobile layout renders without horizontal scrolling.
    await page.setViewportSize({ width: 390, height: 844 });
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth + 1);
    check(!overflow, 'no horizontal scroll at 390px');
    await page.screenshot({ path: path.join(out, '4-mobile.png'), fullPage: true });
    check(consoleErrors.length === 0, 'no browser console errors' + (consoleErrors.length ? ': ' + consoleErrors.join(' | ') : ''));
    console.log('UI SMOKE TEST PASSED');
  } catch (e) {
    console.error(e.message);
    await page.screenshot({ path: path.join(out, 'failure.png'), fullPage: true }).catch(() => {});
    process.exitCode = 1;
  } finally {
    await browser.close();
  }
})();
