# 01 — Context and architecture

Evidence labels: **[live]** = read-only from the live Make blueprint/hook config on 2026-10-27;
**[export]** = from the `.mforge` export (SHA-256 `8052c17b…797c`) or the supplied files;
**[doc]** = RSFA technical knowledge base (`AUTO-MACROFORGE-001`); **[inference]** = not verified.

## Business context

RSFA is a New Zealand financial-advice business. Advisers prepare bank "end forms" (boarder
declarations, handover forms, FATCA/CRS forms, loan-structure forms…) for ANZ, ASB, GHL, Kiwibank,
WBC and BNZ. Today the **licensed, desktop-based MacroForge** fills these PDFs. RSFA intends to retire it.
Only the **filling engine** is replaced. The adviser workflow must not change.

## Current production flow

```text
Masterboard: End Forms (Monday board 5026186805)
  │ adviser changes "End Forms Filling Status" (column id `status`)
  ▼
Make 8731317 "M.com to Macroforge: Trigger Macroforge End Forms Filling Flow (Feb 2026)"
  1 monday watch (hook 3905270, column `status`)  → 13 set variables → 42 pick webhook by userId
  4 get item + subitems → 19/20/21 collect address columns → 26 OpenAI gpt-4o-mini splits addresses
  8 JavaScript builds `result` → 6 compose JSON body → 3 HTTP POST (noAuth) to MacroForge webhook
  ▼
MacroForge "Flow C — End Forms Flow Filling" (desktop app; webhook trigger "Masterboard Main Webhook")
  for each lender in "Banks to Send" (Contains ASB | KiwiBank | WBC | ANZ | BNZ):
     PDF Batch Filler → files saved to an export folder (local/synced, path not recorded here)
  All Banks_DOAD.pdf (always) → "Send to Zapier/Make" callback
  ▼
Make 9254149 "Macroforge to M.com: End Forms Filled (May 2026)"
  webhook (hook 4136771) → Monday `status` := "Filled"  (only `id` is used from the payload)
```

Facts and caveats:
- **Re-trigger risk (`Filled` → `8731317`), reviewed read-only on 2026-10-27:**
  - **[live]** Hook `3905270` config is `{columnId: "status"}`, with no value or previous-value filter. `8731317` has no
    filter on any module, and no module reads the trigger label (only `pulseId` and `userId`). By design, any `status`
    change that Monday emits, including `Filled` written by `9254149`, runs the whole chain and POSTs to MacroForge again.
  - **[live, 1 sample]** Make's execution history returned a single pair, as follows. `8731317` ran 2026-10-01 23:16:43Z
    (SUCCESS, 20 ops), then `9254149` ran 23:17:09Z (SUCCESS, 2 ops). **No `8731317` execution followed.** Run details (item id,
    label, HTTP status) were not retrievable, and no older runs were returned (retention or API limit unknown).
  - **Conclusion:** possible by design, **not observed** in the only available sample. Why it did not fire is unknown.
    Possible reasons: the item was already `Filled`; Monday did not emit the event for an API write; the callback `id` pointed
    elsewhere (module 2 errors are ignored).
  - **Read-only next checks:** the Monday activity log for column `status` around 2026-10-01 23:16–23:20 UTC, or
    the two runs in Make's execution log UI.
  - **Proposed guard (production change, needs a change plan and RSFA approval, not done):** a filter after module 1
    so `8731317` continues only for the intended label (e.g. `Reviewed`).
- **[export]** `module-3-output-bundles.json` is only the HTTP *response* envelope: `200 {"status":"routed","success":true}`.
  It proves dispatch, **not completion**, and contains no request body.
- **[live]** Labels on `status`: `Filled`, `Reviewed`, `Reviewing`, `Cancelled`, and (seen 2026-10-27 in the board
  schema) `Historical`.
- **[live, board schema 2026-10-27, read-only, columns only]** Board `5026186805` is titled "SOP Masterboard" (401 columns).
  - Column `dropdown_mkzvfgeg`, which the Make contract and MacroForge call "Banks to Send", is now titled **"Selected Banks"**.
    Its labels are `ASB, ANZ, BNZ, KiwiBank, WBC`.
  - Some dropdown labels differ from the values that MacroForge rules test (docs/06).
  - Subitem titles are e.g. `Fixed term (months)`, `Loan term (years)`.
- **[live, blueprint read 2026-10-28]** Module 8 keys `result` by `col.title || col.id`, so keys follow the **live
  column titles**: the rename above changes the key MacroForge receives. Full drift analysis: docs/08 (D-030).
- **[live]** Error handling: GetItem/HTTP retry 3× every 5 min (incomplete-executions queue on). An OpenAI failure resumes
  with `{}` (address parts silently missing). A Code-module failure emails via `8732008`.

## Data contract the engine consumes (`data` = Make's `result` object)

**[live]** `result` keys are Monday **column titles** (not IDs), built at run time:
- Text/dropdown/numeric/etc. columns: `.text` string or `null`. Checkbox columns: boolean. Date columns: `DDMMYYYY`.
- Columns sharing a title are merged into **arrays**. Seven titles are affected, e.g. `Loan processing fee`, `Cash contribution`.
  The engine fails these with `AMBIGUOUS_SOURCE_VALUE` instead of guessing.
- Derived keys:
  - `<date field> Day|Month|Year` for 6 date fields
  - `<address field> Street|Suburb|City|Province|Postcode|Country` for 10 address fields (from OpenAI)
- `Subitems`: an array of `{name, values{…}}`. It is not consumed by the engine yet.
- **[export]** MacroForge normalises incoming keys by turning `/` and `.` into `_`. Evidence: 0 of 476 keys in its
  stored sample payload contain either character, while 30 Make contract keys do. Manifests declare
  `"sourceKeyNormalization": "macroforge-v1"` and the engine applies the same rule.
- **[inference]** The column list cached in the Make blueprint is older than the live board. 11 keys used by imported mappings
  (e.g. `Applicant 1 Full Name`, `Adviser Address`) are absent from it but present in MacroForge's sample.
  TODO: verify against the live board before cutover.
- Renaming a Monday column silently changes the contract. The engine reports `SOURCE_KEY_ABSENT` warnings so drift is visible.

## Target architecture (POC → later)

The Make integration contract and proposal (auth, filing, Monday status without recursion, test plan) are in
docs/07. The sketch below is the original target.

```text
Make 8731317 (unchanged trigger + preprocessing)
  ├─► MacroForge webhook (unchanged, during parallel run)
  └─► [later] POST /generate per template  ──► end-forms-engine (Cloud Run, private)
                                                 ├─ mappings/ (baked into the image, from git)
                                                 └─ templates/ (read-only GCS volume mount, private bucket)
       ◄── application/pdf ──┘
  Make uploads PDFs to SharePoint, then sets Monday `status` (as today, via its own modules)
```

Responsibilities:

| Concern | POC (now) | Production (later, needs approval) |
|---|---|---|
| Template choice per lender | caller passes `templateId@templateVersion` | Make loops over `Banks to Send` → template list (to design) |
| Templates | `.local/generated/templates/` | private GCS bucket mounted read-only at `/templates` |
| Mappings | `mappings/` in git | same, baked into the image (immutable per release) |
| Auth | `ENGINE_AUTH=API_KEY` on loopback | `PLATFORM_IAM` (`--no-allow-unauthenticated`), or API key from Secret Manager (see decisions) |
| Filing / Monday update | not done | Make (SharePoint upload + status), unchanged ownership |

## Security boundaries

- The engine never stores request data or PDFs on disk in `serve` mode. It never logs bodies or values
  (verified in a local run: 0 data values in the server log).
- Request body ≤ 1 MiB. Template ≤ 25 MiB, loaded only from configured directories by a safe filename
  (`[A-Za-z0-9._-]+.pdf`) after a path-normalisation check. Template SHA-256 must match the manifest.
- Outbound calls: none. There is no OpenAI, Monday, SharePoint or GCP client in the engine.
- Known legacy risks found during inspection (not fixed here; production changes need RSFA approval):
  - **[live]** Make test modules 43/44 in `8731317` hold a full real-looking client payload and the MacroForge webhook URL.
  - **[live]** The MacroForge webhook URLs are hard-coded and the HTTP call is unauthenticated.
  - **[live]** Every run sends client address data to OpenAI.
  - **[export]** The `.mforge` itself contains a real client `sample_payload`, `flow_data.__raw_webhook_payload` and
    `current_parsed_pdf_data`, and a webhook URL. Treat the file as sensitive.
