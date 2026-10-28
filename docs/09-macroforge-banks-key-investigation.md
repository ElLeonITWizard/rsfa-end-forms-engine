# 09 — "Banks to Send" → "Selected Banks": production investigation of Make `8731317` (2026-10-28)

**Production was not modified.** Everything below was read-only (Make API, Monday activity log, the MacroForge export)
or ran locally on synthetic data.

## Verdict

| Question | Answer | Evidence class |
|---|---|---|
| Was the column renamed? When, and only the title? | **Yes.** `dropdown_mkzvfgeg` (`dropdown`) `Banks to Send` → `Selected Banks` on **2026-10-25 06:36:07 UTC**. Same ID, type and labels | **Confirmed, live** (Monday activity log `update_column_name`; board schema 2026-10-27) |
| Does `8731317` reference the bank column by title or by ID? | **Neither, directly.** No live module, filter or mapping mentions it. Module 8 keys **every** column by its runtime title: `const key = col.title \|\| col.id`. | **Confirmed, live** (blueprint 2026-10-28) |
| Does module 4 deliver the *current* title at run time? | **Yes.** `monday:GetItemV2` v2 outputs `column_values[].title` (module definition). | **Confirmed** (Make module definition) |
| So what does MacroForge receive now? | `result["Selected Banks"]`; **no** `result["Banks to Send"]` | **Reproduced locally** with the production module-8 code and synthetic column values |
| What does MacroForge test? | Flow C gates all 5 lender batches on `Banks to Send Contains ASB / KiwiBank / WBC / ANZ / BNZ`. The export was taken **2026-10-27, after the rename**, and still says so. | **Confirmed** (MacroForge export, structure only) |
| Are bank forms actually lost in production? | **Not observed.** No `8731317` execution since 2026-10-01 (last run SUCCESS). Make keeps no execution detail for it, so no payload could be inspected. | **Unknown** |
| How does MacroForge evaluate `Contains` on a missing key? | Not documented anywhere available; MacroForge is a desktop app that must not be called from here. Most likely `false` (lender block skipped, only `All Banks_DOAD.pdf`), possibly an error. | **Inference** |

**Conclusion:** the mechanism is confirmed on the Make side and reproduced. The final step, MacroForge's behaviour
with the key missing, is not verified, and no production run has happened since the rename. Under the iteration's stop
conditions this is **not a conclusively confirmed production defect**. Production was therefore **not changed**, and the
fix below is **prepared and awaiting approval**.

**Risk while it stays open:** the next adviser status change on the SOP Masterboard runs the chain. If MacroForge treats
the missing key as false, that run produces only `All Banks_DOAD.pdf` and no lender forms. No error is raised, so the
loss could go unnoticed.

## What was inspected

- **Scenario `8731317`** (`M.com to Macroforge: Trigger Macroforge End Forms Filling Flow (Feb 2026)`):
  - **State:** active, not paused, scheduling `immediately`, hook `3905270`, last edit **2026-07-16T22:05:49Z** (blueprint
    identical to the copy read on 2026-10-28 01:37).
  - **Flow (no routers, no filters):** `1 monday:watchBoardColumnValuesV2` → `13 util:SetVariables` → `42 util:SetVariable2` →
    `4 monday:GetItemV2` (Break) → `19 builtin:BasicFeeder` → `20 util:ComposeTransformer` (Ignore) →
    `21 util:TextAggregator` → `26 openai-gpt-3:transformTextToStructuredData` (Resume) →
    `8 code:ExecuteCode` (error → `scenario-service:CallSubscenario` = `8732008`) → `6 util:ComposeTransformer` →
    `3 http:MakeRequest` (Break).
  - **"Banks to Send" appears only in cached metadata:** module 4's output interface label, a designer sample, and two
    orphaned (unconnected, never executed) designer modules. None is evaluated at run time.
  - **Module 6** wraps `"result": {{8.result}}`; module 3 POSTs `{{6.value}}` as a JSON string to the per-user
    MacroForge webhook chosen in module 42 (URLs not reproduced here).
- **Scenario `9254149`** (MacroForge → Monday): a webhook plus one `ChangeMultipleColumnValuesV2` writing `status`. It
  does not read the bank key, so it is **unaffected**.
- **Scenario `8732008`:** error notification only, called by module 8's error handler. **Unaffected.**
- **Other renames on 2026-10-25 (activity log):**
  - `board_relation_mkzv610f` `Related Contacts` → `Contacts`;
  - `lookup_mm8fw9zw` `Client Profile` → `Client Profile from Contacts`;
  - board name `Masterboard: End Forms` → `Masterboard: from SOP` → `SOP Masterboard`.

  No MacroForge mapping or condition uses `Related Contacts` or `Client Profile` (export checked), so **no other
  production impact was found**. Older renames (docs/08 #4) predate the 90-day activity-log window.

## Reproduction (synthetic, local)

`node tests/make/module8_banks_alias_test.js` runs the **production module-8 code** (kept in `.local/`, never committed)
on synthetic column values. 12 checks passed:

| Check | Result |
|---|---|
| Live title `Selected Banks` | `Banks to Send` is **absent** from `result` (reproduces the gap MacroForge would see) |
| Old title `Banks to Send` | key present (pre-2026-10-25 behaviour) |
| Proposed code, each of ASB / ANZ / BNZ / KiwiBank / WBC | the value is visible under both keys |
| Proposed code, multi-selection | `ANZ, KiwiBank, WBC` kept; an unselected bank does not match |
| Proposed code, every other key | **byte-identical** payload (dates, booleans, split dates, subitems) |
| Proposed code, empty selection / title renamed back / column deleted | same as before / no change / no key, no exception |

## Proposed correction (prepared, NOT applied)

**Target:** scenario `8731317`, module **8** (`code:ExecuteCode`), field `codeEditorJavascript`. Nothing else changes:
no connection, hook, schedule, filter, router, error handler, variable or other module. The scenario is not disabled.

**Original (line in production today):**
```js
const result = mapColumns(input.column_values);
```
**Proposed (the same line, followed by the snippet in `make/8731317/module8-banks-alias.snippet.js`):**
```js
const result = mapColumns(input.column_values);
const BANKS_COLUMN_ID = "dropdown_mkzvfgeg";
const LEGACY_BANKS_KEY = "Banks to Send";
const banksColumn = (input.column_values || []).find(col => col && col.id === BANKS_COLUMN_ID);
if (banksColumn && !(LEGACY_BANKS_KEY in result)) {
  result[LEGACY_BANKS_KEY] = extractColumnValue(banksColumn);
}
```

**Expected behavioural difference:** `result` again contains `"Banks to Send": "<selected labels>"`. It is read from
the stable column ID, so future renames of that column cannot break it. `"Selected Banks"` stays, so the payload only
gains one key (MacroForge already receives ~400 keys and ignores those it does not map; inference from the export,
where conditions and mappings name only the keys they use).

**Alternatives considered:**
- **Rename the column back to "Banks to Send":** no Make change. But it reverts a deliberate board change made on
  2026-10-25 and needs separate approval (a Monday change).
- **Edit MacroForge Flow C's 5 conditions:** MacroForge is the system being replaced; editing it moves the dependency.
  Needs RSFA's desktop app.
- **Rewrite module 8 to key everything by ID:** changes every key MacroForge receives. Rejected; not minimal.

### How to confirm before approving (no client operation)

1. **Rod / RSFA, in MacroForge (desktop):** run Flow C manually on a **synthetic** payload whose `result` has
   `"Selected Banks": "BNZ"` and no `"Banks to Send"`. Use MacroForge's own test or preview, never the webhook from
   here. If no BNZ form is produced, the defect is confirmed.
2. Alternatively, accept the change on the evidence above. It is additive and reversible in one paste.

### Application steps, after explicit approval only

1. **Re-read** the scenario. Proceed only if `lastEdit` is still `2026-07-16T22:05:49.471Z`, `isActive` is true and
   the module-8 code SHA-256 (prefix `8656f401…`) matches the backup.
2. **Back up first:**
   - The complete blueprint is already saved as
     `.local/reference/make/8731317-blueprint-2026-10-28-iter8-backup.txt` (SHA-256 prefix `c6c9f44a…`, git-ignored,
     mode 600).
   - Also export it from the Make UI (Scenario → … → Export blueprint) into RSFA's SharePoint IT folder.
3. **Edit (preferred: RSFA in the Make UI):**
   - Open module 8.
   - Paste the snippet directly after `const result = mapColumns(input.column_values);`.
   - Save the scenario. Saving does not deactivate it, and no run is started.
4. **Or edit via the API:** `scenarios_update` with the **complete** fetched blueprint, where only
   `flow[id=8].mapper.codeEditorJavascript` differs (the tool replaces the whole blueprint). No `scheduling` argument.
5. **Verify:**
   - Re-fetch. The diff against the backup must be exactly the inserted lines.
   - `isActive`, `hookId 3905270`, scheduling and the module list must be unchanged.

### Rollback

Remove the inserted lines in module 8 and save. Or restore the backup blueprint (Make UI import, or `scenarios_update`
with the backup's `blueprint`). Either is immediate and leaves the hook and connections intact. No data migration is
involved.

### Validation after an approved change

- **No test run is triggered:** any run posts to MacroForge with a real client's data.
- **Local:** `node tests/make/module8_banks_alias_test.js` (12 checks) against the code read back after saving.
- **First natural run** (the next adviser status change):
  - the `8731317` execution succeeds;
  - RSFA confirms that the selected banks' PDFs appear in MacroForge's export folder;
  - `9254149` sets `Filled` as usual.
- **Duplicate and recursion risk:** unchanged. The fix does not touch the trigger, and `9254149` still writes `status`
  (the re-trigger guard is a separate open item, docs/07 section 5).
- **Evidence:** keep execution IDs and statuses only; no payloads.

### Monitoring afterwards

Check that `8731317` executions after the change stay `SUCCESS` with the usual operation count (20 in the last run),
and that RSFA sees lender forms filed. Make retains no execution detail for this scenario, so the filing in SharePoint
is the observable signal.

## Iteration 9 (2026-10-28): pre-approval verification. Production still unchanged

**Re-read of `8731317` (read-only) against the iteration-8 backup:**

| Check | Result |
|---|---|
| Full API response vs backup | **byte-identical** (SHA-256 `c6c9f44a54f61d52…`). No top-level field differs |
| `lastEdit` | `2026-07-16T22:05:49.471Z` (unchanged) |
| State | active, not paused, not invalid, not locked; hook `3905270`; scheduling `immediately`, 100 runs/min; folder `296569`; team `85772` |
| Executions since 2026-10-01 | none |
| Blueprint digest (canonical JSON) | `ab76a6762bab8a60…` (this is the rollback blueprint) |
| Module 8 code | SHA-256 `8656f40189a41fa7…`; anchor `const result = mapColumns(input.column_values);` occurs **once**, line 138 of 157; the new names `BANKS_COLUMN_ID`, `LEGACY_BANKS_KEY`, `banksColumn` are unused |

**Exact proposal (`tools/make_8731317_proposal.py`, from the fresh read):**
- **Inserted text:** the 7 lines of `make/8731317/module8-banks-alias.snippet.js` (1 comment + 6 code lines), placed
  directly after the anchor.
- **Structural diff of the whole blueprint:** exactly **one path**, `/flow[8]/mapper/codeEditorJavascript`.
  - No connection (`__IMTCONN__`), hook (`__IMTHOOK__`), filter, router, error handler (`onerror`), module mapper,
    metadata, scheduling or designer entry changes.
  - Nothing is removed from the code; 7 lines are added.
- **Digests:**
  - proposed blueprint `1369b087b71c2981…`;
  - proposed module-8 code `08543b93924c4653…`.
- **Validation:**
  - Make's `validate_module_configuration` (`code:ExecuteCode` v1, organisation `882256`, team `85772`):
    `valid: true`, no errors or warnings. This checks the module configuration shape, not the whole program.
  - The full program was syntax-checked and executed locally (below).

**Regression (`node tests/make/module8_banks_alias_test.js`, 24 checks, all passed):**
- The production code with the live title reproduces the missing `Banks to Send` key; the old title still produces it.
- With the change, for 10 selection shapes (each bank, multi-selections, all five, empty, null), `"Banks to Send"`
  is **strictly equal** (`===`) to `"Selected Banks"`, which itself is unchanged.
- Exactly one key is added; no key is removed or renamed. Every other key and value is byte-identical (dates,
  booleans, split dates, subitems, GPT address fields).
- If the column is renamed back, nothing is overridden. If it is deleted, nothing is added and nothing fails.
- The module-8 code inside the proposed blueprint is byte-identical to the code the test ran.

**Backup and rollback files** (all in `.local/reference/make/`, mode 600, git-ignored; they contain webhook URLs and
test payloads):
- `8731317-blueprint-2026-10-28-iter8-backup.txt` and `8731317-current-2026-10-28-iter9.txt` (identical full reads);
- `8731317-ROLLBACK-blueprint.json` (digest `ab76a676…`);
- `8731317-PROPOSED-blueprint.json`;
- `8731317-module8-PROPOSED.js`.

**Risk assessment:**

| Risk | Assessment |
|---|---|
| Payload change breaks MacroForge | Low. One extra key with the exact value MacroForge's own conditions expect; key order changes only by that insertion (MacroForge reads keys by name; inference) |
| Code error in module 8 | Low. Syntax and behaviour tested on synthetic data. On a runtime error the existing handler calls `8732008` (e-mail), as today |
| Saving changes trigger, schedule or activation | Low but unverifiable before the change. The API update replaces only the blueprint; scheduling is not sent. Mitigated by the re-read check below |
| The change triggers a run | None. Saving a scenario does not run it; runs come only from the Monday hook |
| Wrong diagnosis (MacroForge handles the missing key some other way) | The alias is still harmless: it restores the pre-2026-10-25 payload shape |
| Concurrent edit by someone else | Step 1 below refuses if `lastEdit` or the digest changed |

**Application procedure, only after explicit approval:**
1. Re-read the scenario. Stop unless `lastEdit = 2026-07-16T22:05:49.471Z`, the blueprint digest is `ab76a676…`
   and `isActive` is true. Re-run `tools/make_8731317_proposal.py` on that read; it must reproduce the proposed
   digest `1369b087…`.
2. `scenarios_update(scenarioId 8731317, blueprint = 8731317-PROPOSED-blueprint.json)`. No `scheduling`, `name`,
   `folderId` or `description` argument.
3. Re-read and verify:
   - the diff against the rollback blueprint is exactly the 7 lines;
   - `isActive`, `isPaused`, `hookId 3905270`, scheduling, `islinked`, `isinvalid = false` are unchanged;
   - module count and module types are unchanged.

   If anything else differs, roll back immediately (step R).
4. Re-run the local regression against the code read back.
5. Record the new `lastEdit` and digests in this document.

**R. Rollback:** `scenarios_update(8731317, blueprint = 8731317-ROLLBACK-blueprint.json)`, or remove the 7 lines in
module 8 in the Make UI and save. Then repeat the step-3 checks against the rollback digest `ab76a676…`.

**Validation without client operations:** as in the section above. No test run is triggered. The first natural run
is observed: execution `SUCCESS`, and RSFA confirms the selected banks' PDFs are filed.
