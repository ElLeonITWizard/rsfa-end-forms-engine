# 03 — Migration inventory (legacy MacroForge export)

Source: `RSFA End Forms Filling BluePrint 271026.mforge`, SHA-256
`8052c17b374b551272e17f5036de1ebfa9a295328eb8a6d079d30eec788b9a51`, 16.3 MB. It was inspected on 2026-10-27
with `tools/import_mforge.py 0.2.0` (`inventory --with-pdf`). This file contains counts and names only. The full JSON
is written locally to `.local/output/inventory.json`.

## Outcome categories (each mapping lands in exactly one)

| Category | Meaning | Evidence required |
|---|---|---|
| **imported** | An engine rule that resolves against the real PDF field | field exists, kind compatible |
| **unsupported** | A legacy rule type the engine does not implement | the rule type itself |
| **unresolved** | Cannot be imported yet; needs evidence or a business decision | a named category (below) |
| **inert** | Has no effect in the legacy engine, with positive evidence for why | see `sibling_duplicate` |
| **import error** | Contradicts MacroForge's own scan (in `native_field_info` but not in the PDF) | none found (0) |

Nothing is labelled **obsolete**. That word is reserved for RSFA's decision after review.

Unresolved sub-categories:
- `template_without_form_fields`: the PDF has no AcroForm and MacroForge's own scan found no fields. Only a fillable
  version of the template or coordinate overlays could carry these values.
- `line_item_name_conflict`: the `LineItems.<subfield>` name is not produced by the current `line_items_configs`
  (see "LineItems" below).
- `no_target_field`: the field exists neither in this PDF nor in MacroForge's scan. A field with the same name exists in a
  sibling template, but with a *different* rule (or exists nowhere).
- `ambiguous_checked_state`: legacy `checked` without a state, on a field with several on-states (which box?).

Inert sub-category:
- `sibling_duplicate`: no target in this template, **and** an identical rule (same field, source and condition)
  resolves on a sibling template in the same batch node. This is evidence of a copied mapping set.

## Export structure (verified)

- Flows:
  - `Main Flow`, `Flow A`, `Flow B`: email triggers only.
  - **`Flow C` "End Forms Flow Filling"** (production):
    1. a webhook trigger;
    2. 5 lender conditional blocks on `Banks to Send Contains ASB | KiwiBank | WBC | ANZ | BNZ`;
    3. **6 PDF Batch Filler** nodes (the ASB block holds ASB and GHL);
    4. one single filler, `All Banks_DOAD.pdf`;
    5. a "Send to Zapier/Make" callback.
  - `Flow D`: a test flow.
  - `Flow E`–`G`: empty.
- Per batch node:
  - `field_mappings_by_file`
  - `field_conditions_by_file`
  - `custom_fields_by_file` (overlays)
  - `native_field_info_by_file` (MacroForge's own field scan, top-left PDF-point rects)
  - `line_item_instances_by_file` (`{"LineItems": {subfield: {"<n>": "<pdf field>"}}}`)
  - `embedded_pdfs`
- Trigger node: `line_items_configs` = `array_path: result|Subitems` plus 17 `sub_mappings`
  `{source_rel_path, subfield_name}`.
- **Sensitive content (never read by the importer):**
  - `sample_payload` and `variable_map` values
  - `flow_data["Flow C"].__raw_webhook_payload` and `current_parsed_pdf_data` (real data)
  - export folders, filename text blocks and the callback `webhook_url`
- `flow_data["Flow C"].generated_file_objects` is empty, so the export holds **no MacroForge output PDFs** to compare against.

## Totals (Flow C)

**1,013 mappings = 804 imported + 19 unsupported + 138 unresolved + 52 inert + 0 errors.**
Separately, 14 of 14 overlays are imported.

| Legacy kind | Detected | Imported | Unsupported | Unresolved | Inert |
|---|---|---|---|---|---|
| source key | 531 | 411 | 0 | 56 template_without_form_fields, 12 no_target_field | 52 sibling_duplicate |
| `conditional` (single equals/contains; 146 use `Field#state`) | 242 | 223 | 18 line_item_condition (no row index), 1 multi-condition | 0 | 0 |
| `checked` | 38 | 36 | 0 | 2 ambiguous_checked_state | 0 |
| `unchecked` | 13 | 13 | 0 | 0 | 0 |
| `direct_text` `today_date` | 13 | 12 | 0 | 1 template_without_form_fields | 0 |
| `direct_text` literal | 3 | 0 | 0 | 3 no_target_field | 0 |
| `LineItems.<field>_<n>` | 173 | 109 | 0 | 64 line_item_name_conflict | 0 |
| **Total** | **1,013** | **804** | **19** | **138** | **52** |

Of the 124 mappings reported as "stale" in iteration 1:
- 52 are inert sibling duplicates (WBC Boarder 25, WBC Entity 10, WBC Refix 15, ANZ Entity 2);
- 57 are BNZ Gifting (`template_without_form_fields`; all 57 are identical copies of `BNZ_Onboarding_JointPersonal` rules);
- 15 are `no_target_field` (5 each on WBC Boarder, WBC Refix and ANZ Entity, whose sibling uses a different rule).

## Overlays (`custom_fields`): coordinate system

The export records no scale or zoom. The transform was derived from geometry:
**`pdf_point = rect / 1.2`, top-left origin.** This is MacroForge's preview zoom, unlike `native_field_info`, which uses plain PDF points.

| Evidence | Result |
|---|---|
| Vertical fit against the printed label beside 4 overlays on 4 templates (WBC Loan Structure "Customer(s)*", ANZ Boarder "Customer name(s):", BNZ Boarder "Customer name (s)", BNZ Entities account "0 2" prefix) | s = 1.2017, 1.2075, 1.1739, 1.1919 → **1.194 ± 0.013** |
| Visual review of all 14 overlays at s = 1.2 | 14/14 land in the intended blank (customer names, Town/City, Postcode, account number boxes, DOAD Lender/Borrower brackets) |
| s = 1.0 (plain points) | lands beside unrelated labels ("Town/City", "Bank contribution"); several rects exceed the 595 pt page width |

Status: **inference with strong evidence**, not confirmed against a real MacroForge output (none available).
The legacy font and size of overlays are not recorded in the export, so the engine's rendering policy is used (docs/02).

## LineItems (repeating subitems)

- **Verified from the export:** `sub_mappings` define each subfield's source inside a `result|Subitems` element,
  e.g. `values|Loan amount` → `Loan amount`, `values|Interest rate (%)` → `Interest rate`, `name` → `name`.
  `LineItems.<subfield>_<n>` targets the n-th row. For all 109 imported line items, `line_item_instances` names the
  same PDF field as the mapping (0 mismatches).
- **Inference:** row n = element n of Make's `Subitems` array, in array order.
- **Name conflict (64 mappings):** the mappings use `Fixed term in months`, `Fixed term in years`, `Loan term in years`,
  `Interest only term in years` and `Interest only period in months`. The current `sub_mappings` produce `Fixed term months`,
  `Loan term years`, etc. The callback structure Make learned in May 2026 uses the "in" names. Which naming MacroForge
  produces today cannot be determined from the export, so these are unresolved, not aliased.
- **Row-less conditions (18):** for example, three checkboxes test `LineItems.Request to ratelock RLA equals true`
  with no row index. Which subitem they test is not recorded, so they are unsupported.

## Per-template inventory (Flow C)

0 XFA, 0 duplicate field names. 7 ASB/GHL templates are owner-encrypted. 3 have no AcroForm.

| Lender | Template (legacy name) | SHA-256 (12) | Mappings | Imported | of which line items | Unresolved | Inert | Unsupported | Overlays (imported) | Encrypted |
|---|---|---|---|---|---|---|---|---|---|---|
| ASB | `ASB Home Loan Boarder Declaration Form.pdf` | `923b43b8f3fb` | 23 | 23 | 0 | 0 | 0 | 0 | 0 (0) | yes |
| ASB | `ASB&GHL_AdvDec_05 25.pdf` | `f627a4866ae7` | 19 | 19 | 0 | 0 | 0 | 0 | 0 (0) | yes |
| ASB | `ASB_AdvDec_LoanStructure_6 25.pdf` | `114c025f81c3` | 98 | 61 | 25 | line_item_name_conflict 24 | 0 | 13 | 0 (0) | yes |
| ASB | `ASB_CAD_6 19.pdf` | `37fd79696292` | 67 | 67 | 0 | 0 | 0 | 0 | 0 (0) | yes |
| ASB | `GHL_DD_11 24.pdf` | `7bf194a5778b` | 15 | 15 | 0 | 0 | 0 | 0 | 0 (0) | yes |
| ASB | `GHL_N&P Individual_8 19.pdf` | `f256b95d6ecf` | 26 | 26 | 0 | 0 | 0 | 0 | 0 (0) | yes |
| ASB | `GHL_N&P Trust_8 19.pdf` | `0057d507cf71` | 37 | 37 | 0 | 0 | 0 | 0 | 0 (0) | yes |
| KiwiBank | `KB_FMS Form_Oct24_v5.pdf` | `3e2c289774c8` | 17 | 16 | 0 | ambiguous_checked_state 1 | 0 | 0 | 0 (0) | no |
| KiwiBank | `KB_Handover Form_May25.pdf` | `15867550a027` | 36 | 32 | 14 | line_item_name_conflict 4 | 0 | 0 | 0 (0) | no |
| KiwiBank | `KB_Stat Dec_No date.pdf` | `1b71dceb7ee6` | 0 | 0 | 0 | 0 | 0 | 0 | 1 (1) | no |
| WBC | `WBC_BoarderForm_12 24.pdf` | `409228fac9ee` | 48 | 18 | 0 | no_target_field 5 | 25 | 0 | 2 (2) | no |
| WBC | `WBC_Entity_GACTA_5 24.pdf` | `04234a2c35b6` | 64 | 54 | 0 | 0 | 10 | 0 | 0 (0) | no |
| WBC | `WBC_Ind_GATCA_5 24.pdf` | `65e19920621e` | 32 | 32 | 0 | 0 | 0 | 0 | 0 (0) | no |
| WBC | `WBC_LoanStructure_4 23.pdf` | `1a1764698891` | 66 | 45 | 24 | line_item_name_conflict 18 | 0 | 3 | 2 (2) | no |
| WBC | `WBC_Refix_4 23.pdf` | `752fdb014a91` | 42 | 22 | 10 | no_target_field 5 | 15 | 0 | 0 (0) | no |
| ANZ | `ANZ_Entity_FACTA_8 25.pdf` | `ed4acd5fb670` | 77 | 70 | 0 | no_target_field 5 | 2 | 0 | 0 (0) | no |
| ANZ | `ANZ_Individual_FACTA_8 25.pdf` | `b937d275bbe2` | 31 | 31 | 0 | 0 | 0 | 0 | 0 (0) | no |
| ANZ | `Boarder-Income-Declaration 12.02.24.pdf` | `6b0efad0aca6` | 8 | 8 | 0 | 0 | 0 | 0 | 2 (2) | no |
| ANZ | `Gift Declaration form 23.02.26.pdf` | `7e374ea55c92` | 2 | 2 | 0 | 0 | 0 | 0 | 0 (0) | no |
| BNZ | `BNZ_BoarderForm_2.0_8 24.pdf` | `7982ae8cbda9` | 7 | 7 | 0 | 0 | 0 | 0 | 2 (2) | no |
| BNZ | `BNZ_Handover_8 24.pdf` | `ebdc9a1fc831` | 105 | 83 | 36 | ambiguous_checked_state 1, line_item_name_conflict 18 | 0 | 3 | 0 (0) | no |
| BNZ | `BNZ_Onboarding_Entities_CoysTrusts_5 22.pdf` | `80f28b380c55` | 47 | 47 | 0 | 0 | 0 | 0 | 1 (1) | no |
| BNZ | `BNZ_Onboarding_IndividualPersonal_7 24.pdf` | `c4a2dbcb2181` | 32 | 32 | 0 | 0 | 0 | 0 | 0 (0) | no |
| BNZ | `BNZ_Onboarding_JointPersonal_9 22.pdf` | `da26e715858f` | 57 | 57 | 0 | 0 | 0 | 0 | 0 (0) | no |
| BNZ | `BNZ Gifting declaration template.pdf` | `b4c14f050a0b` | 57 | 0 | 0 | template_without_form_fields 57 | 0 | 0 | 0 (0) | no |
| (always) | `All Banks_DOAD.pdf` | `d00b31d65d2a` | 0 | 0 | 0 | 0 | 0 | 0 | 4 (4) | no |

Flow D (test flow) holds a second copy of `ANZ_Entity_FACTA_8 25.pdf` with 2 mappings. It is not counted above.

## Tested vs imported

| Template | Manifest in git | Tested end-to-end (asserted output) |
|---|---|---|
| `BNZ_BoarderForm_2.0_8 24.pdf` | `mappings/bnz/BNZ_BOARDER_FORM@2.0_8-24.json` | **yes**: 7 fields + 2 overlays, plus a macron variant |
| `BNZ_Handover_8 24.pdf` | `mappings/bnz/BNZ_HANDOVER_FORM@8-24.json` | **partially**: line items for 3 facilities (product, amount, rate, frequency, purpose), rows 4–6 empty, approval gate; other fields not exercised |
| `GHL_N&P Trust_8 19.pdf` | `mappings/ghl/GHL_NP_TRUST_FORM@8-19.json` | **yes** (2 fixtures, live labels, refusals); **blocked** by an open mapping question (docs/06) |
| `KB_Handover Form_May25.pdf` | `mappings/kiwibank/KB_HANDOVER_FORM@May25.json` | no: resolution check only |
| `ASB Home Loan Boarder Declaration Form.pdf` | `mappings/asb/ASB_BOARDER_DECLARATION@12-22.json` | **yes** (candidate): all 19 fields, radios, comb dates, encryption, precondition |
| `Boarder-Income-Declaration 12.02.24.pdf` (ANZ) | `mappings/anz/ANZ_BOARDER_INCOME_DECLARATION@02-24.json` | **yes** (candidate): 8 fields + 2 overlays, weekly-amount precondition |
| `GHL_N&P Individual_8 19.pdf` | `mappings/ghl/GHL_NP_INDIVIDUAL_FORM@8-19.json` | **yes** (candidate): 6 checkbox groups, closed options |
| other 18 Flow C templates | not generated | no (reasons per form in docs/06) |

Iteration 6 also checked every condition against the **live** Monday labels (read-only schema). The results are in
`docs/generated/template-coverage.md` and docs/06: label drift on GHL forms, multi-select columns, and the
"Banks to Send" column now titled "Selected Banks".

## Supplied files vs embedded copies

| Supplied file | Result |
|---|---|
| `GHL_N&P Trust_8 19.pdf`, `KB_Handover Form_May25.pdf` | SHA-256 **identical** to the embedded copies |
| `BNZ_BoarderForm_2.0_8 24.pdf` and the other PDFs listed in the brief | **not supplied**; embedded copies used |

## Issues found in the legacy configuration (for RSFA review)

1. **BNZ Gifting declaration:** the embedded PDF has no form fields, and MacroForge's own scan found none. Its 57 rules
   are copies of BNZ Onboarding Joint Personal rules. [inference] The legacy run cannot fill it. TODO: confirm with a real output.
2. **Adviser certification pre-ticked:**
   - BNZ Handover `Confirmation` (page 1, "By ticking here, I confirm I have recommended … as appropriate") is **ticked
     in BNZ's blank template itself**. The engine now clears template presets unless an authorized rule selects them (D-016).
     WBC Refix `Check Box1` (page 2) is the only other preset found.
   - Separately, 36 legacy constant `checked` rules exist (e.g. BNZ Handover `Check Box 31#I have`, page 4). None is applied
     without a validated approval record.
   - Correction: iteration 2 wrongly identified the red-bar certification as `Check Box 31`.
3. **Date format mismatch:** BNZ Handover `First Repayment Date n` is a 6-cell comb (`DDMMYY`), but Make sends subitem dates as
   `DDMMYYYY`. The engine refuses (`TEXT_TOO_LONG`) rather than truncating. A reviewed transform is needed.
4. **Combo options:** values must match the PDF options exactly (e.g. BNZ purposes start with "–  "). Whether the Monday labels match is unverified.
5. Earlier findings still stand:
   - `Sources of credits equals …` on a possibly multi-select column;
   - GHL "$1,000-$5,000" mapped to the "$1,000–$10,000" box;
   - PDF-name escaping (`Top#20Up`), which is decoded.
