#!/usr/bin/env bash
# Packages the relay for Cloud Shell (docs/15): .local/deploy-relay/rsfa-end-forms-relay-bundle.tar.gz (+ .sha256)
#   -> relay-bundle/{deploy_relay.sh, rollback_relay.sh, replay-lifecycle.json, BUNDLE_INFO.json, context/}
# Prerequisite on the same clean commit: python3 tools/stage_relay.py --out .local/deploy-relay
# Reproducible (sorted entries, owner 0, gzip -n, every mtime = the commit time; D-037).
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
WORK=${1:-$ROOT/.local/deploy-relay}
OUT=$WORK/relay-bundle
[ -f "$WORK/context/STAGED_FILES.txt" ] || { echo "run tools/stage_relay.py first" >&2; exit 1; }
[ -z "$(git -C "$ROOT" status --porcelain -- engine deploy mappings tools/stage_relay.py)" ] || { echo "commit first" >&2; exit 1; }
python3 - "$WORK/context" "$ROOT" <<'PY' || { echo "the staged context is stale: re-run tools/stage_relay.py" >&2; exit 1; }
import hashlib, sys
from pathlib import Path
ctx, root = Path(sys.argv[1]), Path(sys.argv[2])
staged = {l.split(" ", 2)[2].strip(): l.split(" ", 1)[0] for l in (ctx / "STAGED_FILES.txt").read_text().splitlines() if l.strip()}
repo = {str(p.relative_to(root)) for base in ("engine/src", "mappings") for p in (root / base).rglob("*") if p.is_file()}
repo |= {"engine/pom.xml", "deploy/relay/Dockerfile", "deploy/relay/cloudbuild.yaml"}
diff = [r for r in sorted(repo) if staged.get(r) != hashlib.sha256((root / r).read_bytes()).hexdigest()]
diff += [s for s in staged if s not in repo and s != "cloudbuild.yaml"]
print(f"staged context vs repository: {len(staged)} files, {len(diff)} differ")
sys.exit(1 if diff else 0)
PY
rm -rf "$OUT"; mkdir -p "$OUT"
cp -r "$WORK/context" "$OUT/context"
cp "$ROOT/deploy/relay/deploy_relay.sh" "$ROOT/deploy/relay/rollback_relay.sh" "$ROOT/deploy/relay/replay-lifecycle.json" "$OUT/"
python3 - "$OUT" "$ROOT" <<'PY'
import hashlib, json, subprocess, sys
from pathlib import Path
out, root = Path(sys.argv[1]), Path(sys.argv[2])
commit = subprocess.run(["git", "-C", str(root), "rev-parse", "HEAD"], capture_output=True, text=True).stdout.strip()
files = sorted(p for p in out.rglob("*") if p.is_file())
(out / "BUNDLE_INFO.json").write_text(json.dumps({"commit": commit, "service": "rsfa-end-forms-relay",
    "engineExpected": "rsfa-end-forms-engine-00005-rih (one change: relay SA invoker)",
    "files": {str(p.relative_to(out)): hashlib.sha256(p.read_bytes()).hexdigest() for p in files}}, indent=2) + "\n")
PY
MTIME=$(git -C "$ROOT" log -1 --format=%ct)
tar --sort=name --mtime="@$MTIME" --owner=0 --group=0 --numeric-owner -cf - -C "$WORK" relay-bundle | gzip -n -9 > "$WORK/rsfa-end-forms-relay-bundle.tar.gz"
CHECK=$(mktemp -d "$WORK/check.XXXX"); trap 'rm -rf "$CHECK"' EXIT
tar -xzf "$WORK/rsfa-end-forms-relay-bundle.tar.gz" -C "$CHECK"
python3 - "$CHECK/relay-bundle" "$MTIME" <<'PY' || { echo "bundle self-check failed" >&2; exit 1; }
import os, sys
root, mtime = sys.argv[1], int(sys.argv[2])
allf = [os.path.join(d, f) for d, _, fs in os.walk(root) for f in fs]
bad = [p for p in allf if int(os.stat(p).st_mtime) != mtime]
need = ["context/engine/src/main/resources/routing/masterboard-v1.json", "context/deploy/relay/Dockerfile", "context/cloudbuild.yaml",
        "deploy_relay.sh", "rollback_relay.sh"]
missing = [n for n in need if not os.path.isfile(os.path.join(root, n))]
pdfs = [p for p in allf if p.lower().endswith(".pdf")]
print(f"self-check: {len(allf)} files, wrong mtimes {len(bad)}, missing {missing}, PDFs {len(pdfs)}")
sys.exit(1 if bad or missing or pdfs or mtime < 946684800 else 0)
PY
( cd "$WORK" && sha256sum rsfa-end-forms-relay-bundle.tar.gz > rsfa-end-forms-relay-bundle.tar.gz.sha256 )
echo "bundle: $WORK/rsfa-end-forms-relay-bundle.tar.gz"
echo "sha256: $(cut -d' ' -f1 "$WORK/rsfa-end-forms-relay-bundle.tar.gz.sha256")"
