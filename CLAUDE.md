# CLAUDE.md — RSFA End Forms Engine

**Read this file first.** It is the short, durable source of truth for any agent working here.
Details live in `docs/`; you should not need any earlier chat transcript.

## Purpose

Replace only the **PDF-filling engine** of RSFA's MacroForge end-forms process with a deterministic,
cloud-ready service. The adviser workflow stays the same: Monday status change → Make → filled
lender PDFs filed to SharePoint → Monday status updated. Monday boards, Make orchestration and
SharePoint filing are **out of scope**.

## Architecture boundaries

- **Engine (this repo):** Java 21 + Apache PDFBox 3. It takes already-prepared JSON (`data`) plus a
  version-controlled mapping manifest and fills one AcroForm template. It returns a PDF or structured
  issues. There is no LLM at runtime and no database.
- **Demo page (`engine/src/main/resources/ui/`):** static HTML/CSS/vanilla JS served by the engine at `/`. It only
  collects values and calls `POST /generate`. It holds no mapping logic, no CDN, no data, and has the same
  authentication as the API (D-020). Not a product: no template upload, mapping editor, user management or multi-lender scope.
- **Importer (`tools/import_mforge.py`):** a one-off Python CLI. It converts the legacy `.mforge`
  export into manifests and inventories, and extracts embedded PDFs into `.local/`.
- **Make** (scenario `8731317`) stays the orchestrator. Future cutover means Make calls
  `POST /generate` in parallel with MacroForge. Do not change Make, Monday or SharePoint from this repo.

## Repository map

```text
CLAUDE.md                 this file (rules + status)
README.md                 5-minute quickstart
docs/01-context-and-architecture.md   RSFA/Make/Monday/MacroForge context, data contract, security
docs/02-poc-guide-and-tests.md        manifest schema, POC steps, what is supported, tests, gaps
docs/03-migration-inventory.md        evidence-based inventory of the legacy .mforge
docs/04-cloud-run-deployment.md       Cloud Run runbook (run by RSFA: revisions 1 and 2), deployment record, rollback
docs/05-demo-script.md                demo page: secure access (gcloud run services proxy) + what to click
docs/06-template-coverage.md          form selection, readiness levels, open questions, forms not chosen
docs/07-make-integration.md           Make contract masterboard.v1, auth A vs B (relay recommended), Make test scenario design
docs/08-monday-schema-drift.md        live Monday schema vs key contract: renames, replaced IDs, option drift (read-only)
docs/09-macroforge-banks-key-investigation.md  8731317 'Banks to Send' rename: evidence, fix APPLIED 2026-10-28 (approved), verification, rollback
docs/10-relay-security-and-infrastructure.md   relay security architecture, durable replay, minimum GCP resources, costs, rollback
docs/11-make-test-scenario.md         isolated Make test scenario, module by module (designed, not created)
docs/12-acceptance-checklists.md      business acceptance checklists for Rod (GHL Individual / Trust)
docs/generated/template-coverage.md   GENERATED per-form coverage report (tools/coverage_report.py)
docs/generated/monday-schema-drift.md GENERATED ID-by-ID schema comparison (tools/schema_drift.py)
docs/decisions.md                     decision log (ADR-lite)
tools/import_mforge.py    legacy importer (inventory | extract | manifest)
tools/stage_deploy.py     builds the minimal, approved build context for Cloud Run (allowlist + SHA + blank check)
tools/coverage_report.py  per-form coverage report from manifests (+ live Monday column schema, Make key contract)
tools/schema_drift.py     Monday schema drift report (column IDs, titles, types, options vs key contract and manifests)
tools/reimport_curated.sh re-imports the four curated forms (optionally a .local preview of pending decisions)
tools/make_like_client.py signs /v1 requests like Make (HMAC) for local relay tests; prints a summary only
curation/                 reviewed per-form validation (closedOptions, requires), corrections (evidence | decision, D-028)
                          and review status, merged by the importer
contracts/                Make contract JSON Schema (masterboard.v1) + fictional example requests + HMAC test vector
make/                     Make material prepared here, never applied automatically: proposed 8731317 module-8 snippet,
                          test-scenario Code module (build + HMAC sign) and synthetic payloads T1–T12
deploy/                   poc-allowlist.json (approved templates), cloudbuild.yaml, deploy_poc.sh (end-to-end deployment),
                          make_bundle.sh (Cloud Shell bundle), verify_pdf.py (post-deploy PDF checks), bucket lifecycle
engine/                   Maven project: library, CLI (inspect | fill | render | serve), HTTP API (/health, /generate,
                          /v1/masterboard/documents), relay (ENGINE_ROLE=relay, D-027), demo page, routing table
                          (resources/routing), Dockerfile
mappings/<lender>/<TEMPLATE_ID>@<version>.json   imported manifests (no client data; reviewed)
tests/fixtures/           synthetic requests (fictional "Jane Example" data only)
tests/importer/           importer + staging unit tests (synthetic .mforge)
tests/ui/                 browser smoke test of the demo page (Playwright from the machine) + PDF checker
tests/make/               Node tests of the make/ material (+ a runner for the local relay chain)
.local/                   GIT-IGNORED: reference exports, lender PDFs, generated output
```

## Commands (run from the repo root)

```bash
python3 tools/import_mforge.py extract   ".local/reference/<file>.mforge"           # lender PDFs -> .local/generated/templates
python3 tools/import_mforge.py inventory ".local/reference/<file>.mforge" --with-pdf --out .local/output/inventory.json
python3 tools/import_mforge.py manifest  ".local/reference/<file>.mforge" --flow "Flow C" \
  --pdf "BNZ_BoarderForm_2.0_8 24.pdf" --template-id BNZ_BOARDER_FORM --template-version 2.0_8-24 \
  --date 2026-10-27 --strict --out "mappings/bnz/BNZ_BOARDER_FORM@2.0_8-24.json"
python3 -m unittest discover -s tests/importer -v                                   # importer tests
(cd engine && mvn -B package)                                                       # Java tests + fat jar
java -jar engine/target/end-forms-engine.jar fill --manifest "mappings/bnz/BNZ_BOARDER_FORM@2.0_8-24.json" \
  --data tests/fixtures/bnz-boarder.request.json --out .local/output/BNZ_BoarderForm_POC.pdf
java -jar engine/target/end-forms-engine.jar render --pdf .local/output/BNZ_BoarderForm_POC.pdf --out-dir .local/output
docker build --network host -f engine/Dockerfile --target runtime-prebuilt -t end-forms-engine:prebuilt .   # see docs/02
ENGINE_AUTH=NONE java -jar engine/target/end-forms-engine.jar serve                 # demo page on http://localhost:8080/ (loopback only)
node tests/ui/demo_smoke.js http://127.0.0.1:8080/ .local/output/ui-smoke 5        # browser smoke test (server running; 5 = forms offered)
bash tools/reimport_curated.sh ".local/reference/<file>.mforge" <YYYY-MM-DD> [--preview-pending .local/preview]
python3 tools/schema_drift.py --help                                               # schema drift report (docs/08)
node tests/make/code_module_sign_test.js && node tests/make/module8_banks_alias_test.js   # Make material (docs/09, docs/11)
python3 tools/coverage_report.py --board .local/reference/masterboard-columns-<date>.json \
  --contract .local/reference/8731317_result_key_contract.tsv --date <YYYY-MM-DD> --out docs/generated/template-coverage.md
```

The legacy-template tests (`LegacyTemplatesTest`) are **skipped** unless the templates have been
extracted to `.local/generated/templates/`. A green build without them proves only the synthetic tests.

## Safety rules (non-negotiable)

1. Never commit `.mforge` files, Make blueprints, bundles, spreadsheets, lender PDFs, generated PDFs,
   webhook URLs, tokens or client data. `.gitignore` enforces most of this, so check `git status` before every commit.
2. The `.mforge` contains **real client data** and a webhook URL. Tools must never print or write values from
   `sample_payload`, `variable_map`, `flow_data` (`__raw_webhook_payload`, `current_parsed_pdf_data`), `export_folder`,
   `filename_generator` or `webhook_url`.
3. Never call the MacroForge webhook or any production endpoint. Never modify Make scenarios `8731317` / `9254149`.
   (Single recorded exception: the user-approved 7-line alias in `8731317` module 8, applied 2026-10-28, docs/09.
   Any further change needs a new explicit approval.)
4. Never fill signature or push-button fields. Never synthesise signatures. **No declaration is certified by default.**
   - Template-preset ticks are cleared.
   - Constant ticks need a validated `approval` record (approvedBy, approvedOn, reference; D-016).
   - Never write an approval, or approve an allowlist entry, on someone's behalf.
   - `[TODAY]` dates near declarations are flagged `reviewRequired`.
5. Issues, logs and errors carry field names, keys and codes only, never data values.
6. Unsupported or unresolved legacy rules must be reported (manifest `unsupported` / `unresolvedLegacyMappings`,
   engine warning). Never silently coerce them, never alias names or invent coordinates, and never label a mapping
   "obsolete" without RSFA's decision (use `inert` only with evidence; see docs/03).
7. Fixtures are fictional. Do not derive them from real records.
8. Nothing is deployed or changed in Google Cloud without RSFA's explicit approval of the exact commands (docs/04).
   Never build from the repo root for deployment: only from the staged context.

## Current status (2026-10-28, iteration 10)

- **Iteration 10:**
  - **`8731317` alias approved** (scope: module-8 code only). All pre-save checks passed: the live read is identical
    to the verified backup and 24/24 regression checks pass.
  - **APPLIED 2026-10-28T12:43:58Z, result PASS** (docs/09 "Iteration 10c"):
    - applied with `tools/apply_8731317_change.py` through the Make API (network-secret authentication);
    - only module 8's code changed; the scenario is active, with the same hook, scheduling and interface;
    - verified via both the API and MCP re-reads; no execution was triggered.
  - **Rollback:** `--rollback` with the `.local` backup, or Make's version history.
  - **Business validation:** on the next normal run. No historical failure is claimed.
  - **Recommended:** revoke the temporary Make API token.
  - **Revision 3:** deployment approved; **RSFA runs it** from Cloud Shell.
    - Attempt 1 failed in Cloud Build: epoch-0 file times in the bundle made Maven skip all resources (D-037). No
      revision was created; `00002-nan` is still serving.
    - Corrected bundle `7a570707…` (commit `a34bd0c`) is rehearsed from the extracted archive and `gcloud`'s exact
      upload set (docs/04). This environment never deploys.

- **Iteration 9 (production unchanged; nothing deployed; `00002-nan` untouched):**
  - **`8731317` fix ready for approval (docs/09):**
    - Fresh read is byte-identical to the backup (`lastEdit` 2026-07-16).
    - The proposal (7 lines in module 8) differs from the current blueprint in exactly one path.
    - Rollback blueprint is kept in `.local/`. Make validates the module configuration; 24 regression checks pass.
    - **Not applied:** it awaits explicit approval.
  - **Engine revision 3 bundle (docs/04, D-035):**
    - `/v1` added; allowlist, demo page and IAM unchanged.
    - Reproducible archive with SHA-256. The deploy script verifies `/v1` and requires `EXPECT_PREVIOUS_REVISION`.
    - Locally rehearsed: image built from the staged context, run as `PLATFORM_IAM` behind an IAM stand-in.
  - **Relay (docs/10 §9, D-036):**
    - Independent review: no HIGH issues. M1, M2, M4 and L1 fixed locally; M3 (Cloud Storage 403 vs 412) must be
      verified at deployment.
    - 17 relay tests.
  - **Tests:** 81 Java + 25 Python + 68 Node checks; local Make-payload chain 12/12; BNZ Boarder bytes unchanged.

- **Iteration 8 (superseded where iteration 9 differs; nothing deployed; no change in Make, Monday, SharePoint or GCP; `00002-nan` untouched):**
  - **`8731317` investigation (docs/09, D-033):**
    - Confirmed: `dropdown_mkzvfgeg` was renamed 2026-10-25, module 8 keys by live title, and MacroForge still tests
      `Banks to Send`. Reproduced locally.
    - Not verified: MacroForge's handling of the missing key. No production run since 2026-10-01.
    - A 6-line additive module-8 fix (by column ID) is prepared and tested, **not applied**; it needs explicit
      approval. A full blueprint backup is in `.local/` (never committed).
  - **Relay:**
    - durable cross-instance replay store (Cloud Storage `ifGenerationMatch=0`, fail-closed; D-031);
    - JSON and signature-header validation; configurable timeout;
    - 13 relay tests.
  - **Make signing:** tested plain-JS HMAC for the Code module (D-032); native `sha256` with a key is still unverified.
    Test scenario guide with re-signing retries, T1–T12 run locally through the signer, relay and engine (docs/11).
  - **Forms (D-034):**
    - GHL Individual/Trust refuse a missing name.
    - New **RSFA-DECISION-3**: MacroForge fills both GHL forms for every ASB deal.
    - ANZ refuses an inclusive **Yes**.
    - Statuses unchanged: GHL ready for acceptance (checklists docs/12); ASB and ANZ blocked.
  - **Tests:** 77 Java + 25 Python + 56 Node checks + 27 browser checks; local Make-payload chain 12/12; BNZ Boarder
    bytes unchanged.

- **Iteration 7 (local only, superseded where iteration 8 differs; nothing deployed or changed in Make, Monday, SharePoint or GCP; `00002-nan` untouched):**
  - Forms (docs/06, D-029):
    - **GHL Individual and GHL Trust: ready for acceptance testing** (routing `acceptance`). GHL Trust in/out defect
      fixed: 3d/3e details come from the dedicated In/Out columns; OUT stays empty without OUT data.
    - **ASB Boarder: blocked** (RSFA-DECISION-1 property address; RSFA-DECISION-2 pre-filled signature dates).
    - **ANZ Boarder: blocked** (RSFA-DECISION-1; `B1/B2 Inclusive` both export `No`, a bank template defect).
    - None is in the Cloud Run allowlist; nothing is compared with MacroForge.
  - Curation corrections (D-028): evidence applied; decisions pending, previewed only under `.local/`.
  - New operators `includes` (multi-select element) and `absent`; `requires` with `when`.
  - Monday drift (docs/08, D-030): keys are live titles (module 8). `Banks to Send` → `Selected Banks` probably breaks
    MacroForge's lender conditions (inference, reported to RSFA, not fixed).
  - Make auth: **relay (Option B) recommended** and implemented in the same jar (D-027); proven locally only. Make's
    native HMAC is unverified (test vector in docs/07). Make test scenario T1–T9 designed, not created.
  - Tests: 65 Java + 25 Python + 27 browser checks passed; BNZ Boarder SHA-256 unchanged.

- **Iteration 6 (local only, superseded where iteration 7 differs; nothing deployed or changed in Make, Monday, SharePoint or GCP):**
  - Forms: ASB Boarder, ANZ Boarder and GHL Individual are **candidates**; GHL Trust is **blocked** (docs/06).
  - Not in the Cloud Run allowlist. The demo page lists only templates `/health` reports usable.
  - Manifest `validation` (D-023):
    - closed options refuse drifted Monday labels and multi-selections instead of leaving boxes blank;
    - preconditions refuse misstated amounts (ANZ weekly).
  - Make contract `POST /v1/masterboard/documents` (D-025). Auth recommendation is API Gateway or a relay; IAM is kept
    and there are no SA keys (D-026, proposed).
  - Live board schema findings: `Selected Banks` (was "Banks to Send"), a new `Historical` status, and label drift (docs/06/07).

- **BNZ Boarder is complete**, including Customer name(s):
  - 7 imported fields + 2 legacy overlays (`rect / 1.2`, docs/03);
  - deterministic (identical bytes across runs, host and container);
  - macrons via the embedded Unicode font.
- **Declarations:** template-preset ticks are cleared (BNZ Handover `Confirmation`). Constant ticks need a validated approval.
- **Comb dates** (e.g. BNZ Handover `Date 3`): centred, with printed placeholders knocked out using the measured background.
- **LineItems:** BNZ Handover fills 3 synthetic facilities into rows 1–3.
- **Coverage, Flow C:** 1,013 mappings = 804 imported + 19 unsupported + 138 unresolved + 52 inert. Six templates are
  tested end-to-end (BNZ Handover partially). **Equivalence with MacroForge is not proven.**
- **Demo page (iteration 5):** BNZ Boarder only, synthetic sample, preview + download + provenance.
  - Browser-tested (20 checks) on the host, in the POC image, and behind an emulated IAM layer + `gcloud run services proxy`.
  - The unedited sample gives the fixture's exact SHA-256.
  - Demo script: docs/05.
- **Cloud Run: revision 1 deployed by RSFA (2026-10-27).**
  - `rsfa-end-forms-engine-00001-6qm`, IAM required, BNZ Boarder only, verified (docs/04 "Deployment record").
  - It has no demo page.
- **Revision 2 `rsfa-end-forms-engine-00002-nan` (demo page) is live**, as reported by RSFA; its record is not yet
  handed back. Rollback target: `00001-6qm` (docs/04). It must stay stable: no new revision without RSFA's explicit
  approval. This environment has no Google Cloud credentials and never deploys.
- `/health` reports usable templates only (`templates` + `available`, D-022). The demo badge counts the forms the page
  offers that are usable.
- **Open decisions** (docs/02 "Known gaps", docs/06, docs/07):
  - the candidate forms' open questions and the GHL label fixes (docs/06);
  - business confirmation of the applied `8731317` fix on the next normal run (docs/09);
  - RSFA-DECISION-1/2/3 (docs/06, docs/12);
  - the relay (D-027/D-031) and its public exposure, and the dedicated Monday engine-status column (docs/07, docs/10);
  - 36 constant ticks;
  - the DDMMYYYY → 6-cell date transform;
  - the LineItems name conflict;
  - the `8731317` re-trigger guard.

## Keep the docs true

When behaviour, schema, commands or status change, **update in the same change**: this file's status,
the affected `docs/*.md`, and `docs/decisions.md` for any technical choice. Label anything
unverified as such. Do not mark a template "tested" without a passing test that asserts its output.
