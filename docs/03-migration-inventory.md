# 03 — Migration inventory (legacy MacroForge export)

Source: `RSFA End Forms Filling BluePrint 271026.mforge`, SHA-256
`8052c17b374b551272e17f5036de1ebfa9a295328eb8a6d079d30eec788b9a51`, 16.3 MB, inspected 2026-10-27
with `tools/import_mforge.py 0.1.0` (`inventory --with-pdf`). This file contains counts and names only. The full JSON
is written locally to `.local/output/inventory.json`.

Terms: **detected** = present in the export; **imported** = turned into an engine rule that resolves against
the real PDF; **tested** = asserted in a passing test; **unsupported** = a legacy rule type the engine does not
implement yet; **stale** = the mapped field exists neither in the PDF nor in MacroForge's own field scan
(`native_field_info`), so the legacy engine cannot fill it either.

## Export structure (verified)

- Top level: `branches`, `current_branch` (= `Flow C`), `flow_display_names`, `flow_data`, `webhook_platform_prefs`, `ai_router`.
- Flows:
  - `Main Flow`, `Flow A`, `Flow B`: one Email Trigger each, no filler. Not migrated.
  - **`Flow C` "End Forms Flow Filling"**, the production flow, has these nodes in order:
    1. Webhook Trigger (org trigger "Masterboard Main Webhook")
    2. 5 conditional blocks (start/end pairs) on `Banks to Send Contains ASB | KiwiBank | WBC | ANZ | BNZ`
    3. **6 PDF Batch Filler nodes**. The ASB block contains two of them: ASB and GHL.
    4. 1 single PDF Filler (`All Banks_DOAD.pdf`, unconditional)
    5. "Send to Zapier/Make" (batch callback)
  - `Flow D` "End Forms Filling Test": a webhook and one PDF Filler (ANZ Entity FACTA, 2 mappings, `flatten_pdf: true`). Test/auxiliary.
  - `Flow E`–`G`: empty.
- Mapping storage per batch node: `field_mappings_by_file[pdf][pdfField] = <source key | sentinel>`, plus
  `field_conditions_by_file`, `custom_fields_by_file` (overlays), `native_field_info_by_file`, `embedded_pdfs` (base64).
- All batch nodes have `flatten_pdf: false`, `auto_fit_text: false` and `highlight_empty: false`.
- Sensitive content present in the export: a real `sample_payload`, `variable_map`, export folder paths,
  filename text blocks and the callback `webhook_url`. None of these is read into importer outputs.

The hypothesis "25 PDFs in six lender groups, ~1,013 mappings" is **confirmed**: Flow C has 25 PDFs in 6 batch nodes
and exactly **1,013** mapping entries. There are also 2 more embedded PDFs: `All Banks_DOAD.pdf` and Flow D's copy of ANZ Entity FACTA.

## Mapping kinds in Flow C (detected → outcome)

| Legacy kind | Detected | Imported | Unsupported | Stale | Engine rule |
|---|---|---|---|---|---|
| source key (plain) | 531 | 411 | 0 | 120 | `text`/`combo` `{"key"}` |
| `conditional` (single `equals`/`contains`; 146 use `Field#state`) | 242 | 223 | 19 (18 on `LineItems.*` columns, 1 multi-condition) | 0 | `checkbox`/`radio` `{"when"}` |
| `checked` | 38 | 38 | 0 | 0 | `{"constant": true}`, `reviewRequired` |
| `unchecked` | 13 | 13 | 0 | 0 | `{"constant": false}`, `reviewRequired` |
| `direct_text` `today_date` | 13 | 12 | 0 | 1 | `{"generated":"today"}`, format from the field's JS action or comb MaxLen, `reviewRequired` |
| `direct_text` literal | 3 | 0 | 0 | 3 | `{"constant": "<text>"}`, `reviewRequired` |
| `LineItems.<field>_<n>` | 173 | 0 | 173 | 0 | not implemented |
| **Total** | **1,013** | **697** | **192** | **124** | 0 import errors |

Overlays (`custom_fields`, free-positioned text, not mapping entries): **14**, all unsupported.

## PDF inventory (all embedded templates)

0 XFA, 0 duplicate fully-qualified field names, 150 fields with more than one widget,
**7 owner-encrypted** (all ASB/GHL). 3 templates have no AcroForm.

| Flow | Node | Lender condition | Template (legacy name) | SHA-256 (12) | Fields (pdf) | Mappings | Imported | Unsupported | Stale | Overlays | Encrypted |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Flow C | 2 | ASB | `ASB Home Loan Boarder Declaration Form.pdf` | `923b43b8f3fb` | radio 2, text 17 | 23 | 23 | 0 | 0 | 0 | yes |
| Flow C | 2 | ASB | `ASB&GHL_AdvDec_05 25.pdf` | `f627a4866ae7` | checkbox 15, radio 2, text 8 | 19 | 19 | 0 | 0 | 0 | yes |
| Flow C | 2 | ASB | `ASB_AdvDec_LoanStructure_6 25.pdf` | `114c025f81c3` | checkbox 48, radio 4, text 78 | 98 | 36 | 62 | 0 | 0 | yes |
| Flow C | 2 | ASB | `ASB_CAD_6 19.pdf` | `37fd79696292` | checkbox 15, radio 7, text 43 | 67 | 67 | 0 | 0 | 0 | yes |
| Flow C | 3 | ASB | `GHL_DD_11 24.pdf` | `7bf194a5778b` | radio 1, text 16 | 15 | 15 | 0 | 0 | 0 | yes |
| Flow C | 3 | ASB | `GHL_N&P Individual_8 19.pdf` | `f256b95d6ecf` | checkbox 8, text 14 | 26 | 26 | 0 | 0 | 0 | yes |
| Flow C | 3 | ASB | `GHL_N&P Trust_8 19.pdf` | `0057d507cf71` | checkbox 13, text 17 | 37 | 37 | 0 | 0 | 0 | yes |
| Flow C | 6 | KiwiBank | `KB_FMS Form_Oct24_v5.pdf` | `3e2c289774c8` | checkbox 12, pushbutton 1, text 20 | 17 | 17 | 0 | 0 | 0 | no |
| Flow C | 6 | KiwiBank | `KB_Handover Form_May25.pdf` | `15867550a027` | checkbox 8, combo 20, pushbutton 2, radio 2, text 51 | 36 | 18 | 18 | 0 | 0 | no |
| Flow C | 6 | KiwiBank | `KB_Stat Dec_No date.pdf` | `1b71dceb7ee6` | none (no AcroForm) | 0 | 0 | 1 | 0 | 1 | no |
| Flow C | 9 | WBC | `WBC_BoarderForm_12 24.pdf` | `409228fac9ee` | checkbox 6, text 19 | 48 | 18 | 2 | 30 | 2 | no |
| Flow C | 9 | WBC | `WBC_Entity_GACTA_5 24.pdf` | `04234a2c35b6` | radio 27, text 70 | 64 | 54 | 0 | 10 | 0 | no |
| Flow C | 9 | WBC | `WBC_Ind_GATCA_5 24.pdf` | `65e19920621e` | radio 6, text 27 | 32 | 32 | 0 | 0 | 0 | no |
| Flow C | 9 | WBC | `WBC_LoanStructure_4 23.pdf` | `1a1764698891` | checkbox 6, text 78 | 66 | 21 | 47 | 0 | 2 | no |
| Flow C | 9 | WBC | `WBC_Refix_4 23.pdf` | `752fdb014a91` | checkbox 1, combo 2, text 51 | 42 | 12 | 10 | 20 | 0 | no |
| Flow C | 12 | ANZ | `ANZ_Entity_FACTA_8 25.pdf` | `ed4acd5fb670` | checkbox 43, text 45 | 77 | 70 | 0 | 7 | 0 | no |
| Flow C | 12 | ANZ | `ANZ_Individual_FACTA_8 25.pdf` | `b937d275bbe2` | checkbox 14, text 22 | 31 | 31 | 0 | 0 | 0 | no |
| Flow C | 12 | ANZ | `Boarder-Income-Declaration 12.02.24.pdf` | `6b0efad0aca6` | radio 2, text 17 | 8 | 8 | 2 | 0 | 2 | no |
| Flow C | 12 | ANZ | `Gift Declaration form 23.02.26.pdf` | `7e374ea55c92` | text 28 | 2 | 2 | 0 | 0 | 0 | no |
| Flow C | 15 | BNZ | `BNZ_BoarderForm_2.0_8 24.pdf` | `7982ae8cbda9` | text 8 | 7 | 7 | 2 | 0 | 2 | no |
| Flow C | 15 | BNZ | `BNZ_Handover_8 24.pdf` | `ebdc9a1fc831` | checkbox 26, combo 26, text 83 | 105 | 48 | 57 | 0 | 0 | no |
| Flow C | 15 | BNZ | `BNZ_Onboarding_Entities_CoysTrusts_5 22.pdf` | `80f28b380c55` | checkbox 32, combo 6, text 91 | 47 | 47 | 1 | 0 | 1 | no |
| Flow C | 15 | BNZ | `BNZ_Onboarding_IndividualPersonal_7 24.pdf` | `c4a2dbcb2181` | checkbox 26, combo 2, text 55 | 32 | 32 | 0 | 0 | 0 | no |
| Flow C | 15 | BNZ | `BNZ_Onboarding_JointPersonal_9 22.pdf` | `da26e715858f` | checkbox 38, combo 4, text 109 | 57 | 57 | 0 | 0 | 0 | no |
| Flow C | 15 | BNZ | `BNZ Gifting declaration template.pdf` | `b4c14f050a0b` | none (no AcroForm) | 57 | 0 | 0 | 57 | 0 | no |
| Flow C | 17 | (unconditional) | `All Banks_DOAD.pdf` | `d00b31d65d2a` | none (no AcroForm) | 0 | 0 | 4 | 0 | 4 | no |
| Flow D | 1 | (unconditional) | `ANZ_Entity_FACTA_8 25.pdf` | `ed4acd5fb670` | checkbox 43, text 45 | 2 | 2 | 0 | 0 | 0 | no |

The "Unsupported" column counts mapping entries *and* overlays for that template.

## Tested vs imported

| Template | Manifest in git | Tested end-to-end |
|---|---|---|
| `BNZ_BoarderForm_2.0_8 24.pdf` | `mappings/bnz/BNZ_BOARDER_FORM@2.0_8-24.json` | **yes**: all 7 imported fields asserted and rendered |
| `GHL_N&P Trust_8 19.pdf` | `mappings/ghl/GHL_NP_TRUST_FORM@8-19.json` | **partially**: 3 text fields + 9 checkbox groups asserted; not every option exercised |
| `KB_Handover Form_May25.pdf` | `mappings/kiwibank/KB_HANDOVER_FORM@May25.json` | no: resolution check only (all rules resolve to real fields) |
| other 22 Flow C templates | not generated | no |

## Supplied files vs embedded copies

| Supplied file | Result |
|---|---|
| `GHL_N&P Trust_8 19.pdf` | SHA-256 **identical** to embedded (`0057d507…`) |
| `KB_Handover Form_May25.pdf` | SHA-256 **identical** to embedded (`15867550…`) |
| `BNZ_BoarderForm_2.0_8 24.pdf`, `ANZ_Entity_FACTA_8 25.pdf`, `BNZ_Onboarding_IndividualPersonal_7 24.pdf`, `ASB&GHL_AdvDec_05 25.pdf` | **not supplied**; embedded copies used |
| `Boarder_Income_Declaration 01.05.26 (NEW).pdf` | **not supplied**. Note: the export embeds `Boarder-Income-Declaration 12.02.24.pdf` (ANZ group), an older-looking version. TODO: confirm which one is current |

## Issues found in the legacy configuration (for RSFA review)

1. **124 stale mappings**:
   - `BNZ Gifting declaration template.pdf` has no form fields but 57 mappings.
   - WBC Boarder/Entity/Refix and ANZ Entity carry 67 mappings to fields that do not exist (e.g. `Adviser Company`, `First Middle`).
2. **PDF-name escaping**: options such as `Check Box 1#Top#20Up` (BNZ Handover) are stored escaped. The importer decodes them (`Top Up`).
3. **`equals` on multi-value columns**: e.g. `Sources of credits equals "Donations"` (GHL Trust). If that column is multi-select
   in Monday, a client with two sources would tick nothing. [inference] TODO: verify the column type.
4. **Label mismatches**: e.g. GHL Trust option 2 maps the Monday value `$1,000-$5,000` to the printed box "$1,000–$10,000".
   This is reproduced faithfully. RSFA should confirm it is intended.
5. Constant `checked` boxes (38) and `[TODAY]` dates beside signature lines are legacy behaviour and are
   flagged `reviewRequired`. They must not be treated as client consent by the new engine without sign-off.
