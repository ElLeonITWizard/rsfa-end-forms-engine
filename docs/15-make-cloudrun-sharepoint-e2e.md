# 15 — First Make → Cloud Run → SharePoint end-to-end: BNZ Boarder (iteration 12)

Status 2026-10-28. **Prepared and tested locally. Nothing deployed or created.**
- No change was made to Google Cloud, Make, Monday or SharePoint.
- Scenarios `8731317`, `9254149` and `8732008` are untouched.
- The engine `rsfa-end-forms-engine-00005-rih` is unchanged.
- Decision: D-044.

**Milestone:** a developer runs one isolated Make scenario and finds the completed BNZ Boarder PDF in a SharePoint
**test** folder, without handling the file.

## 0. What was verified where

| Stage | Where | Result |
|---|---|---|
| 1. Synthetic normalised payload → engine | local (jar) | **Passed.** The T1 payload dated 2026-10-27 through Code module → relay → IAM stand-in → engine gives the verified BNZ Boarder bytes `2ae7bfac…` |
| 1b. The same, relay from the bundle-built container image | local (Docker, read-only filesystem) | **Passed** (28/28 checks) |
| 2. Synthetic payload → deployed private Cloud Run engine | Cloud Run | **Not testable here** (no GCP credentials). Automated in `deploy_relay.sh` step 9: signed BNZ call, replay, stale, unknown bank, wrong secret |
| 3. Isolated Make scenario → engine → PDF | Make | **Not run.** The blueprint structure and every module configuration were validated with Make's validators (§4.4); nothing was imported |
| 4. Make → SharePoint test folder | Make + SharePoint | **Not run.** Needs the test folder (§5), the data store and the relay |
| 5. End-to-end verification of the stored PDF | local | **Passed** with the fake SharePoint (`verify_pdf.py --expect`). For the real file: §6 step 5 |

**Negative tests passed locally** (`tests/make/e2e_scenario_test.js`, 28 checks):
- invalid signature → 401;
- stale timestamp → 401;
- replayed request → 409;
- unknown bank → 422;
- non-BNZ selection, missing data, unparseable address and unapproved item → refused before any call;
- engine 500 and engine timeout → 3 re-signed attempts, then `failed-after-retries`; the next run → filed;
- SharePoint failure → `upload-failed`; the next run → filed once;
- duplicate → skipped before the engine;
- no field value or secret in the relay/engine logs or run records.

## 1. Architecture (smallest reliable path: reuse the HMAC relay)

```text
Make (isolated test scenario, on demand)
  2 Set variables ─ 3 Monday GetItemV2 (approved test item only; "monday" variant) ─ 4 Code: build masterboard.v1 + HMAC
  ─ 7 data store: already filed? ─ 8 repeater ×3 ─ 11 Code: re-sign ─ 12 HTTP POST ──HTTPS + HMAC──▶
                                                    rsfa-end-forms-relay (Cloud Run, public, ENGINE_ROLE=relay)
                                                      HMAC (constant time, 2 secrets) · ±300 s freshness ·
                                                      replay claim in GCS (ifGenerationMatch=0) · 30/min · 1 MiB
                                                      │ Google ID token of its OWN service account (metadata server)
                                                      ▼
                                                    rsfa-end-forms-engine (IAM only, unchanged; relay SA = invoker)
  ◀── JSON {status, documents[{templateId, fileName, pdfBase64, …}], notMigrated, requestDigest} ──
  14 iterate documents ─ 15 SharePoint uploadAFile (TEST folder) ─ 17 data store record (codes, ids, file name, webUrl)
```

**Why a relay (and why it is public):**
- Make's HTTP module cannot present a Google-signed ID token without a service-account JSON key, which is forbidden.
- So Make cannot call the IAM-private engine directly.
- The relay is the only public endpoint. It accepts only signed, fresh, never-replayed requests, and calls the engine
  with its own key-less identity.
- The engine keeps `--no-allow-unauthenticated`.
- Design and security review: docs/07, docs/10 (independent review: no HIGH issues). Implemented since iteration 8.

## 2. Payload: from the Masterboard item to `masterboard.v1`

`make/test-scenario/code-module-sign.js` builds the request. The blueprints embed it verbatim, so Make runs the tested code.
- **synthetic** mode: the built-in fictional payload T1 (item `1000000001`); no Monday read.
- **monday** mode: an **approved test item**, columns mapped **by stable column ID** (module 3 →
  `mappable_column_values.<id>.text`).

| Column ID | Current title (runtime key) | Use |
|---|---|---|
| `text_mm2tsfyq` | Applicant 1 Full Name | name overlay (**required**) |
| `text_mm2tgjpw` | Applicant 2 Full Name | name overlay (optional) |
| `text_mkzvqaw7` | 1st Property Address | split into Street / City / Postcode (**required**) |
| `numeric_mkzvmp91` | Number of rooms proposed to be rented | Text 5 (**required**) |
| `numeric_mkzvk0wg` | Proposed weekly amount(s) per room | Text 6 (**required**) |
| `dropdown_mkzvfgeg` | **Selected Banks** (was "Banks to Send" until 2026-10-25) | must contain `BNZ`; other banks are reported in `otherBanks`, not sent |

**Monday-mode rules:**
- **Banks:** `selection.banks` is `["BNZ"]` only. The engine's routing table also uses the live `Selected Banks`
  labels. The other BNZ forms come back as `notMigrated`: MacroForge stays responsible for them.
- **Address:**
  - `8731317` splits the address with GPT. This POC does not.
  - The split is strict and deterministic: `<street>[, <suburb>], <city> <4-digit postcode>`.
  - Anything else is **refused** (`ADDRESS_UNPARSEABLE`): no guessing, no PDF.
  - This is a known difference from MacroForge. Equivalence is not claimed.
- **Required data:** the engine accepts blanks for BNZ Boarder, so the Code module refuses a missing required value
  (`MISSING_REQUIRED_DATA`) before any call.
- **Idempotency:**
  - `idempotencyKey = <itemId>-bnz-boarder-<sha256(item, data, banks)[:12]>`.
  - The same data never files twice; changed data gives a new key and a new file.
  - The engine's `fileName = <itemId>_BNZ_BOARDER_FORM_2.0_8-24_<digest8>.pdf` holds IDs and a digest only.
- **Dates:** `today` is the Pacific/Auckland date (module 2). The form's two date fields are filled as before.
- **Signatures and declarations:** no signature, adviser certification or customer declaration is filled
  (engine rule 4, unchanged).

## 3. Testing done locally (how to repeat)

```bash
(cd engine && mvn -B package)                         # 97 Java tests
node tests/make/e2e_scenario_test.js                  # 28 checks (real engine + relay processes, fakes for Make/SharePoint)
python3 tools/stage_relay.py --out .local/deploy-relay && bash deploy/relay/make_relay_bundle.sh
# rehearsal of the relay image exactly as Cloud Build builds it, then the same 28 checks against that container:
docker build --no-cache -f deploy/relay/Dockerfile --target relay -t rsfa-end-forms-relay:rehearsal <extracted bundle>/context
RELAY_IMAGE=rsfa-end-forms-relay:rehearsal node tests/make/e2e_scenario_test.js
```

## 4. Make scenario (prepared; import only after approval)

### 4.1 Files

| File | Use |
|---|---|
| `make/e2e-scenario/blueprint.synthetic.json` | **Import first.** Runs T1; no Monday access |
| `make/e2e-scenario/blueprint.monday.json` | Import when a test item is approved; scenario input `itemId` (`interface.monday.json`) |
| `make/e2e-scenario/data-structure.engine-test-runs.json` | Structure of the data store `engine-test-runs` |
| `make/e2e-scenario/scheduling.json` | On demand (no trigger, no webhook URL) |
| `tools/build_make_blueprints.py` | Regenerates the blueprints (tests check they are current) |

### 4.2 Module sequence

| # | Module | Configuration |
|---|---|---|
| 2 | `util:SetVariables` | `today` (NZ date), `runId` (`uuid`), `relayUrl`, `spSite`, `spDrive`, `spFolder`; monday: `approvedItemIds`, `itemId = trim(var.input.itemId)` |
| F1 → 3 | `monday:GetItemV2` (monday only; connection `240128`) | filter F1: `itemId` ∈ `approvedItemIds` **before** the read; board `5026186805`; no subitems |
| 4 | `code:ExecuteCode` (JavaScript) | inputs: mode, column IDs (monday) or T1 (synthetic), `executionId`, `today`, `timestamp = formatDate(now; "X")`, `secret` |
| 5 | Router | route 1: `4.result.refused` exists → **6** record `refused` (code only); route 2 → 7 |
| 7 | `datastore:GetRecord` | key `4.result.idempotencyKey` |
| F2 → 8 | `builtin:BasicRepeater` | 3 repeats; filter F2: `7.status ≠ filed` (**duplicate prevention**) |
| F3 → 9 | `datastore:GetRecord` | filter F3: stop once this run reached a final status |
| 10 | `util:FunctionSleep` | 1 s, 60 s, 120 s (≥ 1 s, so a re-signed retry never reuses a signature) |
| 11 | `code:ExecuteCode` | same inputs as 4: **re-signs** each attempt over the same body |
| 12 | `http:MakeRequest` v4 | POST `relayUrl`; headers `X-RSFA-Timestamp`, `X-RSFA-Signature`; body type Custom `application/json`, body `11.result.body` (the signed bytes); parse response; **return error on HTTP failure: off**; timeout 120 s; no redirects; error handler `Resume` with `statusCode 599` |
| 13 | Router | A `200` + `complete`/`partial`; B `400/401/404/409/413/422` (permanent); C `429/500/502/503/504/599` (retryable) |
| A 14 | `builtin:BasicFeeder` | `12.data.documents` |
| A 15 | `microsoft-sharepoint:uploadAFile` (connection `13527429`) | filter: `status = generated` and `pdfBase64` exists; path mode, manual site/drive/folder from module 2; file name `14.fileName`; data `toBinary(14.pdfBase64; "base64")`; if exists: **replace** (same name = same digest = same content). Error handler → **31** record `upload-failed` + Ignore |
| A 17 | `datastore:AddRecord` | `filed` + engineStatus, requestDigest, fileName, templateId, webUrl, sharepointItemId |
| B 18 / C 19 | `datastore:AddRecord` | `failed` (code) / `retrying` → `failed-after-retries` on the 3rd attempt |

**Scenario settings:**
- sequential processing on;
- **data is confidential** on (the secret and the PDFs are not kept in the execution history);
- max cycles 1;
- on demand.

**Run-level diagnostics:**
- The data store record holds: status, code, item ID, idempotency key, correlation ID (= the relay/engine log key),
  run ID, attempt, HTTP status, file name, SharePoint `webUrl` and item ID, request digest.
- It never holds field values.
- Relay logs: `correlationId`, status, outcome.

### 4.3 Placeholders to set after import (never committed)

| Placeholder | Value |
|---|---|
| `<<RELAY_URL>>` | the relay URL printed by `deploy_relay.sh` (`https://rsfa-end-forms-relay-….run.app`) |
| `<<RELAY_TEST_SECRET>>` | in Cloud Shell: `gcloud secrets versions access latest --secret=rsfa-make-relay-hmac` → paste into **both** Code modules (4 and 11); not stored anywhere else |
| `<<SP_SITE_ID>>`, `<<SP_DRIVE_ID>>` | via the module's ID Finder for the test location (§5) |
| `<<SP_TEST_FOLDER_PATH>>` | the test folder path (§5) |
| `<<APPROVED_ITEM_IDS>>` (monday) | the approved test item ID(s), comma-separated |
| data store `0` | select `engine-test-runs` in modules 6, 7, 9, 17, 18, 19, 31 |
| connections | Monday `240128` and SharePoint `13527429` are RSFA's existing connections (used by `9498962`); confirm in the editor that they are the intended accounts |

### 4.4 Validation done with Make (read-only validators, 2026-10-28)

- `validate_blueprint_schema`: **valid**.
- `validate_module_configuration`: **valid** for `http:MakeRequest` v4, `microsoft-sharepoint:uploadAFile` v1 (connection
  `13527429`), `monday:GetItemV2` v2 (connection `240128`), `code:ExecuteCode` v1, `util:FunctionSleep`,
  `builtin:BasicRepeater`, `util:SetVariables`.
  - The validator found one error, `SetVariables` has no `scope`; it was fixed.
- `validate_scenario_interface`: the `itemId` input is valid.
- **Not validated:**
  - `datastore:AddRecord` and `GetRecord` mappings (they depend on the data store, which does not exist yet);
  - the `var.input.itemId` reference;
  - `formatDate(now; "X")` (verify both in the expression tester at import).
- `code:ExecuteCode` is a **paid-plan app** (premium tier 1): verify that RSFA's Make plan includes it.

## 5. SharePoint destination (confirmed facts, and what RSFA must decide)

**Confirmed (read-only, 2026-10-28):**
- **No existing Make module files end forms today.** MacroForge returns no file to Make (`9254149` only sets the
  status). Its PDFs reach SharePoint through MacroForge's local/OneDrive sync, which is undocumented
  (`AUTO-MACROFORGE-001`).
- **Client documents** live in site `RealSavvyClients`, library `Documents`, one folder per Client Profile
  (`AUTO-SP-FOLDER-001`). **Not to be used for this test.**
- **Make writes to SharePoint today** with `microsoft-sharepoint:uploadAFile`, connection `13527429`, in scenario
  `9498962` (Aircall transcripts, into client folders).
- **Candidate test location (proposal, needs RSFA confirmation):** site `RealSavvyOperations`, library `Documents`,
  existing project folder `6. IT_OPS & PROJECTS/MacroForge (MF) end forms`.
  - New subfolder **`End Forms Engine TEST`**, to be created by RSFA.
  - It is outside the client library and next to the MacroForge project material.
  - Nothing was created.

**RSFA must confirm:**
1. the test folder;
2. that the connection `13527429` account may write there;
3. who may read the folder. The test PDFs contain only fictional data in synthetic mode, and the approved test
   item's data in monday mode.

**Production filing** (the client folder, the naming convention, Monday status updates) is out of scope until the test
passes.

## 6. Synthetic end-to-end test procedure (after the approvals in §9)

1. **Relay** (Cloud Shell, bundle `rsfa-end-forms-relay-bundle.tar.gz`, SHA-256 below):
   - `bash deploy_relay.sh` (plan) → review → `APPLY=1 bash deploy_relay.sh`.
   - Step 9 must report all checks passed while the relay is still **private**: signed BNZ → 200 with a PDF; replay →
     409; stale → 401; unknown bank → 422; wrong secret → 401; engine anonymous → 403; logs clean.
   - Then, after its own approval, `APPLY=1 PHASE=expose bash deploy_relay.sh`: unsigned public call → 401.
2. **Make:**
   - create the data store `engine-test-runs` (§4.1);
   - import `blueprint.synthetic.json` into the RSFA test team (inactive, on demand);
   - set the placeholders (§4.3).
3. **Run once** (synthetic). Expected:
   - record `filed`, `httpStatus 200`, `engineStatus complete`;
   - a `webUrl` in the test folder;
   - `fileName 1000000001_BNZ_BOARDER_FORM_2.0_8-24_<digest>.pdf`.
4. **Run once again:** module 8 is filtered out (`filed`). No engine call, no new file version.
5. **Verify the stored PDF:**
   - Download it; then `python3 deploy/verify_pdf.py <file> --expect expect.json`, with T1's values and today's date
     as `DDMMYY` in Date 1/2;
   - it must print `"ok": true`, no ticked box and no signature value;
   - open it in a PDF viewer and check it reads correctly.
6. **Negative checks in Make:**
   - set a wrong secret → record `failed` `INVALID_SIGNATURE`;
   - set the relay URL to a wrong path → `failed` (404);
   - optional: point the folder to a non-existent path → `upload-failed`, then fix and run again → `filed`.
7. **Monday mode** (only with an approved test item):
   - import `blueprint.monday.json`, set `approvedItemIds`, run with `itemId`;
   - same checks;
   - an item not on the list → no Monday read.

## 7. Relay deployment package

| | |
|---|---|
| Bundle | `.local/deploy-relay/rsfa-end-forms-relay-bundle.tar.gz` (delivered in the session; not committed) |
| SHA-256 | `b242b08caa153ceea354fae4c5cfb4c21a8ed1406bdcf78d2108c78287d654a4` (built twice, identical) |
| Built from | commit `7cdf8cf`; context 60 files, identical to the commit; no PDF |
| Rehearsal | `docker build --no-cache --target relay` from the extracted archive (tests inside: 30.9 s step): **passed**; that image passed the 28 e2e checks |

### Resource and IAM inventory

| Item | New? | Detail |
|---|---|---|
| API `secretmanager.googleapis.com` | new | — |
| SA `rsfa-end-forms-relay@…` | new | no project roles |
| Secret `rsfa-make-relay-hmac` | new | 32 random bytes (hex), generated in Cloud Shell, never printed; replication `australia-southeast1`; relay SA: `secretAccessor` on this secret only |
| Bucket `gs://rsfa-end-forms-engine-relay-replay` | new | uniform access, public access prevention, delete after 1 day; relay SA: `objectCreator` only |
| **Engine IAM** | **one binding** | relay SA → `roles/run.invoker` on `rsfa-end-forms-engine`. Nothing else on the engine: revision, image, env and traffic are checked unchanged |
| Cloud Run `rsfa-end-forms-relay` | new | `ENGINE_ROLE=relay`, target = engine URL, GCS replay store, secret pinned to its version; 1 vCPU, 512 MiB, 0–2 instances, concurrency 4, timeout 120 s; **private until `PHASE=expose`** |
| `allUsers` → `run.invoker` on the relay | new, **separate approval** | the only public endpoint |
| Make: data store `engine-test-runs`, 1 inactive scenario | new, in Make | approval needed |
| SharePoint: test subfolder | new | created by RSFA |
| Engine image or revision, Monday, `8731317` / `9254149` / `8732008` | unchanged | — |

### Monitoring

- **Relay logs** (Cloud Logging, service `rsfa-end-forms-relay`): one line per request with `correlationId`, HTTP
  status and outcome (`forwarded`, `replayed`, `invalid_signature`, `stale`, `upstream_timeout`,
  `upstream_auth_403`…). No values.
- **Suggested log-based alert** (free): `outcome=upstream_auth_403` or a burst of `invalid_signature` (a leaked or
  wrong secret).
- **Make:** the data store record per idempotency key; the execution history (confidential mode keeps the module
  data out).

### Rollback (`rollback_relay.sh`, plan by default)

| Command | Effect |
|---|---|
| `CLOSE=1 APPLY=1` | Removes public access. Instant; cuts Make off; nothing deleted |
| `REMOVE=1 APPLY=1` | Also removes the relay SA's engine invoker binding, deletes the relay service and disables the SA |
| `PURGE=1 APPLY=1` | Also deletes the secret and the replay bucket |

- **Make:** deactivate or delete the test scenario.
- **SharePoint:** delete the test folder.
- **Engine:** needs no rollback.

### Expected cost (estimates; verify against the pricing pages)

| Item | Expected |
|---|---|
| Relay (Cloud Run, min 0) | cents per month at test volume |
| Secret Manager (1 active version, about US$0.06/month; access at start) | < US$0.10/month |
| Replay bucket (a few bytes, deleted daily) | ≈ 0 |
| Cloud Build | free minutes or ≈ US$0.10/build |
| Artifact Registry | ≈ US$0.02/image/month |
| **Total** | **< US$1/month** |
| Make | operations per run: about 15–25. The `code` app needs a paid plan |

### Security review (summary)

- **Unchanged and held:** the engine stays IAM-private; no JSON keys; the relay authenticates, checks integrity,
  freshness and replay, and caps the rate and size.
- **Make side:**
  - "Data is confidential" protects the secret input from execution history (docs/10 L5).
  - Anyone with edit access to the Make scenario can read the secret. Keep the scenario in a restricted team, and use
    a different secret per environment (docs/10 L2).
- **Monday mode:** reads only items on the approved list (filter before the read, re-checked in code).
- **SharePoint:** writes only to the configured test folder with deterministic names; `replace` makes reruns
  idempotent.
- **To verify at deployment:** docs/10 M3. With `objectCreator` only, Cloud Storage may answer 403 instead of 412.
  `deploy_relay.sh` step 9's replay check reports it: 409 is expected, and 503 means apply the documented fix.

## 8. Still untested

- the relay on Cloud Run (step 9 of the script);
- Make's runtime (import, expressions `var.input`, `formatDate X`, data store mappings, `toBinary`);
- SharePoint upload permissions and the real test folder;
- the Monday read of a real approved test item. None exists: the Masterboard search found no test item, and only
  fictional items were used locally;
- equivalence with MacroForge (notably the address split).

## 9. Approvals needed from RSFA (in order)

1. **Relay infrastructure, private:** the exact plan output of `deploy_relay.sh` (§7 inventory, including the one
   engine invoker binding).
2. **Public exposure** of the relay (`PHASE=expose`).
3. **Make:** create the data store and import the synthetic scenario (inactive, on demand) in the test team; confirm
   the plan includes the Code app.
4. **SharePoint:** the test folder and the connection account's access to it.
5. **Optional, for monday mode:** a synthetic Masterboard test item, and its ID on the approved list.
