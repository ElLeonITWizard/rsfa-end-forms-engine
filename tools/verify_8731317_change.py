#!/usr/bin/env python3
"""Read-only verification of the approved 8731317 module-8 change (docs/09), after it was saved in Make.

Compares a fresh scenarios_get result (saved under .local, never committed) with the pre-change blueprint and reports:
  * scenario state (active, paused, hook, scheduling, validity) vs before;
  * every blueprint path that differs (expected: only /flow[i]/mapper/codeEditorJavascript of module 8, plus
    editor-only metadata that the Make UI rewrites on save, reported separately);
  * whether module 8's code is the old code with exactly the approved 7 lines inserted after the anchor
    (line endings normalised, because the UI editor may store CRLF);
  * that the new code still parses and passes the synthetic regression (run tests/make/module8_banks_alias_test.js
    against the code it writes to .local).
Prints no values from the blueprint. Exit 0 only when everything matches.

Usage: python3 tools/verify_8731317_change.py <pre-change read>.txt <post-change read>.txt
       python3 tools/verify_8731317_change.py --rollback <pre-change read>.txt <post-rollback read>.txt
       (rollback mode: module 8 must be back to the original code and nothing else may differ)
"""
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LOCAL = (ROOT / ".local" / "reference" / "make").resolve()
ANCHOR = "const result = mapColumns(input.column_values);"
STATE = ("isActive", "isPaused", "hookId", "scheduling", "isinvalid", "islinked", "islocked", "folderId", "teamId",
         "name", "type")
EDITOR_ONLY = ("/metadata/designer", "/metadata/scenario")  # rewritten by the Make editor on save; reported, not fatal


def load(p: Path) -> dict:
    p = p.resolve()
    if LOCAL not in p.parents:
        sys.exit("refusing: scenario reads must live under .local/reference/make/")
    t = p.read_text()
    return json.loads(t[t.find("{"):])


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
    args = sys.argv[1:]
    rollback = args[:1] == ["--rollback"]
    if rollback:
        args = args[1:]
    before, after = load(Path(args[0])), load(Path(args[1]))
    problems = []
    for k in STATE:
        if before.get(k) != after.get(k):
            problems.append(f"scenario field changed: {k}")
    print("state after:", json.dumps({k: after.get(k) for k in ("isActive", "isPaused", "hookId", "scheduling",
                                                                  "isinvalid", "lastEdit")}))
    b0, b1 = before["blueprint"], after["blueprint"]
    idx = [i for i, m in enumerate(b0["flow"]) if m["id"] == 8 and m["module"] == "code:ExecuteCode"]
    if len(idx) != 1:
        sys.exit("module 8 not found exactly once in the pre-change blueprint")
    code_path = f"/flow[{idx[0]}]/mapper/codeEditorJavascript"
    paths = list(diff(b0, b1))
    if rollback:
        old = b0["flow"][idx[0]]["mapper"]["codeEditorJavascript"].replace("\r\n", "\n")
        new = b1["flow"][idx[0]]["mapper"]["codeEditorJavascript"].replace("\r\n", "\n")
        others = [p for p in paths if p != code_path and not p.startswith(EDITOR_ONLY)]
        print("module-8 code back to the original:", old == new)
        print("other paths changed:", others if others else "none")
        problems += [f"unexpected change: {p}" for p in others]
        if old != new:
            problems.append("module 8 code differs from the original")
        print("RESULT:", "OK (rolled back)" if not problems else "PROBLEMS: " + "; ".join(problems))
        return 0 if not problems else 1
    unexpected = [p for p in paths if p != code_path and not p.startswith(EDITOR_ONLY)]
    editor = [p for p in paths if p.startswith(EDITOR_ONLY)]
    print("module-8 code changed:", code_path in paths)
    print("editor-only metadata paths changed:", len(editor))
    print("other paths changed:", unexpected if unexpected else "none")
    problems += [f"unexpected change: {p}" for p in unexpected]
    if code_path not in paths:
        problems.append("module 8 code did not change")
    old = b0["flow"][idx[0]]["mapper"]["codeEditorJavascript"].replace("\r\n", "\n")
    new = b1["flow"][idx[0]]["mapper"]["codeEditorJavascript"].replace("\r\n", "\n")
    snippet = (ROOT / "make/8731317/module8-banks-alias.snippet.js").read_text().rstrip("\n")
    expected = old.replace(ANCHOR, ANCHOR + "\n" + snippet, 1)
    exact = new == expected
    print("module-8 code == old code + the approved 7 lines after the anchor:", exact)
    if not exact:
        o, n = old.split("\n"), new.split("\n")
        print("  line counts old/new:", len(o), len(n), "(differences not printed)")
        problems.append("module 8 code is not exactly the approved change")
    out = LOCAL / "8731317-module8-LIVE-after.js"
    out.write_text(new)
    out.chmod(0o600)
    r = subprocess.run(["node", str(ROOT / "tests/make/module8_banks_alias_test.js"), str(LOCAL / "8731317-module8-code.js")],
                       capture_output=True, text=True)
    print("regression (pre-change code + snippet):", r.stdout.strip().splitlines()[-1] if r.stdout else r.stderr[-200:])
    chk = subprocess.run(["node", "-e", "new Function('input', require('fs').readFileSync(process.argv[1],'utf8'))", str(out)],
                         capture_output=True, text=True)
    print("live module-8 code parses:", chk.returncode == 0)
    if chk.returncode != 0 or r.returncode != 0:
        problems.append("live code does not parse or regression failed")
    print("RESULT:", "OK" if not problems else "PROBLEMS: " + "; ".join(problems))
    return 0 if not problems else 1


if __name__ == "__main__":
    sys.exit(main())
