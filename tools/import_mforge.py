#!/usr/bin/env python3
"""Import legacy MacroForge `.mforge` exports into engine mapping manifests.

Privacy contract (see CLAUDE.md):
  * Never prints or writes payload values. `sample_payload`, `variable_map` values,
    webhook URLs, export folders and filename text blocks are never read into outputs.
  * Mapping *configuration* (PDF field names, Monday column titles used as source keys,
    condition option labels such as "Yes"/"weekly") is not client data and may be emitted.
  * Constant `direct_text` literals ARE emitted in manifests because the engine needs them;
    they are flagged reviewRequired so a human confirms none is personal data before commit.

Subcommands:
  inventory  privacy-safe counts per flow / lender group / mapping kind (+ optional markdown)
  extract    write embedded PDFs to a local, git-ignored directory with SHA-256 identity
  manifest   build an engine mapping manifest for one embedded template, cross-checked
             against the PDF's real AcroForm fields

Requires: Python 3.11+, pypdf (see tools/requirements.txt).
"""
from __future__ import annotations

import argparse
import base64
import datetime as dt
import hashlib
import io
import json
import re
import sys
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from pathlib import Path

IMPORTER_VERSION = "0.2.0"
BATCH_FILLER = "PDF Batch Filler"
SINGLE_FILLER = "PDF Filler"
CONDITIONAL = "Conditional Block"
SENTINELS = {"direct_text", "conditional", "checked", "unchecked"}
LINE_ITEM = re.compile(r"^LineItems\.(?P<field>.+)_(?P<n>\d+)$")
MAX_INPUT_BYTES = 200 * 1024 * 1024
MANIFEST_SCHEMA_VERSION = 1
# MacroForge stores free-positioned overlay rects in its preview coordinates: top-left origin,
# PDF points x 1.2. Evidence (2026-10-27): 4 overlays with printed labels next to them fit
# s = 1.194 +/- 0.013, and all 14 overlays land in their intended blanks at s = 1.2 (docs/03).
OVERLAY_PREVIEW_SCALE = 1.2


class ImportError_(Exception):
    """User-facing import failure (bad input, ambiguous selection)."""


# --------------------------------------------------------------------------- loading


def load_mforge(path: Path) -> dict:
    if not path.is_file():
        raise ImportError_(f"input not found: {path}")
    size = path.stat().st_size
    if size > MAX_INPUT_BYTES:
        raise ImportError_(f"input is {size} bytes; refusing files over {MAX_INPUT_BYTES}")
    raw = path.read_bytes()
    if raw[:2] == b"PK":
        raise ImportError_("input looks like a ZIP archive; only JSON .mforge exports are supported")
    try:
        doc = json.loads(raw.decode("utf-8-sig"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ImportError_(f"input is not valid UTF-8 JSON: {exc.__class__.__name__}") from None
    if not isinstance(doc, dict) or not isinstance(doc.get("branches"), dict):
        raise ImportError_("unexpected .mforge structure: missing top-level 'branches' object")
    return doc


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def safe_filename(name: str) -> str:
    stem = re.sub(r"[^A-Za-z0-9._-]+", "_", name).strip("._") or "template"
    return stem[:120]


def node_kind(node: dict) -> str:
    t = str(node.get("type", ""))
    for k in (BATCH_FILLER, SINGLE_FILLER, CONDITIONAL):
        if t.endswith(k):
            return k
    return re.sub(r"^[^A-Za-z]+", "", t).strip() or "unknown"


# --------------------------------------------------------------------------- template enumeration


@dataclass
class TemplateRef:
    flow: str
    flow_display: str
    node_index: int
    node_kind: str
    pdf_name: str
    lender_condition: str | None
    pdf_bytes: bytes
    mappings: dict
    conditions: dict
    custom_fields: dict
    line_items: dict
    native_fields: set  # field names MacroForge itself detected in this PDF (native_field_info_by_file)
    line_item_cfg: dict = field(default_factory=dict)  # LineItems subfield name -> {"array", "path"}

    @property
    def sha256(self) -> str:
        return sha256(self.pdf_bytes)


def _describe_condition(cond_node: dict | None) -> str | None:
    if not cond_node:
        return None
    parts = [f"{c.get('field')} {c.get('operator')} {c.get('value')}" for c in cond_node.get("conditions", [])]
    gate = f" {cond_node.get('logic_gate', '')} ".join(parts)
    return gate or None


def line_item_config(nodes: list) -> dict:
    """LineItems subfield -> source location, from the flow trigger's line_items_configs.

    Each config has array_path (e.g. 'result|Subitems') and sub_mappings
    [{source_rel_path: 'values|Loan amount', subfield_name: 'Loan amount'}]; excluded_paths drop subfields.
    The engine's `data` is Make's `result` object, so a leading 'result|' is removed from array_path.
    """
    cfg = {}
    for node in nodes:
        for lic in node.get("line_items_configs", []) or []:
            array = str(lic.get("array_path", "")).split("|")
            if array[:1] == ["result"]:
                array = array[1:]
            excluded = set(lic.get("excluded_paths", []) or [])
            for sm in lic.get("sub_mappings", []) or []:
                rel = sm.get("source_rel_path")
                if rel and rel not in excluded and sm.get("subfield_name"):
                    cfg[sm["subfield_name"]] = {"array": "|".join(array), "path": rel.split("|")}
    return cfg


def iter_templates(doc: dict):
    """Yield every embedded template with its mapping configuration and routing condition."""
    names = doc.get("flow_display_names", {})
    for flow, nodes in doc["branches"].items():
        if not isinstance(nodes, list):
            continue
        li_cfg = line_item_config(nodes)
        open_condition: dict | None = None
        for idx, node in enumerate(nodes):
            kind = node_kind(node)
            if kind == CONDITIONAL:
                open_condition = node if node.get("mode") == "start" else None
                continue
            if kind == BATCH_FILLER:
                for pdf_name, b64 in node.get("embedded_pdfs", {}).items():
                    yield TemplateRef(
                        flow, names.get(flow, flow), idx, kind, pdf_name,
                        _describe_condition(open_condition), base64.b64decode(b64),
                        node.get("field_mappings_by_file", {}).get(pdf_name, {}),
                        node.get("field_conditions_by_file", {}).get(pdf_name, {}),
                        node.get("custom_fields_by_file", {}).get(pdf_name, {}),
                        node.get("line_item_instances_by_file", {}).get(pdf_name, {}),
                        set(node.get("native_field_info_by_file", {}).get(pdf_name, {})),
                        li_cfg,
                    )
            elif kind == SINGLE_FILLER and node.get("pdf_data_base64"):
                yield TemplateRef(
                    flow, names.get(flow, flow), idx, kind, node.get("pdf_filename", f"node{idx}.pdf"),
                    _describe_condition(open_condition), base64.b64decode(node["pdf_data_base64"]),
                    node.get("field_mappings", {}), node.get("field_conditions", {}),
                    node.get("custom_fields", {}), node.get("line_item_instances", {}),
                    set(node.get("native_field_info_by_file", {}).get(node.get("pdf_filename"), {})),
                    li_cfg,
                )


# --------------------------------------------------------------------------- PDF field inventory


@dataclass
class PdfField:
    name: str
    kind: str  # text | checkbox | radio | combo | list | pushbutton | signature | unknown
    states: list[str] = field(default_factory=list)  # checkbox/radio on-states, choice options
    widgets: int = 0
    max_len: int | None = None
    comb: bool = False
    read_only: bool = False
    date_format: str | None = None  # from an Acrobat AFDate_FormatEx action, converted to Java pattern


def inspect_pdf(pdf_bytes: bytes) -> dict:
    from pypdf import PdfReader  # imported lazily so `inventory` works without PDF parsing

    reader = PdfReader(io.BytesIO(pdf_bytes))
    root = reader.trailer["/Root"]
    acro = root.get("/AcroForm")
    acro = acro.get_object() if acro is not None else None
    result = {
        "pages": len(reader.pages),
        "encrypted": reader.is_encrypted,
        "acroform": acro is not None,
        "xfa": bool(acro is not None and acro.get("/XFA") is not None),
        "fields": {},
        "duplicate_names": [],
        "page_sizes": [(float(p.mediabox.width), float(p.mediabox.height), int(p.get("/Rotate", 0) or 0),
                        float(p.mediabox.left), float(p.mediabox.bottom)) for p in reader.pages],
    }
    if acro is None:
        return result

    fields: dict[str, PdfField] = {}
    terminal_objects: dict[str, set] = defaultdict(set)

    def ff_kind(ft, ff, obj):
        if ft == "/Tx":
            return "text"
        if ft == "/Sig":
            return "signature"
        if ft == "/Btn":
            if ff & (1 << 16):
                return "pushbutton"
            return "radio" if ff & (1 << 15) else "checkbox"
        if ft == "/Ch":
            return "combo" if ff & (1 << 17) else "list"
        return "unknown"

    def walk(obj, parent_name, inherited):
        obj = obj.get_object()
        partial = obj.get("/T")
        name = parent_name if partial is None else (f"{parent_name}.{partial}" if parent_name else str(partial))
        inh = dict(inherited)
        for key in ("/FT", "/Ff", "/MaxLen", "/Opt"):
            if key in obj:
                inh[key] = obj[key]
        kids = obj.get("/Kids")
        named_kids = [k for k in (kids or []) if "/T" in k.get_object()]
        if named_kids:
            for k in kids:
                walk(k, name, inh)
            return
        # terminal field (its kids, if any, are widgets)
        widgets = [k.get_object() for k in kids] if kids else [obj]
        ff = int(inh.get("/Ff", 0))
        kind = ff_kind(inh.get("/FT"), ff, obj)
        f = fields.get(name) or PdfField(name, kind)
        f.widgets += len(widgets)
        f.read_only = bool(ff & 1)
        f.comb = bool(ff & (1 << 24)) and kind == "text"
        if "/MaxLen" in inh:
            f.max_len = int(inh["/MaxLen"])
        for w in [obj, *widgets]:
            aa = w.get("/AA")
            fmt = aa.get_object().get("/F") if aa is not None else None
            js = str(fmt.get_object().get("/JS", "")) if fmt is not None else ""
            m = re.search(r'AFDate_FormatEx\("([dmy/ .-]+)"\)', js)
            if m:
                f.date_format = m.group(1).replace("m", "M")
        if kind in ("checkbox", "radio"):
            for w in widgets:
                ap = w.get("/AP")
                n = ap.get_object().get("/N") if ap is not None else None
                for st in (n.get_object().keys() if n is not None else []):
                    st = str(st).lstrip("/")
                    if st != "Off" and st not in f.states:
                        f.states.append(st)
        if kind in ("combo", "list") and "/Opt" in inh:
            for o in inh["/Opt"]:
                o = o.get_object()
                f.states.append(str(o[0] if isinstance(o, list) else o))
        fields[name] = f
        terminal_objects[name].add(id(obj))

    for top in acro.get("/Fields", []):
        walk(top, "", {})
    result["fields"] = fields
    result["duplicate_names"] = sorted(n for n, objs in terminal_objects.items() if len(objs) > 1)
    return result


# --------------------------------------------------------------------------- classification


@dataclass
class Classified:
    entries: list = field(default_factory=list)
    unsupported: list = field(default_factory=list)
    errors: list = field(default_factory=list)
    warnings: list = field(default_factory=list)
    overlays: list = field(default_factory=list)
    inert: list = field(default_factory=list)       # no effect in legacy, with evidence (not "obsolete")
    unresolved: list = field(default_factory=list)  # cannot be imported yet; needs evidence or a decision


def decode_pdf_name(s: str) -> str:
    """Undo PDF name escaping (e.g. 'Top#20Up' -> 'Top Up') used in legacy option suffixes."""
    return re.sub(r"#([0-9A-Fa-f]{2})", lambda m: chr(int(m.group(1), 16)), s)


def classify(ref: TemplateRef, pdf_info: dict | None, siblings: tuple = ()) -> Classified:
    """Translate legacy mapping entries into engine field entries, never guessing silently.

    `siblings` = [(TemplateRef, pdf_info)] of the other templates in the same batch node; they are
    the evidence for recognising copied mapping sets.
    """
    out = Classified()
    pdf_fields: dict[str, PdfField] = (pdf_info or {}).get("fields", {})
    duplicates = set((pdf_info or {}).get("duplicate_names", []))

    def target(pdf_field: str):
        """Resolve 'Field#export' radio/checkbox option syntax against the real PDF."""
        base, _, option = pdf_field.partition("#")
        option = decode_pdf_name(option) if option else option
        if pdf_info is None:
            return base, option or None, None
        if pdf_field in pdf_fields:
            return pdf_field, None, pdf_fields[pdf_field]
        if option and base in pdf_fields:
            return base, option, pdf_fields[base]
        return base, option or None, None

    for pdf_field, source in ref.mappings.items():
        cfg = ref.conditions.get(pdf_field, {})
        name, option, pf = target(pdf_field)
        legacy = {"field": pdf_field, "mapping": source if source in SENTINELS else "source_key"}
        if pdf_info is not None and pf is None:
            if name in ref.native_fields or pdf_field in ref.native_fields:
                out.errors.append({"code": "UNRESOLVED_PDF_FIELD", "pdfField": pdf_field})
                continue
            # Neither in the PDF nor in MacroForge's own scan: the legacy engine cannot fill it either.
            dup = [s.pdf_name for s, si in siblings
                   if s.mappings.get(pdf_field) == source and s.conditions.get(pdf_field) == ref.conditions.get(pdf_field)
                   and si and name in si.get("fields", {})]
            seen = [s.pdf_name for s, si in siblings if si and name in si.get("fields", {})]
            item = {"pdfField": pdf_field, "mapping": legacy["mapping"]}
            if not pdf_fields:
                out.unresolved.append({**item, "category": "template_without_form_fields",
                                       "evidence": {"duplicateOf": dup[:1]},
                                       "reason": "template has no form fields; only a fillable version or "
                                                 "coordinate overlays could carry this value"})
            elif dup:
                out.inert.append({**item, "category": "sibling_duplicate", "evidence": {"duplicateOf": dup[0]},
                                  "reason": "identical rule resolves on a sibling template in the same batch; "
                                            "no target here"})
            else:
                out.unresolved.append({**item, "category": "no_target_field", "evidence": {"nameFoundIn": seen},
                                       "reason": "no such field in this template; the same name elsewhere has a "
                                                 "different rule" if seen else "no such field in any template"})
            continue
        if name in duplicates:
            out.errors.append({"code": "AMBIGUOUS_PDF_FIELD", "pdfField": pdf_field})
            continue
        kind = pf.kind if pf else None
        if kind in ("signature", "pushbutton"):
            out.errors.append({"code": "FORBIDDEN_TARGET", "pdfField": pdf_field, "pdfKind": kind})
            continue
        if option is not None and pf is not None and option not in pf.states:
            out.errors.append({"code": "UNKNOWN_OPTION", "pdfField": pdf_field, "option": option,
                               "available": pf.states})
            continue

        m = LINE_ITEM.match(source)
        if m:
            sub, pos = m["field"], int(m["n"])
            loc = ref.line_item_cfg.get(sub)
            if loc is None:
                out.unresolved.append({"pdfField": pdf_field, "mapping": "line_item", "category": "line_item_name_conflict",
                                       "evidence": {"subfield": sub, "position": pos,
                                                    "knownSubfields": sorted(ref.line_item_cfg)},
                                       "reason": "subfield not produced by the current line_items_configs"})
                continue
            if kind not in (None, "text", "combo", "list"):
                out.unsupported.append({"pdfField": pdf_field, "legacyType": "line_item", "pdfKind": kind,
                                        "reason": "line item mapped to a button field"})
                continue
            inst = (ref.line_items.get("LineItems", {}) or {}).get(sub, {}).get(str(pos))
            if inst is not None and inst != pdf_field:
                out.warnings.append({"code": "LINE_ITEM_INSTANCE_MISMATCH", "pdfField": pdf_field})
            out.entries.append({"pdfField": name, "kind": kind or "text",
                                "source": {"lineItem": {"array": loc["array"], "position": pos, "path": loc["path"]}},
                                "legacy": {**legacy, "mapping": "line_item", "lineItem": source},
                                "reviewRequired": "row order = order of the Make Subitems array (inferred)"})
            continue

        if source in ("checked", "unchecked"):
            default = cfg.get("default")
            if default not in (None, source):
                out.errors.append({"code": "CONFLICTING_DEFAULT", "pdfField": pdf_field, "mapping": source})
                continue
            if kind not in (None, "checkbox", "radio"):
                out.errors.append({"code": "KIND_MISMATCH", "pdfField": pdf_field, "pdfKind": kind,
                                   "legacyType": source})
                continue
            if source == "checked" and option is None and pf is not None and len(pf.states) > 1:
                out.unresolved.append({"pdfField": pdf_field, "mapping": source, "category": "ambiguous_checked_state",
                                       "evidence": {"onStates": pf.states},
                                       "reason": "legacy 'checked' without a state on a field with several on-states "
                                                 "does not say which box to tick"})
                continue
            entry = {"pdfField": name, "kind": kind or "checkbox",
                     "source": {"constant": source == "checked"}, "legacy": legacy,
                     "reviewRequired": "constant checkbox state: confirm it is not a client declaration/consent"}
            if option:
                entry["option"] = option
            out.entries.append(entry)
            out.warnings.append({"code": "CONSTANT_CHECKBOX", "pdfField": pdf_field})
            continue

        if source == "direct_text":
            if cfg.get("type") == "today_date":
                fmt, why = None, "is an assumption"
                if pf and pf.date_format:
                    fmt, why = pf.date_format, "taken from the field's own AFDate_FormatEx action"
                elif pf and pf.comb and pf.max_len in (6, 8):
                    fmt, why = ("ddMMyy" if pf.max_len == 6 else "ddMMyyyy"), "derived from comb MaxLen"
                out.entries.append({"pdfField": name, "kind": "text",
                                    "source": {"generated": "today", "format": fmt or "dd/MM/yyyy",
                                               "zone": "Pacific/Auckland"},
                                    "legacy": legacy,
                                    "reviewRequired": "MacroForge '[TODAY]' output format is not documented in the "
                                                      "export; format " + why})
                out.warnings.append({"code": "TODAY_FORMAT_UNVERIFIED", "pdfField": pdf_field})
            elif cfg.get("type") is None and isinstance(cfg.get("direct_text"), str):
                out.entries.append({"pdfField": name, "kind": "text",
                                    "source": {"constant": cfg["direct_text"]}, "legacy": legacy,
                                    "reviewRequired": "constant literal: confirm it contains no personal data"})
                out.warnings.append({"code": "CONSTANT_TEXT", "pdfField": pdf_field})
            else:
                out.unsupported.append({"pdfField": pdf_field, "legacyType": f"direct_text:{cfg.get('type')}",
                                        "reason": "unknown direct_text type"})
            continue

        if source == "conditional":
            conds = cfg.get("conditions", [])
            if len(conds) != 1 or cfg.get("operators"):
                out.unsupported.append({"pdfField": pdf_field, "legacyType": "conditional",
                                        "conditionCount": len(conds),
                                        "reason": "only single-condition rules are imported"})
                continue
            c = conds[0]
            if str(c.get("column", "")).startswith("LineItems."):
                out.unsupported.append({"pdfField": pdf_field, "legacyType": "line_item_condition",
                                        "reason": "condition on a LineItems column without a row index; which "
                                                  "subitem it tests is not recorded in the export"})
                continue
            op = str(c.get("operator", "")).lower()
            if op not in ("equals", "contains"):
                out.unsupported.append({"pdfField": pdf_field, "legacyType": "conditional", "operator": op,
                                        "reason": "operator not supported"})
                continue
            if kind not in (None, "checkbox", "radio"):
                out.unsupported.append({"pdfField": pdf_field, "legacyType": "conditional", "pdfKind": kind,
                                        "reason": "conditional on non-button field: legacy semantics unknown"})
                continue
            entry = {"pdfField": name, "kind": kind or ("radio" if option else "checkbox"),
                     "source": {"when": {"key": c.get("column"), "operator": op, "value": c.get("value")}},
                     "legacy": legacy,
                     "reviewRequired": "legacy equals/contains case-sensitivity and whitespace handling unverified"}
            if option:
                entry["option"] = option
            out.entries.append(entry)
            continue

        if source in SENTINELS:  # defensive: sentinel without handler
            out.unsupported.append({"pdfField": pdf_field, "legacyType": source, "reason": "no handler"})
            continue

        # plain source key
        if option:
            out.unsupported.append({"pdfField": pdf_field, "legacyType": "source_key_with_option",
                                    "reason": "'#option' target with a value key: legacy semantics unknown"})
            continue
        if kind not in (None, "text", "combo", "list"):
            out.unsupported.append({"pdfField": pdf_field, "legacyType": "source_key", "pdfKind": kind,
                                    "reason": "value key mapped to a button field: truthiness rules unknown"})
            continue
        out.entries.append({"pdfField": name, "kind": kind or "text", "source": {"key": source}, "legacy": legacy})

    for fld in ref.conditions:
        if fld not in ref.mappings:
            out.warnings.append({"code": "ORPHAN_CONDITION", "pdfField": fld})

    sizes = (pdf_info or {}).get("page_sizes", [])
    for cid, cf in ref.custom_fields.items():
        ov = overlay_entry(cid, cf, sizes) if pdf_info is not None else None
        if isinstance(ov, dict):
            out.overlays.append(ov)
            if "constant" in ov["source"]:
                out.warnings.append({"code": "CONSTANT_TEXT", "overlay": cid})
        else:
            out.unsupported.append({"pdfField": None, "legacyType": f"overlay_{cf.get('type')}",
                                    "customFieldId": cid, "page": cf.get("page"), "sourceKey": cf.get("mapping"),
                                    "reason": ov or "PDF not inspected"})
    if ref.line_items:
        out.warnings.append({"code": "LINE_ITEM_INSTANCES_PRESENT", "count": len(ref.line_items)})
    return out


def overlay_entry(cid: str, cf: dict, page_sizes: list):
    """Convert a MacroForge custom text field to an engine overlay, or return a reason string."""
    if cf.get("type") != "text":
        return f"overlay type {cf.get('type')!r} not supported"
    page, rect = cf.get("page"), cf.get("rect")
    if not isinstance(page, int) or not (0 <= page < len(page_sizes)) or not isinstance(rect, list) or len(rect) != 4:
        return "overlay page/rect invalid"
    w, h, rot, llx, lly = page_sizes[page]
    if rot % 360:
        return "rotated page: overlay transform unverified"
    x0, y0, x1, y1 = (v / OVERLAY_PREVIEW_SCALE for v in rect)
    pdf_rect = [round(llx + x0, 2), round(lly + h - y1, 2), round(llx + min(x1, w), 2), round(lly + h - y0, 2)]
    mapping = cf.get("mapping")
    if mapping == "direct_text":
        if not isinstance(cf.get("current_text"), str):
            return "direct_text overlay without text"
        source = {"constant": cf["current_text"]}
    elif isinstance(mapping, str) and mapping and mapping not in SENTINELS and not LINE_ITEM.match(mapping):
        source = {"key": mapping}
    else:
        return f"overlay mapping {mapping!r} not supported"
    return {"id": cid, "page": page, "rect": pdf_rect, "source": source,
            "legacy": {"rect": rect, "transform": f"pdf = rect / {OVERLAY_PREVIEW_SCALE}, top-left origin"},
            "reviewRequired": "overlay position derived from the evidenced 1.2 preview scale; font/size are "
                              "engine policy (legacy font not recorded)"
                              + ("; constant literal: confirm it contains no personal data"
                                 if "constant" in source else "")}


def siblings_of(ref: TemplateRef, refs_with_info: list) -> tuple:
    return tuple((r, i) for r, i in refs_with_info
                 if r.flow == ref.flow and r.node_index == ref.node_index and r is not ref)


# --------------------------------------------------------------------------- commands


def lender_label(cond: str | None) -> str:
    if not cond:
        return "(unconditional)"
    m = re.search(r"Contains (\S+)", cond)
    return m.group(1) if m else cond


def cmd_inventory(args) -> int:
    doc = load_mforge(Path(args.mforge))
    report: dict = {"importerVersion": IMPORTER_VERSION, "sourceSha256": sha256(Path(args.mforge).read_bytes()),
                    "flows": {}, "templates": [], "totals": Counter()}
    for flow, nodes in doc["branches"].items():
        report["flows"][flow] = {
            "displayName": doc.get("flow_display_names", {}).get(flow, flow),
            "nodeTypes": dict(Counter(node_kind(n) for n in nodes)) if isinstance(nodes, list) else {},
            "current": doc.get("current_branch") == flow,
        }
    refs = [(r, inspect_pdf(r.pdf_bytes) if args.with_pdf else None) for r in iter_templates(doc)]
    for ref, info in refs:
        cls = classify(ref, info, siblings_of(ref, refs))
        kinds = Counter()
        for src in ref.mappings.values():
            kinds["line_item" if LINE_ITEM.match(src) else (src if src in SENTINELS else "source_key")] += 1
        row = {
            "flow": ref.flow, "node": ref.node_index, "nodeKind": ref.node_kind, "pdf": ref.pdf_name,
            "lender": lender_label(ref.lender_condition), "sha256": ref.sha256, "bytes": len(ref.pdf_bytes),
            "mappings": len(ref.mappings), "mappingKinds": dict(kinds), "overlays": len(ref.custom_fields),
            "conditionEntries": len(ref.conditions),
            "imported": len(cls.entries), "unsupported": len(cls.unsupported), "errors": len(cls.errors),
            "overlaysImported": len(cls.overlays), "inert": len(cls.inert), "unresolved": len(cls.unresolved),
            "warnings": len(cls.warnings),
        }
        if info is not None:
            fk = Counter(f.kind for f in info["fields"].values())
            row["pdf"] = ref.pdf_name
            row["pdfInfo"] = {"pages": info["pages"], "acroform": info["acroform"], "xfa": info["xfa"],
                              "encrypted": info["encrypted"], "fieldKinds": dict(fk),
                              "duplicateNames": len(info["duplicate_names"]),
                              "multiWidgetFields": sum(1 for f in info["fields"].values() if f.widgets > 1)}
            row["errorCodes"] = dict(Counter(e["code"] for e in cls.errors))
            row["unsupportedTypes"] = dict(Counter(u["legacyType"] for u in cls.unsupported))
            row["unresolvedCategories"] = dict(Counter(u["category"] for u in cls.unresolved))
            row["inertCategories"] = dict(Counter(u["category"] for u in cls.inert))
            row["importedKinds"] = dict(Counter(next(iter(e["source"])) for e in cls.entries))
        report["templates"].append(row)
        report["totals"]["templates"] += 1
        report["totals"]["mappings"] += len(ref.mappings)
        report["totals"]["overlays"] += len(ref.custom_fields)
        if info is not None:
            report["totals"]["imported"] += len(cls.entries)
            report["totals"]["unsupported"] += len(cls.unsupported)
            report["totals"]["errors"] += len(cls.errors)
            report["totals"]["overlaysImported"] += len(cls.overlays)
            report["totals"]["inert"] += len(cls.inert)
            report["totals"]["unresolved"] += len(cls.unresolved)
        for k, v in row["mappingKinds"].items():
            report["totals"][f"kind:{k}"] += v
    report["totals"] = dict(report["totals"])
    text = json.dumps(report, indent=2, ensure_ascii=False)
    if args.out:
        Path(args.out).parent.mkdir(parents=True, exist_ok=True)
        Path(args.out).write_text(text + "\n", encoding="utf-8")
        print(f"inventory written: {args.out}")
    else:
        print(text)
    return 0


def cmd_extract(args) -> int:
    doc = load_mforge(Path(args.mforge))
    out = Path(args.out_dir)
    out.mkdir(parents=True, exist_ok=True)
    index = []
    for ref in iter_templates(doc):
        fname = f"{ref.sha256[:12]}__{safe_filename(ref.pdf_name)}"
        if not fname.endswith(".pdf"):
            fname += ".pdf"
        target = out / fname
        if not target.exists() or sha256(target.read_bytes()) != ref.sha256:
            target.write_bytes(ref.pdf_bytes)
        index.append({"pdf": ref.pdf_name, "flow": ref.flow, "node": ref.node_index, "sha256": ref.sha256,
                      "file": fname, "bytes": len(ref.pdf_bytes)})
    (out / "templates-index.json").write_text(json.dumps(index, indent=2, ensure_ascii=False) + "\n", "utf-8")
    print(f"extracted {len(index)} templates to {out}")
    return 0


def select_template(doc: dict, pdf_name: str, flow: str | None, sha: str | None) -> TemplateRef:
    hits = [r for r in iter_templates(doc) if r.pdf_name == pdf_name and (flow is None or r.flow == flow)]
    if sha:
        hits = [r for r in hits if r.sha256 == sha.lower()]
    if not hits:
        raise ImportError_(f"no embedded template named {pdf_name!r}" + (f" in flow {flow!r}" if flow else ""))
    if len({r.sha256 for r in hits}) > 1 or len({(r.flow, r.node_index) for r in hits}) > 1:
        where = ", ".join(f"{r.flow}#node{r.node_index}:{r.sha256[:12]}" for r in hits)
        raise ImportError_(f"template name is ambiguous ({where}); pass --flow and/or --sha256")
    return hits[0]


def cmd_manifest(args) -> int:
    src = Path(args.mforge)
    doc = load_mforge(src)
    ref = select_template(doc, args.pdf, args.flow, args.sha256)
    info = inspect_pdf(ref.pdf_bytes)
    if info["xfa"]:
        print("warning: template contains XFA", file=sys.stderr)
    if info["encrypted"] and args.encryption == "reject":
        print("warning: template is encrypted; the engine will refuse it unless --encryption preserve",
              file=sys.stderr)
    refs = [(r, inspect_pdf(r.pdf_bytes) if (r.flow, r.node_index) == (ref.flow, ref.node_index) else None)
            for r in iter_templates(doc)]
    cls = classify(ref, info, siblings_of(ref, refs))
    template_file = f"{ref.sha256[:12]}__{safe_filename(ref.pdf_name)}"
    if not template_file.endswith(".pdf"):
        template_file += ".pdf"
    manifest = {
        "schemaVersion": MANIFEST_SCHEMA_VERSION,
        "templateId": args.template_id,
        "templateVersion": args.template_version,
        "templateFile": template_file,
        "templateSha256": ref.sha256,
        "encryption": args.encryption,
        "sourceKeyNormalization": "macroforge-v1",
        "fontFallback": args.font_fallback,
        "provenance": {
            "importer": f"tools/import_mforge.py {IMPORTER_VERSION}",
            "sourceFile": src.name,
            "sourceSha256": sha256(src.read_bytes()),
            "flow": ref.flow, "flowDisplayName": ref.flow_display, "node": ref.node_index,
            "legacyPdfName": ref.pdf_name, "lenderCondition": ref.lender_condition,
            "importedOn": args.date or dt.date.today().isoformat(),
        },
        "pdf": {"pages": info["pages"], "acroform": info["acroform"], "xfa": info["xfa"],
                "fields": {n: {"kind": f.kind, **({"states": f.states} if f.states else {}),
                               **({"maxLen": f.max_len} if f.max_len else {}),
                               **({"comb": True} if f.comb else {}),
                               **({"widgets": f.widgets} if f.widgets > 1 else {})}
                           for n, f in sorted(info["fields"].items())}},
        "fields": sorted(cls.entries, key=lambda e: (e["pdfField"], e.get("option", ""))),
        "overlays": cls.overlays,
        "unsupported": cls.unsupported,
        "unresolvedLegacyMappings": cls.unresolved,
        "inertLegacyMappings": cls.inert,
        "importErrors": cls.errors,
        "importWarnings": cls.warnings,
    }
    mapped = {e["pdfField"] for e in cls.entries}
    manifest["unmappedPdfFields"] = sorted(n for n, f in info["fields"].items()
                                           if n not in mapped and f.kind not in ("pushbutton",))
    text = json.dumps(manifest, indent=2, ensure_ascii=False) + "\n"
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(text, encoding="utf-8")
    print(f"manifest written: {out}  fields={len(cls.entries)} unsupported={len(cls.unsupported)} "
          f"overlays={len(cls.overlays)} unresolved={len(cls.unresolved)} inert={len(cls.inert)} "
          f"errors={len(cls.errors)} warnings={len(cls.warnings)}")
    return 1 if cls.errors and args.strict else 0


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    sub = p.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("inventory", help="privacy-safe inventory as JSON")
    s.add_argument("mforge")
    s.add_argument("--with-pdf", action="store_true", help="parse each embedded PDF and validate mappings")
    s.add_argument("--out")
    s.set_defaults(fn=cmd_inventory)
    s = sub.add_parser("extract", help="extract embedded PDFs (keep output out of git)")
    s.add_argument("mforge")
    s.add_argument("--out-dir", default=".local/generated/templates")
    s.set_defaults(fn=cmd_extract)
    s = sub.add_parser("manifest", help="build an engine mapping manifest for one template")
    s.add_argument("mforge")
    s.add_argument("--pdf", required=True, help="legacy embedded PDF name, e.g. 'BNZ_BoarderForm_2.0_8 24.pdf'")
    s.add_argument("--flow", help="restrict to one flow (e.g. 'Flow C')")
    s.add_argument("--sha256", help="require this embedded template SHA-256")
    s.add_argument("--template-id", required=True)
    s.add_argument("--template-version", required=True)
    s.add_argument("--date", help="override provenance date (YYYY-MM-DD) for reproducible output")
    s.add_argument("--strict", action="store_true", help="exit 1 when import errors exist")
    s.add_argument("--font-fallback", choices=["unicode", "none"], default="unicode",
                   help="engine policy when a field's own font cannot encode a value (e.g. macrons)")
    s.add_argument("--encryption", choices=["reject", "preserve"], default="reject",
                   help="engine policy for encrypted templates; 'preserve' = incremental save keeping the "
                        "bank's encryption (record the human review in docs/decisions.md)")
    s.add_argument("--out", required=True)
    s.set_defaults(fn=cmd_manifest)
    args = p.parse_args(argv)
    try:
        return args.fn(args)
    except ImportError_ as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
