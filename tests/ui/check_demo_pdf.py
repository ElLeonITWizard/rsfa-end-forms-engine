#!/usr/bin/env python3
"""Check a PDF downloaded from the demo UI (synthetic data only).

Prints JSON with "ok": every --expect text is in a field value or in the page text (overlays), no checkbox/radio is
ticked, no signature field has a value, and the SHA-256 equals --sha (the fingerprint the page displayed).
Usage: check_demo_pdf.py <pdf> --expect TEXT [--expect TEXT ...] [--sha HEX]"""
import argparse
import hashlib
import json
import sys
from pathlib import Path

from pypdf import PdfReader


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("pdf")
    ap.add_argument("--expect", action="append", default=[])
    ap.add_argument("--sha")
    ap.add_argument("--allow-ticks", action="store_true",
                    help="forms whose rules tick boxes (radio/checkbox questions); unruled ticks are covered by Java tests")
    a = ap.parse_args()
    reader = PdfReader(a.pdf)
    if reader.is_encrypted:  # bank owner-password templates (ASB, GHL): empty user password
        reader.decrypt("")
    fields = reader.get_fields() or {}
    values = [str(v.get("/V")) for v in fields.values() if v.get("/V") is not None]
    text = " ".join(page.extract_text() or "" for page in reader.pages)
    found = {t: any(t == v for v in values) or t in text for t in a.expect}
    ticked = sorted(k for k, v in fields.items() if v.get("/FT") == "/Btn" and str(v.get("/V", "/Off")) not in ("/Off", "Off", ""))
    signed = sorted(k for k, v in fields.items() if v.get("/FT") == "/Sig" and v.get("/V") is not None)
    sha = hashlib.sha256(Path(a.pdf).read_bytes()).hexdigest()
    result = {"found": found, "tickedButtons": ticked, "signatureFieldsWithValue": signed,
              "sha256MatchesPage": (sha == a.sha) if a.sha else "not compared"}
    result["ok"] = (all(found.values()) and (a.allow_ticks or not ticked) and not signed
                    and result["sha256MatchesPage"] is not False)
    print(json.dumps(result, ensure_ascii=True))
    return 0 if result["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
