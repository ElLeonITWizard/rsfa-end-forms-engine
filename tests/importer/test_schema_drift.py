"""tools/schema_drift.py on a synthetic contract and board schema."""
import json
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "tools"))
import schema_drift as sd  # noqa: E402


class SchemaDriftTest(unittest.TestCase):
    def test_classifies_renames_type_aliases_removals_and_new_columns(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            contract = d / "c.tsv"
            contract.write_text("source\tmonday_column_id\tresult_key\tmonday_type_hint\tvalue_rule\n"
                                "column\tdropdown_a\tBanks to Send\tdropdown\tx\n"
                                "column\tboolean_b\tFlag\tboolean\tx\n"
                                "column\ttext_c\tGone\ttext\tx\n"
                                "column\tboolean_d\tReplaced\tboolean\tx\n"
                                "derived-address(OpenAI module 26)\t\tX Street\t\tx\n")
            board = d / "b.json"
            board.write_text(json.dumps({"board": {"id": "1", "name": "Test board", "columns": [
                {"id": "dropdown_a", "title": "Selected Banks", "type": "dropdown"},
                {"id": "boolean_b", "title": "Flag", "type": "checkbox"},
                {"id": "text_e", "title": "Replaced", "type": "text"},
                {"id": "text_new", "title": "Brand new", "type": "text"}]}}))
            out = d / "r.md"
            sd.main(["--contract", str(contract), "--board", str(board), "--date", "2026-10-28", "--out", str(out)])
            t = out.read_text()
            self.assertIn("| **Display-name change** (same ID, new title) | 1 |", t)
            self.assertIn("| **Real type change** (same ID) | 0 |", t, "boolean/checkbox is the same type")
            self.assertIn("| `dropdown_a` | Banks to Send | **Selected Banks** | dropdown |", t)
            self.assertIn("| `text_c` | Gone | **none: key no longer sent** |", t)
            self.assertIn("value type changed: boolean → text", t)
            self.assertIn("| `text_new` | Brand new | text |", t)


if __name__ == "__main__":
    unittest.main()
