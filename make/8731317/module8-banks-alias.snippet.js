// PROPOSED, NOT APPLIED. Insert in scenario 8731317, module 8 (code:ExecuteCode), directly after
//   const result = mapColumns(input.column_values);
// Restores the key MacroForge Flow C conditions read ("Banks to Send Contains <bank>") after column
// dropdown_mkzvfgeg was renamed "Selected Banks" (Monday activity log, 2026-10-25). Keyed by the stable column ID.
// Additive only: "Selected Banks" stays; nothing else in the payload changes.
const BANKS_COLUMN_ID = "dropdown_mkzvfgeg";
const LEGACY_BANKS_KEY = "Banks to Send";
const banksColumn = (input.column_values || []).find(col => col && col.id === BANKS_COLUMN_ID);
if (banksColumn && !(LEGACY_BANKS_KEY in result)) {
  result[LEGACY_BANKS_KEY] = extractColumnValue(banksColumn);
}
