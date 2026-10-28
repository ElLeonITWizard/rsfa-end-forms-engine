# 07 — Make integration contract and proposed architecture (prepared, not connected)

**Status (2026-10-28, iteration 8):**
- **Engine side:** implemented and tested locally (`POST /v1/masterboard/documents`).
- **Recommended authentication:** the relay (section 3). It is implemented in the same jar (`ENGINE_ROLE=relay`) and
  proven locally end-to-end: Make-style signed request → relay → IAM stand-in → engine.
- **Iteration 8:**
  - durable cross-instance replay protection (Cloud Storage, D-031);
  - security architecture and minimum infrastructure: [docs/10](10-relay-security-and-infrastructure.md);
  - Make signing (D-032) and the module-by-module test scenario: [docs/11](11-make-test-scenario.md);
  - production finding on `8731317` (`Banks to Send` rename): [docs/09](09-macroforge-banks-key-investigation.md).
- **Not changed:** no Make scenario, Monday board, SharePoint site or Google Cloud resource was created or changed.
  The live Cloud Run revision (`00002-nan`) does not contain `/v1` or the relay.
- **Proposals:** everything below that touches Make, Monday, SharePoint or GCP needs RSFA's approval of exact steps.

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
  - **[live, blueprint read 2026-10-28]** `8731317` module 8 builds every result key from the column's **live title**
    (`key = col.title || col.id`). The old inference that it maps column IDs to fixed keys was **wrong**: since the
    rename, `8731317` sends **"Selected Banks"**, not "Banks to Send". See docs/08 for the consequence for MacroForge.

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
  - It generates only `tested` forms, plus `acceptance`/`candidate` forms with `includeCandidates=true` (testing only).
    `blocked` forms are never routed (docs/06).
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

## 3. Service-to-service authentication (Make → private Cloud Run): decision

**Constraints:**
- The engine stays `--no-allow-unauthenticated`.
- No service-account JSON keys.
- No Owner/Editor grants.
- The fewest new dependencies.

**Why Make cannot call the engine directly:**
- Cloud Run IAM accepts only a **Google-signed ID token** (audience = service URL) of a principal with `run.invoker`.
- Make has no built-in way to mint one without a private key.
- **Not verified:** whether Make can issue an OIDC token per scenario.

So a public-facing entry point is needed, and it must authenticate Make itself.

### Option A — Google API Gateway in front of the private engine

| Aspect | How it works |
|---|---|
| Make → entry point | Static **API key** (header `x-api-key`, checked by the gateway). The gateway does not "authenticate Make" in any stronger sense: anyone holding the key is accepted. JWT validation is possible, but Make would then need to sign a JWT, which is the same problem as the ID token. |
| Entry point → engine | The gateway's service account calls Cloud Run with a Google-minted ID token (`x-google-backend` with `jwt_audience`); `run.invoker` on the engine only. No key file. |
| Secrets and rotation | API key created in the project (APIs & Services > Credentials), restricted to the gateway's API, stored in Make. Rotation: create a second key, switch Make, delete the old one. |
| Replay protection | **None.** A captured request can be replayed for as long as the key is valid. |
| Request and response size | Gateway limits apply on top of Cloud Run's 32 MiB response limit. The backend `deadline` must be raised from its short default for 120 s calls. **Not verified:** the exact limits. |
| Rate limiting, logging | Per-key quotas in the API config; access logs in Cloud Logging. Retries and idempotency stay with Make and the engine (unchanged). |
| Cost and maintenance | Within the free tier at RSFA volumes (list price, verify). New: 3 APIs (API Gateway, Service Management, Service Control), an OpenAPI config, an API, a gateway, an API key. Every change is a new immutable API config. |
| Failure recovery | Managed service. A config error makes every call fail until a corrected config is rolled out. |

### Option B — Small authenticated Cloud Run relay (implemented as a PoC)

| Aspect | How it works |
|---|---|
| Make → entry point | Every request is **signed**: `X-RSFA-Timestamp: <unix seconds>`, `X-RSFA-Signature: v1=<hex HMAC-SHA256(secret, timestamp + "." + body)>`. The relay is the only public endpoint and accepts nothing unsigned (only `/health` and `/v1/masterboard/documents` exist). |
| Entry point → engine | The relay's own service account (`rsfa-make-relay@`) gets a Google-signed ID token from the **Cloud Run metadata server** (audience = engine URL; cached until 5 min before expiry). It is the only invoker added to the engine. No key file anywhere. |
| Secrets and rotation | One HMAC secret in **Secret Manager**, mounted as `RELAY_HMAC_SECRETS`, with `secretAccessor` for the relay SA on that secret only. The same value is stored in Make (see "Make side"). Rotation, two secrets active at once: add version 2, deploy the relay with `old,new`, switch Make, then deploy with `new` only. Tested (`RelayServerTest`). |
| Replay protection | The timestamp must be within ±300 s, and each signature is accepted once (`409 REPLAYED_REQUEST`), recorded in a **shared Cloud Storage bucket** with an atomic create-if-absent, so a replay on another instance is also refused (iteration 8, D-031; the per-instance memory store is refused on Cloud Run unless the relay runs as a single instance). The store fails closed (503). |
| Request and response size | Request ≤ 1 MiB (checked before the signature, `413`), response ≤ 32 MiB passed through; engine limits unchanged (20 MiB raw PDFs). Upstream timeout 110 s, below Cloud Run's 120 s. |
| Rate limiting, logging | Fixed window per instance (default 30/min, `429` with `Retry-After: 60`). One log line per request: `relay correlationId=… status=… outcome=… ms=…`. No body, values, secrets or tokens. |
| Retry and idempotency | Relay errors carry `retryable`: true for `429`, `502 UPSTREAM_UNAVAILABLE`, `503 IDENTITY_TOKEN_UNAVAILABLE` and `504`. Engine responses are passed through unchanged, so engine idempotency (`requestDigest`, deterministic file names) is preserved. A retry must be **re-signed** with a new timestamp; Make re-evaluates the expressions when it re-runs the module. |
| Cost and maintenance | Same container image as the engine, so no new build pipeline or runtime. Scale-to-zero, within the Cloud Run free tier at RSFA volumes. Secret Manager and the replay bucket cost cents per month (list price, verify). New: 1 API (Secret Manager), 1 secret, 1 service account, 1 bucket, 1 Cloud Run service. About 450 lines of tested code to maintain (docs/10 §7). |
| Failure recovery | The relay is stateless: redeploy or roll back its revision independently of the engine. If the engine is down, Make gets `502`/`504` with `retryable: true`. |

### Is HMAC signing possible in Make without external code?

**Yes, with Make's Code module: implemented and tested (iteration 8, D-032).** `make/test-scenario/code-module-sign.js`
computes SHA-256/HMAC in plain JavaScript. It is checked against Node's crypto, the Java relay and the vector in
`contracts/hmac-test-vector.json` (44 checks). It needs the premium `code:ExecuteCode` module, which `8731317` already
uses. **Make's native `sha256` with a key: still not verified** (details below).
- **Indications:** Make's `sha256(text; [encoding]; [key]; [key encoding])` computes an HMAC when a key is given,
  according to Make Community threads that point to the official functions reference.
- **Why it could not be confirmed:** `www.make.com` and `community.make.com` are not reachable from this session.
- **Verification step, before anything is deployed.** In any existing Make scenario's expression tester, or a throwaway
  scenario in a sandbox team, evaluate:

  `sha256("1793152800.{""contractVersion"":""masterboard.v1""}"; "hex"; "rsfa-test-vector-secret-NOT-FOR-USE-0001")`

  The expected result is `3089d368d7df95f95bafd039bc244e302c350d306f1095ebe43466b47f49601b`. This is HMAC-SHA256 of
  `1793152800.{"contractVersion":"masterboard.v1"}` with that test key; quoting may differ in Make's editor.
- **Fallback if Make cannot compute HMAC natively** (both options keep the engine private):
  - B1: the relay accepts a static bearer key, with the same security as Option A and no replay protection.
  - B2: Make's Code (JavaScript) module computes the HMAC. `8731317` already uses a Code module (module 8), so it is
    not a new dependency.

**Where the secret lives in Make:** a scenario-level or team custom variable is visible to scenario editors.
**Not verified:** which Make feature (keychain/connection vs custom variable) hides it best on RSFA's plan. Decide
during Phase 1.

### Recommendation: Option B (relay)

- **It is the only option that authenticates *requests*** (integrity, freshness, replay window) rather than the mere
  possession of a static key.
- **It adds the fewest new kinds of dependency.**
  - Same image, Cloud Run and Cloud Build as today.
  - Secret Manager is the only new API. API Gateway would add three APIs and an OpenAPI config lifecycle.
- **It is already proven locally:**
  - `RelayServerTest` (4 tests) covers a valid request, rotation, tampered body, wrong secret, stale timestamp, replay,
    rate limit, a non-invoker identity (`502 UPSTREAM_AUTH_FAILED`), an oversized body, and unsafe configurations.
  - A run with the real jar and real templates (`tools/make_like_client.py`) returned:
    - BNZ: 200, the PDF SHA-256 equals the fixture;
    - GHL acceptance: 2 PDFs;
    - contradictory data: 422;
    - unknown bank: 422;
    - wrong secret: 401;
    - IAM layer without a token: 403;
    - logs: no data values.

**Accepted risk:** the relay is a public HTTPS endpoint (`--allow-unauthenticated` **on the relay only**) whose
authentication is the HMAC. Two notes:
- If the organisation policy forbids `allUsers` (domain-restricted sharing), Option A has the same constraint.
- Additional mitigations are available if RSFA wants them:
  - Cloud Armor via a load balancer (adds cost and complexity);
  - a Make egress-IP allowlist inside the relay (Make publishes IP ranges per zone; verify).

### Estimated infrastructure changes (B; nothing done; exact commands will be prepared for approval)

Superseded in detail by [docs/10 §7](10-relay-security-and-infrastructure.md). Iteration 8 adds the replay bucket
(`storage.objectCreator` for the relay SA on that bucket only).

| # | Change | Approval |
|---|---|---|
| 1 | Enable `secretmanager.googleapis.com` | RSFA |
| 2 | Secret `rsfa-make-relay-hmac` (value generated in Cloud Shell with `openssl rand -hex 32`, never in chat or git) | RSFA |
| 3 | Service account `rsfa-make-relay@` with no project roles; `secretmanager.secretAccessor` on that secret only; `run.invoker` on the engine service only | RSFA |
| 4 | Engine revision 3 with `/v1` (same `UPDATE_EXISTING=1` no-traffic procedure, docs/04); allowlist unchanged unless RSFA approves more templates | RSFA |
| 5 | Cloud Run service `rsfa-end-forms-relay` from the same image (`ENGINE_ROLE=relay`, `RELAY_TARGET_URL=<engine URL>`, secret as env; `--allow-unauthenticated`, ingress all, min 0, max 1, concurrency 4, timeout 120 s, 512 MiB) | RSFA (public endpoint) |
| 6 | Make: store the secret, build the isolated test scenario (section 4) | RSFA |

**Rollback:** delete the relay service, or remove its `allUsers` binding (stops all Make access at once). Remove the
relay SA's invoker binding on the engine. The engine revision rolls back with `update-traffic` (docs/04).

## 4. Isolated Make test scenario (designed, not created)

**Superseded by [docs/11](11-make-test-scenario.md)** (iteration 8):
- module-by-module guide with module identifiers verified against the Make API;
- controlled retries that **re-sign** each attempt (Make's `Break` would resend a stale or already-claimed signature);
- raw request body, idempotency data store, test-only SharePoint folder;
- synthetic payloads T1–T12 in `make/test-scenario/payloads/`, run locally through the same Code module against the
  real jar.

T1's expected PDF SHA-256 depends on the request's `today`. With `today = 2026-10-28` (the contract example) BNZ
Boarder is `e08a79e00e8702eb…`, byte-identical to the fixture filled with that date. The fixture SHA `2ae7bfac…` is
for `2026-10-27`.

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
| Contract unit tests | `MasterboardApiTest`: several forms per call, determinism, all-or-nothing vs per-document, bank routing + `notMigrated`, invalid contracts, engine HMAC | **Passed** (5) |
| Relay unit tests | `RelayServerTest`: signed request → IAM stand-in → engine; rotation; tampered/stale/replayed/unsigned; rate limit; wrong identity; size; unsafe config | **Passed** (4) |
| Real templates, local chain | engine (`ENGINE_AUTH=NONE`) → IAM stand-in (403 without the relay's token) → relay (`ENGINE_ROLE=relay`) ← `tools/make_like_client.py` with `contracts/examples/*` | **Passed** 2026-10-28 (results in section 3) |
| Relay hardening | `RelayHardeningTest`: shared replay store across instances, concurrency, fail-closed store, malformed JSON/signatures, timeouts, engine/identity failures, pass-through, logs, Cloud Run guard, test vector | **Passed** (9), iteration 8 |
| Make Code-module signer | `tests/make/code_module_sign_test.js` | **Passed** (44 checks) |
| Make test payloads through the signer → relay → engine | `tests/make/run_payloads_against_relay.js` (T1–T5, T7, T9–T12, replay) | **Passed** 2026-10-28 |
| POC image (local container) | iteration 6: `/v1` in the staged image | **Passed** 2026-10-27 |
| Cloud Run (engine revision 3 + relay) | after approval, no-traffic tag first (docs/04) | Planned |
| Isolated Make scenario | section 4, T1–T9 | Planned (needs approval) |
| Parallel run with MacroForge | PDFs to a comparison folder; compare per form | Planned (needs change plan) |
