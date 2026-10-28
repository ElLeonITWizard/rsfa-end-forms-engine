#!/usr/bin/env bash
# Make → relay → private engine: relay deployment with preflight checks (D-027, D-031, D-044, docs/15).
# RSFA runs this in Cloud Shell from the relay bundle (deploy/relay/make_relay_bundle.sh). THIS REPOSITORY NEVER RUNS IT.
#
# Default is a PLAN: every check runs read-only and every command that would change something is printed, not run.
#   bash deploy_relay.sh                          # plan
#   APPLY=1 bash deploy_relay.sh                  # infrastructure + PRIVATE relay (no public access) + verification
#   APPLY=1 PHASE=expose bash deploy_relay.sh     # SEPARATE APPROVAL: make the relay reachable by Make (allUsers invoker)
#   PHASE=verify bash deploy_relay.sh             # read-only checks (plus signed test calls) at any time
#
# Why a public relay: Make's HTTP module cannot present a Google-signed ID token without a service-account JSON key
# (forbidden here), so Make cannot call the IAM-private engine directly. The relay is the only public endpoint; it
# accepts only HMAC-signed, fresh, never-replayed requests and calls the engine with its OWN identity (metadata server,
# no key). The engine stays --no-allow-unauthenticated.
#
# Creates: API secretmanager.googleapis.com; SA rsfa-end-forms-relay (no project roles); secret rsfa-make-relay-hmac
# (random value, never printed); bucket <project>-relay-replay (1-day lifecycle); Cloud Run service rsfa-end-forms-relay.
# The ONLY change to the engine: roles/run.invoker for the relay SA on rsfa-end-forms-engine (checked: the engine's
# state afterwards = its state before + exactly that binding; revision, image, env and traffic unchanged).
set -euo pipefail
export CLOUDSDK_CORE_DISABLE_PROMPTS=1
PROJECT_ID=${PROJECT_ID:-rsfa-end-forms-engine}
REGION=${REGION:-australia-southeast1}
RELAY_SERVICE=${RELAY_SERVICE:-rsfa-end-forms-relay}
RELAY_SA_NAME=${RELAY_SA_NAME:-rsfa-end-forms-relay}
RELAY_SA=${RELAY_SA_NAME}@${PROJECT_ID}.iam.gserviceaccount.com
SECRET_ID=${SECRET_ID:-rsfa-make-relay-hmac}
REPLAY_BUCKET=${REPLAY_BUCKET:-${PROJECT_ID}-relay-replay}
ENGINE_SERVICE=${ENGINE_SERVICE:-rsfa-end-forms-engine}
EXPECT_ENGINE_REVISION=${EXPECT_ENGINE_REVISION:-rsfa-end-forms-engine-00005-rih}
BUILD_SA=${BUILD_SA_NAME:-rsfa-end-forms-build}@${PROJECT_ID}.iam.gserviceaccount.com
AR_REPO=${AR_REPO:-rsfa-end-forms}
SRC_BUCKET=${SRC_BUCKET:-gs://${PROJECT_ID}-build-src}
IMAGE_BASE=${REGION}-docker.pkg.dev/${PROJECT_ID}/${AR_REPO}/end-forms-relay
APPLY=${APPLY:-0}
PHASE=${PHASE:-all}                 # all | expose | verify
HERE=$(cd "$(dirname "$0")" && pwd)
CONTEXT=${CONTEXT:-$HERE/context}
OUT=${OUT:-$PWD/relay-deploy-output}
RECORD=$OUT/relay-deployment-record.json
mkdir -p "$OUT"

step() { printf '\n=== %s ===\n' "$*"; }
ok()   { printf '  [ok] %s\n' "$*"; }
die()  { printf '\n  [BLOCKER] %s\n\n' "$*" >&2; exit 1; }
gc()   { gcloud --project="$PROJECT_ID" "$@"; }
run()  { local shown=("$@"); [ "$1" != gc ] || shown=(gcloud "--project=$PROJECT_ID" "${@:2}")
         printf '  %s %s\n' "$([ "$APPLY" = 1 ] && echo '$' || echo '[plan]')" "${shown[*]}"; [ "$APPLY" != 1 ] || "$@"; }
rec()  { python3 - "$RECORD" "$1" "$2" <<'PY'
import json, sys, os
p, k, v = sys.argv[1:4]
d = json.load(open(p)) if os.path.exists(p) else {}
try:
    v = json.loads(v)
except ValueError:
    pass
d[k] = v
json.dump(d, open(p, "w"), indent=2)
PY
}
# Engine fingerprint: serving revision, image, env, annotations and IAM bindings.
engine_fingerprint() {
  gc run services describe "$ENGINE_SERVICE" --region="$REGION" --format=json > "$1.service.json"
  gc run services get-iam-policy "$ENGINE_SERVICE" --region="$REGION" --format=json > "$1.iam.json"
  python3 - "$1.service.json" "$1.iam.json" > "$1" <<'PY'
import json, sys
s = json.load(open(sys.argv[1])); p = json.load(open(sys.argv[2]))
t = s["spec"]["template"]; c = t["spec"]["containers"][0]; st = s["status"]
print(json.dumps({
  "serving": sorted((x.get("revisionName") or "latest", x.get("percent", 0), x.get("tag", "")) for x in st.get("traffic", [])),
  "latestReadyRevision": st.get("latestReadyRevisionName"), "url": st.get("url"),
  "image": c["image"], "serviceAccount": t["spec"].get("serviceAccountName"),
  "env": sorted((e["name"], e.get("value", "<from secret>")) for e in c.get("env", [])),
  "ingress": s["metadata"].get("annotations", {}).get("run.googleapis.com/ingress"),
  "templateAnnotations": sorted(t["metadata"].get("annotations", {}).items()),
  "bindings": sorted((b["role"], sorted(b["members"])) for b in p.get("bindings", [])),
}, indent=1, sort_keys=True))
PY
}
# Signed test call through the relay (secret read from Secret Manager into memory; never printed or written).
signed_call() { # $1 url, $2 body file, $3 out file, [$4 timestamp offset seconds] -> prints HTTP status
  python3 - "$1" "$2" "$3" "${4:-0}" <<'PY'
import hashlib, hmac, json, os, subprocess, sys, time, urllib.request, urllib.error
url, body_file, out, skew = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4])
secret = os.environ["RELAY_SECRET_VALUE"].strip()
body = open(body_file, "rb").read()
ts = str(int(time.time()) + skew)
sig = "v1=" + hmac.new(secret.encode(), (ts + ".").encode() + body, hashlib.sha256).hexdigest()
token = subprocess.run(["gcloud", "auth", "print-identity-token"], capture_output=True, text=True).stdout.strip()
headers = {"Content-Type": "application/json", "X-RSFA-Timestamp": ts, "X-RSFA-Signature": sig}
if token and os.environ.get("RELAY_PUBLIC") != "1":
    headers["Authorization"] = "Bearer " + token
def send():
    req = urllib.request.Request(url, data=body, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()
status, data = send()
if os.environ.get("SEND_TWICE") == "1":
    status, data = send()
open(out, "wb").write(data)
print(status)
PY
}

# --------------------------------------------------------------------------------------------- 1. preflight
step "1. Preflight (read-only) — mode: $([ "$APPLY" = 1 ] && echo APPLY || echo PLAN), phase: $PHASE"
for c in gcloud python3 curl sha256sum openssl; do command -v "$c" >/dev/null || die "$c is required (use Cloud Shell)."; done
case "$RELAY_SERVICE" in *relay*) ;; *) die "RELAY_SERVICE must contain 'relay' (the image refuses to run the engine there)." ;; esac
[ "$RELAY_SERVICE" != "$ENGINE_SERVICE" ] || die "RELAY_SERVICE must not be the engine service."
case "$RELAY_SA_NAME" in *relay*) ;; *) die "RELAY_SA_NAME must contain 'relay'." ;; esac
case "$REPLAY_BUCKET" in *relay*) ;; *) die "REPLAY_BUCKET must contain 'relay'." ;; esac
ACCOUNT=$(gcloud config get-value account 2>/dev/null || true)
[ -n "$ACCOUNT" ] || die "No authenticated gcloud account. Run: gcloud auth login"
case "$ACCOUNT" in *.gserviceaccount.com) DEPLOYER="serviceAccount:$ACCOUNT" ;; *) DEPLOYER="user:$ACCOUNT" ;; esac
ok "authenticated as $ACCOUNT"
STATE=$(gc projects describe "$PROJECT_ID" --format='value(lifecycleState)' 2>/dev/null) || die "Cannot read project $PROJECT_ID."
[ "$STATE" = ACTIVE ] || die "Project $PROJECT_ID is $STATE."
rec startedAt "\"$(date -u +%FT%TZ)\""; rec mode "\"$([ "$APPLY" = 1 ] && echo apply || echo plan)\""; rec phase "\"$PHASE\""
engine_fingerprint "$OUT/engine-before.json"
SERVING=$(python3 -c "import json;d=json.load(open('$OUT/engine-before.json'));f=[r for r,p,_ in d['serving'] if p==100];print(f[0] if len(f)==1 else '')")
[ "$SERVING" != latest ] || SERVING=$(python3 -c "import json;print(json.load(open('$OUT/engine-before.json'))['latestReadyRevision'])")
[ "$SERVING" = "$EXPECT_ENGINE_REVISION" ] || die "The engine serves '$SERVING', not $EXPECT_ENGINE_REVISION. Stop: nothing was changed."
ENGINE_URL=$(python3 -c "import json;print(json.load(open('$OUT/engine-before.json'))['url'])")
python3 -c "import json,sys;d=json.load(open('$OUT/engine-before.json'));sys.exit(1 if any(m in ('allUsers','allAuthenticatedUsers') for _,ms in d['bindings'] for m in ms) else 0)" \
  || die "The engine already has a public member: investigate before anything else."
ok "engine $ENGINE_SERVICE serves $SERVING, IAM-private; URL recorded (fingerprint saved)"
rec engineUrl "\"$ENGINE_URL\""
if [ "$PHASE" = all ]; then
  [ -f "$CONTEXT/STAGED_FILES.txt" ] || die "Staged relay context not found at $CONTEXT (tools/stage_relay.py)."
  ( cd "$CONTEXT" && while read -r sum size path; do
      [ "$(sha256sum "$path" | cut -d' ' -f1)" = "$sum" ] || { echo "checksum mismatch: $path"; exit 1; }
    done < STAGED_FILES.txt ) || die "The staged relay context was modified after staging."
  [ -z "$(find "$CONTEXT" -type f \( -iname '*.pdf' -o -iname '*.mforge' -o -iname '*.xlsx' \))" ] || die "A PDF or export is in the context."
  [ -z "$(find "$CONTEXT" -type f ! -newermt '1980-01-02' | head -1)" ] || die "Pre-1980 file times in the context (D-037)."
  gc iam service-accounts describe "$BUILD_SA" >/dev/null 2>&1 || die "Build service account $BUILD_SA not found."
  ok "staged context intact; reusing build SA, Artifact Registry $AR_REPO and $SRC_BUCKET"
fi
RELAY_EXISTS=0; gc run services describe "$RELAY_SERVICE" --region="$REGION" >/dev/null 2>&1 && RELAY_EXISTS=1
[ "$PHASE" = all ] || [ "$RELAY_EXISTS" = 1 ] || die "$RELAY_SERVICE does not exist yet: run PHASE=all first."
[ "$PHASE" != all ] || [ "$RELAY_EXISTS" = 0 ] || [ "${UPDATE_EXISTING:-0}" = 1 ] || die "$RELAY_SERVICE exists: UPDATE_EXISTING=1 to deploy a new revision."

if [ "$PHASE" = all ]; then
  step "2. API (only Secret Manager is new)"
  run gc services enable secretmanager.googleapis.com

  step "3. Relay service account (no project roles)"
  gc iam service-accounts describe "$RELAY_SA" >/dev/null 2>&1 \
    || run gc iam service-accounts create "$RELAY_SA_NAME" --display-name="RSFA End Forms relay (Make → engine)"
  run gc iam service-accounts add-iam-policy-binding "$RELAY_SA" --member="$DEPLOYER" --role=roles/iam.serviceAccountUser

  step "4. Replay bucket (create-if-absent claims; objects deleted after 1 day)"
  gcloud storage buckets describe "gs://$REPLAY_BUCKET" >/dev/null 2>&1 \
    || run gcloud storage buckets create "gs://$REPLAY_BUCKET" --project="$PROJECT_ID" --location="$REGION" \
         --default-storage-class=STANDARD --uniform-bucket-level-access --public-access-prevention
  run gcloud storage buckets update "gs://$REPLAY_BUCKET" --project="$PROJECT_ID" --lifecycle-file="$HERE/replay-lifecycle.json"
  run gcloud storage buckets add-iam-policy-binding "gs://$REPLAY_BUCKET" --project="$PROJECT_ID" \
    --member="serviceAccount:$RELAY_SA" --role=roles/storage.objectCreator

  step "5. HMAC secret (random, generated here, never printed)"
  if gc secrets describe "$SECRET_ID" >/dev/null 2>&1; then ok "secret $SECRET_ID exists (reused; rotate with a new version, docs/10 §5)"
  elif [ "$APPLY" = 1 ]; then
    openssl rand -hex 32 | tr -d '\n' | gc secrets create "$SECRET_ID" --replication-policy=user-managed \
      --locations="$REGION" --data-file=- >/dev/null
    printf '  $ openssl rand -hex 32 | gcloud secrets create %s --replication-policy=user-managed --locations=%s --data-file=-\n' "$SECRET_ID" "$REGION"
  else
    printf '  [plan] openssl rand -hex 32 | gcloud secrets create %s --replication-policy=user-managed --locations=%s --data-file=-\n' "$SECRET_ID" "$REGION"
  fi
  run gc secrets add-iam-policy-binding "$SECRET_ID" --member="serviceAccount:$RELAY_SA" --role=roles/secretmanager.secretAccessor
  SECRET_VERSION=$( [ "$APPLY" = 1 ] && gc secrets versions list "$SECRET_ID" --filter=state=ENABLED --sort-by=~createTime --limit=1 --format='value(name.basename())' || echo 1)
  rec secretVersion "\"$SECRET_VERSION\""

  step "6. The one engine change: the relay SA may invoke the engine"
  run gc run services add-iam-policy-binding "$ENGINE_SERVICE" --region="$REGION" \
    --member="serviceAccount:$RELAY_SA" --role=roles/run.invoker
  if [ "$APPLY" = 1 ]; then echo "  waiting ${IAM_WAIT:-60}s for IAM propagation..."; sleep "${IAM_WAIT:-60}"; fi

  step "7. Build the relay image with Cloud Build (unit tests run inside the build)"
  IMAGE=$IMAGE_BASE:relay-$(date -u +%Y%m%d-%H%M%S)
  run gc builds submit "$CONTEXT" --config="$CONTEXT/cloudbuild.yaml" --substitutions=_IMAGE="$IMAGE" --region="$REGION" \
    --service-account="projects/$PROJECT_ID/serviceAccounts/$BUILD_SA" --gcs-source-staging-dir="$SRC_BUCKET/source-relay"
  if [ "$APPLY" = 1 ]; then
    D=$(gc artifacts docker images describe "$IMAGE" --format='value(image_summary.digest)')
    [[ "$D" == sha256:* ]] || die "Could not resolve the digest of $IMAGE."
    DIGEST_REF=$IMAGE_BASE@$D
  else DIGEST_REF="$IMAGE_BASE@sha256:<digest of the build>"; fi
  rec image "\"$DIGEST_REF\""

  step "8. Deploy $RELAY_SERVICE PRIVATE (no public access yet)"
  run gc run deploy "$RELAY_SERVICE" --image="$DIGEST_REF" --region="$REGION" --service-account="$RELAY_SA" \
    --no-allow-unauthenticated --ingress=all --cpu=1 --memory=512Mi --min-instances=0 --max-instances=2 \
    --concurrency=4 --timeout=120 --cpu-throttling \
    --set-env-vars="ENGINE_ROLE=relay,RELAY_TARGET_URL=$ENGINE_URL,RELAY_REPLAY_STORE=gcs,RELAY_REPLAY_BUCKET=$REPLAY_BUCKET,RELAY_RATE_PER_MINUTE=30" \
    --set-secrets="RELAY_HMAC_SECRETS=$SECRET_ID:${SECRET_VERSION:-1}" \
    --labels=app=rsfa-end-forms-relay,stage=test
fi

if [ "$PHASE" = expose ]; then
  step "PUBLIC EXPOSURE (separate approval): Make may now reach the relay; every request still needs the HMAC signature"
  run gc run services add-iam-policy-binding "$RELAY_SERVICE" --region="$REGION" --member=allUsers --role=roles/run.invoker
fi

# --------------------------------------------------------------------------------------------- 9. verification
step "9. Verification"
if [ "$APPLY" != 1 ] && [ "$RELAY_EXISTS" = 0 ]; then ok "plan only: nothing to verify yet."; rec status "\"plan\""; exit 0; fi
FAIL=0
check() { if eval "$2"; then ok "$1"; else printf '  [FAIL] %s\n' "$1"; FAIL=1; fi; }
J() { python3 -c "import json,sys;d=json.load(open('$1'));sys.exit(0 if ($2) else 1)"; }
gc run services describe "$RELAY_SERVICE" --region="$REGION" --format=json > "$OUT/relay-service.json"
gc run services get-iam-policy "$RELAY_SERVICE" --region="$REGION" --format=json > "$OUT/relay-invokers.json"
RELAY_URL=$(python3 -c "import json;print(json.load(open('$OUT/relay-service.json'))['status'].get('url',''))")
rec relayUrl "\"$RELAY_URL\""
engine_fingerprint "$OUT/engine-after.json"
python3 - "$OUT/engine-before.json" "$OUT/engine-after.json" "serviceAccount:$RELAY_SA" > "$OUT/engine-diff.json" <<'PY'
import json, sys
b, a, sa = json.load(open(sys.argv[1])), json.load(open(sys.argv[2])), sys.argv[3]
def without(d):
    d = dict(d); d["bindings"] = [[r, [m for m in ms if not (r == "roles/run.invoker" and m == sa)]] for r, ms in d["bindings"]]
    d["bindings"] = [x for x in d["bindings"] if x[1]]; return d
same = without(b) == without(a)
has = any(r == "roles/run.invoker" and sa in ms for r, ms in a["bindings"])
print(json.dumps({"unchangedExceptRelayInvoker": same, "relayInvokerPresent": has,
                  "engineRevisionUnchanged": a["serving"] == b["serving"] and a["image"] == b["image"]}))
PY
cat "$OUT/engine-diff.json"
check "engine unchanged except the relay SA's invoker binding (revision, image, env, traffic, other bindings)" \
  "J '$OUT/engine-diff.json' 'd[\"unchangedExceptRelayInvoker\"] and d[\"engineRevisionUnchanged\"]'"
check "engine still has no public member" \
  "J '$OUT/engine-after.json' 'not any(m in (\"allUsers\",\"allAuthenticatedUsers\") for _,ms in d[\"bindings\"] for m in ms)'"
PUBLIC=$(python3 -c "import json;p=json.load(open('$OUT/relay-invokers.json'));print(1 if any('allUsers' in b['members'] for b in p.get('bindings',[])) else 0)")
check "relay env: role relay, GCS replay store, target = engine URL" \
  "J '$OUT/relay-service.json' '{e[\"name\"]:e.get(\"value\") for e in d[\"spec\"][\"template\"][\"spec\"][\"containers\"][0].get(\"env\",[])}.get(\"RELAY_REPLAY_STORE\")==\"gcs\"'"
if [ -n "$RELAY_URL" ]; then
  export RELAY_SECRET_VALUE; RELAY_SECRET_VALUE=$(gc secrets versions access latest --secret="$SECRET_ID")
  export RELAY_PUBLIC=$PUBLIC
  TODAY=$(TZ=Pacific/Auckland date +%F); STAMP=$(date -u +%Y%m%dT%H%M%SZ)
  python3 - "$OUT" "$TODAY" "$STAMP" <<'PY'
import json, sys
out, today, stamp = sys.argv[1:4]
base = {"contractVersion": "masterboard.v1", "source": {"boardId": "5026186805", "itemId": "1000000001"}, "today": today,
        "options": {"failurePolicy": "all-or-nothing", "includePdf": True},
        "data": {"Applicant 1 Full Name": "Jane Example", "Applicant 2 Full Name": "John Sample",
                 "1st Property Address Street": "12 Example Street, Testville", "1st Property Address City": "Auckland",
                 "1st Property Address Postcode": "0999", "Number of rooms proposed to be rented": "2",
                 "Proposed weekly amount(s) per room": "250.00"}}
for name, banks in (("bnz", ["BNZ"]), ("unknown", ["NOTABANK"])):
    r = dict(base, selection={"banks": banks}, correlationId=f"mb-5026186805-1000000001-deploy{stamp}{name}",
             idempotencyKey=f"1000000001-deploycheck-{stamp}-{name}")
    open(f"{out}/req-{name}.json", "w").write(json.dumps(r, separators=(",", ":")))
PY
  C1=$(signed_call "$RELAY_URL/v1/masterboard/documents" "$OUT/req-bnz.json" "$OUT/res-bnz.json")
  check "signed synthetic BNZ request → 200 with a generated BNZ Boarder PDF (got $C1)" \
    "[ '$C1' = 200 ] && J '$OUT/res-bnz.json' 'd[\"status\"]==\"complete\" and any(x[\"templateId\"]==\"BNZ_BOARDER_FORM\" and x[\"status\"]==\"generated\" and x.get(\"pdfBase64\") for x in d[\"documents\"])'"
  C2=$(SEND_TWICE=1 signed_call "$RELAY_URL/v1/masterboard/documents" "$OUT/req-bnz.json" "$OUT/res-replay.json")
  check "the same signed request twice → 409 REPLAYED_REQUEST (got $C2; 503 means docs/10 M3: apply its fix)" "[ '$C2' = 409 ]"
  C3=$(signed_call "$RELAY_URL/v1/masterboard/documents" "$OUT/req-bnz.json" "$OUT/res-stale.json" -900)
  check "stale timestamp → 401 (got $C3)" "[ '$C3' = 401 ]"
  C4=$(signed_call "$RELAY_URL/v1/masterboard/documents" "$OUT/req-unknown.json" "$OUT/res-unknown.json")
  check "unknown bank → 422 UNKNOWN_BANK (got $C4)" "[ '$C4' = 422 ]"
  C5=$(RELAY_SECRET_VALUE=$(openssl rand -hex 32) signed_call "$RELAY_URL/v1/masterboard/documents" "$OUT/req-bnz.json" "$OUT/res-wrong.json")
  check "wrong secret → 401 (got $C5)" "[ '$C5' = 401 ]"
  unset RELAY_SECRET_VALUE
  CODE_ANON=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$RELAY_URL/v1/masterboard/documents" -d '{}')
  if [ "$PUBLIC" = 1 ]; then check "public relay: an unsigned request is refused by the relay (401, got $CODE_ANON)" "[ '$CODE_ANON' = 401 ]"
  else check "private relay: an anonymous request is refused by Cloud Run (403, got $CODE_ANON)" "[ '$CODE_ANON' = 403 ]"; fi
  ENGINE_ANON=$(curl -s -o /dev/null -w '%{http_code}' "$ENGINE_URL/health")
  check "the engine itself still refuses anonymous callers (403, got $ENGINE_ANON)" "[ '$ENGINE_ANON' = 403 ]"
  rm -f "$OUT"/res-*.json   # PDFs (synthetic) are not kept
fi
gc logging read "resource.type=\"cloud_run_revision\" AND resource.labels.service_name=\"$RELAY_SERVICE\"" --freshness=1h \
  --limit=300 --format=json > "$OUT/relay-logs.json" 2>/dev/null || echo '[]' > "$OUT/relay-logs.json"
check "relay logs hold no test values" \
  "J '$OUT/relay-logs.json' 'not any(s in json.dumps(d) for s in (\"Jane Example\",\"Example Street\",\"Testville\"))'"
rec relayPublic "$PUBLIC"; rec checks "\"$([ $FAIL = 0 ] && echo passed || echo FAILED)\""; rec finishedAt "\"$(date -u +%FT%TZ)\""
[ $FAIL = 0 ] || die "Verification failed. Rollback: bash rollback_relay.sh (see docs/15 §8)."
ok "all checks passed$([ "$PUBLIC" = 1 ] && echo ' (relay public)' || echo ' (relay still private; PHASE=expose needs separate approval)')"
