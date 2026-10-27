"""Tests for tools/coverage_report.py: synthetic manifest and board schema only."""
import json
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "tools"))
import coverage_report as cr  # noqa: E402


class CoverageReportTest(unittest.TestCase):
    def test_flags_stale_labels_unruled_labels_and_missing_keys(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            maps = d / "mappings" / "x"
            maps.mkdir(parents=True)
            m = {"templateId": "T", "templateVersion": "1", "templateSha256": "a" * 64, "encryption": "reject",
                 "provenance": {"legacyPdfName": "t.pdf"}, "pdf": {"pages": 1, "fields": {
                     "Box": {"kind": "checkbox"}, "Name": {"kind": "text"}, "Sig": {"kind": "text"}}},
                 "fields": [{"pdfField": "Box", "kind": "checkbox", "option": "1",
                             "source": {"when": {"key": "Freq", "operator": "equals", "value": "Old label"}}},
                            {"pdfField": "Name", "kind": "text", "source": {"key": "Gone column"}}],
                 "unmappedPdfFields": ["Sig"], "review": {"status": "candidate", "openQuestions": ["why"]}}
            (maps / "T@1.json").write_text(json.dumps(m))
            board = d / "board.json"
            board.write_text(json.dumps({"board": {"columns": [
                {"title": "Freq", "type": "dropdown", "settings": {"limit_select": True, "labels": [
                    {"id": 1, "label": "New label"}, {"id": 2, "label": "Never"}]}}]}}))
            out = d / "r.md"
            cr.main(["--mappings", str(d / "mappings"), "--board", str(board), "--date", "2026-10-27", "--out", str(out)])
            text = out.read_text()
            self.assertIn("rule value not a live label: `Old label`", text)
            self.assertIn("live label without rule: `New label`, `Never`", text)
            self.assertIn("NOT on the live board", text)
            self.assertIn("| `T@1` | candidate | 3 | 2 | 1 | 2 | 0 | 0 | 1 | 2 | 1 | 1 |", text)


if __name__ == "__main__":
    unittest.main()
