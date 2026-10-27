#!/usr/bin/env python3
"""Stage a minimal, reviewable build context for the Cloud Run POC image.

Only these files are copied into the context (everything else, including .local/reference and the .mforge,
stays out of the upload):
  engine/pom.xml, engine/src/**, engine/Dockerfile   source + tests (tests run inside the image build), including the
                                                      demo UI assets in engine/src/main/resources/ui/
  deploy/cloudbuild.yaml                              Cloud Build config
  mappings/<...>.json                                 only manifests of allowlisted templates
  poc-templates/<file>.pdf                            only allowlisted, SHA-256 verified, BLANK templates

Safety checks (any failure aborts and leaves no context behind):
  * every allowlist entry has a complete human approval (approvedBy, approvedOn <= today, reference), unless
    --unapproved-local-test, which writes UNAPPROVED_LOCAL_TEST_ONLY into the context (never deploy it);
  * template SHA-256 == allowlist == manifest templateSha256;
  * templates are blank: no text/choice field has a value (a filled client PDF can never be staged by mistake);
  * no staged file is an export (.mforge/.xlsx/blueprint) or contains webhook URLs;
  * the demo UI assets are present, load nothing from another origin (no CDN), and only offer allowlisted templates;
  * the output directory must be inside <repo>/.local/.

Usage:
  python3 tools/stage_deploy.py --templates .local/generated/templates --out .local/deploy/context
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import re
import shutil
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
FORBIDDEN_SUFFIXES = {".mforge", ".xlsx", ".xls"}
FORBIDDEN_TEXT = re.compile(r"hook\.[a-z0-9]+\.make\.com|webhook_url|sample_payload|__raw_webhook_payload", re.I)
TEXT_SUFFIXES = {".json", ".java", ".xml", ".yaml", ".txt", ".html", ".js", ".css", ".md", ".properties", ""}
UI_DIR = "engine/src/main/resources/ui"
UI_ASSETS = ("index.html", "app.css", "app.js", "forms.json")
EXTERNAL_URL = re.compile(r"https?://", re.I)


class StageError(Exception):
    pass


def sha256(p: Path) -> str:
    return hashlib.sha256(p.read_bytes()).hexdigest()


def approval_problem(a: dict | None, today: dt.date) -> str | None:
    if not isinstance(a, dict):
        return "no approval block"
    if not str(a.get("approvedBy", "")).strip() or not str(a.get("reference", "")).strip():
        return "approval needs approvedBy and reference"
    try:
        on = dt.date.fromisoformat(str(a.get("approvedOn", "")))
    except ValueError:
        return "approvedOn must be an ISO date"
    return "approvedOn is in the future" if on > today else None


def blank_problem(pdf: Path) -> str | None:
    from pypdf import PdfReader

    reader = PdfReader(str(pdf))
    for name, f in (reader.get_fields() or {}).items():
        v = f.get("/V")
        if f.get("/FT") in ("/Tx", "/Ch") and v not in (None, "", []):
            return f"field {name!r} already has a value: not a blank template"
    return None


def ui_problem(ui_dir: Path, allowed: set[str]) -> str | None:
    """The demo UI must be complete, self-contained (no CDN/third-party URLs) and offer only allowlisted templates."""
    for name in UI_ASSETS:
        p = ui_dir / name
        if not p.is_file():
            return f"demo UI asset missing: {name}"
        if EXTERNAL_URL.search(p.read_text(encoding="utf-8", errors="ignore")):
            return f"demo UI asset references another origin: {name}"
    for t in json.loads((ui_dir / "forms.json").read_text(encoding="utf-8")).get("templates", []):
        key = f"{t.get('templateId')}@{t.get('templateVersion')}"
        if key not in allowed:
            return f"demo UI offers a template that is not allowlisted: {key}"
    return None


def content_problem(p: Path) -> str | None:
    if p.suffix.lower() in FORBIDDEN_SUFFIXES or "blueprint" in p.name.lower():
        return "forbidden file staged"
    if p.suffix.lower() in TEXT_SUFFIXES and FORBIDDEN_TEXT.search(p.read_text(encoding="utf-8", errors="ignore")):
        return "forbidden content (webhook/payload marker)"
    return None


def find_manifest(mappings: Path, template_id: str, version: str) -> Path:
    hits = []
    for p in sorted(mappings.rglob("*.json")):
        m = json.loads(p.read_text(encoding="utf-8"))
        if m.get("templateId") == template_id and m.get("templateVersion") == version:
            hits.append(p)
    if len(hits) != 1:
        raise StageError(f"expected exactly one manifest for {template_id}@{version}, found {len(hits)}")
    return hits[0]


def copy(src: Path, dst: Path) -> None:
    dst.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(src, dst)


def stage(allowlist: Path, mappings: Path, templates: Path, out: Path, local_test: bool, today: dt.date) -> list[str]:
    local = (REPO / ".local").resolve()
    out = out.resolve()
    if local not in out.parents:
        raise StageError(f"--out must be inside {local}")
    entries = json.loads(allowlist.read_text(encoding="utf-8")).get("templates", [])
    if not entries:
        raise StageError("allowlist has no templates")
    if out.exists():
        shutil.rmtree(out)
    try:
        notes = []
        for e in entries:
            key = f"{e['templateId']}@{e['templateVersion']}"
            problem = approval_problem(e.get("approval"), today)
            if problem and not local_test:
                raise StageError(f"{key}: {problem} (fill in deploy/poc-allowlist.json after review)")
            if problem:
                notes.append(f"{key}: UNAPPROVED ({problem})")
            mpath = find_manifest(mappings, e["templateId"], e["templateVersion"])
            manifest = json.loads(mpath.read_text(encoding="utf-8"))
            if manifest.get("templateSha256") != e["sha256"]:
                raise StageError(f"{key}: manifest SHA-256 differs from allowlist")
            tpath = templates / manifest["templateFile"]
            if not tpath.is_file() or sha256(tpath) != e["sha256"]:
                raise StageError(f"{key}: template file missing or SHA-256 mismatch")
            problem = blank_problem(tpath)
            if problem:
                raise StageError(f"{key}: {problem}")
            copy(mpath, out / "mappings" / mpath.relative_to(mappings))
            copy(tpath, out / "poc-templates" / tpath.name)
        copy(REPO / "engine" / "pom.xml", out / "engine" / "pom.xml")
        copy(REPO / "engine" / "Dockerfile", out / "engine" / "Dockerfile")
        copy(REPO / "deploy" / "cloudbuild.yaml", out / "cloudbuild.yaml")
        shutil.copytree(REPO / "engine" / "src", out / "engine" / "src")
        problem = ui_problem(out / UI_DIR, {f"{e['templateId']}@{e['templateVersion']}" for e in entries})
        if problem:
            raise StageError(problem)
        (out / ".gcloudignore").write_text("# Staged by tools/stage_deploy.py: upload exactly these files.\n"
                                           ".gcloudignore\nSTAGED_FILES.txt\n", encoding="utf-8")
        if local_test:
            (out / "UNAPPROVED_LOCAL_TEST_ONLY").write_text("Do not build or deploy this context to Google Cloud.\n")

        listing = []
        for p in sorted(x for x in out.rglob("*") if x.is_file()):
            rel = p.relative_to(out).as_posix()
            problem = content_problem(p)
            if problem:
                raise StageError(f"{problem}: {rel}")
            listing.append(f"{sha256(p)}  {p.stat().st_size:>9}  {rel}")
        (out / "STAGED_FILES.txt").write_text("\n".join(listing) + "\n", encoding="utf-8")
        return notes + [f"{len(listing)} files staged in {out}"]
    except Exception:
        shutil.rmtree(out, ignore_errors=True)
        raise


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    p.add_argument("--allowlist", default=str(REPO / "deploy" / "poc-allowlist.json"))
    p.add_argument("--mappings", default=str(REPO / "mappings"))
    p.add_argument("--templates", required=True, help="directory of extracted templates (e.g. .local/generated/templates)")
    p.add_argument("--out", required=True, help="context directory inside .local/ (recreated)")
    p.add_argument("--unapproved-local-test", action="store_true",
                   help="stage without approvals for a LOCAL docker test; the context is marked non-deployable")
    p.add_argument("--today", help=argparse.SUPPRESS)
    a = p.parse_args(argv)
    today = dt.date.fromisoformat(a.today) if a.today else dt.date.today()
    try:
        for line in stage(Path(a.allowlist), Path(a.mappings), Path(a.templates), Path(a.out),
                          a.unapproved_local_test, today):
            print(line)
    except (StageError, KeyError, json.JSONDecodeError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
