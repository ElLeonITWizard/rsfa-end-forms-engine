#!/usr/bin/env python3
"""Verify a BNZ Boarder PDF generated from tests/fixtures/bnz-boarder.request.json (synthetic data).

Checks: the 7 imported field values, both customer-name overlays in the page content, that no checkbox/radio is ticked
and no signature field has a value (no automatic certification), and the SHA-256 against the local build of the same
code. Prints JSON with "ok". Usage: python3 verify_pdf.py <pdf> [expected_sha256_file]"""
import hashlib
import json
import sys
from pathlib import Path

from pypdf import PdfReader

EXPECTED_FIELDS = {
    "Text 2": "12 Example Street, Testville",
    "Text 3": "Auckland",
    "Text 4": "0999",
    "Text 5": "2",
    "Text 6": "250.00",
    "Date 1": "271026",
    "Date 2": "271026",
}
EXPECTED_OVERLAY_TEXT = ["Jane Example", "John Sample"]


def main(pdf_path: str, expected_sha_file: str | None) -> int:
    data = Path(pdf_path).read_bytes()
    reader = PdfReader(pdf_path)
    fields = reader.get_fields() or {}
    values = {k: (str(v.get("/V")) if v.get("/V") is not None else "") for k, v in fields.items()}
    field_ok = {k: values.get(k) == v for k, v in EXPECTED_FIELDS.items()}
    ticked = sorted(k for k, v in fields.items() if v.get("/FT") == "/Btn" and str(v.get("/V", "/Off")) not in ("/Off", "Off", ""))
    signed = sorted(k for k, v in fields.items() if v.get("/FT") == "/Sig" and v.get("/V") is not None)
    text = " ".join(page.extract_text() or "" for page in reader.pages)
    overlay_ok = {t: t in text for t in EXPECTED_OVERLAY_TEXT}
    sha = hashlib.sha256(data).hexdigest()
    expected_sha = None
    if expected_sha_file and Path(expected_sha_file).is_file():
        expected_sha = Path(expected_sha_file).read_text().split()[0]
    result = {
        "sha256": sha,
        "pages": len(reader.pages),
        "fields": field_ok,
        "customerNameOverlays": overlay_ok,
        "tickedButtons": ticked,
        "signatureFieldsWithValue": signed,
        "matchesLocalBuild": (sha == expected_sha) if expected_sha else "not compared",
    }
    result["ok"] = all(field_ok.values()) and all(overlay_ok.values()) and not ticked and not signed
    print(json.dumps(result, indent=2))
    return 0 if result["ok"] else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else None))
