// Verifies the Make Code-module signer against Node's crypto, the documented test vector and edge cases.
// Usage: node tests/make/code_module_sign_test.js
'use strict';
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const assert = require('assert');
const src = fs.readFileSync(path.join(__dirname, '../../make/test-scenario/code-module-sign.js'), 'utf8');
const run = input => new Function('input', src)(input);
const raw = input => run(Object.assign({ raw: true }, input));
const ref = (secret, ts, body) => 'v1=' + crypto.createHmac('sha256', Buffer.from(secret, 'utf8'))
  .update(ts + '.' + body, 'utf8').digest('hex');
let n = 0;
const ok = (c, m) => { assert.ok(c, m); n++; console.log('ok  ', m); };

const vector = JSON.parse(fs.readFileSync(path.join(__dirname, '../../contracts/hmac-test-vector.json'), 'utf8'));
const v = raw({ payload: vector.body, timestamp: vector.timestamp, secret: vector.secret });
ok(v.signature === 'v1=' + vector.expectedSignatureHex, 'documented test vector reproduced');
ok(vector.signingInput === vector.timestamp + '.' + vector.body, 'signing input is timestamp + "." + body');
ok(v.body === vector.body, 'body returned byte-for-byte');

const secret = 'synthetic-relay-secret-0123456789abcdef';
const cases = {
  ascii: '{"contractVersion":"masterboard.v1","data":{"Applicant 1 Full Name":"Jane Example"}}',
  macrons: '{"data":{"Applicant 1 Full Name":"Aroha Tūhoe-Example","City":"Ōtāhuhu"}}',
  emoji: '{"data":{"note":"🏠 synthetic"}}',
  crlf: '{\r\n\t"data": {"x": "y"}\r\n}',
  escaped: '{"data":{"q":"\\"quoted\\" \\\\ slash \\u00e9"}}',
  empty: '{}',
};
for (const [name, body] of Object.entries(cases)) {
  ok(raw({ payload: body, timestamp: '1793152800', secret }).signature === ref(secret, '1793152800', body), `matches crypto: ${name}`);
}
for (let len of [55, 56, 63, 64, 65, 119, 120, 1000, 200000]) {
  const body = JSON.stringify({ pad: 'x'.repeat(Math.max(0, len - 10)) });
  ok(raw({ payload: body, timestamp: '1', secret }).signature === ref(secret, '1', body), `matches crypto: length ${body.length}`);
}
const longKey = 'k'.repeat(100);
ok(raw({ payload: '{}', timestamp: '1', secret: longKey }).signature === ref(longKey, '1', '{}'), 'key longer than block size');
const obj = { contractVersion: 'masterboard.v1', data: { a: 1 } };
const fromObj = raw({ payload: JSON.stringify(obj), timestamp: '1', secret });
ok(fromObj.body === JSON.stringify(obj) && fromObj.signature === ref(secret, '1', fromObj.body), 'raw request signed exactly as given');
const reordered = '{"data":{"a":1},"contractVersion":"masterboard.v1"}';
ok(raw({ payload: reordered, timestamp: '1', secret }).signature !== fromObj.signature, 'any re-serialisation (key order) changes the signature');
assert.throws(() => raw({ payload: '{"a":', timestamp: '1', secret })); n++; console.log('ok   malformed JSON refused inside Make');
assert.throws(() => raw({ payload: '{}', timestamp: '1.5', secret })); n++; console.log('ok   non-integer timestamp refused');
assert.throws(() => raw({ payload: '{}', timestamp: '1', secret: 'short' })); n++; console.log('ok   short secret refused');
// normalisation of synthetic payloads (object or webhook pass-through string)
const t1 = JSON.parse(fs.readFileSync(path.join(__dirname, '../../make/test-scenario/payloads/T1.json'), 'utf8'));
const fromObject = run({ payload: t1, executionId: 'exec1', today: '2026-10-28', timestamp: '1793152800', secret });
const fromString = run({ payload: JSON.stringify(t1), executionId: 'exec1', today: '2026-10-28', timestamp: '1793152800', secret });
ok(fromObject.body === fromString.body && fromObject.signature === fromString.signature, 'webhook object and pass-through string give the same body');
const built = JSON.parse(fromObject.body);
ok(built.contractVersion === 'masterboard.v1' && built.correlationId === 'mb-5026186805-1000000011-exec1'
   && built.idempotencyKey === '1000000011-T1-2026-10-28' && built.selection.banks[0] === 'BNZ'
   && built.options.includeCandidates === true && built.source.itemId === '1000000011', 'contract fields built as documented');
ok(fromObject.signature === ref(secret, '1793152800', fromObject.body), 'signature over the built body');
ok(fromObject.correlationId === built.correlationId && fromObject.idempotencyKey === built.idempotencyKey, 'ids returned for logging');
const retry = run({ payload: t1, executionId: 'exec1', today: '2026-10-28', timestamp: '1793152860', secret });
ok(retry.body === fromObject.body && retry.signature !== fromObject.signature, 'a retry re-signs the same body with a new timestamp');
const comma = run({ payload: Object.assign({}, t1, { selectedBanks: 'BNZ, ASB' }), executionId: 'e', today: '2026-10-28', timestamp: '1', secret });
ok(JSON.stringify(JSON.parse(comma.body).selection.banks) === '["BNZ","ASB"]', 'comma-separated bank labels (Monday text) split');
for (const [bad, why] of [[{ itemId: '1234567890' }, 'non-synthetic item id'], [{ boardId: '1' }, 'other board'],
  [{ testCase: 'X' }, 'bad test case'], [{ data: [] }, 'data not an object'], [{ selectedBanks: [], templates: [] }, 'nothing selected']]) {
  assert.throws(() => run({ payload: Object.assign({}, t1, bad), executionId: 'e', today: '2026-10-28', timestamp: '1', secret }));
  n++; console.log('ok   refused: ' + why);
}
const dir = path.join(__dirname, '../../make/test-scenario/payloads');
for (const f of fs.readdirSync(dir)) {
  const p = JSON.parse(fs.readFileSync(path.join(dir, f), 'utf8'));
  const r = JSON.parse(run({ payload: p, executionId: 'e', today: '2026-10-28', timestamp: '1', secret }).body);
  ok(/^10000000[0-9]{2}$/.test(r.source.itemId) && JSON.stringify(r).indexOf('@') === -1, `payload ${f}: synthetic id, no e-mail-like values`);
}
console.log(`CODE MODULE SIGNER TEST PASSED (${n} checks)`);
