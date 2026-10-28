"""Guards on the importable Make blueprints (make/e2e-scenario, docs/15)."""
from __future__ import annotations

import json
import re
import subprocess
import sys
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
BP = REPO / "make" / "e2e-scenario"
CODE = (REPO / "make" / "test-scenario" / "code-module-sign.js").read_text(encoding="utf-8")


def modules(flow):
    for m in flow:
        yield m
        for r in m.get("routes") or []:
            yield from modules(r["flow"])
        yield from modules(m.get("onerror") or [])


class MakeBlueprintTest(unittest.TestCase):

    def load(self, mode):
        return json.loads((BP / f"blueprint.{mode}.json").read_text(encoding="utf-8"))

    def test_generator_output_is_current(self):
        before = {p.name: p.read_bytes() for p in BP.glob("*.json")}
        subprocess.run([sys.executable, str(REPO / "tools" / "build_make_blueprints.py")], check=True, capture_output=True)
        self.assertEqual(before, {p.name: p.read_bytes() for p in BP.glob("*.json")}, "re-run tools/build_make_blueprints.py")

    def test_code_modules_embed_the_tested_code_verbatim(self):
        for mode in ("synthetic", "monday"):
            codes = [m["mapper"]["codeEditorJavascript"] for m in modules(self.load(mode)["flow"]) if m["module"] == "code:ExecuteCode"]
            self.assertEqual(2, len(codes))
            self.assertTrue(all(c == CODE for c in codes), mode)

    def test_synthetic_never_reads_monday_and_monday_reads_only_approved_items_by_column_id(self):
        syn = self.load("synthetic")
        self.assertFalse(any(m["module"].startswith("monday:") for m in modules(syn["flow"])))
        mon = self.load("monday")
        get = next(m for m in modules(mon["flow"]) if m["module"] == "monday:GetItemV2")
        self.assertIn("approvedItemIds", json.dumps(get["filter"]), "F1 filter before the Monday read")
        self.assertEqual("5026186805", get["mapper"]["boardId"])
        code = next(m for m in modules(mon["flow"]) if m["module"] == "code:ExecuteCode")
        names = {i["name"]: i["value"] for i in code["mapper"]["input"]}
        self.assertEqual("{{3.mappable_column_values.dropdown_mkzvfgeg.text}}", names["dropdown_mkzvfgeg"], "Selected Banks by ID")
        for col in ("text_mm2tsfyq", "text_mm2tgjpw", "text_mkzvqaw7", "numeric_mkzvmp91", "numeric_mkzvk0wg"):
            self.assertIn(col, names)
        self.assertFalse(any("Banks to Send" in i["value"] for i in code["mapper"]["input"]))

    def test_no_secret_url_or_client_value_and_safe_settings(self):
        for mode in ("synthetic", "monday"):
            text = (BP / f"blueprint.{mode}.json").read_text(encoding="utf-8")
            for ph in ("<<RELAY_URL>>", "<<RELAY_TEST_SECRET>>", "<<SP_SITE_ID>>", "<<SP_DRIVE_ID>>", "<<SP_TEST_FOLDER_PATH>>"):
                self.assertIn(ph, text)
            self.assertIsNone(re.search(r"https://[a-z0-9.-]+\.(run\.app|make\.com|sharepoint\.com)", text))
            self.assertIsNone(re.search(r"\b[0-9a-f]{64}\b", text), "no secret-like hex")
            bp = self.load(mode)
            s = bp["metadata"]["scenario"]
            self.assertTrue(s["confidential"] and s["sequential"])
            http = next(m for m in modules(bp["flow"]) if m["module"] == "http:MakeRequest")
            self.assertFalse(http["mapper"]["stopOnHttpError"])
            self.assertEqual("{{11.result.body}}", http["mapper"]["rawBodyContent"], "the signed bytes are sent unchanged")
            up = next(m for m in modules(bp["flow"]) if m["module"] == "microsoft-sharepoint:uploadAFile")
            self.assertEqual("{{2.spFolder}}", up["mapper"]["folder"])


if __name__ == "__main__":
    unittest.main()
