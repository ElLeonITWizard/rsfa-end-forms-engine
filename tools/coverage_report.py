#!/usr/bin/env python3
"""Per-template mapping coverage and unresolved-field report (Markdown), from the committed manifests.

Reads only manifests (no client data) and, optionally, two local schema files that never contain item values:
  --board     a Monday board columns export (get_board_info, columns only): checks keys and condition labels live
  --contract  the Make 8731317 result-key contract TSV: checks that each key is produced by the trigger scenario

Usage:
  python3 tools/coverage_report.py --board .local/reference/masterboard-columns-2026-10-27.json \
      --contract .local/reference/8731317_result_key_contract.tsv --date 2026-10-27 --out docs/generated/template-coverage.md
"""
from __future__ import annotations

import argparse
import collections
import csv
import json
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
DERIVED = ("Street", "Suburb", "City", "Province", "Postcode", "Country", "Day", "Month", "Year")


def norm(k: str) -> str:
    return k.replace("/", "_").replace(".", "_")


def load_board(path: Path | None):
    if not path:
        return None
    cols = json.loads(path.read_text(encoding="utf-8"))["board"]["columns"]
    by = collections.defaultdict(list)
    for c in cols:
        by[norm(c["title"])].append(c)
    return by


def labels(col) -> list[str] | None:
    raw = (col.get("settings") or {}).get("labels")
    if isinstance(raw, list):
        return [x.get("label") or x.get("name") for x in raw if not x.get("is_deactivated")]
    if isinstance(raw, dict):
        return list(raw.values())
    return None


def board_column(board, key):
    """(column, how) for a data key: exact title, or an address/date part derived by Make from a column."""
    if board is None:
        return None, "not checked"
    hit = board.get(norm(key))
    if hit:
        return hit[0], "column" if len(hit) == 1 else f"DUPLICATE title ({len(hit)} columns)"
    for sfx in DERIVED:
        if norm(key).endswith(" " + sfx) and board.get(norm(key)[: -len(sfx) - 1]):
            return board[norm(key)[: -len(sfx) - 1]][0], f"derived ({sfx}) by Make"
    return None, "NOT on the live board"


def rule_keys(m):
    keys = []
    for f in m["fields"]:
        s = f.get("source", {})
        for k in (s.get("key"), (s.get("when") or {}).get("key"), (s.get("lineItem") or {}).get("array")):
            if k and k not in keys:
                keys.append(k)
    for o in m.get("overlays", []):
        k = o.get("source", {}).get("key")
        if k and k not in keys:
            keys.append(k)
    for r in m.get("validation", {}).get("requires", []):
        for k in (r.get("ifPresent"), (r.get("require") or {}).get("key")):
            if k and k not in keys:
                keys.append(k)
    return keys


def source_type(f) -> str:
    s = f.get("source", {})
    if "constant" in s:
        return "constant tick" if s["constant"] is True else ("constant untick" if s["constant"] is False else "constant text")
    return next((k for k in ("key", "when", "generated", "lineItem") if k in s), "?")


def report(m: dict, path: Path, board, contract) -> tuple[dict, str]:
    pdf_fields = m["pdf"]["fields"]
    kinds = collections.Counter(v["kind"] for v in pdf_fields.values())
    mapped = sorted({f["pdfField"] for f in m["fields"]})
    unmapped = m.get("unmappedPdfFields", [])
    src = collections.Counter(source_type(f) for f in m["fields"])
    review = m.get("review", {})
    val = m.get("validation", {})
    lines = [f"## {m['templateId']}@{m['templateVersion']}", "",
             f"- Manifest: `{path.relative_to(REPO).as_posix() if path.is_relative_to(REPO) else path.name}`; legacy PDF `{m['provenance'].get('legacyPdfName')}`; "
             f"SHA-256 `{m['templateSha256'][:12]}`; {m['pdf']['pages']} page(s); encryption policy `{m['encryption']}`.",
             f"- Review status: **{review.get('status', 'not curated')}**"
             + (f" ({review['meaning']})" if review.get("meaning") else "") + ".",
             f"- PDF fields: {len(pdf_fields)} ({', '.join(f'{k} {v}' for k, v in sorted(kinds.items()))}). "
             f"Mapped: **{len(mapped)}**; unmapped: {len(unmapped)} (left blank, as in the legacy configuration).",
             f"- Rules: {len(m['fields'])} ({', '.join(f'{k} {v}' for k, v in sorted(src.items()))}); "
             f"overlays: {len(m.get('overlays', []))}.",
             f"- Legacy mappings not imported: unresolved {len(m.get('unresolvedLegacyMappings', []))}, "
             f"unsupported {len(m.get('unsupported', []))}, inert {len(m.get('inertLegacyMappings', []))}, "
             f"import errors {len(m.get('importErrors', []))}."]
    if src.get("constant tick"):
        lines.append(f"- Constant ticks: {src['constant tick']} — **not applied** without a recorded approval (D-016).")
    if val.get("closedOptions") or val.get("requires"):
        lines.append(f"- Engine validation (curated): {len(val.get('closedOptions', []))} closed option key(s), "
                     f"{len(val.get('requires', []))} precondition(s): "
                     + "; ".join(f"`{r['id']}` ({r['reason']})" for r in val.get("requires", [])) + ".")
    lines += ["", "| Data key | Used by | Live board | Make contract | Condition labels |", "|---|---|---|---|---|"]
    issues = collections.Counter()
    for k in rule_keys(m):
        users = sorted({f["pdfField"] for f in m["fields"]
                        if k in (f["source"].get("key"), (f["source"].get("when") or {}).get("key"))}
                       | {"overlay" for o in m.get("overlays", []) if o["source"].get("key") == k}
                       | {"validation " + r["id"] for r in m.get("validation", {}).get("requires", [])
                          if k in (r.get("ifPresent"), (r.get("require") or {}).get("key"))})
        col, how = board_column(board, k)
        if board is not None and col is None:
            issues["key not on board"] += 1
        in_contract = "not checked" if contract is None else ("yes" if norm(k) in contract else "**no**")
        conds = [f["source"]["when"] for f in m["fields"] if (f["source"].get("when") or {}).get("key") == k]
        cell = ""
        if conds:
            live = labels(col) if col else None
            if live is None:
                cell = f"{len(conds)} rule(s); live labels not available"
            else:
                hit = lambda c: (c["value"] in live) if c["operator"] == "equals" else any(c["value"] in x for x in live)
                stale = sorted({c["value"] for c in conds if not hit(c)})
                cov = lambda x: any((x == c["value"]) if c["operator"] == "equals" else (c["value"] in x) for c in conds)
                norule = [x for x in live if not cov(x)]
                multi = not (col.get("settings") or {}).get("limit_select")
                parts = [f"{len(conds)} rule(s), {len(live)} live label(s){' (multi-select)' if multi else ''}"]
                if stale:
                    parts.append("rule value not a live label: " + ", ".join(f"`{x}`" for x in stale))
                    issues["stale rule label"] += len(stale)
                if norule:
                    parts.append("live label without rule: " + ", ".join(f"`{x}`" for x in norule))
                    issues["live label without rule"] += len(norule)
                cell = "; ".join(parts)
        lines.append(f"| `{k}` | {', '.join(users)[:80]} | {how}{' (' + col['type'] + ')' if col else ''} | {in_contract} | {cell} |")
    if unmapped:
        lines += ["", "Unmapped PDF fields: " + ", ".join(f"`{n}` ({pdf_fields[n]['kind']})" for n in unmapped) + "."]
    if review.get("openQuestions"):
        lines += ["", "Open questions for RSFA:"] + [f"- {q}" for q in review["openQuestions"]]
    summary = {"key": f"{m['templateId']}@{m['templateVersion']}", "status": review.get("status", "not curated"),
               "pdfFields": len(pdf_fields), "mapped": len(mapped), "unmapped": len(unmapped), "rules": len(m["fields"]),
               "overlays": len(m.get("overlays", [])),
               "unresolved": len(m.get("unresolvedLegacyMappings", [])) + len(m.get("unsupported", [])),
               "openQuestions": len(review.get("openQuestions", [])), **issues}
    return summary, "\n".join(lines) + "\n"


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    p.add_argument("--mappings", default=str(REPO / "mappings"))
    p.add_argument("--board", type=Path)
    p.add_argument("--contract", type=Path)
    p.add_argument("--date", required=True, help="report date (YYYY-MM-DD), recorded in the header")
    p.add_argument("--out", required=True)
    a = p.parse_args(argv)
    board = load_board(a.board)
    contract = None
    if a.contract:
        contract = {norm(r["result_key"]) for r in csv.DictReader(a.contract.open(encoding="utf-8"), delimiter="\t")}
    sections, rows = [], []
    for path in sorted(Path(a.mappings).rglob("*.json")):
        m = json.loads(path.read_text(encoding="utf-8"))
        s, text = report(m, path, board, contract)
        rows.append(s)
        sections.append(text)
    head = [f"# Template coverage report (generated {a.date})", "",
            "Generated by `tools/coverage_report.py` from the committed manifests"
            + (f", the live Monday board column schema (`{a.board.name}`, columns only, no items)" if a.board else "")
            + (f" and the Make `8731317` result-key contract (`{a.contract.name}`)" if a.contract else "")
            + ". It contains key names, PDF field names and Monday option labels only, never data values. "
            "Do not edit by hand; re-run the tool.", "",
            "| Template | Status | PDF fields | Mapped | Unmapped | Rules | Overlays | Unresolved + unsupported | "
            "Stale rule labels | Live labels without rule | Keys not on board | Open questions |",
            "|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for r in rows:
        head.append(f"| `{r['key']}` | {r['status']} | {r['pdfFields']} | {r['mapped']} | {r['unmapped']} | {r['rules']} | "
                    f"{r['overlays']} | {r['unresolved']} | {r.get('stale rule label', 0)} | "
                    f"{r.get('live label without rule', 0)} | {r.get('key not on board', 0)} | {r['openQuestions']} |")
    out = Path(a.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("\n".join(head) + "\n\n" + "\n".join(sections), encoding="utf-8")
    print(f"report written: {out} ({len(rows)} templates)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
