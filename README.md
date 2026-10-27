# RSFA End Forms Engine

A deterministic PDF form-filling engine that will replace the MacroForge desktop filler for RSFA
bank end forms. Mappings are version-controlled and the service can run on Cloud Run.
**Status: local proof of concept. Nothing is deployed.** See `CLAUDE.md` for the rules and the current status.

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

Expected output: a JSON report with `"ok": true`, 7 fields filled (`Text 2`–`Text 6`, `Date 1`, `Date 2`)
and 2 `UNSUPPORTED_LEGACY_RULE` warnings for the customer-name overlays. The PDF stays editable.

## Run the HTTP API locally

```bash
export ENGINE_API_KEY=$(python3 -c "import secrets;print(secrets.token_urlsafe(32))")
java -jar engine/target/end-forms-engine.jar serve        # 127.0.0.1:8080, auth=API_KEY

curl -s localhost:8080/health                              # {"status":"ok","templates":3}
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
- `200 application/pdf` with headers `X-Request-Id`, `X-Fields-Filled` and `X-Fill-Warnings`.
- Otherwise JSON `{"status":"error","code":…,"issues":[…]}`:
  - 400 invalid request
  - 401 unauthorised
  - 404 unknown template
  - 413 body over 1 MiB
  - 422 validation failed
  - 503 template file unavailable

## Current limitations

- Two real templates are tested end-to-end (BNZ Boarder, GHL N&P Trust). The other 23 are imported but untested.
- The following legacy rules are not supported:
  - free-positioned overlays (e.g. the BNZ customer names)
  - subitem/`LineItems` mappings
  - multi-condition rules
- The legacy `[TODAY]` format and `equals`/`contains` semantics are inferred, not confirmed.
- Text that the template's font cannot encode (e.g. macrons such as `ā`) fails with `UNENCODABLE_TEXT`. It is never dropped.
- There is no flattening, no SharePoint upload and no Make integration yet.
