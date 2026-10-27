#!/usr/bin/env bash
# First isolated Cloud Run deployment of the RSFA End Forms Engine POC (BNZ Boarder only).
#
# Runs, in order: preflight checks -> minimal infrastructure -> Cloud Build -> deploy (IAM required)
# -> invoker for the CURRENTLY AUTHENTICATED identity only -> verification -> deployment-record.json.
# Idempotent: existing resources are reused, never deleted. Stops at the first blocker with the exact fix.
#
# Never: --allow-unauthenticated, API keys, service-account keys, Owner/Editor grants, other services.
#
# Usage (Cloud Shell or any machine logged in with `gcloud auth login`):
#   bash deploy_poc.sh                       # from the unpacked bundle (expects ./context)
#   CONTEXT=.local/deploy/context bash deploy/deploy_poc.sh     # from the repo
# Options (environment):
#   UPDATE_EXISTING=1   deploy a new revision to an existing service, safely:
#                       - refuses if the live configuration or invoker policy differs from what this script deploys;
#                       - deploys the new revision with NO traffic under the tag "$CANDIDATE_TAG" and verifies it there;
#                       - only then moves 100% traffic to it, re-verifies the main URL and prints the rollback command.
#                       If anything fails before promotion, the previous revision keeps serving all traffic.
#   EXPECT_PREVIOUS_REVISION=<name>  (update) refuse unless this revision is the one currently serving 100%.
set -euo pipefail

PROJECT_ID=${PROJECT_ID:-rsfa-end-forms-engine}
REGION=${REGION:-australia-southeast1}
SERVICE=${SERVICE:-rsfa-end-forms-engine}
RUNTIME_SA=${RUNTIME_SA:-rsfa-end-forms-runtime@${PROJECT_ID}.iam.gserviceaccount.com}
BUILD_SA_NAME=${BUILD_SA_NAME:-rsfa-end-forms-build}
BUILD_SA=${BUILD_SA_NAME}@${PROJECT_ID}.iam.gserviceaccount.com
AR_REPO=${AR_REPO:-rsfa-end-forms}
SRC_BUCKET=${SRC_BUCKET:-gs://${PROJECT_ID}-build-src}
IMAGE_BASE=${REGION}-docker.pkg.dev/${PROJECT_ID}/${AR_REPO}/end-forms-engine
HERE=$(cd "$(dirname "$0")" && pwd)
CONTEXT=${CONTEXT:-$HERE/context}
FIXTURE=${FIXTURE:-$HERE/fixture.request.json}
[ -f "$FIXTURE" ] || FIXTURE=$HERE/../tests/fixtures/bnz-boarder.request.json
VERIFY=${VERIFY:-$HERE/verify_pdf.py}
OUT=${OUT:-$PWD/cloudrun-output}
RECORD=$OUT/deployment-record.json
mkdir -p "$OUT"

step() { printf '\n=== %s ===\n' "$*"; }
ok()   { printf '  [ok] %s\n' "$*"; }
die()  { printf '\n  [BLOCKER] %s\n\n' "$*" >&2; exit 1; }
gc()   { gcloud --project="$PROJECT_ID" "$@"; }
CANDIDATE_TAG=${CANDIDATE_TAG:-candidate}
EXPECTED_HEALTH='"templates":1,"available":["BNZ_BOARDER_FORM@2.0_8-24"]'
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

# Prints the deployment-relevant configuration of a `gcloud run services describe --format=json` file.
summarize() { python3 - "$1" <<'PY'
import json, sys
s = json.load(open(sys.argv[1])); t = s["spec"]["template"]; c = t["spec"]["containers"][0]
ann = {**s["metadata"].get("annotations", {})}; tann = t["metadata"].get("annotations", {})
print(json.dumps({
  "url": s["status"].get("url"),
  "latestReadyRevision": s["status"].get("latestReadyRevisionName"),
  "ready": [x["status"] for x in s["status"].get("conditions", []) if x["type"] == "Ready"],
  "ingress": ann.get("run.googleapis.com/ingress"),
  "serviceAccount": t["spec"].get("serviceAccountName"),
  "cpu": c["resources"]["limits"].get("cpu"), "memory": c["resources"]["limits"].get("memory"),
  "minInstances": tann.get("autoscaling.knative.dev/minScale", "0"),
  "maxInstances": tann.get("autoscaling.knative.dev/maxScale"),
  "concurrency": t["spec"].get("containerConcurrency"), "timeoutSeconds": t["spec"].get("timeoutSeconds"),
  "cpuThrottling(requestBasedBilling)": tann.get("run.googleapis.com/cpu-throttling", "true"),
  "env": {e["name"]: e.get("value") for e in c.get("env", [])},
  "image": c["image"]}, indent=2))
PY
}
# Fails unless a summary has exactly the configuration this script deploys (so an update never silently changes it).
config_matches() { python3 - "$1" "$RUNTIME_SA" <<'PY'
import json, sys
s = json.load(open(sys.argv[1]))
want = {"ingress": "all", "serviceAccount": sys.argv[2], "cpu": "1", "memory": "1Gi", "minInstances": "0",
        "maxInstances": "2", "concurrency": 1, "timeoutSeconds": 120, "cpuThrottling(requestBasedBilling)": "true",
        "env": {"ENGINE_AUTH": "PLATFORM_IAM"}}
diff = sorted(k for k, v in want.items() if s.get(k) != v)
print("  config differences:", diff if diff else "none")
sys.exit(1 if diff else 0)
PY
}
# Fails unless the invoker policy has no public members and exactly one invoker: the given member.
invoker_only() { python3 - "$1" "$2" <<'PY'
import json, sys
p = json.load(open(sys.argv[1])); members = [m for b in p.get("bindings", []) for m in b.get("members", [])]
bad = [m for m in members if m in ("allUsers", "allAuthenticatedUsers")]
inv = [m for b in p.get("bindings", []) if b["role"] == "roles/run.invoker" for m in b["members"]]
print("  invokers:", inv)
sys.exit(1 if bad or inv != [sys.argv[2]] else 0)
PY
}
# Prints the IAM bindings in a canonical form (etag/version ignored) for before/after comparison.
bindings() { python3 -c "import json,sys;p=json.load(open(sys.argv[1]));print(json.dumps(sorted((b['role'],sorted(b['members'])) for b in p.get('bindings',[]))))" "$1"; }

# --------------------------------------------------------------------------------------------- 1. preflight
step "1. Preflight: identity, project, billing, permissions, staged context"
command -v gcloud >/dev/null || die "gcloud is not installed (use Cloud Shell)."
command -v python3 >/dev/null || die "python3 is required."
ACCOUNT=$(gcloud config get-value account 2>/dev/null || true)
[ -n "$ACCOUNT" ] || die "No authenticated gcloud account. Run: gcloud auth login"
case "$ACCOUNT" in
  *.gserviceaccount.com) MEMBER="serviceAccount:$ACCOUNT" ;;
  *) MEMBER="user:$ACCOUNT" ;;
esac
ok "authenticated as $ACCOUNT (invoker will be granted to exactly this identity)"
rec startedAt "\"$(date -u +%FT%TZ)\""
rec project "\"$PROJECT_ID\""; rec region "\"$REGION\""; rec service "\"$SERVICE\""

STATE=$(gc projects describe "$PROJECT_ID" --format='value(lifecycleState)' 2>/dev/null) \
  || die "Cannot read project $PROJECT_ID with $ACCOUNT (wrong account or no access)."
[ "$STATE" = ACTIVE ] || die "Project $PROJECT_ID is $STATE."
ok "project $PROJECT_ID is ACTIVE"

BILLING=$(gcloud billing projects describe "$PROJECT_ID" --format='value(billingEnabled)' 2>/dev/null || echo unknown)
case "$BILLING" in
  True) ok "billing enabled" ;;
  False) die "Billing is not enabled on $PROJECT_ID. Link a billing account (Console > Billing) and re-run." ;;
  *) echo "  [warn] billing could not be read with this account; Cloud Run API enablement will fail clearly if it is off" ;;
esac

TOKEN=$(gcloud auth print-access-token)
NEEDED='["serviceusage.services.enable","artifactregistry.repositories.create","artifactregistry.repositories.setIamPolicy",
"iam.serviceAccounts.create","iam.serviceAccounts.setIamPolicy","iam.serviceAccounts.actAs","resourcemanager.projects.setIamPolicy",
"storage.buckets.create","storage.buckets.setIamPolicy","cloudbuild.builds.create","run.services.create","run.services.setIamPolicy",
"run.services.get","logging.logEntries.list"]'
MISSING=$(curl -sS -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  "https://cloudresourcemanager.googleapis.com/v1/projects/${PROJECT_ID}:testIamPermissions" \
  -d "{\"permissions\": $NEEDED}" | python3 -c "
import json,sys
need=set(json.loads('''$NEEDED'''))
got=set(json.load(sys.stdin).get('permissions',[]))
print(' '.join(sorted(need-got)))")
[ -z "$MISSING" ] || die "$ACCOUNT lacks these project permissions: $MISSING
  Ask a project owner to grant you (project-level): roles/serviceusage.serviceUsageAdmin, roles/artifactregistry.admin,
  roles/iam.serviceAccountAdmin, roles/resourcemanager.projectIamAdmin, roles/storage.admin,
  roles/cloudbuild.builds.editor, roles/run.admin, roles/logging.viewer (or Owner), then re-run."
ok "all required permissions present"

[ -f "$CONTEXT/STAGED_FILES.txt" ] || die "Staged context not found at $CONTEXT (run tools/stage_deploy.py)."
[ ! -e "$CONTEXT/UNAPPROVED_LOCAL_TEST_ONLY" ] || die "Context is marked UNAPPROVED_LOCAL_TEST_ONLY: refusing to deploy."
( cd "$CONTEXT" && while read -r sum size path; do
    [ "$(sha256sum "$path" | cut -d' ' -f1)" = "$sum" ] || { echo "checksum mismatch: $path"; exit 1; }
  done < STAGED_FILES.txt ) || die "Staged context was modified after staging."
[ "$(find "$CONTEXT/poc-templates" -type f | wc -l)" -eq 1 ] || die "Expected exactly one template in the context."
! grep -R -I -l -E 'hook\.[a-z0-9]+\.make\.com|sample_payload|__raw_webhook_payload' "$CONTEXT" >/dev/null \
  || die "Forbidden content found in the staged context."
ok "staged context intact: $(wc -l < "$CONTEXT/STAGED_FILES.txt") files, 1 allowlisted template, no forbidden content"

# --------------------------------------------------------------------------------------------- 2. infrastructure
step "2. Minimal infrastructure (APIs, Artifact Registry, build-source bucket)"
gc services enable run.googleapis.com artifactregistry.googleapis.com cloudbuild.googleapis.com \
  iam.googleapis.com storage.googleapis.com logging.googleapis.com
ok "APIs enabled"

MODE=create
if gc run services describe "$SERVICE" --region="$REGION" >/dev/null 2>&1; then
  [ "${UPDATE_EXISTING:-0}" = 1 ] || die "Cloud Run service $SERVICE already exists in $REGION. This script only creates it the first time.
  To deploy a new revision deliberately, re-run with UPDATE_EXISTING=1."
  MODE=update
fi
rec mode "\"$MODE\""
if [ "$MODE" = update ]; then
  gc run services describe "$SERVICE" --region="$REGION" --format=json > "$OUT/before-service.json"
  summarize "$OUT/before-service.json" > "$OUT/before-summary.json"
  gc run services get-iam-policy "$SERVICE" --region="$REGION" --format=json > "$OUT/before-iam.json"
  PREV_REV=$(python3 - "$OUT/before-service.json" <<'PY'
import json, sys
s = json.load(open(sys.argv[1])); st = s["status"]
full = [t for t in st.get("traffic", []) if t.get("percent") == 100]
if len(full) == 1:
    print(full[0].get("revisionName") or (st.get("latestReadyRevisionName") if full[0].get("latestRevision") else ""))
PY
)
  [ -n "$PREV_REV" ] || die "Could not identify a single revision serving 100% of traffic. Inspect:
  gcloud run services describe $SERVICE --region=$REGION --format='yaml(status.traffic)'"
  if [ -n "${EXPECT_PREVIOUS_REVISION:-}" ] && [ "$PREV_REV" != "$EXPECT_PREVIOUS_REVISION" ]; then
    die "The serving revision is $PREV_REV, not $EXPECT_PREVIOUS_REVISION. Nothing was changed."
  fi
  config_matches "$OUT/before-summary.json" || die "The live service configuration differs from what this script deploys
  (see $OUT/before-summary.json). Updating would overwrite it, so nothing was changed. Review the difference first."
  invoker_only "$OUT/before-iam.json" "$MEMBER" || die "The live invoker policy is not exactly [$MEMBER] (see $OUT/before-iam.json).
  Run this script as the identity that deployed the service. Nothing was changed."
  ok "update mode: $PREV_REV serves 100%; live configuration and invoker policy match what this script deploys"
  rec previousRevision "\"$PREV_REV\""
fi
gc iam service-accounts describe "$RUNTIME_SA" >/dev/null 2>&1 \
  || die "Runtime service account $RUNTIME_SA does not exist."
ok "runtime service account exists"

if ! gc artifacts repositories describe "$AR_REPO" --location="$REGION" >/dev/null 2>&1; then
  gc artifacts repositories create "$AR_REPO" --repository-format=docker --location="$REGION" \
    --description="RSFA End Forms Engine images (private)"
fi
ok "Artifact Registry $AR_REPO (private)"

if ! gcloud storage buckets describe "$SRC_BUCKET" --project="$PROJECT_ID" >/dev/null 2>&1; then
  gcloud storage buckets create "$SRC_BUCKET" --project="$PROJECT_ID" --location="$REGION" \
    --uniform-bucket-level-access --public-access-prevention
fi
printf '{"rule":[{"action":{"type":"Delete"},"condition":{"age":7}}]}\n' > "$OUT/lifecycle.json"
gcloud storage buckets update "$SRC_BUCKET" --project="$PROJECT_ID" --lifecycle-file="$OUT/lifecycle.json" >/dev/null
ok "build-source bucket $SRC_BUCKET (regional, private, 7-day lifecycle)"

# --------------------------------------------------------------------------------------------- 3. build SA
step "3. Dedicated least-privilege Cloud Build service account"
gc iam service-accounts describe "$BUILD_SA" >/dev/null 2>&1 \
  || gc iam service-accounts create "$BUILD_SA_NAME" --display-name="RSFA End Forms Cloud Build"
gc artifacts repositories add-iam-policy-binding "$AR_REPO" --location="$REGION" \
  --member="serviceAccount:$BUILD_SA" --role=roles/artifactregistry.writer >/dev/null
gcloud storage buckets add-iam-policy-binding "$SRC_BUCKET" --project="$PROJECT_ID" \
  --member="serviceAccount:$BUILD_SA" --role=roles/storage.objectViewer >/dev/null
gc projects add-iam-policy-binding "$PROJECT_ID" --member="serviceAccount:$BUILD_SA" \
  --role=roles/logging.logWriter --condition=None >/dev/null
gc iam service-accounts add-iam-policy-binding "$BUILD_SA" --member="$MEMBER" \
  --role=roles/iam.serviceAccountUser >/dev/null
gc iam service-accounts add-iam-policy-binding "$RUNTIME_SA" --member="$MEMBER" \
  --role=roles/iam.serviceAccountUser >/dev/null
ok "build SA: artifactregistry.writer (repo), storage.objectViewer (bucket), logging.logWriter (project)"
ok "$MEMBER may act as the build SA and the runtime SA (needed to build and deploy)"
echo "  waiting ${IAM_WAIT:-60}s for IAM propagation..."; sleep "${IAM_WAIT:-60}"

# --------------------------------------------------------------------------------------------- 4/5. build
step "4-5. Build the POC image with Cloud Build (tests run inside the build)"
TAG=poc-$(date -u +%Y%m%d-%H%M%S)
IMAGE=$IMAGE_BASE:$TAG
if gc builds submit "$CONTEXT" --config="$CONTEXT/cloudbuild.yaml" --substitutions=_IMAGE="$IMAGE" \
     --region="$REGION" --service-account="projects/$PROJECT_ID/serviceAccounts/$BUILD_SA" \
     --gcs-source-staging-dir="$SRC_BUCKET/source" 2>&1 | tee "$OUT/cloudbuild.log"; then
  BUILT_WITH="Cloud Build"
else
  echo "  [warn] Cloud Build failed (log: $OUT/cloudbuild.log). Trying the documented fallback: local Docker build + push."
  command -v docker >/dev/null || die "Cloud Build failed and Docker is not available for the fallback. See $OUT/cloudbuild.log."
  gc artifacts repositories add-iam-policy-binding "$AR_REPO" --location="$REGION" \
    --member="$MEMBER" --role=roles/artifactregistry.writer >/dev/null
  gcloud auth configure-docker "${REGION}-docker.pkg.dev" --quiet
  DOCKER_BUILDKIT=1 docker build -f "$CONTEXT/engine/Dockerfile" --target poc -t "$IMAGE" "$CONTEXT"
  docker push "$IMAGE"
  BUILT_WITH="local Docker (fallback)"
fi
DIGEST_REF=$(gc artifacts docker images describe "$IMAGE" --format='value(image_summary.fully_qualified_digest)' 2>/dev/null || true)
if [ -z "$DIGEST_REF" ]; then
  D=$(gc artifacts docker images describe "$IMAGE" --format='value(image_summary.digest)')
  DIGEST_REF="$IMAGE_BASE@$D"
fi
[[ "$DIGEST_REF" == *@sha256:* ]] || die "Could not resolve the image digest for $IMAGE."
ok "image $IMAGE -> $DIGEST_REF ($BUILT_WITH)"
rec imageTag "\"$IMAGE\""; rec imageDigest "\"$DIGEST_REF\""; rec builtWith "\"$BUILT_WITH\""

# --------------------------------------------------------------------------------------------- 6. deploy
step "6. Deploy Cloud Run (authentication required)"
TRAFFIC_FLAGS=()
[ "$MODE" = update ] && TRAFFIC_FLAGS=(--no-traffic --tag="$CANDIDATE_TAG")
gc run deploy "$SERVICE" --image="$DIGEST_REF" --region="$REGION" --service-account="$RUNTIME_SA" "${TRAFFIC_FLAGS[@]}" \
  --no-allow-unauthenticated --ingress=all --cpu=1 --memory=1Gi --min-instances=0 --max-instances=2 \
  --concurrency=1 --timeout=120 --cpu-throttling --set-env-vars=ENGINE_AUTH=PLATFORM_IAM \
  --labels=app=rsfa-end-forms-engine,stage=poc
gc run services describe "$SERVICE" --region="$REGION" --format=json > "$OUT/service.json"
summarize "$OUT/service.json" > "$OUT/service-summary.json"
cat "$OUT/service-summary.json"
rec configuration "$(cat "$OUT/service-summary.json")"
URL=$(python3 -c "import json;print(json.load(open('$OUT/service-summary.json'))['url'])")
REV=$(python3 -c "import json;print(json.load(open('$OUT/service-summary.json'))['latestReadyRevision'])")
[ -n "$URL" ] && [ -n "$REV" ] || die "Service has no URL/ready revision. Check: gcloud run services describe $SERVICE --region=$REGION"
config_matches "$OUT/service-summary.json" || die "Deployed configuration differs from the intended one (see $OUT/service-summary.json)."
VURL=$URL
if [ "$MODE" = update ]; then
  [ "$REV" != "$PREV_REV" ] || die "No new revision became ready (latest ready is still $PREV_REV)."
  IFS='|' read -r VURL SERVING < <(python3 - "$OUT/service.json" "$CANDIDATE_TAG" "$REV" <<'PY'
import json, sys
st = json.load(open(sys.argv[1]))["status"]; tr = st.get("traffic", [])
tag = [t for t in tr if t.get("tag") == sys.argv[2] and t.get("revisionName") == sys.argv[3]]
full = [t.get("revisionName", "") for t in tr if t.get("percent") == 100]
print((tag[0].get("url", "") if tag else "") + "|" + (full[0] if len(full) == 1 else ""))
PY
)
  [ "$SERVING" = "$PREV_REV" ] || die "Expected $PREV_REV to keep 100% traffic during verification, found '$SERVING'."
  [ -n "$VURL" ] || die "The candidate tag URL for $REV was not found."
  ok "new revision $REV deployed with NO traffic; $PREV_REV still serves 100%; candidate URL $VURL"
  rec candidateUrl "\"$VURL\""
fi
ok "configuration unchanged: IAM required, ingress all, runtime SA, 1 vCPU, 1 GiB, min 0, max 2, concurrency 1, 120 s, request-based billing, ENGINE_AUTH=PLATFORM_IAM"
rec url "\"$URL\""; rec revision "\"$REV\""

# --------------------------------------------------------------------------------------------- 7. invoker
step "7. Invoker: only the currently authenticated identity"
gc run services add-iam-policy-binding "$SERVICE" --region="$REGION" --member="$MEMBER" --role=roles/run.invoker >/dev/null
gc run services get-iam-policy "$SERVICE" --region="$REGION" --format=json > "$OUT/service-iam.json"
invoker_only "$OUT/service-iam.json" "$MEMBER" || die "Unexpected public or extra invoker binding: see $OUT/service-iam.json"
if [ "$MODE" = update ]; then
  [ "$(bindings "$OUT/before-iam.json")" = "$(bindings "$OUT/service-iam.json")" ] \
    || die "The service IAM policy changed during the update (compare $OUT/before-iam.json and $OUT/service-iam.json)."
  ok "service IAM policy identical to before the update"
fi
ok "no allUsers/allAuthenticatedUsers; invoker = $MEMBER only"
rec invokers "[\"<the deployer's own identity>\"]"

# --------------------------------------------------------------------------------------------- 8. verify
step "8. Verification$([ "$MODE" = update ] && echo " of the candidate revision (no traffic yet)")"
UNAUTH=$(curl -s -o /dev/null -w '%{http_code}' "$VURL/health")
[ "$UNAUTH" = 403 ] || [ "$UNAUTH" = 401 ] || die "Unauthenticated /health returned $UNAUTH (expected 401/403)."
ok "unauthenticated /health rejected ($UNAUTH)"
ID_TOKEN=$(gcloud auth print-identity-token 2>/dev/null) || die "Cannot mint an identity token for $ACCOUNT (log in with: gcloud auth login)."
HEALTH=""; for i in $(seq 1 24); do   # IAM bindings can take a minute to apply
  CODE=$(curl -s -o "$OUT/health.json" -w '%{http_code}' -H "Authorization: Bearer $ID_TOKEN" "$VURL/health")
  [ "$CODE" = 200 ] && { HEALTH=$(cat "$OUT/health.json"); break; }; sleep 5; done
[ -n "$HEALTH" ] || die "Authenticated /health did not return 200 within 2 minutes (last: $CODE)."
case "$HEALTH" in *"$EXPECTED_HEALTH"*) ;; *) die "Unexpected /health (expected only BNZ Boarder usable): $HEALTH" ;; esac
ok "authenticated /health: $HEALTH"
CODE=$(curl -s -D "$OUT/generate.headers" -o "$OUT/BNZ_Boarder_cloudrun.pdf" -w '%{http_code}' \
  -H "Authorization: Bearer $ID_TOKEN" -H 'Content-Type: application/json' --data @"$FIXTURE" "$VURL/generate")
[ "$CODE" = 200 ] || die "Authenticated /generate returned $CODE: $(head -c 400 "$OUT/BNZ_Boarder_cloudrun.pdf")"
ok "authenticated /generate: 200, $(grep -i -E '^x-fields-filled|^x-fill-warnings|^content-type' "$OUT/generate.headers" | tr -d '\r' | tr '\n' ' ')"
UI_UNAUTH=$(curl -s -o /dev/null -w '%{http_code}' "$VURL/")
[ "$UI_UNAUTH" = 403 ] || [ "$UI_UNAUTH" = 401 ] || die "Unauthenticated demo UI returned $UI_UNAUTH (expected 401/403)."
CODE=$(curl -s -o "$OUT/ui.html" -w '%{http_code}' -H "Authorization: Bearer $ID_TOKEN" "$VURL/")
[ "$CODE" = 200 ] && grep -q 'RSFA End Forms Engine' "$OUT/ui.html" || die "Authenticated demo UI returned $CODE."
ok "demo UI: rejected without a token ($UI_UNAUTH), served with one (open it via gcloud run services proxy)"
[ -d "$OUT/venv" ] || python3 -m venv "$OUT/venv"
"$OUT/venv/bin/pip" -q install 'pypdf==6.17.0' >/dev/null
"$OUT/venv/bin/python" "$VERIFY" "$OUT/BNZ_Boarder_cloudrun.pdf" "$HERE/expected_pdf_sha256.txt" | tee "$OUT/pdf-verification.json"
grep -q '"ok": true' "$OUT/pdf-verification.json" || die "PDF verification failed: see $OUT/pdf-verification.json"
rec pdfVerification "$(cat "$OUT/pdf-verification.json")"

if [ "$MODE" = update ]; then
  step "9. Promote $REV to 100% traffic, then re-verify the main URL"
  gc run services update-traffic "$SERVICE" --region="$REGION" --to-latest >/dev/null
  gc run services update-traffic "$SERVICE" --region="$REGION" --remove-tags="$CANDIDATE_TAG" >/dev/null
  gc run services describe "$SERVICE" --region="$REGION" --format=json > "$OUT/after-service.json"
  SERVING=$(python3 -c "import json,sys;t=json.load(open(sys.argv[1]))['status'].get('traffic',[]);print(','.join(f\"{x.get('revisionName','latest')}={x.get('percent',0)}\" for x in t if x.get('percent')))" "$OUT/after-service.json")
  case "$SERVING" in "$REV=100"|"latest=100") ;; *) die "Traffic after promotion is '$SERVING'. Roll back with:
  gcloud run services update-traffic $SERVICE --region=$REGION --project=$PROJECT_ID --to-revisions=$PREV_REV=100" ;; esac
  M_UNAUTH=$(curl -s -o /dev/null -w '%{http_code}' "$URL/")
  [ "$M_UNAUTH" = 403 ] || [ "$M_UNAUTH" = 401 ] || die "Main URL served the UI without a token ($M_UNAUTH). Roll back now:
  gcloud run services update-traffic $SERVICE --region=$REGION --project=$PROJECT_ID --to-revisions=$PREV_REV=100"
  M_OK=""; for i in $(seq 1 12); do
    curl -s -o "$OUT/main-health.json" -H "Authorization: Bearer $ID_TOKEN" "$URL/health"
    curl -s -o "$OUT/main-ui.html" -H "Authorization: Bearer $ID_TOKEN" "$URL/"
    curl -s -o "$OUT/main-generate.pdf" -H "Authorization: Bearer $ID_TOKEN" -H 'Content-Type: application/json' \
      --data @"$FIXTURE" "$URL/generate"
    if grep -qF "$EXPECTED_HEALTH" "$OUT/main-health.json" && grep -q 'RSFA End Forms Engine' "$OUT/main-ui.html" \
       && [ "$(sha256sum "$OUT/main-generate.pdf" | cut -d' ' -f1)" = "$(cut -d' ' -f1 "$HERE/expected_pdf_sha256.txt")" ]; then
      M_OK=1; break; fi
    sleep 5; done
  [ -n "$M_OK" ] || die "Main URL did not serve the new revision correctly. Roll back now:
  gcloud run services update-traffic $SERVICE --region=$REGION --project=$PROJECT_ID --to-revisions=$PREV_REV=100"
  ok "main URL: 100% on $REV; UI rejected without a token ($M_UNAUTH) and served with one; /health and /generate (identical PDF) OK"
  rec traffic "\"$SERVING\""
  rec rollback "\"gcloud run services update-traffic $SERVICE --region=$REGION --project=$PROJECT_ID --to-revisions=$PREV_REV=100\""
fi

echo "  waiting ${LOG_WAIT:-30}s for request logs to arrive..."; sleep "${LOG_WAIT:-30}"
gcloud logging read "resource.type=\"cloud_run_revision\" AND resource.labels.service_name=\"$SERVICE\"" \
  --project="$PROJECT_ID" --freshness=1h --limit=500 --format=json > "$OUT/logs.json"
python3 - "$OUT/logs.json" "$FIXTURE" > "$OUT/log-check.json" <<'PY'
import json, sys
logs = json.load(open(sys.argv[1])); data = json.load(open(sys.argv[2]))["data"]
values = [v for v in data.values() if isinstance(v, str) and len(v) >= 4 and any(ch.isalpha() for ch in v)]
text = json.dumps(logs)
hits = sorted({v for v in values if v in text})
print(json.dumps({"entries": len(logs), "fixtureValuesFound": len(hits), "ok": not hits and len(logs) > 0}, indent=2))
PY
cat "$OUT/log-check.json"
grep -q '"ok": true' "$OUT/log-check.json" || die "Log check failed (no entries, or payload values in logs): $OUT/log-check.json"
rec logCheck "$(cat "$OUT/log-check.json")"
rec tests "{\"verifiedUrl\": \"$VURL\", \"unauthenticatedHealth\": $UNAUTH, \"authenticatedHealth\": 200, \"authenticatedGenerate\": 200, \"unauthenticatedUi\": $UI_UNAUTH, \"authenticatedUi\": 200, \"mainUrlAfterPromotion\": \"$([ -n "${M_OK:-}" ] && echo ok || echo not applicable)\"}"
rec finishedAt "\"$(date -u +%FT%TZ)\""

step "DONE"
echo "  Service URL : $URL"
echo "  Revision    : $REV"
echo "  Image       : $DIGEST_REF"
echo "  Record      : $RECORD   (send this file and $OUT/BNZ_Boarder_cloudrun.pdf back for the repo docs)"
echo "  Demo UI     : gcloud run services proxy $SERVICE --region=$REGION --project=$PROJECT_ID --port=8080, then open localhost:8080 (docs/05)"
if [ "$MODE" = update ]; then
  echo "  Rollback    : gcloud run services update-traffic $SERVICE --region=$REGION --project=$PROJECT_ID --to-revisions=$PREV_REV=100"
else
  echo "  Rollback    : gcloud run services delete $SERVICE --region=$REGION --project=$PROJECT_ID"
fi
