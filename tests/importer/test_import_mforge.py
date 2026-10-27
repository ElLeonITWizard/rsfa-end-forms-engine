"""Tests for tools/import_mforge.py using a synthetic .mforge (structure mirrors the real export,
values are fictional). Run: python3 -m unittest discover -s tests/importer -v"""
import base64
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "tools"))
import import_mforge as im  # noqa: E402

SECRET = "Zq-SECRET-CLIENT-VALUE"


def minimal_form_pdf(first_field: str = "Name") -> bytes:
    """Hand-built one-page A4 AcroForm: text <first_field>, comb date 'Date 1' (AFDate ddmmyy),
    checkbox 'Box' (on=Yes), multi-widget checkbox 'Freq' (on-states 1 and 2)."""
    objs = []

    def add(body: str) -> int:
        objs.append(body)
        return len(objs)

    ap_on = add("<< /Length 0 >>\nstream\n\nendstream")
    ap_off = add("<< /Length 0 >>\nstream\n\nendstream")
    js = add("<< /S /JavaScript /JS (AFDate_FormatEx\\(\"ddmmyy\"\\);) >>")
    name = add(f"<< /FT /Tx /T ({first_field}) /Type /Annot /Subtype /Widget /Rect [50 700 300 720] /P 9 0 R /DA (/Helv 10 Tf 0 g) >>")
    date = add(f"<< /FT /Tx /T (Date 1) /Ff 16777216 /MaxLen 6 /AA << /F {js} 0 R >> /Type /Annot /Subtype /Widget "
               "/Rect [50 670 150 690] /P 9 0 R /DA (/Helv 10 Tf 0 g) >>")
    box = add(f"<< /FT /Btn /T (Box) /Type /Annot /Subtype /Widget /Rect [50 640 64 654] /P 9 0 R "
              f"/AP << /N << /Yes {ap_on} 0 R /Off {ap_off} 0 R >> >> /AS /Off >>")
    freq = add("<< /FT /Btn /T (Freq) /Kids [11 0 R 12 0 R] >>")
    acro = add(f"<< /Fields [{name} 0 R {date} 0 R {box} 0 R {freq} 0 R] /DA (/Helv 0 Tf 0 g) >>")
    page = add(f"<< /Type /Page /Parent 10 0 R /MediaBox [0 0 595 842] /Annots [{name} 0 R {date} 0 R {box} 0 R 11 0 R 12 0 R] >>")
    pages = add(f"<< /Type /Pages /Kids [{page} 0 R] /Count 1 >>")
    for i, st in enumerate(("1", "2")):
        add(f"<< /Type /Annot /Subtype /Widget /Parent {freq} 0 R /Rect [{50 + 30 * i} 610 {64 + 30 * i} 624] "
            f"/P {page} 0 R /AP << /N << /{st} {ap_on} 0 R /Off {ap_off} 0 R >> >> /AS /Off >>")
    catalog = add(f"<< /Type /Catalog /Pages {pages} 0 R /AcroForm {acro} 0 R >>")
    assert (page, pages) == (9, 10)

    out = bytearray(b"%PDF-1.6\n")
    offsets = []
    for i, body in enumerate(objs, 1):
        offsets.append(len(out))
        out += f"{i} 0 obj\n{body}\nendobj\n".encode()
    xref = len(out)
    out += f"xref\n0 {len(objs) + 1}\n0000000000 65535 f \n".encode()
    out += b"".join(f"{o:010d} 00000 n \n".encode() for o in offsets)
    out += f"trailer\n<< /Size {len(objs) + 1} /Root {catalog} 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode()
    return bytes(out)


def synthetic_mforge(pdf: bytes, sibling: bytes) -> dict:
    b64 = base64.b64encode(pdf).decode()
    return {
        "branches": {
            "Flow C": [
                {"type": "⚡ Webhook Trigger", "sample_payload": {"name": SECRET}, "variable_map": {"result|x": SECRET},
                 "line_items_configs": [{"array_path": "result|Subitems", "sub_mappings": [
                     {"source_rel_path": "name", "subfield_name": "name"},
                     {"source_rel_path": "values|Loan amount", "subfield_name": "Loan amount"}]}]},
                {"type": "🚦 Conditional Block", "mode": "start", "logic_gate": "OR",
                 "conditions": [{"field": "Banks to Send", "operator": "Contains", "value": "TESTBANK"}]},
                {"type": "📝 PDF Batch Filler", "export_folder": SECRET,
                 "embedded_pdfs": {"Test Form.pdf": b64, "Sibling Form.pdf": base64.b64encode(sibling).decode()},
                 "field_mappings_by_file": {
                     "Test Form.pdf": {
                         "Name": "Applicant 1 Full Name",
                         "Date 1": "direct_text",
                         "Box": "checked",
                         "Freq": "unchecked",
                         "Freq#2": "conditional",
                         "Missing field": "Some key",
                         "Name 2": "LineItems.Loan amount_1",
                         "Other name": "Applicant 2 Full Name",
                     },
                     "Sibling Form.pdf": {
                         "Other name": "Applicant 2 Full Name",
                         "Date 1": "LineItems.Loan amount_2",
                         "Box": "LineItems.Fixed term in months_1",
                         "Freq": "checked",
                     }},
                 "field_conditions_by_file": {"Test Form.pdf": {
                     "Date 1": {"direct_text": "[TODAY]", "type": "today_date"},
                     "Box": {"default": "checked"},
                     "Freq": {"default": "unchecked"},
                     "Freq#2": {"conditions": [{"column": "Frequency", "operator": "equals", "value": "Fortnightly"}],
                                "operators": []},
                 }, "Sibling Form.pdf": {"Freq": {"default": "checked"}}},
                 "native_field_info_by_file": {"Test Form.pdf": {"Name": {}, "Date 1": {}, "Box": {}, "Freq": {},
                                                                  "Missing field": {}},
                                               "Sibling Form.pdf": {"Other name": {}, "Date 1": {}, "Box": {},
                                                                    "Freq": {}}},
                 "line_item_instances_by_file": {"Sibling Form.pdf": {"LineItems": {"Loan amount": {"2": "Date 1"}}}},
                 "custom_fields_by_file": {"Test Form.pdf": {
                     "custom_text_1_1": {"type": "text", "rect": [120.0, 120.0, 360.0, 144.0], "page": 0,
                                         "mapping": "Applicant 2 Full Name"},
                     "custom_text_2_1": {"type": "text", "rect": [120.0, 300.0, 360.0, 324.0], "page": 0,
                                         "mapping": "direct_text", "current_text": "Synthetic Lender Ltd"}}},
                 "filename_generator": {"blocks": [{"type": "text", "value": SECRET}]}},
                {"type": "🚦 Conditional Block", "mode": "end", "logic_gate": "AND", "conditions": []},
                {"type": "➡️ Send to Zapier/Make", "webhook_url": "https://example.invalid/" + SECRET},
            ]
        },
        "flow_display_names": {"Flow C": "Synthetic Flow"},
        "current_branch": "Flow C",
    }


class ImporterTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.dir = Path(cls.tmp.name)
        cls.pdf = minimal_form_pdf()
        cls.sibling = minimal_form_pdf("Other name")
        cls.mforge = cls.dir / "synthetic.mforge"
        cls.mforge.write_text(json.dumps(synthetic_mforge(cls.pdf, cls.sibling)), encoding="utf-8")

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def run_cli(self, *args):
        return subprocess.run([sys.executable, "-I", str(REPO / "tools" / "import_mforge.py"), *map(str, args)],
                              capture_output=True, text=True)

    def test_manifest_classification(self):
        out = self.dir / "m.json"
        r = self.run_cli("manifest", self.mforge, "--pdf", "Test Form.pdf", "--template-id", "T",
                         "--template-version", "1", "--date", "2026-01-01", "--out", out)
        self.assertEqual(r.returncode, 0, r.stderr)
        m = json.loads(out.read_text())
        self.assertEqual(m["templateSha256"], im.sha256(self.pdf))
        self.assertEqual(m["provenance"]["lenderCondition"], "Banks to Send Contains TESTBANK")
        by = {(f["pdfField"], f.get("option")): f for f in m["fields"]}
        self.assertEqual(by[("Name", None)]["source"], {"key": "Applicant 1 Full Name"})
        self.assertEqual(by[("Date 1", None)]["source"]["format"], "ddMMyy")
        self.assertEqual(by[("Box", None)]["source"], {"constant": True})
        self.assertEqual(by[("Freq", "2")]["source"]["when"],
                         {"key": "Frequency", "operator": "equals", "value": "Fortnightly"})
        self.assertEqual(by[("Freq", None)]["source"], {"constant": False})
        # 'Missing field' is known to MacroForge's own scan (native_field_info) but absent from the PDF -> error
        self.assertEqual([(e["code"], e["pdfField"]) for e in m["importErrors"]], [("UNRESOLVED_PDF_FIELD", "Missing field")])
        # 'Other name' has no target here but the identical rule resolves on the sibling: inert, not obsolete
        self.assertEqual([(x["pdfField"], x["category"], x["evidence"]) for x in m["inertLegacyMappings"]],
                         [("Other name", "sibling_duplicate", {"duplicateOf": "Sibling Form.pdf"})])
        # 'Name 2' exists nowhere: unresolved with its evidence
        self.assertEqual([(x["pdfField"], x["category"]) for x in m["unresolvedLegacyMappings"]],
                         [("Name 2", "no_target_field")])
        self.assertEqual(m["unsupported"], [])
        self.assertEqual(m["fontFallback"], "unicode")

    def test_overlays_are_converted_from_the_1_2_preview_scale(self):
        out = self.dir / "o.json"
        self.assertEqual(self.run_cli("manifest", self.mforge, "--pdf", "Test Form.pdf", "--template-id", "T",
                                      "--template-version", "1", "--out", out).returncode, 0)
        ov = {o["id"]: o for o in json.loads(out.read_text())["overlays"]}
        # top-left preview rect / 1.2 -> PDF points, bottom-left origin on an 842pt-high page
        self.assertEqual(ov["custom_text_1_1"]["rect"], [100.0, 722.0, 300.0, 742.0])
        self.assertEqual(ov["custom_text_1_1"]["source"], {"key": "Applicant 2 Full Name"})
        self.assertEqual(ov["custom_text_2_1"]["source"], {"constant": "Synthetic Lender Ltd"})
        self.assertIn("constant literal", ov["custom_text_2_1"]["reviewRequired"])

    def test_line_items_follow_line_items_configs(self):
        out = self.dir / "li.json"
        self.assertEqual(self.run_cli("manifest", self.mforge, "--pdf", "Sibling Form.pdf", "--template-id", "S",
                                      "--template-version", "1", "--out", out).returncode, 0)
        m = json.loads(out.read_text())
        by = {f["pdfField"]: f for f in m["fields"]}
        self.assertEqual(by["Date 1"]["source"],
                         {"lineItem": {"array": "Subitems", "position": 2, "path": ["values", "Loan amount"]}})
        unresolved = {x["pdfField"]: x for x in m["unresolvedLegacyMappings"]}
        self.assertEqual(unresolved["Box"]["category"], "line_item_name_conflict")
        self.assertEqual(unresolved["Box"]["evidence"]["knownSubfields"], ["Loan amount", "name"])
        self.assertEqual(unresolved["Freq"]["category"], "ambiguous_checked_state")
        self.assertFalse([w for w in m["importWarnings"] if w["code"] == "LINE_ITEM_INSTANCE_MISMATCH"])

    def test_strict_mode_fails_on_import_errors(self):
        r = self.run_cli("manifest", self.mforge, "--pdf", "Test Form.pdf", "--template-id", "T",
                         "--template-version", "1", "--strict", "--out", self.dir / "s.json")
        self.assertEqual(r.returncode, 1)

    def test_outputs_never_contain_payload_or_webhook_values(self):
        inv = self.dir / "inv.json"
        man = self.dir / "m2.json"
        r1 = self.run_cli("inventory", self.mforge, "--with-pdf", "--out", inv)
        r2 = self.run_cli("manifest", self.mforge, "--pdf", "Test Form.pdf", "--template-id", "T",
                          "--template-version", "1", "--out", man)
        for text in (inv.read_text(), man.read_text(), r1.stdout, r1.stderr, r2.stdout, r2.stderr):
            self.assertNotIn(SECRET, text)

    def test_inventory_counts(self):
        r = self.run_cli("inventory", self.mforge, "--with-pdf")
        rep = json.loads(r.stdout)
        self.assertEqual(rep["totals"]["templates"], 2)
        self.assertEqual(rep["totals"]["mappings"], 12)
        row = rep["templates"][0]
        self.assertEqual(row["lender"], "TESTBANK")
        self.assertEqual(row["mappingKinds"], {"source_key": 3, "direct_text": 1, "checked": 1, "unchecked": 1,
                                               "conditional": 1, "line_item": 1})
        self.assertEqual(row["pdfInfo"]["fieldKinds"], {"text": 2, "checkbox": 2})
        # every mapping lands in exactly one bucket (unsupported also counts overlays that were not imported)
        for t in rep["templates"]:
            unsupported_mappings = t["unsupported"] - (t["overlays"] - t["overlaysImported"])
            self.assertEqual(t["mappings"],
                             t["imported"] + t["errors"] + t["inert"] + t["unresolved"] + unsupported_mappings)

    def test_extract_is_idempotent_and_named_by_hash(self):
        out = self.dir / "tpl"
        for _ in range(2):
            self.assertEqual(self.run_cli("extract", self.mforge, "--out-dir", out).returncode, 0)
        files = sorted(p.name for p in out.glob("*.pdf"))
        self.assertEqual(files, sorted([f"{im.sha256(self.pdf)[:12]}__Test_Form.pdf",
                                        f"{im.sha256(self.sibling)[:12]}__Sibling_Form.pdf"]))

    def test_rejects_non_json_and_unknown_template(self):
        z = self.dir / "x.mforge"
        z.write_bytes(b"PK\x03\x04junk")
        self.assertEqual(self.run_cli("inventory", z).returncode, 2)
        r = self.run_cli("manifest", self.mforge, "--pdf", "Nope.pdf", "--template-id", "T",
                         "--template-version", "1", "--out", self.dir / "n.json")
        self.assertEqual(r.returncode, 2)
        self.assertIn("no embedded template", r.stderr)


if __name__ == "__main__":
    unittest.main()
