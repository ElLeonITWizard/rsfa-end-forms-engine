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
  issues. There is no LLM at runtime, no database and no UI.
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
docs/04-cloud-run-deployment.md       Cloud Run runbook (prepared, not executed), rollback
docs/decisions.md                     decision log (ADR-lite)
tools/import_mforge.py    legacy importer (inventory | extract | manifest)
tools/stage_deploy.py     builds the minimal, approved build context for Cloud Run (allowlist + SHA + blank check)
deploy/                   poc-allowlist.json (approved templates), cloudbuild.yaml, deploy_poc.sh (end-to-end deployment),
                          make_bundle.sh (Cloud Shell bundle), verify_pdf.py (post-deploy PDF checks), bucket lifecycle
engine/                   Maven project: library, CLI (inspect | fill | serve), HTTP API, Dockerfile
mappings/<lender>/<TEMPLATE_ID>@<version>.json   imported manifests (no client data; reviewed)
tests/fixtures/           synthetic requests (fictional "Jane Example" data only)
tests/importer/           importer unit tests (synthetic .mforge)
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

## Current status (2026-10-27, iteration 3)

- **BNZ Boarder is complete**, including Customer name(s):
  - 7 imported fields + 2 legacy overlays (`rect / 1.2`, docs/03);
  - deterministic (identical bytes across runs, host and container);
  - macrons via the embedded Unicode font.
- **Declarations:** template-preset ticks are cleared (BNZ Handover `Confirmation`). Constant ticks need a validated approval.
- **Comb dates** (e.g. BNZ Handover `Date 3`): centred, with printed placeholders knocked out using the measured background.
- **LineItems:** BNZ Handover fills 3 synthetic facilities into rows 1–3.
- **Coverage, Flow C:** 1,013 mappings = 804 imported + 19 unsupported + 138 unresolved + 52 inert. Only 3 templates
  are tested end-to-end. **Equivalence with MacroForge is not proven.**
- **Cloud Run: approved by RSFA, NOT yet deployed.**
  - The allowlist entry for the blank BNZ Boarder template is approved (packaging and testing only).
  - `deploy/deploy_poc.sh` is ready and tested against a stubbed `gcloud`.
  - Blocker: this environment has no Google Cloud credentials. Run the script in Cloud Shell (docs/04, option A),
    then record the results in docs/04 "Deployment record".
- **Open decisions** (docs/02 "Known gaps"):
  - 36 constant ticks;
  - the DDMMYYYY → 6-cell date transform;
  - the LineItems name conflict;
  - the `8731317` re-trigger guard;
  - Make service-to-service auth.

## Keep the docs true

When behaviour, schema, commands or status change, **update in the same change**: this file's status,
the affected `docs/*.md`, and `docs/decisions.md` for any technical choice. Label anything
unverified as such. Do not mark a template "tested" without a passing test that asserts its output.
