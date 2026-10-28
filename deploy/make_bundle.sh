#!/usr/bin/env bash
# Packages the approved staged context with the deployment script for Cloud Shell:
#   .local/deploy/rsfa-end-forms-bundle.tar.gz (+ .sha256)  ->  bundle/{deploy_poc.sh, verify_pdf.py, BUNDLE_INFO.json,
#     fixture.request.json, expected_pdf_sha256.txt,                       (/generate check, BNZ Boarder)
#     v1-bnz.request.json, expected_v1_pdf_sha256.txt,                     (/v1 check, revision 3)
#     v1-unknown-bank.request.json, v1-asb-candidates.request.json,        (/v1 refusals)
#     context/}
# Prerequisites: tools/stage_deploy.py (approved) and `cd engine && mvn -B package` on the same, clean commit.
# The archive is reproducible (sorted entries, owner 0, gzip -n, every mtime = the commit time): the same commit gives
# the same SHA-256. The mtime must be a real date: Maven's resources plugin copies a file only when it is newer than
# the (missing) target, i.e. newer than epoch 0, so an epoch-0 mtime silently drops every resource from the build
# (revision-3 Cloud Build failure 2026-10-28, "routing table missing from the build"; D-037).
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
MTIME=$(git -C "$ROOT" log -1 --format=%ct)
tar --sort=name --mtime="@$MTIME" --owner=0 --group=0 --numeric-owner -cf - -C "$WORK" bundle \
  | gzip -n -9 > "$WORK/rsfa-end-forms-bundle.tar.gz"
# Self-check on the EXTRACTED archive (what Cloud Shell and Cloud Build actually get).
CHECK=$(mktemp -d "$WORK/check.XXXX"); trap 'rm -rf "$CHECK"' EXIT
tar -xzf "$WORK/rsfa-end-forms-bundle.tar.gz" -C "$CHECK"
python3 - "$CHECK/bundle" "$MTIME" <<'PY' || { echo "bundle self-check failed" >&2; exit 1; }
import os, sys
root, mtime = sys.argv[1], int(sys.argv[2])
bad = [p for d, _, fs in os.walk(root) for p in (os.path.join(d, f) for f in fs) if int(os.stat(p).st_mtime) != mtime]
old = mtime < 946684800  # 2000-01-01
need = ["context/engine/src/main/resources/routing/masterboard-v1.json", "context/engine/src/main/resources/ui/index.html",
        "context/engine/src/main/resources/ui/forms.json", "context/engine/Dockerfile", "context/cloudbuild.yaml"]
missing = [n for n in need if not os.path.isfile(os.path.join(root, n))]
print(f"self-check: {sum(len(f) for _, _, f in os.walk(root))} files, mtime {mtime}, wrong mtimes {len(bad)}, missing {missing}")
sys.exit(1 if bad or old or missing else 0)
PY
if command -v gcloud >/dev/null; then  # exactly what `gcloud builds submit` would upload (local listing, nothing is sent)
  UP=$(gcloud meta list-files-for-upload "$CHECK/bundle/context" 2>/dev/null | sort)
  echo "$UP" | grep -qx 'engine/src/main/resources/routing/masterboard-v1.json' \
    || { echo "gcloud upload listing lacks the routing table" >&2; exit 1; }
  echo "self-check: gcloud would upload $(echo "$UP" | wc -l) files, routing table and UI included"
fi
( cd "$WORK" && sha256sum rsfa-end-forms-bundle.tar.gz > rsfa-end-forms-bundle.tar.gz.sha256 )
echo "bundle: $WORK/rsfa-end-forms-bundle.tar.gz"
echo "sha256: $(cut -d' ' -f1 "$WORK/rsfa-end-forms-bundle.tar.gz.sha256")"
echo "expected PDF sha256: /generate $(cat "$OUT/expected_pdf_sha256.txt"), /v1 $(cat "$OUT/expected_v1_pdf_sha256.txt")"
