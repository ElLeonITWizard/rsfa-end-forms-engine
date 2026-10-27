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
docs/decisions.md                     decision log (ADR-lite)
tools/import_mforge.py    legacy importer (inventory | extract | manifest)
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
```

The legacy-template tests (`LegacyTemplatesTest`) are **skipped** unless the templates have been
extracted to `.local/generated/templates/`. A green build without them proves only the synthetic tests.

## Safety rules (non-negotiable)

1. Never commit `.mforge` files, Make blueprints, bundles, spreadsheets, lender PDFs, generated PDFs,
   webhook URLs, tokens or client data. `.gitignore` enforces most of this, so check `git status` before every commit.
2. The `.mforge` contains a **real client `sample_payload`** and a webhook URL. Tools must never print or
   write values from `sample_payload`, `variable_map`, `export_folder`, `filename_generator` or `webhook_url`.
3. Never call the MacroForge webhook or any production endpoint. Never modify Make scenarios `8731317` / `9254149`.
4. Never fill signature or push-button fields. Never synthesise signatures. Constant "checked" boxes and
   `[TODAY]` dates near declarations are legacy behaviour flagged `reviewRequired`, not auto-approved.
5. Issues, logs and errors carry field names, keys and codes only, never data values.
6. Unsupported legacy rules must be reported (manifest `unsupported`, engine warning). Never silently coerce them.
7. Fixtures are fictional. Do not derive them from real records.

## Current status (2026-10-27)

- **Working POC:** `BNZ_BoarderForm_2.0_8 24.pdf` filled from the **imported** legacy mapping with synthetic
  data. The output was checked by assertions and by rendering in two renderers (PDFBox and poppler). Customer name(s)
  stays empty because the legacy app writes it with a free-positioned overlay that is not migrated (see docs/02).
- Second template tested: `GHL_N&P Trust_8 19.pdf` (checkboxes, multi-widget on-states, owner-encrypted,
  preserved via incremental save).
- Import coverage across all 25 Flow C templates: 697/1013 mappings imported, 192 unsupported, 124 stale. Only 2 templates
  are tested end-to-end. **Equivalence with MacroForge is not proven for any template.**
- HTTP API exercised locally. Docker image defined but **not built**: there is no Docker daemon in the POC environment.
  Nothing is deployed.

## Keep the docs true

When behaviour, schema, commands or status change, **update in the same change**: this file's status,
the affected `docs/*.md`, and `docs/decisions.md` for any technical choice. Label anything
unverified as such. Do not mark a template "tested" without a passing test that asserts its output.
