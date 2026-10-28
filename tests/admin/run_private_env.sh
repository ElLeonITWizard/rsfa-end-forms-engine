#!/usr/bin/env bash
# Production-like LOCAL environment of the private admin service (D-041, docs/14 §8). Nothing leaves this machine.
#   the real admin image (deploy/admin/Dockerfile, target admin-prebuilt, from the staged context + host-built jar)
#   x2 instances (ports 8091, 8092; ENGINE_ROLE=admin; read-only root filesystem; non-root user)
#   + a fake Cloud Storage JSON API (port 4443) seeded with the sanitised Monday schema
#   + an IAP stand-in (port 8443) that plays Google sign-in and IAP, with its own signing key.
# Usage: bash tests/admin/run_private_env.sh up|down     (then: node tests/admin/private_admin_e2e.js)
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
WORK=$ROOT/.local/private-admin-env
IMAGE=${IMAGE:-rsfa-end-forms-admin:local}   # IMAGE=<tag> SKIP_BUILD=1: test an image built elsewhere (e.g. the bundle rehearsal)
AUD=/projects/123456789012/locations/australia-southeast1/services/rsfa-end-forms-admin
# Fictional identities: two admin users of the app, one IAP-only account (passes IAP, not on the app allowlist).
USERS=${USERS:-admin.test@firbot.example.org:admin,approver.test@rsfa.example.org:approver,viewer.test@rsfa.example.org:viewer}
IAP_USERS=${IAP_USERS:-admin.test@firbot.example.org,approver.test@rsfa.example.org,viewer.test@rsfa.example.org,iap-only.test@rsfa.example.org}

down() {
  docker rm -f rsfa-admin-a rsfa-admin-b >/dev/null 2>&1 || true
  for f in "$WORK"/*.pid; do [ -f "$f" ] && kill "$(cat "$f")" 2>/dev/null || true; rm -f "$f"; done
}
if [ "${1:-up}" = down ]; then down; echo "stopped"; exit 0; fi
down
mkdir -p "$WORK"
[ -f "$ROOT/.local/deploy-admin/context/STAGED_FILES.txt" ] || python3 "$ROOT/tools/stage_admin.py" \
  --schema "$(ls "$ROOT"/.local/reference/masterboard-columns-*.json | sort | tail -1)" --out "$ROOT/.local/deploy-admin"
[ -f "$ROOT/engine/target/end-forms-engine.jar" ] || (cd "$ROOT/engine" && mvn -B -q package -DskipTests)
if [ "${SKIP_BUILD:-0}" != 1 ]; then
rm -rf "$WORK/ctx"; cp -r "$ROOT/.local/deploy-admin/context" "$WORK/ctx"
mkdir -p "$WORK/ctx/engine/target"; cp "$ROOT/engine/target/end-forms-engine.jar" "$WORK/ctx/engine/target/"
SECRET=(); [ -f /root/.ccr/ca-bundle.crt ] && SECRET=(--secret id=build_ca,src=/root/.ccr/ca-bundle.crt)
DOCKER_BUILDKIT=1 docker build -q --network host "${SECRET[@]}" \
  --build-arg http_proxy="${http_proxy:-${HTTP_PROXY:-}}" --build-arg https_proxy="${https_proxy:-${HTTPS_PROXY:-}}" \
  -f "$WORK/ctx/deploy/admin/Dockerfile" --target admin-prebuilt -t "$IMAGE" "$WORK/ctx" >/dev/null
fi
echo "image $IMAGE: $(docker image inspect "$IMAGE" --format '{{.Id}}' | cut -c1-19)"

node "$ROOT/tests/admin/fake_gcs.js" --port 4443 --bucket rsfa-admin-local --token local-token \
  --seed "config/monday-schema.json=$ROOT/.local/deploy-admin/config/monday-schema.json" > "$WORK/gcs.log" 2>&1 &
echo $! > "$WORK/gcs.pid"
node "$ROOT/tests/admin/iap_standin.js" --port 8443 --upstreams http://127.0.0.1:8091,http://127.0.0.1:8092 \
  --audience "$AUD" --iap-users "$IAP_USERS" > "$WORK/iap.log" 2>&1 &
echo $! > "$WORK/iap.pid"
sleep 1
for n in a:8091 b:8092; do
  docker run -d --name "rsfa-admin-${n%%:*}" --network host --read-only --tmpfs /tmp --user 10001 \
    -e PORT="${n##*:}" -e ENGINE_BIND=127.0.0.1 \
    -e ADMIN_EMULATION=1 -e ADMIN_USERS="$USERS" -e IAP_AUDIENCE="$AUD" -e ADMIN_BUCKET=rsfa-admin-local \
    -e ADMIN_GCS_ENDPOINT=http://127.0.0.1:4443 -e ADMIN_GCS_TOKEN=local-token -e IAP_KEYS_URL=http://127.0.0.1:8443/_iap/jwk \
    "$IMAGE" >/dev/null
done
for p in 8091 8092; do
  for i in $(seq 1 40); do curl -s -o /dev/null "http://127.0.0.1:$p/" && break; sleep 0.5; done
done
echo "admin instances: $(docker ps --filter name=rsfa-admin- --format '{{.Names}} {{.Status}}' | tr '\n' ';')"
echo "open http://127.0.0.1:8443/ (IAP stand-in; sign in as one of: ${IAP_USERS//,/ })"
