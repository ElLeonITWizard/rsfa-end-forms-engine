# make/ — Make.com material prepared in this repository (never applied automatically)

Nothing here is deployed to Make by this repository. Production scenarios `8731317`, `9254149` and `8732008` are
changed only by RSFA, after explicit approval of an exact change and rollback (docs/09).

| Path | What it is |
|---|---|
| `8731317/module8-banks-alias.snippet.js` | **Proposed, not applied** compatibility fix for the `Banks to Send` → `Selected Banks` rename (docs/09). Tested by `tests/make/module8_banks_alias_test.js` against the production code kept in `.local/` (not committed: Make blueprints are never committed) |
| `test-scenario/code-module-sign.js` | Code module for the isolated test scenario: builds the `masterboard.v1` body from a synthetic payload and signs it (HMAC-SHA256 in plain JavaScript, no external code). Tests: `tests/make/code_module_sign_test.js` |
| `test-scenario/payloads/T*.json` | Fictional webhook payloads for test cases T1–T12 (docs/11 §5); item IDs in the synthetic range `10000000xx` |

Local checks (Node 18+):

```bash
node tests/make/module8_banks_alias_test.js          # skipped without .local/reference/make/8731317-module8-code.js
node tests/make/code_module_sign_test.js
RELAY_HMAC_SECRET=... node tests/make/run_payloads_against_relay.js http://127.0.0.1:18203   # with a local relay chain
```
