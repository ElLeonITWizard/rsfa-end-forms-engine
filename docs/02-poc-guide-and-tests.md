# 02 — POC guide, manifest schema and tests

## End-to-end POC steps

See the README quickstart. In summary: (1) copy the `.mforge` to `.local/reference/`, (2) `import_mforge.py extract`,
(3) `mvn package` (runs all tests), (4) `fill` with `tests/fixtures/bnz-boarder.request.json`,
(5) render with `pdftoppm` and look at it.

To re-import a manifest after the legacy export changes, run `import_mforge.py manifest … --strict --date <YYYY-MM-DD>`
and review the diff. Every `reviewRequired` entry needs a human decision before production.

## How the BNZ Boarder template was classified

| Property | Result | Evidence |
|---|---|---|
| Identity | embedded in Flow C node 15 (`Banks to Send Contains BNZ`), SHA-256 `7982ae8cbda97f0be9173c2632839afaa7c0d725349cab32dddbd62520509795`, 49,354 bytes | importer |
| External copy | **not supplied**, so no SHA comparison was possible. The embedded copy was used. | — |
| Structure | 1 page A4, AcroForm, no XFA, not encrypted, 8 text fields (`Text 1`–`Text 6`, `Date 1`, `Date 2`), no duplicates | pypdf + PDFBox |
| Fonts | field DA uses embedded `SerranoPro-Regular` (Type1C, MacRoman). ASCII fills correctly; `ā` fails | experiment |
| Dates | `Date 1`/`Date 2`: comb, MaxLen 6, Acrobat action `AFDate_FormatEx("ddmmyy")`, so `ddMMyy` | PDF |
| Legacy mapping | 7 entries: 5 source keys (`1st Property Address Street/City/Postcode`, `Number of rooms proposed to be rented`, `Proposed weekly amount(s) per room`) + 2 `direct_text` `[TODAY]` (`today_date`) | `.mforge` |
| Not migrated | 2 `custom_fields` overlays (`Applicant 1/2 Full Name`) drawn at free positions. `Text 1` (Customer name(s)) has no AcroForm mapping in the legacy config | `.mforge` |

The overlay rects (`[121,160,395,182]`, `[398,161,671,182]`, page 0) do not match the page in PDF points
(they exceed the 595pt width) or at 96 DPI. MacroForge's own `native_field_info` rects *are* top-left PDF points
(verified against the widget rects). The overlay coordinate system is therefore **unknown**, and no position is guessed.
Proposed next step: confirm with RSFA whether `Text 1` should receive `Applicant 1 Full Name` + `Applicant 2 Full Name`.
That would be a hand-authored, reviewed mapping change, not an import.

The second test template, **GHL N&P Trust**, uses the user-supplied file, which is SHA-256 identical to the embedded copy
(`0057d507…797c`). It is AES-128 owner-encrypted (`/P -1084`, fill-in permitted). It uses checkbox fields with
several widgets, each with its own on-state (e.g. `Check Box101` = 1/2/3/4). The legacy `Field#state` syntax selects among them.

## Manifest schema (v1)

```jsonc
{
  "schemaVersion": 1,
  "templateId": "BNZ_BOARDER_FORM", "templateVersion": "2.0_8-24",
  "templateFile": "7982ae8cbda9__BNZ_BoarderForm_2.0_8_24.pdf",   // looked up in template dirs
  "templateSha256": "7982ae8c…",                                  // must match the bytes, else TEMPLATE_HASH_MISMATCH
  "encryption": "reject" | "preserve",                            // preserve = incremental save, original encryption kept
  "sourceKeyNormalization": "none" | "macroforge-v1",             // '/' and '.' in data keys -> '_'
  "provenance": { … },  "pdf": { …field inventory… },            // informational
  "fields": [
    {"pdfField": "Text 2", "kind": "text", "source": {"key": "1st Property Address Street"}, "required": false},
    {"pdfField": "Date 1", "kind": "text", "source": {"generated": "today", "format": "ddMMyy", "zone": "Pacific/Auckland"}},
    {"pdfField": "Check Box95", "kind": "checkbox", "option": "3",
     "source": {"when": {"key": "Total credits or deposits (next 12 months) (GHL Trust)", "operator": "equals", "value": "$1M-$5M"}}},
    {"pdfField": "Some box", "kind": "checkbox", "source": {"constant": true}}
  ],
  "unsupported": [ … ],  "importErrors": [ … ],  "importWarnings": [ … ],  "staleLegacyMappings": [ … ]
}
```

Kinds: `text`, `checkbox`, `radio`, `combo`, `list`. Sources: `key` | `constant` | `generated:"today"` | `when`.

## Engine behaviour (what is supported)

| Case | Behaviour |
|---|---|
| Text from key/constant/today | `setValue` + PDFBox appearance generation; MaxLen enforced (no truncation); line breaks rejected in single-line fields |
| Number value | rendered as plain decimal (`250.00` stays as sent if it is a string) |
| Boolean/array/object value into text | `UNSUPPORTED_VALUE_TYPE` / `AMBIGUOUS_SOURCE_VALUE` error |
| Missing value | required → `MISSING_REQUIRED_VALUE` error; optional → skipped; key absent → also `SOURCE_KEY_ABSENT` warning |
| Checkbox/radio `when` | exact (`equals`) / substring (`contains`), case-sensitive; a non-match leaves the field untouched; >1 selected option → `CONFLICTING_SELECTION` |
| Checkbox constant | `true` → on-state, `false` → Off (legacy `checked`/`unchecked`) |
| Combo/list | value must be an export or display option, else `INVALID_OPTION` |
| Signature / push button target | always `FORBIDDEN_TARGET` |
| Unencodable characters | `UNENCODABLE_TEXT` (field font lacks the glyph); never dropped |
| Encrypted template | `reject` → `ENCRYPTED_TEMPLATE`; `preserve` → requires fill-in permission, incremental save |
| XFA / no AcroForm | `XFA_PRESENT` / `NO_ACROFORM` |
| Any ERROR | no PDF is produced (strict) |
| Output | new bytes; template never modified; editable (not flattened); JavaScript format actions are not run (the value is written exactly as resolved) |

**Not supported (legacy rule types):**
- `overlay_text` (14 in Flow C)
- `line_item` (173), using `LineItems.<field>_<n>` keys, with subitem key naming still unverified
- `line_item_condition` (18)
- multi-condition `conditional` (1)

## Acceptance criteria for this POC

| # | Criterion | Status |
|---|---|---|
| 1 | One existing lender PDF filled locally from a mapping **imported** from the `.mforge`, using fictional data | **Passed**: `BNZ_BoarderForm_2.0_8 24.pdf`, 7/7 imported fields |
| 2 | Field values asserted on the output | **Passed**: `LegacyTemplatesTest.bnzBoarderFormFromImportedMapping` |
| 3 | Output visibly rendered (appearance streams), no clipping/misalignment | **Passed** (PDFBox raster assertion + manual poppler render review) |
| 4 | Same input + mapping → equivalent field values on repeated runs | **Passed** (field values; bytes are not compared) |
| 5 | Checkbox/radio template smoke test | **Passed**: GHL N&P Trust (checkboxes), poppler render reviewed with the URW Dingbats font. **Radio** passes only on the synthetic template |
| 6 | Same output as MacroForge for the same input | **Not testable** here: no MacroForge output available |
| 7 | Customer names on BNZ form | **Failed / not migrated** (overlay) |

## Repeatable tests

| Suite | Command | Count | Notes |
|---|---|---|---|
| Importer (synthetic .mforge) | `python3 -m unittest discover -s tests/importer -v` | 6 | includes a "no payload/webhook value leaks" test |
| Engine unit (synthetic AcroForm) | `cd engine && mvn -B test` | 14 | every widget kind, errors, normalisation, rendering, no value echo |
| HTTP API | (same) | 6 | health, 200 PDF, 401/404/400/413, 422 without data echo, loopback-only `NONE` |
| Legacy templates | (same) | 3 | BNZ, GHL, every committed manifest resolves against its template; **skipped without `.local` templates** |

Last full run: 2026-10-27, all 29 passed (6 Python + 23 Java, none skipped).

## Known gaps / next work

1. Decide on BNZ `Text 1` (customer names) and the overlay strategy in general (14 overlays, including `All Banks_DOAD.pdf`).
2. Line items: map `LineItems.<field>_<n>` to `Subitems[n-1].values[<title>]` once the title normalisation is verified.
3. Confirm the legacy `[TODAY]` format, the `equals`/`contains` semantics and the `checked` defaults against real MacroForge output (parallel run).
4. Verify the 11 source keys missing from the cached Make column list against the live board.
5. Fonts: embed a Unicode fallback font for macrons (a decision is needed, as it changes appearance).
6. Review the 124 stale mappings with RSFA (e.g. 57 on `BNZ Gifting declaration template.pdf`, which has no form fields).
