#!/usr/bin/env bash
# Packages the private admin service for Cloud Shell (D-041, docs/14):
#   .local/deploy-admin/rsfa-end-forms-admin-bundle.tar.gz (+ .sha256) -> admin-bundle/{
#     deploy_admin.sh, rollback_admin.sh, bucket-lifecycle.json, BUNDLE_INFO.json,
#     config/monday-schema.json (sanitised), example-bank-form.pdf (FICTIONAL form for the browser check),
#     context/ (staged build context: source, manifests, admin Dockerfile; no PDF)}
# Prerequisites, on the same clean commit:
#   python3 tools/stage_admin.py --schema .local/reference/masterboard-columns-<date>.json --out .local/deploy-admin
#   (cd engine && mvn -B package)
# Reproducible like the engine bundle (D-037): sorted entries, owner 0, gzip -n, every mtime = the commit time.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
WORK=${1:-$ROOT/.local/deploy-admin}
OUT=$WORK/admin-bundle
JAR=$ROOT/engine/target/end-forms-engine.jar
[ -f "$WORK/context/STAGED_FILES.txt" ] && [ -f "$WORK/config/monday-schema.json" ] || { echo "run tools/stage_admin.py first" >&2; exit 1; }
[ -f "$JAR" ] || { echo "build the jar first: (cd engine && mvn -B package)" >&2; exit 1; }
[ -z "$(git -C "$ROOT" status --porcelain -- engine deploy mappings curation tools/stage_admin.py)" ] \
  || { echo "uncommitted changes in engine/deploy/mappings/curation: commit first" >&2; exit 1; }
rm -rf "$OUT"; mkdir -p "$OUT/config"
cp -r "$WORK/context" "$OUT/context"
cp "$WORK/config/monday-schema.json" "$OUT/config/"
cp "$ROOT/deploy/admin/deploy_admin.sh" "$ROOT/deploy/admin/rollback_admin.sh" "$ROOT/deploy/admin/bucket-lifecycle.json" "$OUT/"
java -jar "$JAR" example-form --out "$OUT/example-bank-form.pdf" >/dev/null
python3 - "$OUT" "$ROOT" <<'PY'
import hashlib, json, subprocess, sys
from pathlib import Path
out, root = Path(sys.argv[1]), Path(sys.argv[2])
commit = subprocess.run(["git", "-C", str(root), "rev-parse", "HEAD"], capture_output=True, text=True).stdout.strip()
files = sorted(p for p in out.rglob("*") if p.is_file())
info = {"commit": commit, "service": "rsfa-end-forms-admin", "engineExpected": "rsfa-end-forms-engine-00005-rih (read only)",
        "files": {str(p.relative_to(out)): hashlib.sha256(p.read_bytes()).hexdigest() for p in files}}
(out / "BUNDLE_INFO.json").write_text(json.dumps(info, indent=2) + "\n")
PY
MTIME=$(git -C "$ROOT" log -1 --format=%ct)
tar --sort=name --mtime="@$MTIME" --owner=0 --group=0 --numeric-owner -cf - -C "$WORK" admin-bundle \
  | gzip -n -9 > "$WORK/rsfa-end-forms-admin-bundle.tar.gz"
CHECK=$(mktemp -d "$WORK/check.XXXX"); trap 'rm -rf "$CHECK"' EXIT
tar -xzf "$WORK/rsfa-end-forms-admin-bundle.tar.gz" -C "$CHECK"
python3 - "$CHECK/admin-bundle" "$MTIME" <<'PY' || { echo "bundle self-check failed" >&2; exit 1; }
import os, sys
root, mtime = sys.argv[1], int(sys.argv[2])
allf = [os.path.join(d, f) for d, _, fs in os.walk(root) for f in fs]
bad = [p for p in allf if int(os.stat(p).st_mtime) != mtime]
need = ["context/engine/src/main/resources/ui/admin/admin.js", "context/engine/src/main/resources/ui/admin/index.html",
        "context/engine/src/main/resources/routing/masterboard-v1.json", "context/deploy/admin/Dockerfile",
        "context/cloudbuild.yaml", "config/monday-schema.json", "deploy_admin.sh", "rollback_admin.sh"]
missing = [n for n in need if not os.path.isfile(os.path.join(root, n))]
pdfs = [p for p in allf if p.lower().endswith(".pdf") and not p.endswith("/example-bank-form.pdf")]
print(f"self-check: {len(allf)} files, mtime {mtime}, wrong mtimes {len(bad)}, missing {missing}, other PDFs {len(pdfs)}")
sys.exit(1 if bad or mtime < 946684800 or missing or pdfs else 0)
PY
( cd "$WORK" && sha256sum rsfa-end-forms-admin-bundle.tar.gz > rsfa-end-forms-admin-bundle.tar.gz.sha256 )
echo "bundle: $WORK/rsfa-end-forms-admin-bundle.tar.gz"
echo "sha256: $(cut -d' ' -f1 "$WORK/rsfa-end-forms-admin-bundle.tar.gz.sha256")"
