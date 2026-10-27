# 07 — Make integration contract and proposed architecture (prepared, not connected)

**Status (2026-10-27):** the engine side is implemented and tested locally (`POST /v1/masterboard/documents`). **No Make
scenario, Monday board, SharePoint site or Google Cloud resource was created or changed.** The live Cloud Run revision
does not contain this endpoint. Everything below that touches Make, Monday, SharePoint or GCP is a **proposal** that
needs RSFA's approval of exact steps.

Production systems that stay untouched:

| System | ID | Role today |
|---|---|---|
| Monday board "SOP Masterboard" (called *Masterboard: End Forms* in RSFA docs) | `5026186805` | source record; `status` = End Forms Filling Status |
| Make `M.com to Macroforge: Trigger Macroforge End Forms Filling Flow (Feb 2026)` | `8731317` | watches `status`, builds `result`, POSTs to MacroForge |
| Make `Macroforge to M.com: End Forms Filled (May 2026)` | `9254149` | MacroForge callback → `status := Filled` |
| Shared error handler `COMP-MAKE-ERROR-001` | `8732008` | failure e-mails |

## 1. What the engine already accepts (evidence)

- `POST /generate` (unchanged): one template per call, binary PDF response. It suits the demo page and single-form calls.
- `data` is Make's existing **`result` object** (docs/01):
  - keys are Monday column titles, normalised `/` and `.` → `_`;
  - text columns are strings or `null`, checkboxes are booleans, dates are `DDMMYYYY`;
  - address parts are derived keys.
- **[live, 2026-10-27, read-only]** Board column schema:
  - `dropdown_mkzvfgeg` is titled **"Selected Banks"**. The Make contract and MacroForge's conditions say
    **"Banks to Send"**. The labels are `ASB, ANZ, BNZ, KiwiBank, WBC`.
  - `status` labels: `Reviewing, Reviewed, Cancelled, Filled, Historical`.
  - **Inference:** `8731317` maps column IDs to fixed result keys (the contract file lists id → key), so the rename has
    not broken MacroForge. **TODO: verify** in the `8731317` code module before relying on it.

## 2. Contract `masterboard.v1` — `POST /v1/masterboard/documents`

JSON Schema: [`contracts/masterboard-request.v1.schema.json`](../contracts/masterboard-request.v1.schema.json).
Examples: [`contracts/examples/`](../contracts/examples/) (fictional data only).

```json
{
  "contractVersion": "masterboard.v1",
  "correlationId": "mb-5026186805-<itemId>-<makeExecutionId>",
  "idempotencyKey": "<itemId>-<trigger event timestamp>",
  "source": {"boardId": "5026186805", "itemId": "<itemId>"},
  "today": "2026-10-27",
  "selection": {"banks": ["BNZ", "ASB"]},
  "options": {"failurePolicy": "all-or-nothing", "includePdf": true, "includeCandidates": false},
  "data": { "...": "Make's result object for that item" }
}
```

**Selection.** Exactly one of the following:
- **`banks`:** the "Selected Banks" labels, split on `", "`. The engine uses the version-controlled routing table
  `engine/src/main/resources/routing/masterboard-v1.json`. It is MacroForge Flow C's bank → form list, ASB including GHL.
  - It generates only `tested` forms, plus `candidate` forms with `includeCandidates=true` (testing only).
  - Every other form of that bank, plus the always-filled `All Banks_DOAD.pdf`, is listed in **`notMigrated`**, so Make
    knows that MacroForge must still produce it during the parallel run.
  - An unknown bank label gives 422 `UNKNOWN_BANK`.
- **`templates`:** an explicit `templateId`/`templateVersion` list, at most 12. It is used by test scenarios.

**Response (200):**

```json
{"contractVersion": "masterboard.v1", "status": "complete | partial", "correlationId": "…", "idempotencyKey": "…",
 "requestDigest": "<sha256 of contract, today, resolved templates, data (keys sorted)>",
 "engine": "rsfa-end-forms-engine; deterministic-mapping; pdfbox; no-ai", "today": "2026-10-27", "retryable": false,
 "documents": [{"templateId": "BNZ_BOARDER_FORM", "templateVersion": "2.0_8-24", "bank": "BNZ", "status": "generated",
                "fileName": "<itemId>_BNZ_BOARDER_FORM_2.0_8-24_<digest8>.pdf", "contentType": "application/pdf",
                "sizeBytes": 56840, "sha256": "…", "fieldsFilled": 9, "warnings": [], "pdfBase64": "…"}],
 "notMigrated": [{"bank": "BNZ", "legacyPdfName": "BNZ_Handover_8 24.pdf", "status": "partial", "reason": "…"}]}
```

**Errors.** Every error body carries `code` and `retryable`. Per-document failures carry `errors: [{severity, code,
pdfField, sourceKey, message}]`, with names and codes only and **never data values**.

| HTTP | `code` | Retry? | Make action |
|---|---|---|---|
| 200 `complete` | — | — | file every document |
| 200 `partial` (`failurePolicy: per-document`) | per document | no | file the generated ones; report the failed ones |
| 400 | `UNSUPPORTED_CONTRACT`, `INVALID_REQUEST`, `INVALID_SELECTION`, `TOO_MANY_DOCUMENTS`, `INVALID_JSON` | no | fix the scenario mapping |
| 401 | `UNAUTHORISED`, `INVALID_SIGNATURE` | no | credentials/configuration |
| 403 (Cloud Run/IAM or gateway) | — | no | credentials/configuration |
| 413 | `PAYLOAD_TOO_LARGE` (request > 1 MiB), `RESPONSE_TOO_LARGE` | no | split the request per template |
| 422 | `UNKNOWN_BANK`, `NOTHING_TO_GENERATE`, `failed` + `VALIDATION_FAILED`/`UNKNOWN_TEMPLATE` | no | data problem: set the Monday engine status to `Failed`, notify the adviser |
| 503 | `failed` + `TEMPLATE_UNAVAILABLE` | **yes** | retry with backoff |
| 500 | `INTERNAL_ERROR` | **yes** (once) | retry, then alert |
| timeout / 429 / 502 / 504 (platform) | — | **yes** | retry with backoff |

**Default `failurePolicy: all-or-nothing`.** If any selected form fails, **no** PDF is returned: successful ones are
marked `withheld` and the response is 422. A record is never half-filed.

**Limits:**
- request ≤ 1 MiB;
- ≤ 12 documents;
- ≤ 10 MiB per PDF;
- ≤ 20 MiB raw PDFs per response, which keeps base64 + JSON below Cloud Run's 32 MiB response limit.

Measured sizes (synthetic fixtures): BNZ Boarder 57 KB, ANZ Boarder 247 KB, ASB Boarder 487 KB and GHL Individual 685 KB. The bank templates carry their own images and fonts.
`includePdf=false` gives a dry run that validates and returns `sha256` without PDFs.

**Correlation and logs:**
- `correlationId` is echoed in `X-Correlation-Id` and in the single log line per request:
  `masterboard correlationId=… status=… generated=… failed=… ms=…`.
- Bodies, values and PDFs are never logged.

**Idempotency (stateless by design, no database):**
- **What the engine guarantees.** The same `selection`, `today` and `data` give the same `requestDigest`, the same
  `fileName`, and, for unencrypted templates, the same bytes.
- **Encrypted bank templates (ASB/GHL).** The incremental save uses a random AES IV, so bytes and `sha256` differ per
  call while the content is identical. Compare `requestDigest`, not `sha256`.
- **`today` is required.** Make sends the date the run was requested (Pacific/Auckland), so a retry after midnight
  produces the same document.
- **What Make owns.** Side effects (SharePoint upload, Monday update) are de-duplicated by Make using
  `idempotencyKey`:
  - a Make data store of processed keys;
  - SharePoint uploads named `fileName` with conflict behaviour *replace*, so a retry overwrites the identical document.

**Timeouts and retries:**
- Engine work is about 0.05–0.5 s per form when warm. A cold start takes a few seconds.
- Cloud Run request timeout: 120 s (unchanged).
- Make HTTP module timeout: set it to **120 s** (Make allows 1–300 s per its HTTP app documentation).
- Retry only on the "Retry? yes" rows, at most 3 attempts with 30 s → 2 min → 5 min backoff, keeping the same
  `idempotencyKey` and `today`. Never retry a 4xx.

**Optional request signature (defence in depth, off by default, D-026):**
- When the engine has `ENGINE_HMAC_SECRET` (from Secret Manager, never in git), `/v1/*` requires two headers:
  - `X-RSFA-Timestamp`: unix seconds, within ±300 s;
  - `X-RSFA-Signature: v1=<hex HMAC-SHA256(secret, timestamp + "." + body)>`.
- It is checked after authentication; it never replaces IAM. Tested locally.
- **Unverified:** whether Make can compute HMAC-SHA256 natively. Community threads suggest `sha256()` alone is not an
  HMAC; a Code (JavaScript) module may be needed.

## 3. Service-to-service authentication (Make → private Cloud Run)

**Constraints:**
- Cloud Run must stay `--no-allow-unauthenticated`.
- No service-account JSON keys.
- No Owner/Editor grants.

**Why Make cannot simply call it:**
- Cloud Run IAM accepts only a **Google-signed ID token** (audience = service URL) for a principal with `run.invoker`.
- Minting one for a service account outside Google needs that account's private key (a JSON key) or a workload
  identity federation token exchange.
- Make has no built-in way to sign such a JWT without the key.
- **Not verified:** whether Make can issue an OIDC token per scenario.
- A user OAuth access token, e.g. from a Make "Google" connection, is **not** an ID token.

| Option | How Make authenticates | Cloud Run stays IAM-only? | Keys? | Effort / risk | Verdict |
|---|---|---|---|---|---|
| **A. API Gateway (Google)** in front of Cloud Run | API key restricted to this API, stored in a Make connection/keychain; the gateway calls Cloud Run with its own service account (`run.invoker`) and a Google-minted ID token | **yes** | no SA keys (API key is a revocable bearer secret) | managed, no code; adds a gateway + config; verify payload limits | **Recommended for the first isolated test** |
| **B. Relay service** (a small Cloud Run service "make-relay") | API key or HMAC checked against Secret Manager; can restrict to Make's published egress IPs; calls the engine with its own SA identity (metadata server) | **yes** | none | full control (HMAC, IP allowlist, rate limit), but one more service to maintain | Recommended if RSFA wants HMAC/IP pinning |
| C. Workload Identity Federation | Make presents an OIDC token | yes | none | Make does not issue per-scenario OIDC tokens (verify) | Not applicable today |
| D. Engine `API_KEY` mode + `--allow-unauthenticated` | bearer key | **no** | — | weakens the platform boundary | **Rejected** |
| E. Service-account JSON key in Make | signs its own ID token | yes | **yes** | key exfiltration risk, forbidden by RSFA | **Rejected** |

**Least-privilege layout (A or B):**
- a dedicated `make-gateway@` (or `make-relay@`) service account with `roles/run.invoker` **on the engine service only**;
- the deployer's own invoker binding unchanged;
- the API key restricted to the gateway API, rotated on a schedule and revoked on suspicion;
- Cloud Run logs and the gateway or relay logs correlated by `X-Correlation-Id`.

Exact `gcloud` commands will be prepared for approval when RSFA chooses A or B. **Nothing is created now.**

## 4. Proposed Make architecture (future, isolated)

**Phase 1 — isolated test scenario (needs RSFA approval to create).** It uses fictional data only and never touches the
production scenarios or board.

```text
[Manual run / test webhook]  "TEST – Masterboard to End Forms Engine (fictional)"
  1 Set variables: correlationId = mb-<boardId>-<itemId>-{{executionId}}, idempotencyKey, today (Pacific/Auckland)
  2 Data store "engine-runs-test": key = idempotencyKey exists and status=filed?  → stop (already done)
  3 HTTP POST {gateway}/v1/masterboard/documents   (timeout 120 s; parse JSON; API key header)
       error handler: retryable=true → Break (3 attempts, 30 s/2 min/5 min); else → route "failed"
  4 Router
     ├─ complete/partial → Iterator(documents where status=generated)
     │      → SharePoint (TEST library/folder) upload  name = fileName, content = toBinary(pdfBase64; base64), conflict = replace
     │      → (after iterator) Monday TEST board: "Engine Filling Status" := Generated, "Engine Run" := correlationId
     └─ failed / 4xx → Monday TEST board: "Engine Filling Status" := Failed; e-mail summary (codes/fields only)
  5 Data store: idempotencyKey → filed, requestDigest, fileNames
```

- The test **board** is a copy of the Masterboard *schema* with fictional items only. The test **SharePoint** location
  is separate from client folders.
- Test payloads come from `contracts/examples/` or from fictional items. Production items are never used.
- **`COMP-MAKE-ERROR-001` (`8732008`) is not wired into the test scenario.** Using the shared production handler needs
  approval.

**Phase 2 — parallel run (production change; needs a change plan, approval and a rollback):**
- Add a parallel branch to `8731317`, after it has built `result`, that calls the engine with `selection.banks` from
  "Selected Banks".
- MacroForge stays primary.
- The engine's PDFs go to a *comparison* folder, never to the client's filing folder.
- Compare them with MacroForge's outputs per form. This is the only way to prove equivalence (not proven today).

**Phase 3 — cutover per form:** only forms that passed the parallel comparison and that RSFA approved are added to the
Cloud Run allowlist and filed for real. Forms in `notMigrated` stay with MacroForge.

## 5. Reflecting completion in Monday without recursive runs

**[live]**
- Hook `3905270` (scenario `8731317`) fires on **any** change of column `status`, with no value filter.
- `9254149` writes `status := Filled`.
- So any automation that writes `status` can re-trigger the whole chain (possible by design; not observed in the one
  sample, docs/01).

**Proposal:**
1. The engine flow **never writes `status`**. It writes:
   - a **new, dedicated status column** "Engine Filling Status" (`Queued / Generated / Partially generated / Failed /
     Not migrated`);
   - a text column "Engine Run" (correlationId).

   Changes to other columns do not fire a hook that is configured for `columnId: status` (live config).
2. Before any production use, add the guard filter after module 1 of `8731317` (continue only for the intended
   trigger label, e.g. `Reviewed`). It is already proposed in docs/01 and needs approval.
3. Idempotency in Make (data store keyed by `idempotencyKey`) also stops a duplicate trigger from filing twice.
4. Creating the new columns is a board change: approval required. For Phase 1 they live on the **test board** only.

## 6. Testing with fictional data, never production scenarios

| Level | How | Status |
|---|---|---|
| Unit/contract | `MasterboardApiTest`: several forms in one call, determinism, all-or-nothing vs per-document, bank routing + `notMigrated`, invalid contracts, HMAC (valid/stale/tampered) | **Passed** (5 tests) |
| Real templates, local | `ENGINE_AUTH=NONE java -jar engine/target/end-forms-engine.jar serve`, then `curl --data @contracts/examples/<file>.json localhost:8080/v1/masterboard/documents` | **Passed** 2026-10-27: BNZ 1 generated / 6 notMigrated; ASB+ANZ candidates 3 generated (dry run) / 9 notMigrated |
| POC image (local container, staged context, PLATFORM_IAM) | `docker build --target poc` from `stage_deploy.py` output, then call `/v1` | **Passed** 2026-10-27: BNZ generated, SHA `2ae7bfac…` = fixture, no data in logs |
| Cloud Run candidate revision | deploy with `UPDATE_EXISTING=1` (no-traffic tag, docs/04) after approval; call the tag URL with the deployer's ID token | Planned |
| Isolated Make scenario | Phase 1 above, with a test board, test SharePoint folder and fictional items | Planned (needs approval) |
| Parallel run | Phase 2 | Planned (needs change plan) |

**Acceptance criteria for Phase 1:**
1. A fictional item with `Selected Banks = BNZ` files exactly one PDF, named `fileName`, whose SHA-256 equals the local build.
2. Re-running the same execution files nothing new (data store) or overwrites the identical file.
3. A fictional item with an unmapped label shows `Failed`, the e-mail lists codes and keys only, and nothing is filed.
4. A forced 503 (e.g. a candidate revision without templates) retries 3 times, then reports `Failed`.
5. `status` on the test board is never written by the test scenario, and no production scenario runs (execution
   history of `8731317`/`9254149` unchanged during the test window).
6. Logs (Cloud Run, gateway, Make) contain no data values.
