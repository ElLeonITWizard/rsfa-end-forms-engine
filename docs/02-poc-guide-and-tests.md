# 02 — POC guide, manifest schema and tests

## End-to-end POC steps

See the README quickstart. In summary:
1. Copy the `.mforge` to `.local/reference/`.
2. Run `import_mforge.py extract`.
3. Run `mvn package` (runs all tests).
4. Run `fill` with a fixture from `tests/fixtures/`.
5. Render with `pdftoppm` or the engine's `render` command and look at the output.

To re-import a manifest after the legacy export changes, run `import_mforge.py manifest … --strict --date <YYYY-MM-DD>`
and review the diff. Every `reviewRequired` entry needs a human decision before production.

## BNZ Boarder (acceptance template)

| Property | Result | Evidence |
|---|---|---|
| Identity | Flow C node 15 (`Banks to Send Contains BNZ`), SHA-256 `7982ae8c…0509795`, 49,354 bytes; the external copy was not supplied | importer |
| Structure | 1 page A4, AcroForm, no XFA, not encrypted, 8 text fields (`Text 1`–`Text 6`, `Date 1`, `Date 2`) | pypdf + PDFBox |
| Fonts | field DA uses an embedded `SerranoPro-Regular` subset (Type1C, MacRoman): ASCII works; `Ō`/`ā` need the Unicode fallback | experiment + test |
| Dates | `Date 1`/`Date 2`: comb, MaxLen 6, Acrobat `AFDate_FormatEx("ddmmyy")`, so `ddMMyy` | PDF |
| Mapping | 5 source keys + 2 `[TODAY]` + **2 overlays** `Applicant 1/2 Full Name` (legacy rects `[121,160,395,182]` and `[398,161,671,182]` → PDF `[100.83,690.22,329.17,708.56]` and `[331.67,690.22,559.17,707.72]`) | `.mforge`, transform evidence in docs/03 |
| Customer name(s) | Filled by the 2 overlays exactly as the legacy config does. `Text 1` stays unmapped, as in the legacy config. Its widget has no background or appearance, so the overlay text stays visible | `.mforge` + PDF |

## Second and third templates

- **GHL N&P Trust** (user-supplied, SHA-identical to the embedded copy):
  - AES-128 owner-encrypted, fill-in permitted, preserved via incremental save.
  - Checkbox fields with several widgets, each with its own on-state.
- **BNZ Handover** (embedded) is the **LineItems** demonstration: 6 repeating loan-facility rows. 36 line-item rules are imported:
  - per row: `BNZ product` (combo), `Loan amount`, `Interest rate`, `Repayment frequency` (combo),
    `Main purpose for each facility` (combo), `First repayment date`;
  - 18 more (`Loan term`, `Fixed term`, `Interest only period`) are unresolved name conflicts (docs/03).

## Additional forms (iteration 6)

ASB Boarder, ANZ Boarder, GHL N&P Individual (candidates) and GHL N&P Trust (blocked):
- selection and open questions: docs/06;
- per-form coverage: `docs/generated/template-coverage.md`;
- fixtures: `tests/fixtures/{asb-boarder,anz-boarder,ghl-np-individual,ghl-np-trust-live-labels}.request.json`.

## Manifest schema (v1)

```jsonc
{
  "schemaVersion": 1,
  "templateId": "BNZ_BOARDER_FORM", "templateVersion": "2.0_8-24",
  "templateFile": "7982ae8cbda9__BNZ_BoarderForm_2.0_8_24.pdf",   // looked up in template dirs
  "templateSha256": "7982ae8c…",                                  // must match the bytes, else TEMPLATE_HASH_MISMATCH
  "encryption": "reject" | "preserve",                            // preserve = incremental save, original encryption kept
  "sourceKeyNormalization": "none" | "macroforge-v1",             // '/' and '.' in top-level data keys -> '_'
  "fontFallback": "none" | "unicode",                             // engine default none; importer writes unicode
  "provenance": { … },  "pdf": { …field inventory… },            // informational
  "fields": [
    {"pdfField": "Text 2", "kind": "text", "source": {"key": "1st Property Address Street"}, "required": false},
    {"pdfField": "Date 1", "kind": "text", "source": {"generated": "today", "format": "ddMMyy", "zone": "Pacific/Auckland"}},
    {"pdfField": "Check Box95", "kind": "checkbox", "option": "3",
     "source": {"when": {"key": "Total credits or deposits (next 12 months) (GHL Trust)", "operator": "equals", "value": "$1M-$5M"}}},
    {"pdfField": "Some box", "kind": "checkbox", "source": {"constant": true},
     "approval": {"approvedBy": "<name/role>", "approvedOn": "YYYY-MM-DD", "reference": "<ticket/decision>"}},
    {"pdfField": "Loan Amount 2", "kind": "text",
     "source": {"lineItem": {"array": "Subitems", "position": 2, "path": ["values", "Loan amount"]}}}
  ],
  "overlays": [
    {"id": "custom_text_1_1777387659", "page": 0, "rect": [100.83, 690.22, 329.17, 708.56],   // PDF pt, bottom-left
     "source": {"key": "Applicant 1 Full Name"}, "required": false, "fontSize": 10}            // fontSize optional (max)
  ],
  "unsupported": [ … ], "unresolvedLegacyMappings": [ … ], "inertLegacyMappings": [ … ],
  "importErrors": [ … ], "importWarnings": [ … ],
  // optional, merged from curation/<ID>@<version>.json by the importer (D-023):
  "validation": {
    "closedOptions": [{"key": "Boarders - Board amount 1 frequency", "multiSelect": false, "onUnmapped": "error"}],
    "requires": [{"id": "ANZ-BOARDER-WEEKLY-1", "ifPresent": "Boarders - Board amount 1",
                  "require": {"key": "Boarders - Board amount 1 frequency", "operator": "equals", "value": "Weekly"},
                  "reason": "the ANZ form only has a 'Weekly board amount' box"}]},
  "review": {"status": "candidate", "openQuestions": ["…"]}       // informational; never an approval
}
```

Condition operators: `equals`, `contains` (legacy), `present` (engine, `validation.requires` only).
Validation codes: `UNMAPPED_OPTION`, `UNMAPPED_OPTION_LEFT_BLANK`, `MULTI_SELECT_NOT_REPRESENTABLE`, `PRECONDITION_FAILED`.

Kinds: `text`, `checkbox`, `radio`, `combo`, `list`. Sources: `key` | `constant` | `generated:"today"` | `when` | `lineItem`.

## Engine behaviour

| Case | Behaviour |
|---|---|
| Text from key/constant/today/lineItem | `setValue` + PDFBox appearance generation; MaxLen enforced (no truncation); line breaks rejected in single-line fields |
| Number value | rendered as plain decimal |
| Boolean/array/object value into text | `UNSUPPORTED_VALUE_TYPE` / `AMBIGUOUS_SOURCE_VALUE` error |
| Missing value | required → `MISSING_REQUIRED_VALUE`; optional → skipped; key absent → also `SOURCE_KEY_ABSENT` warning |
| **LineItems** | element `position` (1-based) of the array, then `path` inside it. Fewer rows than the form → skipped quietly. Array absent / column absent in a row → `SOURCE_KEY_ABSENT`. Non-array → error |
| Checkbox/radio `when` | exact (`equals`) / substring (`contains`), case-sensitive. A non-match leaves the field untouched. More than one selected option → `CONFLICTING_SELECTION` |
| Checkbox constant `false` | forces Off; works on multi-state fields without an option (legacy field-level `unchecked` default) |
| **Checkbox constant `true`** | applied **only with a valid `approval` record** (approvedBy, past/present ISO `approvedOn`, reference). Absent → left Off with `CONSTANT_CHECK_NOT_APPROVED`. Malformed, or `approved: true` alone → `INVALID_APPROVAL` error (D-016) |
| **Template preset buttons** | any checkbox/radio already on in the blank template and not selected or forced Off by a rule of this fill is reset to Off (`TEMPLATE_PRESET_CLEARED`), e.g. BNZ Handover `Confirmation` (D-016) |
| **Comb fields** (`DD\|MM\|YY` boxes) | engine-drawn appearance: each character centred in its cell. Printed placeholder letters are knocked out in filled cells using the background measured from the blank template. A non-uniform background means no knockout and `COMB_BACKGROUND_NOT_UNIFORM`. The value is never changed (D-017) |
| Combo/list | value must be an export or display option, else `INVALID_OPTION` |
| Signature / push button target | always `FORBIDDEN_TARGET` |
| **Overlays** | single line drawn into the page content in **embedded Liberation Sans** at `rect`: size min(maxSize 10, 70% of the rect height), shrunk to fit the width, 2pt left padding, vertically centred. Under 6pt → `OVERLAY_TEXT_TOO_LONG`. Rect outside the page → `OVERLAY_OUT_OF_PAGE`. A widget with a background colour under it → `OVERLAY_UNDER_OPAQUE_WIDGET` warning. Encrypted template → `OVERLAY_ON_ENCRYPTED_TEMPLATE` (not verified yet). Rotated page → error |
| **Unicode in fields** | each value is checked against the field's own font first. If it cannot be encoded: `fontFallback=none` → `UNENCODABLE_TEXT`; `unicode` → the field's DA is pointed at the fully embedded Liberation Sans (`FONT_FALLBACK` warning). Characters are never dropped |
| Encrypted template | `reject` → `ENCRYPTED_TEMPLATE`; `preserve` → requires fill-in permission, incremental save |
| XFA / no AcroForm | `XFA_PRESENT` / `NO_ACROFORM` |
| Any ERROR | no PDF is produced (strict) |
| Output | new bytes; template never modified; editable (not flattened); JavaScript format actions are not run |
| Determinism | same input → identical bytes for unencrypted templates (host and container, verified). Encrypted (incremental) outputs differ in bytes on every run because AES uses a random IV, but field values are identical |

**Not supported (legacy rule types):** `line_item_condition` without a row index (18) and multi-condition `conditional` (1).
Unresolved items (138) are listed per manifest in `unresolvedLegacyMappings`.

## Acceptance criteria

| # | Criterion | Status |
|---|---|---|
| 1 | Existing lender PDF filled from an **imported** mapping with fictional data | **Passed**: BNZ Boarder |
| 2 | **Complete BNZ Boarder incl. customer name(s)**, deterministic | **Passed**: 7 fields + 2 overlays. The overlay text is asserted by region extraction. Bytes are identical across runs and host/container |
| 3 | Māori macrons in overlays and fields | **Passed**: `Aroha Tūhoe-Example`, `Mere Tāwhiri-Sample`, `Ōtāhuhu` (host + container render) |
| 4 | Repeated LineItems example | **Passed**: BNZ Handover, 3 facilities into rows 1–3, rows 4–6 empty |
| 4a | No declaration certified without a validated approval | **Passed**: BNZ Handover `Confirmation` (pre-ticked in the template) is cleared; constant ticks need a validated approval |
| 4b | Comb dates readable (BNZ Handover `Date 3`, BNZ Boarder `Date 1/2`) | **Passed**: centred digits, placeholders hidden, values unchanged |
| 5 | Checkbox template | **Passed**: GHL N&P Trust. Radio only on the synthetic template |
| 6 | Docker image runs the engine with required fonts | **Passed** with caveats (see below) |
| 7 | Same output as MacroForge for the same input | **Not testable**: no MacroForge output is available. Overlay font/size are engine policy |

## Repeatable tests

| Suite | Command | Count |
|---|---|---|
| Importer (synthetic .mforge, incl. `--curation` and **corrections**) + deployment staging + coverage report + schema drift + contract examples vs JSON Schema | `python3 -m unittest discover -s tests/importer -v` | 14 + 7 + 1 + 1 + 2 |
| Engine unit (synthetic AcroForm): widget kinds, errors, normalisation, line items, overlays, fonts, approval validation, template presets, comb appearance, rendering, no value echo, closed options and preconditions, **`includes`/`absent` operators, `when` requirements** | `cd engine && mvn -B test` | 32 |
| HTTP API (PLATFORM_IAM only with `K_SERVICE`, `X-Engine`/`X-Template`, demo page allowlist, CSP and auth, `/health` usable templates) | (same) | 9 |
| Make contract `/v1/masterboard/documents` (several forms per record, determinism, all-or-nothing/per-document, bank routing + `notMigrated`, invalid contracts, HMAC) | (same) | 5 |
| Relay (`ENGINE_ROLE=relay`): signed request → IAM stand-in → engine; rotation; tampered/stale/replayed/unsigned; rate limit; wrong identity; size; unsafe config | (same) | 4 |
| Administration area (iteration 10): upload validation (non-PDF, filled values, scanned/flattened, encrypted, signed), inspector (kinds, options, geometry, labels, protected), compiler refusals, versioned conflict-safe audited drafts, approval gates, synthetic test generation (never signs or declares, deterministic), mock suggestions, local-only guard | (same) | 7 |
| Relay hardening (iteration 8, 9): shared replay store across instances and under concurrency, fail-closed store, malformed JSON and signature headers, future/stale timestamps, timeouts, engine/identity unavailable, 422/partial pass-through, logs without data, Cloud Run store guard, shared test vector | (same) | 13 |
| Demo page forms = manifest keys; samples = fixtures. **Skipped without `mappings/`** | (same) | 1 |
| Legacy templates: BNZ Boarder (+ macrons), BNZ Handover, the DDMMYYYY gap, GHL Trust (2 fixtures, **in/out separation, contradictory transfers**), ASB Boarder, ANZ Boarder, **GHL Individual (reconciled labels, section 5, refusals), boarder amount guards**, no unruled ticks, manifest resolution. **Skipped without `.local` templates**. Iteration 8: ANZ inclusive "Yes" refused, GHL missing names refused, GHL Trust unknown/ambiguous selections | (same) | 17 |
| Demo page in a real browser (needs a running server and the machine's Playwright) | `node tests/ui/demo_smoke.js <base-url> <out-dir> <forms>` | 27 checks with 5 forms; 21 with 1 |
| Administration area in a real browser (fictional Example Bank form; library, refused/accepted upload, inspector, column mapping, signature/declaration guards, MOCK suggestions, save, test PDF + comparison, approval gate, audit, no external requests) | `node tests/ui/admin_smoke.js <base-url> <out-dir>` | 27 checks |
| Make material (Node): proposed `8731317` module-8 alias against the production code in `.local` (skipped without it); Code-module signer | `node tests/make/module8_banks_alias_test.js`; `node tests/make/code_module_sign_test.js` | 12; 44 |
| Make test payloads → signer → relay → IAM stand-in → engine (needs the local chain running) | `RELAY_HMAC_SECRET=… node tests/make/run_payloads_against_relay.js <relay-url>` | 12 |

Last full run (2026-10-28, iteration 10):
- Host: **113 passed** (25 Python + 88 Java, none skipped); Node: 12 + 44 checks; demo browser test 27 checks (5 forms);
  admin browser test 27 checks.
- BNZ Boarder regression: SHA-256 still `2ae7bfac…` (CLI). `/admin/` returns 404 without `ENGINE_ADMIN=local`.
- Two relay tests that streamed a large body against an early 413/401 refusal were intermittent (connection reset);
  they now send headers only (D-038). Relay tests: 6 consecutive runs with 0 failures.

Iteration 8 run (2026-10-28):
- Host: **102 passed** (25 Python + 77 Java, none skipped); Node: 12 + 44 checks; local Make-payload chain 12/12;
  browser smoke test 27 checks.
- BNZ Boarder regression: SHA-256 still `2ae7bfac…` for `today = 2026-10-27` (CLI). Through the relay with the contract
  example (`today = 2026-10-28`): `e08a79e0…`, identical to the fixture filled with that date.

Iteration 7 run (2026-10-28):
- Host: **90 passed** (25 Python + 65 Java, none skipped).
- BNZ Boarder regression: SHA-256 still `2ae7bfac…` (CLI).
- Browser smoke test: 27 checks passed with 5 forms (status suffixes shown; GHL contradictory cash answers and ANZ
  fortnightly amount refused with the rule id).
- Relay chain with the real jar and templates: see docs/07 section 3.

Previous run (2026-10-27, iteration 6):
- Host: **76 passed** (20 Python + 56 Java, none skipped).
- BNZ Boarder regression: the PDF SHA-256 is still `2ae7bfac…3269d6f6` (CLI and UI).
- Browser smoke test passed:
  - locally with 5 forms (each sample generated and checked; `UNMAPPED_OPTION` and `PRECONDITION_FAILED` refusals shown);
  - on a server built from the staged (allowlisted) context with 1 form.
- The POC image was built locally from the staged (allowlisted) context, with the tests passing inside the build. It was **not** pushed or deployed.
- In that container, `/health` lists BNZ only, and `/v1/masterboard/documents` with `request.bank-bnz.json` returns the BNZ PDF with SHA-256 `2ae7bfac…`. The log line holds no data.

## Docker verification (2026-10-27)

| Check | Result |
|---|---|
| `runtime-prebuilt` image (host-built jar) | built; runs as uid 10001; Temurin 21 JRE; fontconfig + URW base-35 (D050000L Dingbats) |
| Full default build (Maven inside Docker, tests inside) | **passed with the official images**: since iteration 3, base images are pulled via `mirror.gcr.io` and pinned by digest. In iteration 2, a stand-in was needed (Docker Hub rate limit) |
| Fill in the container (4 fixtures, read-only `/templates` mount) | all OK; 3 of 4 outputs **byte-identical** to the host. GHL differs only in the encrypted appended revision (random AES IV) |
| Render in the container (PDFBox) | macrons in overlays and fallback field correct; bank ZapfDingbats ✔ renders via URW |
| HTTP in the container | `$PORT` honoured; `/health` 200; `/generate` 200 PDF; 401 without key; `NONE` on 0.0.0.0 refuses to start; `PLATFORM_IAM` starts; 0 data values in logs |
| Proxy / CA handling | optional BuildKit secret `build_ca` + `APT_MIRROR`/`MAVEN_EXTRA_OPTS` build args; no CA or secret left in the image. Iteration 5: apt reads a temporary 0644 copy of the CA (D-021) |
| Demo page in the POC image (iteration 5) | `/` served with CSP; browser smoke test passed; generated PDF byte-identical to the host fixture output |

Reproduce:
```bash
cd engine && mvn -B package && cd ..
docker build --network host -f engine/Dockerfile --target runtime-prebuilt -t end-forms-engine:prebuilt .
docker run --rm -v "$PWD/.local/generated/templates:/templates:ro" -v "$PWD/tests/fixtures:/fixtures:ro" \
  -v "$PWD/.local/output:/out" end-forms-engine:prebuilt fill \
  --manifest "/app/mappings/bnz/BNZ_BOARDER_FORM@2.0_8-24.json" --templates /templates \
  --data /fixtures/bnz-boarder-macrons.request.json --out /out/macrons.pdf --today 2026-10-27
docker build -f engine/Dockerfile -t end-forms-engine:dev .     # full build, where Docker Hub is reachable
```

## Known gaps / next work

1. Overlay transform (1.2) and engine font policy: compare with one real MacroForge output (any template with overlays).
2. LineItems name conflict (`… in months/years` vs `… months/years`): confirm which names MacroForge produces today.
3. Decide on a reviewed transform for Make's `DDMMYYYY` subitem dates into 6-cell `DDMMYY` comb fields (BNZ Handover).
4. Approve or reject the 36 constant ticks: record an `approval` per rule, or leave them unapplied.
5. Confirm the legacy `[TODAY]` format and the `equals`/`contains` semantics in a parallel run.
6. Verify that the Monday labels match the PDF combo options exactly (BNZ product and purpose). For checkbox and radio
   conditions this is now checked against the live board (`tools/coverage_report.py`; drift found on GHL, docs/06).
7. Re-run the full Docker build with the official Maven base image where Docker Hub is reachable.
8. RSFA decisions on the candidate forms' open questions (docs/06) and on the Make authentication option (docs/07).
