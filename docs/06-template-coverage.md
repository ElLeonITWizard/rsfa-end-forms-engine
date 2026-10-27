# 06 — Template coverage: selection, readiness and open questions (iteration 6)

The per-form mapping report (every key, label check and unmapped field) is generated:
[`docs/generated/template-coverage.md`](generated/template-coverage.md) (`tools/coverage_report.py`).
This page explains **which forms were chosen, why the others were not, and what each form still needs**.

Evidence used (2026-10-27):
- **Imported manifests:** `tools/import_mforge.py`.
- **Printed label next to every mapped widget:** a geometric label check on the real PDFs.
- **Rendered output:** every candidate, reviewed visually with synthetic data.
- **[live] Monday schema (read-only):** column schema of board `5026186805` via the Monday connector, columns
  only, no items. It is saved locally as `.local/reference/masterboard-columns-2026-10-27.json` (git-ignored).
- **Make contract:** the `8731317` result-key contract (`.local/reference/8731317_result_key_contract.tsv`).

## Readiness levels

| Level | Meaning |
|---|---|
| **tested** | Output asserted by automated tests; deployed with RSFA's approval for packaging/testing (BNZ Boarder only). |
| **candidate** | Every rule checked against the printed label and the live Monday labels. Synthetic end-to-end tests pass, and refusals are tested. **Not approved by RSFA and not compared with a MacroForge output.** Available locally (demo selector, `/v1` with `includeCandidates`). Not in the Cloud Run allowlist. |
| **blocked** | Generates, but an open question means the document could be wrong. Not routed by bank selection. |
| not selected | Not migrated in this iteration; MacroForge remains responsible. |

## Selected forms

| Form | Template key | Level | Why selected | Engine features exercised |
|---|---|---|---|---|
| ASB Boarder Income Declaration | `ASB_BOARDER_DECLARATION@12-22` | candidate | Same business case as BNZ Boarder. 23/23 legacy rules imported. All 19 PDF fields mapped. All frequency labels match the live board exactly. | text, radio + conditions, `[TODAY]` comb dates (DDMMYYYY), encrypted template (preserve), closed options, `present` precondition |
| ANZ Boarder Income Declaration | `ANZ_BOARDER_INCOME_DECLARATION@02-24` | candidate | Boarder family. 8/8 rules plus 2 overlays. Simple layout. | text, overlays (rect / 1.2), **weekly-amount precondition** |
| GHL Account Use Questionnaire — Individual | `GHL_NP_INDIVIDUAL_FORM@8-19` | candidate | 26/26 rules, checkbox-heavy, no line items. | checkbox groups + conditions, encrypted template, closed options (single and multi-select) |
| GHL Account Use Questionnaire — Trust | `GHL_NP_TRUST_FORM@8-19` | **blocked** | Was "partially tested". Now fully exercised with live labels. | as above |

The **form version** is taken from the bank's own form code where the file name has none. ASB `56140 25905 1222` → `12-22`
follows the same convention as GHL `56140-26142-1124`, which is printed "November 2024".

## What the review found (and how the engine now handles it)

1. **Monday label drift.** The live board has labels that no legacy rule tests, because the labels were renamed after
   MacroForge was configured:
   - GHL Individual:
     - `$150,001-$250,000` (rule tests `$150,000-$250,000`);
     - `Occasionally (monthly / quarterly)` ×2 (rule tests `Occasionally (monthly/quarterly)`).
   - GHL Trust: `$1,000-$10,000` ×2 (rule tests `$1,000-$5,000`; the box is printed `$1,000-$10,000`).

   MacroForge would silently leave those boxes blank. The engine now **refuses** such values (`UNMAPPED_OPTION`)
   through the curated `validation.closedOptions` (D-023). The obvious rule fixes are **not** applied. They change the
   legacy mapping and need RSFA's decision.
2. **Multi-select columns.** These columns use legacy `equals` rules, which can represent only one label:
   - `Sources of credits`;
   - `GHL Why have you come to Sovereign Home Loans?`;
   - `What do you intend to use your accounts for? (GHL Individual)`.

   Two labels are refused (`MULTI_SELECT_NOT_REPRESENTABLE`).
3. **Misstated amounts.** The ANZ form only has a "Weekly board amount" box, but Monday allows Weekly, Fortnightly or
   Monthly. A non-weekly amount is refused (`PRECONDITION_FAILED`, `ANZ-BOARDER-WEEKLY-1/2`). ASB requires a frequency
   whenever an amount is given (`ASB-BOARDER-FREQ-1/2`).
4. **GHL Trust activity details (BLOCKING).** The legacy configuration writes the same key into both detail boxes:
   - the key is `International payments activity details (including countries involved)`;
   - the boxes are 3d (transfers **in**) and 3e (transfers **out**);
   - so details appear under "out" even when "out" is `Never`;
   - the 3c cash-deposit details box and average value are not mapped.
5. **Template quirk.** The GHL Individual PDF carries an XMP metadata stream (object 187) with bad AES padding in the
   bank's own file. PDFBox logs a `SEVERE` decrypt message (no data) and the incremental save keeps the original
   bytes. Output verified with PDFBox and pypdf.
6. **Live schema facts relevant to Make** (also in docs/07):
   - The board is titled **"SOP Masterboard"**.
   - Column `dropdown_mkzvfgeg`, which the Make contract and MacroForge call "Banks to Send", is now titled
     **"Selected Banks"**. Its labels are `ASB, ANZ, BNZ, KiwiBank, WBC`.
   - `status` has a fifth label, **`Historical`**.
   - Contract key `KIWIBANK Account Number 1` vs live title `Kiwibank Account Number 1`.

## Open questions per form (for RSFA)

| Form | Question | Blocking? |
|---|---|---|
| ASB Boarder | Property address uses Applicant 1 *physical* address Street / Province / Country (legacy). Suburb and City are not mapped, and it may not be the property where the boarders live. | review |
| ASB Boarder | Signature dates are pre-filled with today (legacy `[TODAY]`). The DDMMYYYY format is inferred from the 8-cell comb. | review |
| ANZ Boarder | Property address = Applicant 1 Physical Address (legacy). The "inclusive of food/bills" Yes/No boxes are not mapped. | review |
| GHL Individual | Apply the three label fixes above? Multi-select: should both boxes be ticked (new `includes` semantics)? | for those values |
| GHL Trust | Correct keys for the 3c/3d/3e activity details; the `$1,000-$10,000` fix; multi-select semantics. | **yes** |

## Forms reviewed and not selected (this iteration)

| Form | Reason (evidence) |
|---|---|
| BNZ Onboarding Individual Personal | Date of birth is a 6-cell DDMMYY comb, but Monday dates are DDMMYYYY (the open transform decision). FATCA/tax-residency questions are unmapped while TINs are filled. `Text 50` (a print-name line on page 3) receives Applicant **2** on an individual form. |
| WBC Individual GATCA (tax self-certification) | Q2 "tax resident in another country?" is driven by a Monday **checkbox**: an unticked/unset box (`false`) would answer **Yes**. The legacy rule puts the **adviser's** name in "Print full name" of the customer's declaration (section D). |
| ANZ Individual / Entity FACTA | Same family of tax self-certifications; boolean-driven answers carry the same risk. ANZ Entity also has 5 `no_target_field` and 3 stale labels. |
| GHL Direct Debit | Account number is a 15-cell comb (bank / branch / account / suffix), and the GO Home Loan number has a printed `12` prefix. The Monday text format is unknown; a wrong account number on a debit authority is unacceptable. |
| ASB&GHL Adviser Declaration | 11 constant ticks on an **adviser declaration**. They are never applied without approvals (D-016), so the form would be largely blank. |
| WBC Boarder | 5 `no_target_field` + 25 inert (copied mapping set). |
| BNZ Handover, ASB Loan Structure, WBC Loan Structure, KB Handover | LineItems name conflict (`… in months/years`). The live subitem titles are `Fixed term (months)`, `Loan term (years)`, …. The DDMMYYYY → 6-cell repayment date is also open. |
| BNZ Gifting, KB Stat Dec, All Banks DOAD | No AcroForm (overlay-only or no fields). |
| BNZ Joint Personal, BNZ Entities, ASB CAD, WBC Entity GACTA, KB FMS | Larger forms with date-of-birth/identity fields, tax answers or constant ticks. Next candidates once the date-transform and boolean-answer decisions are made. |

## Production allowlist

Unchanged: `deploy/poc-allowlist.json` still approves **BNZ Boarder only**. Adding a candidate needs RSFA to:
1. review the rendered sample;
2. answer the open questions;
3. record an approval in the allowlist.

Never write that approval on RSFA's behalf (CLAUDE.md rule 4).
