# 06 — Form validation: status, corrections, decisions (iteration 8)

The detailed report per form is generated: [`docs/generated/template-coverage.md`](generated/template-coverage.md)
(`tools/coverage_report.py`). It lists, for every form:
- every data key and its live-board and Make-contract status;
- the label check of every condition;
- confirmed, corrected and unresolved mappings, stakeholder decisions and remaining risks.

This page summarises it and explains the reasoning.

**Evidence used (2026-10-27/28):**
- the bank's blank PDFs (field geometry, states, export values, printed labels next to each widget);
- the imported MacroForge rules;
- the live Monday column schema (read-only, docs/08);
- the `8731317` key contract and live module-8 code;
- rendered synthetic outputs.

**Nothing here is approved by RSFA, and no form is compared with a MacroForge output.**

**Iteration 8 changes (D-034):**
- Statuses are unchanged.
- **GHL Individual and Trust** now refuse to generate without the customer/trust name.
- **New decision RSFA-DECISION-3:** MacroForge fills **both** GHL questionnaires for every ASB deal (no
  individual/trust condition in Flow C). So for an individual client the Trust form is refused, and an all-or-nothing
  ASB request fails until RSFA says which form applies to which client.
- **ANZ Boarder** refuses an inclusive-of-food/bills **Yes**, which its PDF would record as No, instead of silently
  leaving it blank.
- **ASB Boarder:** re-checked against the same evidence; nothing new. The property-address and signature-date
  questions remain RSFA-DECISION-1/2.
- **Business checklists for Rod:** [docs/12](12-acceptance-checklists.md).

## Readiness levels

| Level | Meaning |
|---|---|
| **tested** | Output asserted by automated tests; deployed with RSFA's approval for packaging/testing (BNZ Boarder only). |
| **ready-for-acceptance-testing** | Every rule checked against the printed form and the live Monday schema. Evidence-based corrections applied and documented. Contradictory or unrepresentable input is refused. No material ambiguity left. **Safe to propose for RSFA acceptance testing; not approved, not in the Cloud Run allowlist.** |
| **blocked** | A material question needs an RSFA decision. Generates locally for review only (with the legacy mapping, plus a preview of the proposed correction); never routed by bank. |

## Corrections policy (D-028)

Reviewed corrections live in `curation/<ID>@<version>.json`, next to the closed options and preconditions:
- **Basis `evidence`:** applied by the importer and documented. Examples: a renamed Monday label whose printed option
  is unambiguous; a dedicated Monday column for a box the legacy configuration left empty or filled from the wrong
  column. The old legacy value stays accepted next to a corrected label.
- **Basis `decision`:** business choices, e.g. which address is "the property". **Never applied** without a recorded
  RSFA decision (`approvedBy`, `approvedOn`, `reference`), which is never written on RSFA's behalf.
  `import_mforge.py --preview-pending` builds a preview manifest under `.local/` for visual review only.
- A correction must match the imported rule exactly, otherwise the import fails. It can never add a constant tick
  (D-016) or target a signature.

## Summary per form

| Form | Status | Confirmed | Corrected | Unresolved (left blank) | Decision needed |
|---|---|---|---|---|---|
| BNZ Boarder `BNZ_BOARDER_FORM@2.0_8-24` | tested | 7 fields + 2 overlays | — | `Text 1` (legacy) | — |
| GHL Account Use — Individual `GHL_NP_INDIVIDUAL_FORM@8-19` | **ready-for-acceptance-testing** | 10 fields | 6: three renamed labels; section 5 Yes/No + details | 10 (internal-use boxes, dates) | RSFA-DECISION-3 (which GHL form per client); two review questions |
| GHL Account Use — Trust `GHL_NP_TRUST_FORM@8-19` | **ready-for-acceptance-testing** | 13 fields | 8: **3d/3e in/out details from dedicated columns**; 3c details; `$1,000-$10,000` ×2; multi-select sources | 11, incl. 3c average value (no matching column) | RSFA-DECISION-3; review questions |
| ASB Boarder `ASB_BOARDER_DECLARATION@12-22` | **blocked** | 13 fields (names, household, boarders, amounts, frequencies); 6 under decision | — | — | RSFA-DECISION-1 (address), RSFA-DECISION-2 (signature dates) |
| ANZ Boarder `ANZ_BOARDER_INCOME_DECLARATION@02-24` | **blocked** | 5 fields + 2 overlays; 3 under decision | — | food/bills Yes/No (**bank template defect**: "Yes" refused, "No" left for the customer), signature dates | RSFA-DECISION-1 (address); ANZ form defect |

### GHL Individual: ready for acceptance testing

- **Confirmed:**
  - name and occupation;
  - all six checkbox groups against the printed options and the live labels;
  - free-text details.
- **Corrected (evidence):**
  - `$150,001-$250,000`, and `Occasionally (monthly / quarterly)` in 3b and 4: Monday labels renamed, same printed
    option. MacroForge leaves those boxes blank today.
  - Section 5 "anything other than personal use?" from `Use account for anything other than personal use?` (Yes/No)
    and `Other account use details (GHL)`. The legacy configuration leaves it empty.
- **Refused:**
  - a missing or blank customer name (`GHL-IND-NAME-REQUIRED`, iteration 8);
  - two "intend to use" labels at once: one checkbox field, so only one state can be recorded;
  - any unknown label;
  - contradictions: cash or international "Never" with an average value or details; other use "No" with details.
- **Review questions (not blocking):**
  - Section 4 average value comes from the ASB column (legacy). A `(GHL Individual)` column with the same bands
    also exists.
  - In a parallel run MacroForge will differ where the engine fills more.

### GHL Trust: ready for acceptance testing (inbound/outbound defect fixed)

- **Corrected (evidence):**
  - 3d details ← `International Money Transfers – In activity details (including countries)`.
  - 3e details ← `… – Out activity details (including countries)`.
  - These replace the legacy rule that copied one generic text into both boxes. Without OUT details the OUT box stays
    empty (tested).
  - 3c cash details ← `Cash deposits activity details`.
  - `$1,000-$10,000` for both average-value questions.
  - `Sources of credits` uses `includes`: each selected source ticks its own checkbox.
- **Refused:**
  - a missing or blank trust/organisation name (`GHL-TRUST-NAME-REQUIRED`, iteration 8);
  - IN/OUT/cash "Never" combined with an average or details;
  - third parties "No" combined with a purpose;
  - unknown sources;
  - both "why have you come" reasons at once (one checkbox field).
- **Unresolved:** 3c average value (Check Box100: the Monday cash column for this form does not exist; the ASB one
  has other bands). Left blank as in MacroForge.
- **Review question:** which near-duplicate Monday columns advisers actually fill (docs/generated).

### ASB Boarder: blocked by RSFA-DECISION-1

- **Confirmed:**
  - borrower names, full names, household numbers;
  - boarder names, amounts and the three frequencies (labels exact);
  - the radio state positions;
  - encryption preserved.
- **Refused:** an unknown frequency; a frequency without an amount; an amount without a frequency.
- **Decision 1 (material):** "Property address".
  - Legacy: the applicant's *physical* address printed as Street / **Province** / **Country**, so Suburb and City are
    never printed.
  - Proposal `ASB-ADDR-1..4`: *1st Property Address* Street / Suburb / City / Postcode, as BNZ Boarder does.
  - The right answer depends on the deal: purchase vs refinance.
  - Preview PDF provided.
- **Decision 2:** the signature dates are pre-filled with today (legacy). Proposal `ASB-SIGNATURE-DATE-1/2`: leave them
  blank for the customer.

### ANZ Boarder: blocked by RSFA-DECISION-1

- **Confirmed:**
  - customer-name overlays, visually on the dotted line;
  - customer number, boarder names;
  - weekly amounts, guarded: Fortnightly/Monthly is refused;
  - signature dates blank.
- **Decision 1:**
  - Legacy mixes the free-text *physical* address with the structured *residential* City/Town and Postcode columns.
  - Proposal `ANZ-ADDR-1..3`: *1st Property Address* + its City + Postcode. Preview PDF provided.
- **Unresolved, bank template defect:**
  - Monday has dedicated Yes/No columns for "inclusive of food and/or bills".
  - But in ANZ's PDF **both** widgets of `B1 Inclusive`/`B2 Inclusive` export the value `No` (`/Opt ['No','No']`).
    A visually ticked Yes would be recorded as No.
  - Not filled. Needs ANZ's corrected form or RSFA's explicit acceptance of a visual-only tick.
  - Iteration 8: a **Yes** answer now refuses generation (`ANZ-INCLUSIVE-YES-1/2`) instead of being dropped silently.
    A **No** is left blank for the customer, as in the legacy configuration (tested).
- **Risk:** `Customer number(s)` takes one Monday column, so a second customer's number cannot be printed.

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
