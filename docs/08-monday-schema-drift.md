# 08 — Monday schema drift (read-only investigation, 2026-10-28)

**Nothing was changed in Monday, Make or MacroForge.** Iteration 8 added the Monday activity-log evidence (rename
dates) and the production investigation of `8731317` (docs/09).

**Sources:**
- **[live]** Board `5026186805` column schema: Monday connector, `get_board_info` with columns only and no items, read
  2026-10-27. Saved locally as `.local/reference/masterboard-columns-2026-10-27.json` (git-ignored).
- **[live]** Make scenario `8731317` blueprint: read 2026-10-28 through the Make connector and saved locally under
  `.local/reference/make/` (git-ignored, because modules may hold test payloads). Analysed by script: module list,
  filters and the module-8 code only.
- **Documented contract:** the `8731317` result-key contract `.local/reference/8731317_result_key_contract.tsv`
  (column ID → result key, derived from the blueprint's cached interface).

The full ID-by-ID comparison is generated: [`docs/generated/monday-schema-drift.md`](generated/monday-schema-drift.md)
(`tools/schema_drift.py`).

## How Make builds the keys MacroForge and the engine receive

**[live]** `8731317` module 8 (Code, JavaScript):
- `const key = col.title || col.id;` over the item's `column_values` from module 4 (`GetItemV2`).
- Columns that share a title are merged into an array.
- Date columns become `DDMMYYYY`; checkboxes become booleans; everything else is the column's `.text`.

So the keys are the **live column titles**:
- A display-name change in Monday changes the key at the next run.
- A new column is sent automatically.
- A deleted and re-created column with the same title keeps its key, but loses its old values.

This **corrects** the inference in iteration 6 that keys are fixed per column ID.

## Findings

| # | Finding | Kind | Evidence |
|---|---|---|---|
| 1 | Board display name is **"SOP Masterboard"** (RSFA documents call it "Masterboard: End Forms"); ID `5026186805` unchanged. Renamed 2026-10-25 07:06 → "Masterboard: from SOP", 07:09 → "SOP Masterboard" | display name | live schema; activity log (iteration 8) |
| 2 | `status` (End Forms Filling Status) keeps ID and type; it gained the label **`Historical`** (5 labels: Reviewing, Reviewed, Cancelled, Filled, Historical) | option added | live schema |
| 3 | `dropdown_mkzvfgeg`: **"Banks to Send" → "Selected Banks"**; same ID, type and labels (`ASB, ANZ, BNZ, KiwiBank, WBC`) | **display-name change with key impact** | live schema; **activity log: renamed 2026-10-25 06:36:07 UTC** (iteration 8); blueprint cache still says "Banks to Send" (last edit 2026-07-16) |
| 4 | Five more renames, same ID: `Related Contacts`→`Contacts` (2026-10-25 07:03; also `lookup_mm8fw9zw` `Client Profile`→`Client Profile from Contacts` 06:37; neither is used by MacroForge); `KIWIBANK Account Number 1`→`Kiwibank Account Number 1`; `Which facility`→`Which Facility – ASB Lending`; `Capitalise to loan`→`Capitalise to loan (Yes/No)`; and **`boolean_mkzvcgkc` "Applicant 2 Tax resident of one or more other countries…" → "Applicant 1 Tax resident…"** | display-name change; the last one changes **meaning** | generated report |
| 5 | Nine column IDs removed. Seven have a new column with the same title, so the key is kept. Notably: both boarder names and Boarder 2 amount and frequency are new columns; "Applicant 2 Tax resident in one or more other countries/jurisdictions" changed from **checkbox to text**. Two keys are gone: "Applicant 1 Preferred Time To Call" and the old boolean "Capitalise to loan" | replacement / removal | generated report |
| 6 | No real type change on a kept ID. The 65 apparent ones are Monday's legacy API type names (`boolean`/`numeric` = `checkbox`/`numbers`) | none | ID prefixes |
| 7 | 17 new columns, including `Applicant 1/2 Full Name`, `ASB Account Number`, `Adviser Occupation/Address`, `Boarder 1/2 amount inclusive of food and/or bills/utilities`, `Purchase Property 1–3 Address` | new | generated report |
| 8 | **No duplicate titles remain.** The 7 duplicated titles (e.g. `Loan processing fee`, `Cash contribution`) that `8731317` merged into arrays are gone | resolved | live schema |
| 9 | Option drift inside kept dropdowns: GHL frequency/amount labels renamed (e.g. `Occasionally (monthly / quarterly)`, `$150,001-$250,000`, `$1,000-$10,000`) | option change | docs/06 |

## Consequences

- **Risk to the current production flow (inference, not observed; for RSFA, not fixed here):**
  - MacroForge Flow C selects each lender batch with `Banks to Send Contains <bank>`.
  - Since the rename, `8731317` sends `Selected Banks` instead. **The next production run may fill no bank forms**
    (only `All Banks_DOAD.pdf`, which is unconditional).
  - The last `8731317` execution in Make's history is 2026-10-01 (SUCCESS). No run since, so it is unobserved.
  - **Rename date confirmed (iteration 8):** 2026-10-25 06:36:07 UTC, after the last `8731317` run (2026-10-01).
    Full investigation, reproduction and the prepared fix: [docs/09](09-macroforge-banks-key-investigation.md).
  - **Possible fixes (production changes, need a change plan and approval):**
    - rename the column back to "Banks to Send";
    - add a key alias in `8731317` module 8;
    - change MacroForge's conditions.
- **Engine:**
  - Unaffected by #3: the `/v1` contract takes `selection.banks` explicitly, so Make maps the column, whatever its title.
  - `KB_HANDOVER_FORM` (untested) reads `KIWIBANK Account Number 1` and would now get nothing. A key
    correction is needed before that form is curated.
  - The tax-residency rename (#4/#5) is a further reason the tax self-certification forms stay unselected (docs/06).
- **Contract file:** the result-key contract TSV is a snapshot older than the live board. Treat
  `tools/schema_drift.py` output as the current truth and re-run it before each release.

## Recommendation

- Before any parallel run, RSFA confirms finding #3 against MacroForge's behaviour.
- Keep `tools/schema_drift.py` and `tools/coverage_report.py` in the release checklist, both run against a fresh
  read-only schema export.
- A future contract version could key `data` by column ID instead of title, which would make renames harmless. That
  needs a change to `8731317` (or the new scenario) and is a decision for RSFA.
