#!/usr/bin/env bash
# Packages the approved staged context with the deployment script for Cloud Shell:
#   .local/deploy/rsfa-end-forms-bundle.tar.gz (+ .sha256)  ->  bundle/{deploy_poc.sh, verify_pdf.py, BUNDLE_INFO.json,
#     fixture.request.json, expected_pdf_sha256.txt,                       (/generate check, BNZ Boarder)
#     v1-bnz.request.json, expected_v1_pdf_sha256.txt,                     (/v1 check, revision 3)
#     v1-unknown-bank.request.json, v1-asb-candidates.request.json,        (/v1 refusals)
#     context/}
# Prerequisites: tools/stage_deploy.py (approved) and `cd engine && mvn -B package` on the same, clean commit.
# The archive is reproducible (sorted entries, fixed mtime/owner, gzip -n): the same inputs give the same SHA-256.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CTX=${1:-$ROOT/.local/deploy/context}
WORK=$ROOT/.local/deploy
OUT=$WORK/bundle
JAR=$ROOT/engine/target/end-forms-engine.jar
[ -f "$CTX/STAGED_FILES.txt" ] || { echo "no staged context at $CTX" >&2; exit 1; }
[ ! -e "$CTX/UNAPPROVED_LOCAL_TEST_ONLY" ] || { echo "context is not approved" >&2; exit 1; }
[ -z "$(git -C "$ROOT" status --porcelain -- engine deploy mappings contracts tests/fixtures)" ] \
  || { echo "uncommitted changes in engine/deploy/mappings/contracts/fixtures: commit first" >&2; exit 1; }
rm -rf "$OUT"; mkdir -p "$OUT"
cp -r "$CTX" "$OUT/context"
cp "$ROOT/deploy/deploy_poc.sh" "$ROOT/deploy/verify_pdf.py" "$OUT/"
cp "$ROOT/tests/fixtures/bnz-boarder.request.json" "$OUT/fixture.request.json"
cp "$ROOT/contracts/examples/request.bank-bnz.json" "$OUT/v1-bnz.request.json"
cp "$ROOT/contracts/examples/request.negative-unknown-bank.json" "$OUT/v1-unknown-bank.request.json"
cp "$ROOT/contracts/examples/request.bank-asb-acceptance.json" "$OUT/v1-asb-candidates.request.json"
MANIFEST=$(find "$CTX/mappings" -name '*.json' | head -1)
fill() { # request.json today -> sha256 of the PDF the staged manifest and template produce
  java -jar "$JAR" fill --manifest "$MANIFEST" --templates "$CTX/poc-templates" --data "$1" --today "$2" \
    --out "$WORK/expected.pdf" >/dev/null 2>&1
  sha256sum "$WORK/expected.pdf" | cut -d' ' -f1; rm -f "$WORK/expected.pdf"
}
today() { python3 -c "import json,sys;print(json.load(open(sys.argv[1]))['today'])" "$1"; }
fill "$OUT/fixture.request.json" "$(today "$OUT/fixture.request.json")" > "$OUT/expected_pdf_sha256.txt"
fill "$OUT/v1-bnz.request.json" "$(today "$OUT/v1-bnz.request.json")" > "$OUT/expected_v1_pdf_sha256.txt"
python3 - "$OUT" "$ROOT" <<'PY'
import hashlib, json, subprocess, sys
from pathlib import Path
out, root = Path(sys.argv[1]), Path(sys.argv[2])
commit = subprocess.run(["git", "-C", str(root), "rev-parse", "HEAD"], capture_output=True, text=True).stdout.strip()
files = sorted(p for p in out.rglob("*") if p.is_file())
info = {
    "commit": commit,
    "allowlist": json.loads((root / "deploy/poc-allowlist.json").read_text())["templates"][0]["templateId"],
    "expectedPdfSha256": {"generate": (out / "expected_pdf_sha256.txt").read_text().strip(),
                          "v1": (out / "expected_v1_pdf_sha256.txt").read_text().strip()},
    "files": {str(p.relative_to(out)): hashlib.sha256(p.read_bytes()).hexdigest() for p in files},
}
(out / "BUNDLE_INFO.json").write_text(json.dumps(info, indent=2) + "\n")
PY
tar --sort=name --mtime='@0' --owner=0 --group=0 --numeric-owner -cf - -C "$WORK" bundle \
  | gzip -n -9 > "$WORK/rsfa-end-forms-bundle.tar.gz"
( cd "$WORK" && sha256sum rsfa-end-forms-bundle.tar.gz > rsfa-end-forms-bundle.tar.gz.sha256 )
echo "bundle: $WORK/rsfa-end-forms-bundle.tar.gz"
echo "sha256: $(cut -d' ' -f1 "$WORK/rsfa-end-forms-bundle.tar.gz.sha256")"
echo "expected PDF sha256: /generate $(cat "$OUT/expected_pdf_sha256.txt"), /v1 $(cat "$OUT/expected_v1_pdf_sha256.txt")"
