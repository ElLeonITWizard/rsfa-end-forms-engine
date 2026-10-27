#!/usr/bin/env bash
# Packages the approved staged context with the deployment script for Cloud Shell:
#   .local/deploy/rsfa-end-forms-poc-bundle.tar.gz  ->  bundle/{deploy_poc.sh, verify_pdf.py, fixture.request.json,
#                                                      expected_pdf_sha256.txt, context/}
# Prerequisites: tools/stage_deploy.py (approved) and `cd engine && mvn -B package` on the same commit.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CTX=${1:-$ROOT/.local/deploy/context}
OUT=$ROOT/.local/deploy/bundle
[ -f "$CTX/STAGED_FILES.txt" ] || { echo "no staged context at $CTX" >&2; exit 1; }
[ ! -e "$CTX/UNAPPROVED_LOCAL_TEST_ONLY" ] || { echo "context is not approved" >&2; exit 1; }
rm -rf "$OUT"; mkdir -p "$OUT"
cp -r "$CTX" "$OUT/context"
cp "$ROOT/deploy/deploy_poc.sh" "$ROOT/deploy/verify_pdf.py" "$OUT/"
cp "$ROOT/tests/fixtures/bnz-boarder.request.json" "$OUT/fixture.request.json"
MANIFEST=$(find "$CTX/mappings" -name '*.json' | head -1)
java -jar "$ROOT/engine/target/end-forms-engine.jar" fill --manifest "$MANIFEST" --templates "$CTX/poc-templates" \
  --data "$OUT/fixture.request.json" --out "$ROOT/.local/deploy/expected.pdf" >/dev/null 2>&1
sha256sum "$ROOT/.local/deploy/expected.pdf" | cut -d' ' -f1 > "$OUT/expected_pdf_sha256.txt"
rm -f "$ROOT/.local/deploy/expected.pdf"
tar -czf "$ROOT/.local/deploy/rsfa-end-forms-poc-bundle.tar.gz" -C "$ROOT/.local/deploy" bundle
echo "bundle: $ROOT/.local/deploy/rsfa-end-forms-poc-bundle.tar.gz (expected PDF sha256 $(cat "$OUT/expected_pdf_sha256.txt"))"
