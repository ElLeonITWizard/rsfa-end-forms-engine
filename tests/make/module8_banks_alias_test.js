// Reproduces the 8731317 module-8 key behaviour with SYNTHETIC column values, with and without the proposed alias.
// The production code is read from .local (never committed); the test is skipped when it is absent.
// Usage: node tests/make/module8_banks_alias_test.js [path-to-module8.js]
'use strict';
const fs = require('fs');
const path = require('path');
const assert = require('assert');

const root = path.resolve(__dirname, '..', '..');
const original = process.argv[2] || path.join(root, '.local/reference/make/8731317-module8-code.js');
if (!fs.existsSync(original)) { console.log('SKIPPED: production module-8 code not in .local'); process.exit(0); }
const code = fs.readFileSync(original, 'utf8');
const snippet = fs.readFileSync(path.join(root, 'make/8731317/module8-banks-alias.snippet.js'), 'utf8');
const ANCHOR = 'const result = mapColumns(input.column_values);';
assert.strictEqual(code.split(ANCHOR).length, 2, 'anchor must occur exactly once');
const patched = code.replace(ANCHOR, ANCHOR + '\n' + snippet);
const run = (src, input) => new Function('input', src)(JSON.parse(JSON.stringify(input)));

// Synthetic item: fictional values only. Titles as they are live since 2026-10-25.
const col = (id, title, text, value) => ({ id, title, text, value: value === undefined ? null : value });
function item(bankTitle, banks) {
  return {
    column_values: [
      col('dropdown_mkzvfgeg', bankTitle, banks, banks ? JSON.stringify({ ids: [1] }) : null),
      col('text_example1', 'Applicant 1 Full Name', 'Jane Example'),
      col('date_example1', 'Settlement Date', '2026-11-30', JSON.stringify({ date: '2026-11-30' })),
      col('boolean_example1', 'Capitalise to loan (Yes/No)', '', JSON.stringify({ checked: true })),
    ],
    subitems: [],
    DateFieldsToSplit: 'Settlement Date',
    split_addresses_json: '{}',
  };
}
const contains = (r, bank) => typeof r['Banks to Send'] === 'string' && r['Banks to Send'].includes(bank);
let checks = 0;
const ok = (cond, msg) => { assert.ok(cond, msg); checks++; console.log('ok  ', msg); };

// 1. Reproduction: live title -> MacroForge's key is absent.
const live = run(code, item('Selected Banks', 'ASB, BNZ'));
ok(!('Banks to Send' in live) && live['Selected Banks'] === 'ASB, BNZ',
   'production code + live title: "Banks to Send" absent, so every "Banks to Send Contains <bank>" condition has no field');
// 2. Before the rename the key existed (historical behaviour).
const before = run(code, item('Banks to Send', 'ASB, BNZ'));
ok(contains(before, 'ASB') && contains(before, 'BNZ'), 'production code + old title: key present (pre-2026-10-25 behaviour)');
// 3. Patched: alias restored from the column ID, every bank label.
for (const bank of ['ASB', 'ANZ', 'BNZ', 'KiwiBank', 'WBC']) {
  const r = run(patched, item('Selected Banks', bank));
  ok(r['Banks to Send'] === bank && r['Selected Banks'] === bank, `patched: "${bank}" visible under both keys`);
}
const multi = run(patched, item('Selected Banks', 'ANZ, KiwiBank, WBC'));
ok(['ANZ', 'KiwiBank', 'WBC'].every(b => contains(multi, b)) && !contains(multi, 'BNZ'), 'patched: multi-selection kept; unselected bank not matched');
// 4. Additive only: every other key and value identical.
const base = run(code, item('Selected Banks', 'ASB'));
const pat = run(patched, item('Selected Banks', 'ASB'));
delete pat['Banks to Send'];
ok(JSON.stringify(base) === JSON.stringify(pat), 'patched: payload otherwise byte-identical (dates, booleans, split dates, subitems)');
// 5. Empty selection: alias empty, as before the rename.
const empty = run(patched, item('Selected Banks', ''));
const emptyBefore = run(code, item('Banks to Send', ''));
ok(empty['Banks to Send'] === emptyBefore['Banks to Send'], 'patched: empty selection gives the same value as before the rename');
// 6. Idempotent if the column is renamed back: no override.
const back = run(patched, item('Banks to Send', 'WBC'));
ok(back['Banks to Send'] === 'WBC' && !('Selected Banks' in back), 'patched + title renamed back: unchanged behaviour');
// 7. Column missing (deleted): no alias, no error.
const missing = run(patched, { column_values: [], subitems: [], DateFieldsToSplit: '', split_addresses_json: '' });
ok(!('Banks to Send' in missing), 'patched + column absent: no key, no exception');
console.log(`MODULE 8 ALIAS TEST PASSED (${checks} checks)`);
