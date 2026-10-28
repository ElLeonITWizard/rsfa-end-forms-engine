#!/usr/bin/env python3
"""Stage the minimal, reviewable build context and configuration for the private admin service (D-041, docs/14).

Context (uploaded to Cloud Build; becomes the image):
  engine/pom.xml, engine/src/**          source + unit tests (tests run inside the image build)
  deploy/admin/Dockerfile, deploy/admin/cloudbuild.yaml
  mappings/**.json, curation/**.json     repository manifests and curation (library statuses; no client data)
  deploy/poc-allowlist.json, deploy/deployment-state.json   approval and deployment status shown in the library

Configuration (NOT in the image; uploaded by deploy_admin.sh to the private bucket):
  config/monday-schema.json              the Monday column schema, SANITISED: column id, title, type and active option
                                         labels only (no board owners, creators, views, groups, items or URLs)

Safety checks (any failure aborts and leaves nothing behind):
  * no PDF, .mforge, spreadsheet or blueprint anywhere in the context (templates are uploaded at runtime);
  * no webhook URL or MacroForge payload marker in any staged text file;
  * the admin UI assets exist and load nothing from another origin;
  * the sanitised schema has columns and no text that looks like an email address or phone number;
  * the output directory must be inside <repo>/.local/.

Usage:
  python3 tools/stage_admin.py --schema .local/reference/masterboard-columns-2026-10-27.json --out .local/deploy-admin
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
FORBIDDEN_SUFFIXES = {".pdf", ".mforge", ".xlsx", ".xls", ".csv"}
FORBIDDEN_TEXT = re.compile(r"hook\.[a-z0-9]+\.make\.com|webhook_url|sample_payload|__raw_webhook_payload|blueprint\.json", re.I)
TEXT_SUFFIXES = {".json", ".java", ".xml", ".yaml", ".txt", ".html", ".js", ".css", ".md", ".properties", ""}
UI_ADMIN = "engine/src/main/resources/ui/admin"
UI_ASSETS = ("index.html", "admin.css", "admin.js")
EXTERNAL_URL = re.compile(r"https?://", re.I)
EMAIL = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")
PHONE = re.compile(r"(?:\+?64|0)[\s-]?\d{1,2}[\s-]?\d{3}[\s-]?\d{3,4}\b")


class StageError(Exception):
    pass


def sha256(p: Path) -> str:
    return hashlib.sha256(p.read_bytes()).hexdigest()


def sanitise_schema(raw: dict) -> dict:
    """Keeps only what the mapping editor needs: id, title, type and active option labels of each column."""
    cols = (raw.get("board") or {}).get("columns", raw.get("columns"))
    if not isinstance(cols, list) or not cols:
        raise StageError("the schema export has no columns")
    out = []
    for c in cols:
        if not isinstance(c, dict) or not c.get("id") or "title" not in c:
            raise StageError("a column has no id or title")
        labels = []
        settings = c.get("settings") if isinstance(c.get("settings"), dict) else {}
        raw_labels = settings.get("labels")
        if isinstance(raw_labels, list):
            for x in raw_labels:
                if isinstance(x, dict) and not x.get("is_deactivated", False):
                    labels.append(str(x.get("label", x.get("name", ""))))
                elif isinstance(x, str):
                    labels.append(x)
        elif isinstance(raw_labels, dict):  # status columns: {"0": "Working on it", ...}
            labels = [str(v) for v in raw_labels.values()]
        col = {"id": str(c["id"]), "title": str(c["title"]), "type": str(c.get("type", ""))}
        if labels:
            col["settings"] = {"labels": [{"label": l} for l in labels]}
        out.append(col)
    text = json.dumps(out, ensure_ascii=False)
    if EMAIL.search(text) or PHONE.search(text):
        raise StageError("the sanitised schema contains text that looks like an email address or phone number; "
                         "review the Monday labels before staging")
    return {"board": {"columns": out}, "sanitised": {"fields": ["id", "title", "type", "settings.labels[].label"],
                                                      "columns": len(out)}}


def stage(schema: Path, out: Path) -> dict:
    out = out.resolve()
    local = (REPO / ".local").resolve()
    if local not in out.parents:
        raise StageError("--out must be inside <repo>/.local/")
    if out.exists():
        shutil.rmtree(out)
    ctx = out / "context"
    try:
        files: list[tuple[str, Path]] = [("engine/pom.xml", REPO / "engine/pom.xml"),
                                         ("deploy/admin/Dockerfile", REPO / "deploy/admin/Dockerfile"),
                                         ("deploy/admin/cloudbuild.yaml", REPO / "deploy/admin/cloudbuild.yaml"),
                                         ("deploy/poc-allowlist.json", REPO / "deploy/poc-allowlist.json"),
                                         ("deploy/deployment-state.json", REPO / "deploy/deployment-state.json")]
        for base in ("engine/src", "mappings", "curation"):
            for p in sorted((REPO / base).rglob("*")):
                if p.is_file():
                    files.append((str(p.relative_to(REPO)), p))
        for rel, src in files:
            if src.suffix.lower() in FORBIDDEN_SUFFIXES:
                raise StageError(f"forbidden file type in the admin context: {rel}")
            if (rel.startswith("mappings/") or rel.startswith("curation/")) and src.suffix != ".json":
                raise StageError(f"unexpected file in {rel.split('/')[0]}/: {rel}")
            if src.suffix in TEXT_SUFFIXES and FORBIDDEN_TEXT.search(src.read_text(encoding="utf-8", errors="replace")):
                raise StageError(f"forbidden content in {rel}")
            dst = ctx / rel
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(src, dst)
        for a in UI_ASSETS:
            p = ctx / UI_ADMIN / a
            if not p.is_file():
                raise StageError(f"admin UI asset missing: {a}")
            if EXTERNAL_URL.search(re.sub(r"https?://www\.w3\.org/[^\"' ]*", "", p.read_text(encoding="utf-8"))):
                raise StageError(f"admin UI asset {a} references another origin")
        # cloudbuild.yaml at the context root, like the engine context (gcloud builds submit --config)
        shutil.copy2(REPO / "deploy/admin/cloudbuild.yaml", ctx / "cloudbuild.yaml")
        staged = sorted(p for p in ctx.rglob("*") if p.is_file())
        (ctx / "STAGED_FILES.txt").write_text("".join(f"{sha256(p)} {p.stat().st_size} {p.relative_to(ctx)}\n" for p in staged))
        cfg = out / "config"
        cfg.mkdir(parents=True)
        sanitised = sanitise_schema(json.loads(schema.read_text(encoding="utf-8")))
        (cfg / "monday-schema.json").write_text(json.dumps(sanitised, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
        summary = {"contextFiles": len(staged) + 1, "manifests": len(list((ctx / "mappings").rglob("*.json"))),
                   "schemaColumns": sanitised["sanitised"]["columns"],
                   "schemaSha256": sha256(cfg / "monday-schema.json"), "pdfsInContext": 0}
        (out / "STAGE_SUMMARY.json").write_text(json.dumps(summary, indent=2) + "\n")
        return summary
    except BaseException:
        shutil.rmtree(out, ignore_errors=True)
        raise


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--schema", required=True, type=Path, help="Monday column export (.local/reference/masterboard-columns-*.json)")
    ap.add_argument("--out", required=True, type=Path, help="output directory inside .local/")
    a = ap.parse_args()
    try:
        print(json.dumps(stage(a.schema, a.out), indent=2))
    except StageError as e:
        print(f"stage_admin: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
