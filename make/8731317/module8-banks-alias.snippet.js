// RSFA compatibility (2026-10): MacroForge Flow C reads "Banks to Send"; column dropdown_mkzvfgeg is now titled "Selected Banks". Additive alias by column ID.
const BANKS_COLUMN_ID = "dropdown_mkzvfgeg";
const LEGACY_BANKS_KEY = "Banks to Send";
const banksColumn = (input.column_values || []).find(col => col && col.id === BANKS_COLUMN_ID);
if (banksColumn && !(LEGACY_BANKS_KEY in result)) {
  result[LEGACY_BANKS_KEY] = extractColumnValue(banksColumn);
}
