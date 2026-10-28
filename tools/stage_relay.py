#!/usr/bin/env python3
"""Stage the minimal, reviewable build context of the relay image (docs/15).

Context: engine/pom.xml, engine/src/** (tests run inside the build), mappings/**.json (lets the unit tests that read
manifests run; not used by the relay at runtime), deploy/relay/{Dockerfile,cloudbuild.yaml}. No PDF, no export, no
secret, no webhook URL. Output must be inside <repo>/.local/.
Usage: python3 tools/stage_relay.py --out .local/deploy-relay
"""
from __future__ import annotations

import argparse
import json
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from stage_admin import FORBIDDEN_SUFFIXES, FORBIDDEN_TEXT, REPO, TEXT_SUFFIXES, StageError, sha256  # noqa: E402


def stage(out: Path) -> dict:
    out = out.resolve()
    if (REPO / ".local").resolve() not in out.parents:
        raise StageError("--out must be inside <repo>/.local/")
    if out.exists():
        shutil.rmtree(out)
    ctx = out / "context"
    try:
        files = [("engine/pom.xml", REPO / "engine/pom.xml"), ("deploy/relay/Dockerfile", REPO / "deploy/relay/Dockerfile"),
                 ("deploy/relay/cloudbuild.yaml", REPO / "deploy/relay/cloudbuild.yaml")]
        for base in ("engine/src", "mappings"):
            files += [(str(p.relative_to(REPO)), p) for p in sorted((REPO / base).rglob("*")) if p.is_file()]
        for rel, src in files:
            if src.suffix.lower() in FORBIDDEN_SUFFIXES:
                raise StageError(f"forbidden file type in the relay context: {rel}")
            if src.suffix in TEXT_SUFFIXES and FORBIDDEN_TEXT.search(src.read_text(encoding="utf-8", errors="replace")):
                raise StageError(f"forbidden content in {rel}")
            dst = ctx / rel
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(src, dst)
        shutil.copy2(REPO / "deploy/relay/cloudbuild.yaml", ctx / "cloudbuild.yaml")
        staged = sorted(p for p in ctx.rglob("*") if p.is_file())
        (ctx / "STAGED_FILES.txt").write_text("".join(f"{sha256(p)} {p.stat().st_size} {p.relative_to(ctx)}\n" for p in staged))
        summary = {"contextFiles": len(staged) + 1, "pdfsInContext": 0}
        (out / "STAGE_SUMMARY.json").write_text(json.dumps(summary, indent=2) + "\n")
        return summary
    except BaseException:
        shutil.rmtree(out, ignore_errors=True)
        raise


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", required=True, type=Path)
    try:
        print(json.dumps(stage(ap.parse_args().out), indent=2))
    except StageError as e:
        print(f"stage_relay: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
