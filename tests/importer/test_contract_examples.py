"""The Make contract examples must validate against the published JSON Schema and stay fictional."""
import json
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]

try:
    import jsonschema
except ImportError:  # optional dev dependency
    jsonschema = None


class ContractExamplesTest(unittest.TestCase):
    def test_examples_validate_and_use_only_fictional_identifiers(self):
        schema = json.loads((REPO / "contracts" / "masterboard-request.v1.schema.json").read_text(encoding="utf-8"))
        examples = sorted((REPO / "contracts" / "examples").glob("request.*.json"))
        self.assertGreaterEqual(len(examples), 3)
        for p in examples:
            doc = json.loads(p.read_text(encoding="utf-8"))
            if jsonschema is not None:
                jsonschema.validate(doc, schema)
            self.assertTrue(doc["source"]["itemId"].startswith("10000000"), f"{p.name}: fictional item ids only")
            self.assertIn(doc["data"].get("Applicant 1 Full Name"), ("Jane Example", None), p.name)
            self.assertNotRegex(p.read_text(encoding="utf-8"), r"hook\.[a-z0-9]+\.make\.com|webhook_url")

    @unittest.skipIf(jsonschema is None, "jsonschema not installed")
    def test_schema_rejects_both_selection_kinds_and_unknown_banks(self):
        schema = json.loads((REPO / "contracts" / "masterboard-request.v1.schema.json").read_text(encoding="utf-8"))
        doc = json.loads((REPO / "contracts" / "examples" / "request.bank-bnz.json").read_text(encoding="utf-8"))
        for bad in ({"banks": ["BNZ"], "templates": [{"templateId": "A", "templateVersion": "1"}]}, {"banks": ["Westpac"]}):
            with self.assertRaises(jsonschema.ValidationError):
                jsonschema.validate({**doc, "selection": bad}, schema)


if __name__ == "__main__":
    unittest.main()
