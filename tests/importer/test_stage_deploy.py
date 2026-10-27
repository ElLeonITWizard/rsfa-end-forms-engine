"""Tests for tools/stage_deploy.py (deployment context staging). Synthetic PDFs only.
Run: python3 -m unittest discover -s tests/importer -v"""
import datetime as dt
import hashlib
import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "tools"))
sys.path.insert(0, str(Path(__file__).resolve().parent))
import stage_deploy as sd  # noqa: E402
from test_import_mforge import minimal_form_pdf  # noqa: E402

TODAY = dt.date(2026, 10, 27)
APPROVAL = {"approvedBy": "Test Approver (fictional)", "approvedOn": "2026-10-27", "reference": "TEST-APPROVAL-1"}


def filled_pdf() -> bytes:
    """The minimal form with a value in its text field (what a filled client PDF looks like)."""
    return minimal_form_pdf().replace(b"/T (Name)", b"/T (Name) /V (Jane)", 1)


class StageDeployTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        (REPO / ".local").mkdir(exist_ok=True)
        self.out = Path(tempfile.mkdtemp(dir=REPO / ".local", prefix="test-stage-")) / "context"
        self.templates = self.tmp / "templates"
        self.mappings = self.tmp / "mappings" / "test"
        self.templates.mkdir(parents=True)
        self.mappings.mkdir(parents=True)

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)
        shutil.rmtree(self.out.parent, ignore_errors=True)

    def setup_template(self, pdf: bytes, approval=None, allow_sha=None):
        sha = hashlib.sha256(pdf).hexdigest()
        name = f"{sha[:12]}__Test_Form.pdf"
        (self.templates / name).write_bytes(pdf)
        (self.mappings / "T@1.json").write_text(json.dumps(
            {"schemaVersion": 1, "templateId": "T", "templateVersion": "1", "templateFile": name, "templateSha256": sha,
             "fields": []}))
        allow = self.tmp / "allow.json"
        allow.write_text(json.dumps({"templates": [{"templateId": "T", "templateVersion": "1",
                                                    "sha256": allow_sha or sha, "approval": approval}]}))
        return allow

    def stage(self, allow, local_test=False, out=None):
        return sd.stage(allow, self.mappings.parent, self.templates, out or self.out, local_test, TODAY)

    def test_approved_blank_template_is_staged_with_exact_file_set(self):
        self.stage(self.setup_template(minimal_form_pdf(), APPROVAL))
        files = sorted(p.relative_to(self.out).as_posix() for p in self.out.rglob("*") if p.is_file())
        self.assertIn("mappings/test/T@1.json", files)
        self.assertEqual([f for f in files if f.startswith("poc-templates/")],
                         [f"poc-templates/{hashlib.sha256(minimal_form_pdf()).hexdigest()[:12]}__Test_Form.pdf"])
        self.assertFalse((self.out / "UNAPPROVED_LOCAL_TEST_ONLY").exists())
        self.assertTrue(all(f.startswith(("engine/", "mappings/", "poc-templates/")) or f in
                            (".gcloudignore", "cloudbuild.yaml", "STAGED_FILES.txt") for f in files), files)

    def test_missing_or_future_approval_is_refused(self):
        for bad in (None, {"approvedBy": "", "approvedOn": "2026-10-27", "reference": "x"},
                    {**APPROVAL, "approvedOn": "2026-12-01"}):
            with self.assertRaises(sd.StageError):
                self.stage(self.setup_template(minimal_form_pdf(), bad))
            self.assertFalse(self.out.exists(), "no partial context may remain")

    def test_local_test_mode_marks_the_context_non_deployable(self):
        self.stage(self.setup_template(minimal_form_pdf(), None), local_test=True)
        self.assertTrue((self.out / "UNAPPROVED_LOCAL_TEST_ONLY").exists())

    def test_sha_mismatch_and_filled_pdfs_are_refused(self):
        with self.assertRaisesRegex(sd.StageError, "SHA-256"):
            self.stage(self.setup_template(minimal_form_pdf(), APPROVAL, allow_sha="0" * 64))
        with self.assertRaisesRegex(sd.StageError, "not a blank template"):
            self.stage(self.setup_template(filled_pdf(), APPROVAL))
        self.assertFalse(self.out.exists())

    def test_output_outside_local_is_refused(self):
        with self.assertRaisesRegex(sd.StageError, "inside"):
            self.stage(self.setup_template(minimal_form_pdf(), APPROVAL), out=self.tmp / "ctx")


if __name__ == "__main__":
    unittest.main()
