# 11 — Isolated Make test scenario: module-by-module implementation guide (designed, NOT created)

**Do not build this until RSFA approves it, and only after the relay exists (docs/10 §8).** It replaces the outline in
docs/07 section 4.

**It must not touch:**
- scenarios `8731317`, `9254149`, `8732008`;
- board `5026186805` or any Monday column;
- any production SharePoint folder.

**Module identifiers** were checked with the Make API on 2026-10-28 (`app-modules_list` / `app-module_get`,
organisation `882256`). Connection, data store, site, drive and folder IDs are **not** known: they are chosen in the
Make editor when building. None is invented here.

## 1. Flow

```text
 1 gateway:CustomWebHook v1   "RSFA TEST – End Forms Engine (synthetic)"   (JSON pass-through ON)
   └ filter F1: itemId matches ^10000000[0-9]{2}$  (synthetic range only)
 2 util:SetVariables v1       today, runId, idempotencyKey
 3 datastore:GetRecord v1     store "engine-test-runs", key = idempotencyKey
 4 builtin:BasicRouter v1
   ├ R0 filter: 3.status = "filed"            → 5 gateway:WebhookRespond v1 (200, "duplicate", no call)
   └ R1 filter: 3.status ≠ "filed"
      6 builtin:BasicRepeater v1  repeats 3
      7 datastore:GetRecord v1    same key; filter F2: status not in (filed, failed, failed-after-retries)
      8 util:FunctionSleep v1     delay = if(6.i = 1; 1; 60 * (6.i - 1))   (1 s, 60 s, 120 s)
      9 code:ExecuteCode v1       make/test-scenario/code-module-sign.js  → body, timestamp, signature, ids
     10 http:MakeRequest v4       POST relay; raw body; error handler → builtin:Resume (statusCode 599)
     11 builtin:BasicRouter v1
        ├ A  filter: 10.statusCode = 200
        │    12 builtin:BasicFeeder v1     10.data.documents
        │       filter F3: status = "generated" AND pdfBase64 exists
        │    13 microsoft-sharepoint:uploadAFile v1   TEST folder only; conflictBehavior = replace
        │    14 builtin:BasicAggregator v1  (source 12) fileName, status, code, webUrl
        │    15 datastore:AddRecord v1      status = filed | filed-partial | dry-run
        │    16 gateway:WebhookRespond v1   200 summary
        ├ B  filter: statusCode in (400,401,404,409,413,422)   (never retried)
        │    17 datastore:AddRecord v1      status = failed, codes
        │    18 gateway:WebhookRespond v1   summary
        └ C  filter: statusCode in (429,502,503,504,599) OR 10.data.retryable = true
             19 datastore:AddRecord v1      status = if(6.i = 3; "failed-after-retries"; "retrying"), attempt = 6.i
                filter F4: 6.i = 3
             20 gateway:WebhookRespond v1   summary "failed after retries"
```

**Why a repeater instead of Make's `Break` (retry) handler:**
- `Break` re-runs the failed module with the **same** inputs: the same timestamp and signature. After the first
  attempt reached the relay, that signature is already claimed (409 `REPLAYED_REQUEST`), and after 5 minutes it is
  stale (401).
- With the repeater, every attempt re-runs module 9, so it is **re-signed** with a fresh timestamp over the same body.
- Module 7's filter stops further attempts once one succeeded or failed permanently.
- Total wait ≤ 3 min plus 3 × the 120 s HTTP timeout. This stays within Make's 40-minute scenario limit (verify on
  RSFA's plan).

## 2. Configuration per module

| # | Field | Value |
|---|---|---|
| 1 | Webhook | new webhook in the test team; **JSON pass-through** on (gives the raw text as `value`); data structure from `make/test-scenario/payloads/T1.json`. The URL is a secret for the tester only, never stored in git |
| F1 | Condition | `{{1.itemId}}` *Text: matches pattern* `^10000000[0-9]{2}$`. Module 9 re-checks this and also requires board `5026186805` and a `T<n>` test case |
| 2 | Variables | `today = {{formatDate(now; "YYYY-MM-DD"; "Pacific/Auckland")}}`; `runId = {{uuid}}` (or the execution-id system variable, if present on the plan: verify); `idempotencyKey = {{1.itemId}}-{{1.testCase}}-{{today}}` |
| 3, 7 | Data store | `engine-test-runs` (structure in §3), key `{{2.idempotencyKey}}`; "Return wrong" off |
| 6 | Repeats | `3`, initial value `1` |
| 9 | Code | language JavaScript; code = `make/test-scenario/code-module-sign.js` (verbatim). Inputs: `payload = {{1.value}}` (pass-through text); `executionId = {{2.runId}}`; `today = {{2.today}}`; `timestamp = {{formatDate(now; "X")}}` (unix seconds: **verify the `X` token** in the expression tester; alternative `{{round(now / 1000)}}`); `secret` = the test secret (§4) |
| 10 | HTTP | URL `https://<relay>/v1/masterboard/documents` (custom variable); method POST; headers `X-RSFA-Timestamp = {{9.timestamp}}`, `X-RSFA-Signature = {{9.signature}}`; **Body content type: Custom**, content type value `application/json`, body content `{{9.body}}` (raw: no re-serialisation, so the signed bytes are sent); parse response yes; advanced: **stop on HTTP error = no** (4xx/5xx are routed, not thrown), timeout 120 s, request compressed content no, follow redirects no |
| 10 error handler | builtin:Resume | substitute output `statusCode = 599` (network error or timeout → route C, retried with re-signing) |
| F3 | Condition | `{{12.status}} = generated` AND `{{12.pdfBase64}}` exists (dry runs return no PDF) |
| 13 | SharePoint | Enter: folder **path**, manually: Site ID and Drive ID of the **test** library RSFA designates; folder `/End Forms Engine TEST/{{1.itemId}}`; file name `{{12.fileName}}` (`<itemId>_<templateId>_<version>_<digest8>.pdf`, no client data); data `{{toBinary(12.pdfBase64; "base64")}}`; if exists: **Replace** (same name ⇒ same requestDigest ⇒ same content) |
| 15 | Record | `status = {{if(10.data.status = "partial"; "filed-partial"; if(length(14.array) = 0; "dry-run"; "filed"))}}`, plus the fields in §3 |
| 5, 16, 18, 20 | Response | status 200, body = the same JSON as the data-store record (codes and file names only) |

**Scenario settings:**
- **Sequential processing:** on (two runs of one key cannot overlap).
- **Allow storing incomplete executions:** off (retries are explicit).
- **Data is confidential:** on (inputs and outputs of modules, including the secret and the PDFs, are not kept in the
  execution history; the data store record is the trace).
- **Max number of cycles:** 1.
- **Schedule:** on demand / immediately on webhook.
- Kept **inactive** except during a test session.

## 3. Data store `engine-test-runs` (traceable summary; no client data)

| Field | Type | Content |
|---|---|---|
| key | — | `idempotencyKey` (`<itemId>-<testCase>-<YYYY-MM-DD>`) |
| testCase, itemId | text | from the payload (synthetic) |
| correlationId | text | `mb-5026186805-<itemId>-<runId>` (also returned by the relay as `X-Correlation-Id`) |
| attempt | number | repeater index of the last attempt |
| httpStatus | number | relay or engine status |
| status | text | `filed`, `filed-partial`, `dry-run`, `failed`, `retrying`, `failed-after-retries` |
| code | text | relay or engine error code |
| requestDigest | text | from the response |
| documents | text | `templateId:status[:code]` list |
| fileNames, fileUrls | text | uploaded test files |
| notMigratedCount | number | forms MacroForge would still produce |
| finishedAt | date | `now` |

## 4. HMAC secret in Make (verify before building)

- **Native option (preferred if it works):** compute the header in module 10 directly:
  `v1={{sha256(9.timestamp + "." + 9.body; "hex"; <secret>)}}`. Use it only after the test vector
  `contracts/hmac-test-vector.json` reproduces `3089d368…601b` in Make's expression tester.
  - **Not verified:** whether `sha256` with a key computes an HMAC. help.make.com is not reachable from this
    environment, and one Make Community thread reports a mismatch.
- **Code-module option (implemented and tested, no external code):**
  - Module 9 computes SHA-256 and HMAC itself in plain JavaScript.
  - Checked against Node's crypto (UTF-8, macrons, emoji, CRLF, escapes, block-boundary lengths, long keys), the
    test vector, and the Java relay (`tests/make/code_module_sign_test.js`, 44 checks).
  - `code:ExecuteCode` is a **premium** module (tier 1 = paid plans); `8731317` already uses it.
- **Where the secret lives:**
  - A **test-only** secret, never the production one.
  - Stored as a team custom variable or typed into module 9's input.
  - With "Data is confidential" on, it does not appear in execution history.
  - **Not verified:** whether RSFA's plan offers custom variables and who can read them. Scenario editors can always
    see module configuration.

## 5. Test cases (payloads in `make/test-scenario/payloads/`, fictional data)

All rows except T6 and T8 (Make-side behaviour) were run locally on 2026-10-28 through the same Code module against the real jar
(relay → IAM stand-in → engine): `node tests/make/run_payloads_against_relay.js`. Result: **all passed**.

| Case | Payload | Expected (verified locally where marked ✔) |
|---|---|---|
| T1 | `T1.json` BNZ | ✔ 200 `complete`; 1 PDF (BNZ Boarder); `notMigrated` 6; file `1000000011_BNZ_BOARDER_FORM_2.0_8-24_<digest>.pdf` |
| T2 | `T2.json` ASB | ✔ 200 `complete`; GHL Individual + GHL Trust (acceptance); ASB Boarder blocked, not routed |
| T3 | `T3.json` dry run | ✔ 200; statuses only, no PDF; record `dry-run`, nothing uploaded |
| T4 | `T4.json` contradictory | ✔ 422; Trust `PRECONDITION_FAILED GHL-TRUST-OUT-NEVER-*`; Individual refused (no individual's name); nothing uploaded |
| T5 | `T5.json` unknown bank | ✔ 422 `UNKNOWN_BANK`; nothing uploaded |
| T6 | `T6.json` (= T1 again, same day) | stopped at R0: "duplicate", no relay call, no new file |
| T7 | T1 with a wrong secret | ✔ 401 `INVALID_SIGNATURE`; route B; nothing uploaded |
| T8 | T1 with the relay URL pointing at a stopped revision | 502/503/599 ×3, re-signed each time, record `failed-after-retries` |
| T9 | T1 with `timestamp` = now − 600 | ✔ 401 `STALE_TIMESTAMP` |
| T10 | `T10.json` BNZ + ASB | ✔ 200; 3 PDFs in one response; 3 uploads |
| T11 | `T11.json` per-document | ✔ 200 `partial`; BNZ + GHL Individual uploaded; GHL Trust failed (missing trust name); record `filed-partial` |
| T12 | `T12.json` individual client, ASB | ✔ 200 `partial`; GHL Individual only; Trust refused (missing trust name). Illustrates RSFA-DECISION-3 |

The same run also confirmed that an **exact replay** of a signed request is refused (409).

**Acceptance for the Make phase:**
- every case matches;
- files exist only under `/End Forms Engine TEST/`;
- one data-store record per run;
- the execution history of `8731317` / `9254149` / `8732008` is unchanged during the test window;
- no data values appear in the relay, engine or Make logs (Make with "Data is confidential").

## 6. Recovery and rollback

- **Stop:** deactivate the test scenario (its only trigger is its own webhook).
- **Clean up:**
  - delete the `/End Forms Engine TEST/` folder contents;
  - delete the data store records, or the store itself;
  - delete the scenario and its webhook.
- **Production untouched:** no step uses a production scenario, board, column or folder, so nothing in production
  needs rolling back.
- **Relay side:** remove the relay's `allUsers` binding to cut Make off at once (docs/10 §6).
