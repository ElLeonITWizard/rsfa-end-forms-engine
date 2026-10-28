#!/usr/bin/env python3
"""Sends a contract request to the relay the way the planned Make scenario will (local proof of concept only).

Signature = lowercase hex HMAC-SHA256(secret, timestamp + "." + body), the value Make's
sha256(<timestamp>.<body>; "hex"; <secret>) is expected to produce (to verify in Make, docs/07 "HMAC test vector").
Prints the HTTP status and a document summary: codes, file names, sizes. Never prints PDFs or data values.

Usage: RELAY_HMAC_SECRET=... python3 tools/make_like_client.py <relay-base-url> <request.json> [--save-dir DIR]
"""
import base64
import hashlib
import hmac
import json
import os
import sys
import time
import urllib.request
from pathlib import Path


def main() -> int:
    base, req_path = sys.argv[1], Path(sys.argv[2])
    save = Path(sys.argv[sys.argv.index("--save-dir") + 1]) if "--save-dir" in sys.argv else None
    secret = os.environ["RELAY_HMAC_SECRET"].encode()
    body = req_path.read_bytes()
    ts = str(int(time.time()))
    sig = hmac.new(secret, (ts + ".").encode() + body, hashlib.sha256).hexdigest()
    req = urllib.request.Request(base.rstrip("/") + "/v1/masterboard/documents", data=body, method="POST",
                                 headers={"Content-Type": "application/json", "X-RSFA-Timestamp": ts,
                                          "X-RSFA-Signature": "v1=" + sig})
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        res = opener.open(req, timeout=120)
        status, payload, headers = res.status, res.read(), res.headers
    except urllib.error.HTTPError as e:
        status, payload, headers = e.code, e.read(), e.headers
    out = json.loads(payload or b"{}")
    summary = {"http": status, "relay": headers.get("X-Relay"), "correlationId": headers.get("X-Correlation-Id"),
               "status": out.get("status"), "code": out.get("code"), "retryable": out.get("retryable"),
               "documents": [{k: d.get(k) for k in ("templateId", "status", "code", "fileName", "sizeBytes", "sha256")}
                             for d in out.get("documents", [])],
               "notMigrated": len(out.get("notMigrated", []))}
    print(json.dumps(summary, indent=2))
    if save:
        save.mkdir(parents=True, exist_ok=True)
        for d in out.get("documents", []):
            if d.get("pdfBase64"):
                (save / d["fileName"]).write_bytes(base64.b64decode(d["pdfBase64"]))
    return 0 if status == 200 else 1


if __name__ == "__main__":
    sys.exit(main())
