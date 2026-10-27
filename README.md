# RSFA End Forms Engine

A deterministic PDF form-filling engine that will replace the MacroForge desktop filler for RSFA
bank end forms. Mappings are version-controlled and the service can run on Cloud Run.
**Status: proof of concept. A private Cloud Run service (BNZ Boarder only) is deployed by RSFA; the demo-page revision is prepared (docs/04).** See `CLAUDE.md` for the rules and the current status.

## Prerequisites

- Java 21, Maven 3.9+
- Python 3.11+ with `pip install -r tools/requirements.txt` (pypdf)
- A copy of the legacy export `RSFA End Forms Filling BluePrint 271026.mforge` (private, never committed)
- Optional: `pdftoppm` (poppler) to view output. Install the `fonts-urw-base35` package so the lenders'
  ZapfDingbats check marks render instead of "NO GLYPH".

## 5-minute quickstart

```bash
# 1. Put the private export where git ignores it
mkdir -p .local/reference && cp "/path/to/RSFA End Forms Filling BluePrint 271026.mforge" .local/reference/

# 2. Extract the embedded lender PDFs (-> .local/generated/templates, named <sha256-12>__<name>.pdf)
python3 tools/import_mforge.py extract ".local/reference/RSFA End Forms Filling BluePrint 271026.mforge"

# 3. Build and test (the legacy-template tests run now because step 2 put the templates in place)
(cd engine && mvn -B package)

# 4. Fill the BNZ Boarder form from the imported mapping and synthetic data
java -jar engine/target/end-forms-engine.jar fill \
  --manifest "mappings/bnz/BNZ_BOARDER_FORM@2.0_8-24.json" \
  --data tests/fixtures/bnz-boarder.request.json \
  --out .local/output/BNZ_BoarderForm_POC.pdf --today 2026-10-27
pdftoppm -r 110 -png -singlefile .local/output/BNZ_BoarderForm_POC.pdf .local/output/BNZ_BoarderForm_POC
```

Expected output: a JSON report with `"ok": true`, no issues, and 9 entries filled. These are the fields
`Text 2`–`Text 6`, `Date 1` and `Date 2`, plus the two customer-name overlays `overlay:custom_text_…`. The PDF stays editable.

Other fixtures:
- `bnz-boarder-macrons.request.json`: te reo Māori macrons, which use the Unicode fallback.
- `bnz-handover.request.json`: repeated loan facilities via LineItems.
- `ghl-np-trust.request.json`: checkboxes on an encrypted template.

To view the output without poppler, run `java -jar engine/target/end-forms-engine.jar render --pdf <file> --out-dir <dir>`.

## Run the HTTP API locally

```bash
export ENGINE_API_KEY=$(python3 -c "import secrets;print(secrets.token_urlsafe(32))")
java -jar engine/target/end-forms-engine.jar serve        # 127.0.0.1:8080, auth=API_KEY

curl -s localhost:8080/health    # {"status":"ok","templates":N,"available":[...]}: templates whose blank PDF is present with the right SHA-256
curl -s -o out.pdf -D - -H "Authorization: Bearer $ENGINE_API_KEY" \
  -H 'Content-Type: application/json' --data @tests/fixtures/bnz-boarder.request.json \
  localhost:8080/generate
```

`POST /generate` request (the full schema is in `docs/02-poc-guide-and-tests.md`):

```json
{"requestId": "poc-bnz-boarder-0001", "templateId": "BNZ_BOARDER_FORM", "templateVersion": "2.0_8-24",
 "today": "2026-10-27", "data": {"1st Property Address Street": "12 Example Street, Testville", "...": "..."}}
```

Responses:
- `200 application/pdf` with headers `X-Request-Id`, `X-Fields-Filled`, `X-Fill-Warnings`, `X-Template` and
  `X-Engine` (`rsfa-end-forms-engine; deterministic-mapping; pdfbox; no-ai`).
- Otherwise JSON `{"status":"error","code":…,"issues":[…]}`:
  - 400 invalid request
  - 401 unauthorised
  - 404 unknown template
  - 413 body over 1 MiB
  - 422 validation failed
  - 503 template file unavailable

## Demo web page

The engine also serves a small demo page at `/`. It is plain HTML, CSS and JavaScript bundled in the jar, with no CDN
and no framework. The page shows a form for BNZ Boarder with Load Sample Data (synthetic), Generate PDF, a preview, a
download link, and the engine's provenance (deterministic mapping, not AI; fields filled; SHA-256). It calls the same
`POST /generate`; no mapping logic lives in the browser.

```bash
ENGINE_AUTH=NONE java -jar engine/target/end-forms-engine.jar serve    # loopback only; open http://localhost:8080/
```

- On Cloud Run, the page is private like the API. Open it through `gcloud run services proxy` (see `docs/05-demo-script.md`).
- In `API_KEY` mode, the page also requires the bearer key, so a browser cannot use it there.
- Browser smoke test (uses the machine's Playwright, not a project dependency):
  `node tests/ui/demo_smoke.js http://127.0.0.1:8080/ .local/output/ui-smoke`.
- What to click during a demo: [`docs/05-demo-script.md`](docs/05-demo-script.md).

## Docker (local only, nothing deployed)

```bash
(cd engine && mvn -B package)
docker build --network host -f engine/Dockerfile --target runtime-prebuilt -t end-forms-engine:prebuilt .
docker run --rm -p 127.0.0.1:8080:8080 -e ENGINE_AUTH=API_KEY -e ENGINE_API_KEY="$ENGINE_API_KEY" \
  -v "$PWD/.local/generated/templates:/templates:ro" end-forms-engine:prebuilt
```

The default target (`docker build -f engine/Dockerfile .`) compiles and tests inside Docker. Proxy and CA options are in
the Dockerfile header. The verification results are in `docs/02-poc-guide-and-tests.md`.

## Google Cloud Run (revision 1 deployed by RSFA; revision 2 with the demo page prepared)

The first private deployment generates BNZ Boarder only. The full runbook, with exact `gcloud` commands, IAM, the
authenticated `/health` and `/generate` calls, and rollback, is in [`docs/04-cloud-run-deployment.md`](docs/04-cloud-run-deployment.md).
Fastest path: build the bundle (`tools/stage_deploy.py`, `mvn package`, `deploy/make_bundle.sh`), upload it to Cloud
Shell and run `bash deploy_poc.sh`. It performs and verifies everything below. Manual steps:
1. RSFA approves the allowlist entry in `deploy/poc-allowlist.json` (done 2026-10-27).
2. `python3 tools/stage_deploy.py --templates .local/generated/templates --out .local/deploy/context`
3. `gcloud builds submit` the staged context.
4. `gcloud run deploy --no-allow-unauthenticated` with `ENGINE_AUTH=PLATFORM_IAM`.
5. Call it with `Authorization: Bearer $(gcloud auth print-identity-token)` or through `gcloud run services proxy`.

Authentication modes:
- `API_KEY`: local development, key from the environment, at least 32 characters, never committed.
- `PLATFORM_IAM`: Cloud Run only. It refuses to start unless `K_SERVICE` is set.
- `NONE`: loopback only.

## Current limitations

- Three real templates are tested end-to-end (BNZ Boarder, BNZ Handover partially, GHL N&P Trust). The other 22 are imported but untested.
- 138 legacy mappings are unresolved and 19 are unsupported (docs/03). Notable cases:
  - LineItems term/period name conflicts;
  - BNZ Gifting has no form fields;
  - row-less line-item conditions.
- The overlay position transform (`/1.2`) is strongly evidenced but not compared with a real MacroForge output.
  The overlay font and size are engine policy.
- No declaration is certified by default: template-preset ticks are cleared, and constant ticks need a validated
  `approval` record in the manifest (D-016).
- The legacy `[TODAY]` format and `equals`/`contains` semantics are inferred, not confirmed.
- There is no flattening, no SharePoint upload and no Make integration yet.
