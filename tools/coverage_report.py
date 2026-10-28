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


def validation_sections(m: dict) -> list[str]:
    """Confirmed / corrected / unresolved mappings, stakeholder decisions and risks, from the manifest and its curation."""
    cur_path = REPO / "curation" / f"{m['templateId']}@{m['templateVersion']}.json"
    cur = json.loads(cur_path.read_text(encoding="utf-8")) if cur_path.is_file() else {}
    by_id = {c["id"]: c for c in cur.get("corrections", [])}
    status = {c["id"]: c["status"] for c in m.get("corrections", [])}
    review = m.get("review", {})
    out = ["", "### Validation report", "",
           f"Status: **{review.get('status', 'not curated')}**" + (f" (blocked by: {'; '.join(review['blockedBy'])})"
                                                                     if review.get("blockedBy") else "") + "."]
    pending_fields = {(c.get("target") or c.get("rule") or {}).get("pdfField") for c in cur.get("corrections", [])
                      if status.get(c["id"]) != "applied"}
    confirmed = sorted({f["pdfField"] for f in m["fields"] if "correction" not in f} - pending_fields)
    if not cur:
        out += ["", f"**Imported mappings ({len(confirmed)} PDF fields), not individually reviewed yet** (no curation file): "
                + ", ".join(f"`{x}`" for x in confirmed) + "."]
    else:
        out += ["", f"**Confirmed mappings ({len(confirmed)} PDF fields):** imported legacy rules checked against the "
                    "printed label next to each widget and, for conditions, the live Monday labels: "
                + ", ".join(f"`{x}`" for x in confirmed) + "."]
        if pending_fields - {None}:
            out += ["", "**Fields under a pending decision (legacy rule still applied):** "
                    + ", ".join(f"`{x}`" for x in sorted(pending_fields - {None})) + "."]
    applied = [c for c in cur.get("corrections", []) if status.get(c["id"]) == "applied"]
    out += ["", f"**Corrected mappings ({len(applied)}):**" + ("" if applied else " none.")]
    out += [f"- `{c['id']}` ({c['type']}, {c['basis']}): {c['reason']} Evidence: {c['evidence']}" for c in applied]
    pending = [c for c in cur.get("corrections", []) if status.get(c["id"]) != "applied"]
    unresolved = [q for q in review.get("openQuestions", []) if q.startswith("UNRESOLVED")]
    out += ["", f"**Unresolved mappings:** {len(m.get('unmappedPdfFields', []))} PDF field(s) left blank (listed above)"
            + (", plus:" if unresolved else ".")] + [f"- {q}" for q in unresolved]
    groups = {}
    for c in pending:
        groups.setdefault(c.get("decisionRef", c["id"]), []).append(c)
    out += ["", f"**Required stakeholder decisions ({len(groups)}):**" + ("" if groups else " none for this form.")]
    for ref, cs in groups.items():
        out.append(f"- {ref}: proposed corrections {', '.join(f'`{c["id"]}`' for c in cs)} (pending, not applied). "
                   f"{cs[0]['reason']} Evidence: {cs[0]['evidence']}")
    risks = [q for q in review.get("openQuestions", []) if not q.startswith("UNRESOLVED")]
    out += ["", "**Remaining risks and open questions:**"] + ([f"- {q}" for q in risks] or ["- none recorded"])
    return out


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
                hit = lambda c: (c["value"] in live) if c["operator"] in ("equals", "includes") else any(c["value"] in x for x in live)
                # a legacy value kept on purpose next to a corrected live label (add-when-value) is not drift any more
                corrected_slots = {(f["pdfField"], f.get("option")) for f in m["fields"]
                                   if "correction" in f and (f["source"].get("when") or {}).get("key") == k}
                kept = sorted({f["source"]["when"]["value"] for f in m["fields"]
                               if (f["source"].get("when") or {}).get("key") == k and "correction" not in f
                               and (f["pdfField"], f.get("option")) in corrected_slots and not hit(f["source"]["when"])})
                stale = sorted({c["value"] for c in conds if not hit(c)} - set(kept))
                if kept:
                    parts_kept = "legacy value kept next to the corrected live label: " + ", ".join(f"`{x}`" for x in kept)
                cov = lambda x: any((x == c["value"]) if c["operator"] in ("equals", "includes") else (c["value"] in x) for c in conds)
                norule = [x for x in live if not cov(x)]
                multi = col.get("type") == "dropdown" and not (col.get("settings") or {}).get("limit_select")
                parts = [f"{len(conds)} rule(s), {len(live)} live label(s){' (multi-select)' if multi else ''}"]
                if kept:
                    parts.append(parts_kept)
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
    lines += validation_sections(m)
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
