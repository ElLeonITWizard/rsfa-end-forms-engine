#!/usr/bin/env python3
"""Applies (or rolls back) the APPROVED 8731317 module-8 alias through the Make API, without retyping the blueprint.

Approved by the user on 2026-10-28, limited to /flow[<module 8>]/mapper/codeEditorJavascript (docs/09). The script:
  1. GET the scenario and its blueprint; refuse unless the state and the blueprint's canonical SHA-256 equal the
     verified pre-change values (lastEdit 2026-07-16T22:05:49.471Z, digest ab76a676…), i.e. nothing changed since
     the iteration-9/10 verification;
  2. save the complete pre-change response under .local/reference/make/ (mode 600; never committed);
  3. insert the 7 lines of make/8731317/module8-banks-alias.snippet.js after the anchor and prove that exactly one
     path differs and the result has the approved digest (1369b087…);
  4. PATCH /api/v2/scenarios/8731317 with {"blueprint": <that JSON as a string>} and nothing else (no scheduling,
     name, folder or activation fields);
  5. GET again and verify with tools/verify_8731317_change.py (only module 8's code changed, exact lines, state
     unchanged). Prints PASS or FAIL. It never rolls back on its own: on FAIL run --rollback.
It never prints blueprint values (webhook URLs, sample data); only digests, paths, states and HTTP codes.

Authentication: either the environment's Network Secret for eu2.make.com (the proxy injects the Authorization
header; this script never sees the token), or MAKE_API_TOKEN (scopes scenarios:read + scenarios:write; never logged).
MAKE_API_BASE defaults to https://eu2.make.com, the organisation's zone. An explicit User-Agent is sent because
Cloudflare in front of Make refuses Python's default one (error 1010).
Usage:  python3 tools/apply_8731317_change.py [--dry-run]     # dry run: steps 1-3 only, no PATCH
        python3 tools/apply_8731317_change.py --rollback      # restore the saved pre-change blueprint, then verify
"""
import copy
import hashlib
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LOCAL = ROOT / ".local" / "reference" / "make"
SCENARIO = 8731317
ANCHOR = "const result = mapColumns(input.column_values);"
EXPECTED = {"lastEdit": "2026-07-16T22:05:49.471Z", "isActive": True, "isPaused": False, "hookId": 3905270,
            "scheduling": {"type": "immediately", "maximum_runs_per_minute": 100}}
# Digests of the blueprint as the Make API returns it ({name, flow, metadata}). The MCP scenarios_get result folds
# "interface" and "scheduling" into the blueprint; without those two keys it is identical (verified 2026-10-28), so
# these correspond to the MCP-format digests ab76a676… (pre) and 1369b087… (approved proposal).
PRE_DIGEST = "5861cc0ee3b987ebb441cfefedd6690c6b35aea88258b73bf3146392dce0d53a"
POST_DIGEST = "e700f1a2b4c13e70a24694285ca177639da8adb38257a480bfa93abc7fea67fa"
PRE_FILE = LOCAL / "8731317-PRE-CHANGE-api.json"


def digest(obj) -> str:
    return hashlib.sha256(json.dumps(obj, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def api(method: str, path: str, body=None):
    base = os.environ.get("MAKE_API_BASE", "https://eu2.make.com").rstrip("/")
    if not (base.startswith("https://") or base.startswith("http://127.0.0.1")):
        sys.exit("FAIL: MAKE_API_BASE must be https (loopback http only for tests)")
    headers = {"Content-Type": "application/json", "User-Agent": "rsfa-end-forms-8731317-fix/1.0"}
    token = os.environ.get("MAKE_API_TOKEN", "")
    if token:  # otherwise the network secret injects Authorization at the proxy
        headers["Authorization"] = f"Token {token}"
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(f"{base}/api/v2{path}", data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        return e.code, {}
    except urllib.error.URLError as e:
        sys.exit(f"FAIL: Make API not reachable ({e.reason.__class__.__name__}); check the network allowlist")


def read_live() -> dict:
    """Scenario object with its blueprint, shaped like the MCP scenarios_get result the verifier expects."""
    s1, sc = api("GET", f"/scenarios/{SCENARIO}")
    s2, bp = api("GET", f"/scenarios/{SCENARIO}/blueprint")
    if s1 != 200 or s2 != 200:
        sys.exit(f"FAIL: reading the scenario returned HTTP {s1}/{s2}; nothing was changed")
    scenario = sc.get("scenario", sc)
    blueprint = (bp.get("response") or {}).get("blueprint")
    if scenario.get("id") != SCENARIO or not isinstance(blueprint, dict):
        sys.exit("FAIL: unexpected API response shape; nothing was changed")
    s3, iface = api("GET", f"/scenarios/{SCENARIO}/interface")
    if s3 != 200:
        sys.exit(f"FAIL: reading the scenario interface returned HTTP {s3}; nothing was changed")
    out = dict(scenario)
    out["blueprint"] = blueprint
    out["_interface"] = iface.get("interface", iface)  # compared before/after; never sent
    out["_blueprintScheduling"] = (bp.get("response") or {}).get("scheduling")
    return out


def save(name: str, obj) -> Path:
    LOCAL.mkdir(parents=True, exist_ok=True)
    p = LOCAL / name
    p.write_text(json.dumps(obj, ensure_ascii=False))
    os.chmod(p, 0o600)
    return p


def state(s: dict) -> dict:
    return {k: s.get(k) for k in EXPECTED}


def verify(pre: Path, post: Path, rollback: bool) -> bool:
    cmd = [sys.executable, str(ROOT / "tools/verify_8731317_change.py")] + (["--rollback"] if rollback else []) \
        + [str(pre), str(post)]
    r = subprocess.run(cmd, capture_output=True, text=True)
    print(r.stdout.rstrip())
    return r.returncode == 0


def patch(blueprint: dict) -> int:
    # Make expects the blueprint as a JSON string; no other field is sent, so scheduling and activation stay as they are.
    status, _ = api("PATCH", f"/scenarios/{SCENARIO}", {"blueprint": json.dumps(blueprint, ensure_ascii=False)})
    return status


def apply(dry_run: bool) -> int:
    live = read_live()
    print("live state:", json.dumps(state(live)))
    if state(live) != EXPECTED:
        sys.exit("FAIL: the live scenario state differs from the verified one; nothing was changed")
    if digest(live["blueprint"]) != PRE_DIGEST:
        sys.exit("FAIL: the live blueprint differs from the verified backup; nothing was changed")
    pre = save(PRE_FILE.name, live)
    print("pre-change blueprint verified (digest matches) and saved:", pre.relative_to(ROOT))
    proposed = copy.deepcopy(live["blueprint"])
    m8 = [m for m in proposed["flow"] if m.get("id") == 8 and m.get("module") == "code:ExecuteCode"]
    snippet = (ROOT / "make/8731317/module8-banks-alias.snippet.js").read_text().rstrip("\n")
    if len(m8) != 1 or m8[0]["mapper"]["codeEditorJavascript"].count(ANCHOR) != 1:
        sys.exit("FAIL: module 8 or its anchor not found exactly once; nothing was changed")
    m8[0]["mapper"]["codeEditorJavascript"] = m8[0]["mapper"]["codeEditorJavascript"].replace(
        ANCHOR, ANCHOR + "\n" + snippet, 1)
    if digest(proposed) != POST_DIGEST:
        sys.exit("FAIL: the patched blueprint is not the approved one; nothing was changed")
    print("patched blueprint equals the approved proposal (digest matches; one path, 7 lines)")
    if dry_run:
        print("DRY RUN: no PATCH sent")
        return 0
    status = patch(proposed)
    print("PATCH /scenarios/8731317 ->", status)
    if status != 200:
        print("FAIL: the update was not accepted; re-read and verify before anything else")
        return 1
    time.sleep(2)
    after = read_live()
    post = save("8731317-POST-CHANGE-api.json", after)
    same_io = after["_interface"] == live["_interface"] and after["_blueprintScheduling"] == live["_blueprintScheduling"]
    print("scenario interface and blueprint scheduling unchanged:", same_io)
    ok = verify(pre, post, rollback=False) and same_io
    if ok:
        b = after["blueprint"]
        m8 = [m for m in b["flow"] if m.get("id") == 8][0]["mapper"]["codeEditorJavascript"]
        print("live blueprint digest equals the approved one:", digest(b) == POST_DIGEST)
        print("module 8 restores 'Banks to Send' from column dropdown_mkzvfgeg and keeps 'Selected Banks':",
              'const BANKS_COLUMN_ID = "dropdown_mkzvfgeg";' in m8 and "mapColumns(input.column_values)" in m8)
    print("PASS" if ok else "FAIL (rollback: python3 tools/apply_8731317_change.py --rollback)")
    return 0 if ok else 1


def rollback() -> int:
    if not PRE_FILE.exists():
        sys.exit("FAIL: no saved pre-change blueprint in this environment; restore from Make's version history")
    pre = json.loads(PRE_FILE.read_text())
    if digest(pre["blueprint"]) != PRE_DIGEST:
        sys.exit("FAIL: the saved pre-change blueprint is not the verified one")
    status = patch(pre["blueprint"])
    print("PATCH (rollback) ->", status)
    time.sleep(2)
    post = save("8731317-POST-ROLLBACK-api.json", read_live())
    ok = verify(PRE_FILE, post, rollback=True)
    print("PASS (rolled back)" if ok else "FAIL")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(rollback() if "--rollback" in sys.argv else apply("--dry-run" in sys.argv))
