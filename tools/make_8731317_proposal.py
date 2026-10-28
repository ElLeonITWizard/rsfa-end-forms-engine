#!/usr/bin/env python3
"""Builds the proposed and rollback blueprints for the 8731317 module-8 alias (docs/09) from a fresh scenario read.

Input: a scenarios_get result saved under .local (it contains webhook URLs and test payloads: never commit it).
Writes .local/reference/make/8731317-{PROPOSED,ROLLBACK}-blueprint.json and the proposed module-8 code, and prints
SHA-256 digests and the structural diff (must be exactly one path). Prints no values from the blueprint.
Usage: python3 tools/make_8731317_proposal.py .local/reference/make/<scenarios_get result>.txt
"""
import copy
import hashlib
import json
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / ".local" / "reference" / "make"
ANCHOR = "const result = mapColumns(input.column_values);"
EXPECTED_LAST_EDIT = "2026-07-16T22:05:49.471Z"


def digest(obj) -> str:
    return hashlib.sha256(json.dumps(obj, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def diff(a, b, p=""):
    if type(a) is not type(b):
        yield p
    elif isinstance(a, dict):
        for k in sorted(set(a) | set(b)):
            yield from diff(a.get(k), b.get(k), f"{p}/{k}")
    elif isinstance(a, list):
        if len(a) != len(b):
            yield p
        else:
            for i, (x, y) in enumerate(zip(a, b)):
                yield from diff(x, y, f"{p}[{i}]")
    elif a != b:
        yield p


def main() -> int:
    src = Path(sys.argv[1]).resolve()
    if OUT.resolve() not in src.parents:
        sys.exit("refusing: the scenario read must live under .local/reference/make/")
    text = src.read_text()
    scenario = json.loads(text[text.find("{"):])
    if scenario.get("id") != 8731317:
        sys.exit("not scenario 8731317")
    state = {k: scenario.get(k) for k in ("isActive", "isPaused", "hookId", "scheduling", "lastEdit", "isinvalid")}
    print("scenario state:", json.dumps(state))
    if scenario.get("lastEdit") != EXPECTED_LAST_EDIT:
        sys.exit("STOP: lastEdit differs from the backup; reconcile before preparing a change")
    bp = scenario["blueprint"]
    snippet = (ROOT / "make/8731317/module8-banks-alias.snippet.js").read_text().rstrip("\n")
    proposed = copy.deepcopy(bp)
    m8 = [m for m in proposed["flow"] if m["id"] == 8 and m["module"] == "code:ExecuteCode"]
    if len(m8) != 1:
        sys.exit("STOP: module 8 (code:ExecuteCode) not found exactly once")
    code = m8[0]["mapper"]["codeEditorJavascript"]
    if code.count(ANCHOR) != 1 or "LEGACY_BANKS_KEY" in code:
        sys.exit("STOP: insertion anchor not found exactly once, or the alias is already present")
    m8[0]["mapper"]["codeEditorJavascript"] = code.replace(ANCHOR, ANCHOR + "\n" + snippet, 1)
    paths = list(diff(bp, proposed))
    print("paths that differ:", paths)
    if len(paths) != 1 or not paths[0].endswith("/mapper/codeEditorJavascript"):
        sys.exit("STOP: the change is not confined to module 8's code")
    for name, obj in (("PROPOSED", proposed), ("ROLLBACK", bp)):
        f = OUT / f"8731317-{name}-blueprint.json"
        f.write_text(json.dumps(obj, ensure_ascii=False))
        os.chmod(f, 0o600)
        print(f"{name.lower()} blueprint sha256:", digest(obj))
    f = OUT / "8731317-module8-PROPOSED.js"
    f.write_text(m8[0]["mapper"]["codeEditorJavascript"])
    os.chmod(f, 0o600)
    print("module 8 current sha256: ", hashlib.sha256(code.encode()).hexdigest())
    print("module 8 proposed sha256:", hashlib.sha256(m8[0]["mapper"]["codeEditorJavascript"].encode()).hexdigest())
    print("lines added:", snippet.count("\n") + 1)
    return 0


if __name__ == "__main__":
    sys.exit(main())
