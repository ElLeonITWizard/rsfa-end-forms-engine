#!/usr/bin/env bash
# Private administration service: deployment with preflight checks (D-041, docs/14). RSFA runs this in Cloud Shell
# from the admin bundle (deploy/admin/make_admin_bundle.sh). THIS REPOSITORY NEVER RUNS IT.
#
# Default is a PLAN: every check runs read-only and every command that would change something is printed, not run.
# Nothing is created or changed unless APPLY=1.
#
#   ADMIN_USERS='you@example.org:admin' bash deploy_admin.sh                 # plan (read-only)
#   ADMIN_USERS='you@example.org:admin' APPLY=1 bash deploy_admin.sh         # deploy
#   ADMIN_USERS='...' APPLY=1 PHASE=access bash deploy_admin.sh              # after enabling IAP in the console (no-org projects)
#   PHASE=verify bash deploy_admin.sh                                        # re-run the verification only (read-only)
#
# Creates ONLY: API iap.googleapis.com; service account rsfa-end-forms-admin (no project roles); private bucket
# <project>-admin (versioned, uniform access, public access prevention); Cloud Run service rsfa-end-forms-admin
# (IAM required + IAP); IAP access for the named users; run.invoker for the IAP service agent on the admin service.
# Reuses (unchanged): Artifact Registry repo rsfa-end-forms, build SA rsfa-end-forms-build, build-source bucket.
# NEVER touches the engine service rsfa-end-forms-engine: it is only read, before and after, to prove it is unchanged.
# Never: --allow-unauthenticated, allUsers/allAuthenticatedUsers, keys, Owner/Editor grants, Make, Monday, SharePoint.
set -euo pipefail

PROJECT_ID=${PROJECT_ID:-rsfa-end-forms-engine}
REGION=${REGION:-australia-southeast1}
ADMIN_SERVICE=${ADMIN_SERVICE:-rsfa-end-forms-admin}
ADMIN_SA_NAME=${ADMIN_SA_NAME:-rsfa-end-forms-admin}
ADMIN_SA=${ADMIN_SA_NAME}@${PROJECT_ID}.iam.gserviceaccount.com
ADMIN_BUCKET=${ADMIN_BUCKET:-${PROJECT_ID}-admin}
ENGINE_SERVICE=${ENGINE_SERVICE:-rsfa-end-forms-engine}            # READ ONLY
EXPECT_ENGINE_REVISION=${EXPECT_ENGINE_REVISION:-rsfa-end-forms-engine-00005-rih}
BUILD_SA=${BUILD_SA_NAME:-rsfa-end-forms-build}@${PROJECT_ID}.iam.gserviceaccount.com
AR_REPO=${AR_REPO:-rsfa-end-forms}
SRC_BUCKET=${SRC_BUCKET:-gs://${PROJECT_ID}-build-src}
IMAGE_BASE=${REGION}-docker.pkg.dev/${PROJECT_ID}/${AR_REPO}/end-forms-admin
SEPARATE_APPROVER=${SEPARATE_APPROVER:-false}
APPLY=${APPLY:-0}
PHASE=${PHASE:-all}                     # all | access | verify
HERE=$(cd "$(dirname "$0")" && pwd)
CONTEXT=${CONTEXT:-$HERE/context}
SCHEMA=${SCHEMA:-$HERE/config/monday-schema.json}
LIFECYCLE=${LIFECYCLE:-$HERE/bucket-lifecycle.json}
OUT=${OUT:-$PWD/admin-deploy-output}
RECORD=$OUT/admin-deployment-record.json
mkdir -p "$OUT"

step() { printf '\n=== %s ===\n' "$*"; }
ok()   { printf '  [ok] %s\n' "$*"; }
warn() { printf '  [warn] %s\n' "$*"; }
die()  { printf '\n  [BLOCKER] %s\n\n' "$*" >&2; exit 1; }
gc()   { gcloud --project="$PROJECT_ID" "$@"; }
# A command that changes something: always printed; executed only with APPLY=1.
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

# Engine fingerprint: serving revision, configuration and IAM bindings (compared before/after; must be identical).
engine_fingerprint() { # $1 = output file
  gc run services describe "$ENGINE_SERVICE" --region="$REGION" --format=json > "$1.service.json"
  gc run services get-iam-policy "$ENGINE_SERVICE" --region="$REGION" --format=json > "$1.iam.json"
  python3 - "$1.service.json" "$1.iam.json" > "$1" <<'PY'
import json, sys
s = json.load(open(sys.argv[1])); p = json.load(open(sys.argv[2]))
t = s["spec"]["template"]; c = t["spec"]["containers"][0]; st = s["status"]
print(json.dumps({
  "serving": sorted((x.get("revisionName") or "latest", x.get("percent", 0), x.get("tag", "")) for x in st.get("traffic", [])),
  "latestReadyRevision": st.get("latestReadyRevisionName"),
  "image": c["image"], "serviceAccount": t["spec"].get("serviceAccountName"),
  "env": sorted((e["name"], e.get("value", "<from secret>")) for e in c.get("env", [])),
  "ingress": s["metadata"].get("annotations", {}).get("run.googleapis.com/ingress"),
  "templateAnnotations": sorted(t["metadata"].get("annotations", {}).items()),
  "bindings": sorted((b["role"], sorted(b["members"])) for b in p.get("bindings", [])),
}, indent=1, sort_keys=True))
PY
}

# --------------------------------------------------------------------------------------------- 1. preflight
step "1. Preflight (read-only) — mode: $([ "$APPLY" = 1 ] && echo APPLY || echo PLAN), phase: $PHASE"
for c in gcloud python3 curl sha256sum; do command -v "$c" >/dev/null || die "$c is required (use Cloud Shell)."; done
gcloud run deploy --help 2>/dev/null | grep -q -- '--\[no-\]iap' || die "this gcloud has no 'run deploy --iap'; run: gcloud components update"
ACCOUNT=$(gcloud config get-value account 2>/dev/null || true)
[ -n "$ACCOUNT" ] || die "No authenticated gcloud account. Run: gcloud auth login"
case "$ACCOUNT" in *.gserviceaccount.com) DEPLOYER="serviceAccount:$ACCOUNT" ;; *) DEPLOYER="user:$ACCOUNT" ;; esac
ok "authenticated as $ACCOUNT"
STATE=$(gc projects describe "$PROJECT_ID" --format='value(lifecycleState)' 2>/dev/null) || die "Cannot read project $PROJECT_ID."
[ "$STATE" = ACTIVE ] || die "Project $PROJECT_ID is $STATE."
PROJECT_NUMBER=$(gc projects describe "$PROJECT_ID" --format='value(projectNumber)')
PARENT_TYPE=$(gc projects describe "$PROJECT_ID" --format='value(parent.type)' 2>/dev/null || true)
case "$PARENT_TYPE" in
  organization|folder) NO_ORG=0; ok "project $PROJECT_ID ($PROJECT_NUMBER) belongs to a Google organization" ;;
  *) NO_ORG=1; ok "project $PROJECT_ID ($PROJECT_NUMBER) has NO Google organization: IAP needs a one-time console step (docs/14 §4)" ;;
esac
IAP_AGENT=service-${PROJECT_NUMBER}@gcp-sa-iap.iam.gserviceaccount.com
IAP_AUDIENCE=/projects/${PROJECT_NUMBER}/locations/${REGION}/services/${ADMIN_SERVICE}
rec startedAt "\"$(date -u +%FT%TZ)\""; rec mode "\"$([ "$APPLY" = 1 ] && echo apply || echo plan)\""; rec phase "\"$PHASE\""
rec project "\"$PROJECT_ID\""; rec projectNumber "\"$PROJECT_NUMBER\""; rec region "\"$REGION\""; rec noOrganization "$([ $NO_ORG = 1 ] && echo true || echo false)"

[ -n "${ADMIN_USERS:-}" ] || [ "$PHASE" = verify ] || die "ADMIN_USERS is required, e.g. ADMIN_USERS='name@example.org:admin'
  (comma-separated email:role; roles viewer | author | approver | admin; at least one admin)."
if [ -n "${ADMIN_USERS:-}" ]; then
  USER_EMAILS=$(python3 - "$ADMIN_USERS" <<'PY'
import re, sys
seen = {}
for e in [x.strip() for x in sys.argv[1].split(",") if x.strip()]:
    if ":" not in e: sys.exit("ADMIN_USERS entries are email:role")
    email, role = e.rsplit(":", 1); email = email.strip().lower(); role = role.strip()
    if not re.fullmatch(r"[^@\s:,]{1,64}@[a-z0-9.-]{1,190}\.[a-z]{2,}", email): sys.exit("malformed email in ADMIN_USERS")
    if role not in ("viewer", "author", "approver", "admin"): sys.exit("unknown role in ADMIN_USERS: " + role)
    if email in seen: sys.exit("duplicate email in ADMIN_USERS")
    seen[email] = role
if "admin" not in seen.values(): sys.exit("ADMIN_USERS needs at least one admin")
print(" ".join(seen))
PY
) || die "ADMIN_USERS is invalid (see the message above)."
  ok "ADMIN_USERS: $(echo "$USER_EMAILS" | wc -w) user(s) (emails are not written to the record)"
fi
case "$SEPARATE_APPROVER" in true|false) ;; *) die "SEPARATE_APPROVER must be true or false" ;; esac

TOKEN=$(gcloud auth print-access-token)
NEEDED='["serviceusage.services.enable","iam.serviceAccounts.create","iam.serviceAccounts.setIamPolicy","iam.serviceAccounts.actAs",
"storage.buckets.create","storage.buckets.update","storage.buckets.setIamPolicy","storage.objects.create","cloudbuild.builds.create",
"run.services.create","run.services.update","run.services.setIamPolicy","run.services.get","logging.logEntries.list"]'
MISSING=$(curl -sS -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  "https://cloudresourcemanager.googleapis.com/v1/projects/${PROJECT_ID}:testIamPermissions" -d "{\"permissions\": $NEEDED}" \
  | python3 -c "import json,sys;need=set(json.loads('''$NEEDED'''));got=set(json.load(sys.stdin).get('permissions',[]));print(' '.join(sorted(need-got)))")
[ -z "$MISSING" ] || die "$ACCOUNT lacks: $MISSING
  Needed roles (docs/14 §3): run.admin, iam.serviceAccountAdmin, storage.admin, serviceusage.serviceUsageAdmin,
  cloudbuild.builds.editor, iap.admin, logging.viewer (Owner has them all)."
ok "deployer permissions present"

# The engine must be exactly as recorded; it is fingerprinted now and compared at the end.
engine_fingerprint "$OUT/engine-before.json"
SERVING=$(python3 -c "import json;d=json.load(open('$OUT/engine-before.json'));f=[r for r,p,_ in d['serving'] if p==100];print(f[0] if len(f)==1 else '')")
if [ "$SERVING" = latest ]; then SERVING=$(python3 -c "import json;print(json.load(open('$OUT/engine-before.json'))['latestReadyRevision'])"); fi
[ "$SERVING" = "$EXPECT_ENGINE_REVISION" ] || die "The engine serves '$SERVING', not $EXPECT_ENGINE_REVISION. Stop: nothing was changed."
ok "engine $ENGINE_SERVICE serves $SERVING (fingerprint saved; it will not be modified)"
python3 -c "import json,sys;d=json.load(open('$OUT/engine-before.json'));sys.exit(1 if any('$ADMIN_SA' in m for _,ms in d['bindings'] for m in ms) else 0)" \
  || die "The admin service account already has a binding on the engine. It must not; investigate before deploying."

if [ "$PHASE" = all ]; then
  [ -f "$CONTEXT/STAGED_FILES.txt" ] || die "Staged admin context not found at $CONTEXT (tools/stage_admin.py)."
  ( cd "$CONTEXT" && while read -r sum size path; do
      [ "$(sha256sum "$path" | cut -d' ' -f1)" = "$sum" ] || { echo "checksum mismatch: $path"; exit 1; }
    done < STAGED_FILES.txt ) || die "The staged admin context was modified after staging."
  [ -z "$(find "$CONTEXT" -type f \( -iname '*.pdf' -o -iname '*.mforge' -o -iname '*.xlsx' \))" ] || die "A PDF or export is in the admin context."
  OLD=$(find "$CONTEXT" -type f ! -newermt '1980-01-02' | head -1)
  [ -z "$OLD" ] || die "Pre-1980 file times in the context (Maven would skip resources, D-037). Re-extract the bundle."
  [ -f "$SCHEMA" ] || die "Sanitised Monday schema not found at $SCHEMA."
  python3 -c "import json,sys;d=json.load(open('$SCHEMA'));sys.exit(0 if d.get('sanitised') and len(d['board']['columns'])>0 else 1)" \
    || die "$SCHEMA is not a sanitised schema (tools/stage_admin.py)."
  ok "staged context intact ($(wc -l < "$CONTEXT/STAGED_FILES.txt") files, no PDFs); sanitised schema present"
  gc iam service-accounts describe "$BUILD_SA" >/dev/null 2>&1 || die "Build service account $BUILD_SA not found (created by the engine deployment)."
  gc artifacts repositories describe "$AR_REPO" --location="$REGION" >/dev/null 2>&1 || die "Artifact Registry $AR_REPO not found."
  ok "reusing build SA, Artifact Registry $AR_REPO and $SRC_BUCKET (unchanged)"
fi
if gc run services describe "$ADMIN_SERVICE" --region="$REGION" >/dev/null 2>&1; then
  ADMIN_EXISTS=1
  [ "$PHASE" != all ] || [ "${UPDATE_EXISTING:-0}" = 1 ] || die "$ADMIN_SERVICE already exists. To deploy a new revision: UPDATE_EXISTING=1.
  To continue after the IAP console step: PHASE=access. To check it: PHASE=verify."
else
  ADMIN_EXISTS=0
  [ "$PHASE" = all ] || die "$ADMIN_SERVICE does not exist yet: run the full deployment first (PHASE=all)."
fi
if gcloud storage buckets describe "gs://$ADMIN_BUCKET" --format='value(name)' >/dev/null 2>&1; then
  BUCKET_PROJECT=$(gcloud storage buckets describe "gs://$ADMIN_BUCKET" --format='value(project_number)' 2>/dev/null || true)
  [ -z "$BUCKET_PROJECT" ] || [ "$BUCKET_PROJECT" = "$PROJECT_NUMBER" ] || die "gs://$ADMIN_BUCKET belongs to another project. Set ADMIN_BUCKET."
  BUCKET_EXISTS=1; ok "bucket gs://$ADMIN_BUCKET exists in this project (reused)"
else
  BUCKET_EXISTS=0; ok "bucket gs://$ADMIN_BUCKET will be created"
fi

# --------------------------------------------------------------------------------------------- 2-6. infrastructure, build, deploy
if [ "$PHASE" = all ]; then
  step "2. APIs (only iap.googleapis.com is new; the others are already enabled for the engine)"
  run gc services enable iap.googleapis.com run.googleapis.com artifactregistry.googleapis.com cloudbuild.googleapis.com \
    iam.googleapis.com storage.googleapis.com logging.googleapis.com

  step "3. Admin service account (no project roles)"
  gc iam service-accounts describe "$ADMIN_SA" >/dev/null 2>&1 \
    || run gc iam service-accounts create "$ADMIN_SA_NAME" --display-name="RSFA End Forms admin (private test environment)"
  run gc iam service-accounts add-iam-policy-binding "$ADMIN_SA" --member="$DEPLOYER" --role=roles/iam.serviceAccountUser

  step "4. Private, versioned admin bucket"
  [ "$BUCKET_EXISTS" = 1 ] || run gcloud storage buckets create "gs://$ADMIN_BUCKET" --project="$PROJECT_ID" --location="$REGION" \
    --default-storage-class=STANDARD --uniform-bucket-level-access --public-access-prevention
  run gcloud storage buckets update "gs://$ADMIN_BUCKET" --project="$PROJECT_ID" --versioning --lifecycle-file="$LIFECYCLE"
  run gcloud storage buckets add-iam-policy-binding "gs://$ADMIN_BUCKET" --project="$PROJECT_ID" \
    --member="serviceAccount:$ADMIN_SA" --role=roles/storage.objectCreator
  run gcloud storage buckets add-iam-policy-binding "gs://$ADMIN_BUCKET" --project="$PROJECT_ID" \
    --member="serviceAccount:$ADMIN_SA" --role=roles/storage.objectViewer
  ok "admin SA: create + read objects in this bucket only (cannot overwrite or delete)"

  step "5. Monday schema (sanitised) -> gs://$ADMIN_BUCKET/config/monday-schema.json"
  WANT=$(sha256sum "$SCHEMA" | cut -d' ' -f1)
  HAVE=$(gcloud storage cat "gs://$ADMIN_BUCKET/config/monday-schema.json" 2>/dev/null | sha256sum | cut -d' ' -f1 || true)
  EMPTY=$(printf '' | sha256sum | cut -d' ' -f1)
  if [ "$HAVE" = "$WANT" ]; then ok "schema already uploaded (identical)"
  elif [ "$HAVE" != "$EMPTY" ] && [ -n "$HAVE" ] && [ "${SCHEMA_REPLACE:-0}" != 1 ]; then
    die "A different schema is already in the bucket. Re-run with SCHEMA_REPLACE=1 to replace it (the bucket keeps the old version)."
  else run gcloud storage cp "$SCHEMA" "gs://$ADMIN_BUCKET/config/monday-schema.json" --project="$PROJECT_ID"; fi
  rec schemaSha256 "\"$WANT\""
  if [ "$APPLY" = 1 ]; then echo "  waiting ${IAM_WAIT:-60}s for IAM propagation..."; sleep "${IAM_WAIT:-60}"; fi

  step "6. Build the admin image with Cloud Build (unit tests run inside the build)"
  TAG=admin-$(date -u +%Y%m%d-%H%M%S)
  IMAGE=$IMAGE_BASE:$TAG
  run gc builds submit "$CONTEXT" --config="$CONTEXT/cloudbuild.yaml" --substitutions=_IMAGE="$IMAGE" --region="$REGION" \
    --service-account="projects/$PROJECT_ID/serviceAccounts/$BUILD_SA" --gcs-source-staging-dir="$SRC_BUCKET/source-admin"
  if [ "$APPLY" = 1 ]; then
    D=$(gc artifacts docker images describe "$IMAGE" --format='value(image_summary.digest)')
    [[ "$D" == sha256:* ]] || die "Could not resolve the digest of $IMAGE."
    DIGEST_REF=$IMAGE_BASE@$D
  else
    DIGEST_REF="$IMAGE_BASE@sha256:<digest of the build>"
  fi
  ok "image $DIGEST_REF"; rec image "\"$DIGEST_REF\""

  step "7. Deploy $ADMIN_SERVICE (IAM required$([ $NO_ORG = 1 ] && [ "$ADMIN_EXISTS" = 0 ] && echo '; IAP is enabled in the console next' || echo ' + IAP'))"
  cat > "$OUT/admin-env.yaml" <<EOF
ENGINE_ROLE: "admin"
ADMIN_USERS: "${ADMIN_USERS}"
IAP_AUDIENCE: "${IAP_AUDIENCE}"
ADMIN_BUCKET: "${ADMIN_BUCKET}"
ADMIN_SEPARATE_APPROVER: "${SEPARATE_APPROVER}"
EOF
  chmod 600 "$OUT/admin-env.yaml"
  IAP_FLAG=--iap
  if [ $NO_ORG = 1 ] && [ "$ADMIN_EXISTS" = 0 ]; then IAP_FLAG=--no-iap; fi  # first enablement must happen in the console
  run gc run deploy "$ADMIN_SERVICE" --image="$DIGEST_REF" --region="$REGION" --service-account="$ADMIN_SA" \
    --no-allow-unauthenticated --ingress=all "$IAP_FLAG" --cpu=1 --memory=1Gi --min-instances=0 --max-instances=2 \
    --concurrency=10 --timeout=60 --cpu-throttling --env-vars-file="$OUT/admin-env.yaml" \
    --labels=app=rsfa-end-forms-admin,stage=private-test
  if [ "$IAP_FLAG" = --no-iap ]; then
    cat <<EOF

  ---------------------------------------------------------------------------------------------------------------
  ONE-TIME CONSOLE STEP (project without a Google organization; Google cannot create IAP's OAuth client by API)
  The service is deployed but closed to everyone (IAM required, no IAP). Now, in the Google Cloud console:
    1. Google Auth Platform > Branding: app name "RSFA End Forms Admin", support email = $ACCOUNT.
       Audience: External; publishing status "Testing"; add every ADMIN_USERS email as a test user.
    2. Cloud Run > $ADMIN_SERVICE > Security > tick "Identity-Aware Proxy (IAP)" > Save.
       Accept the custom OAuth client IAP creates for you.
  Then continue with:  ADMIN_USERS='...' APPLY=1 PHASE=access bash deploy_admin.sh
  ---------------------------------------------------------------------------------------------------------------
EOF
    rec status "\"waiting for the IAP console step\""
    [ "$APPLY" = 1 ] && exit 0
  fi
fi

# --------------------------------------------------------------------------------------------- 8. IAP access
if [ "$PHASE" = all ] || [ "$PHASE" = access ]; then
  step "8. IAP access: the IAP service agent may invoke the admin service; named users may pass IAP"
  run gcloud beta services identity create --service=iap.googleapis.com --project="$PROJECT_ID"
  run gc run services add-iam-policy-binding "$ADMIN_SERVICE" --region="$REGION" \
    --member="serviceAccount:$IAP_AGENT" --role=roles/run.invoker
  for e in $USER_EMAILS; do
    run gc iap web add-iam-policy-binding --resource-type=cloud-run --service="$ADMIN_SERVICE" --region="$REGION" \
      --member="user:$e" --role=roles/iap.httpsResourceAccessor --condition=None
  done
  if [ "$PHASE" = access ]; then
    # makes sure IAP stays on in later deploys of the same revision configuration (console enablement done)
    run gc run services update "$ADMIN_SERVICE" --region="$REGION" --iap
  fi
fi

# --------------------------------------------------------------------------------------------- 9. verification
step "9. Verification (read-only)"
if [ "$APPLY" != 1 ] && [ "$ADMIN_EXISTS" = 0 ]; then
  ok "plan only: the service does not exist yet, so there is nothing to verify. Re-run with APPLY=1 to deploy."
  rec status "\"plan\""; exit 0
fi
FAIL=0
check() { if eval "$2"; then ok "$1"; else printf '  [FAIL] %s\n' "$1"; FAIL=1; fi; }
gc run services describe "$ADMIN_SERVICE" --region="$REGION" --format=json > "$OUT/admin-service.json"
gc run services get-iam-policy "$ADMIN_SERVICE" --region="$REGION" --format=json > "$OUT/admin-invokers.json"
gc iap web get-iam-policy --resource-type=cloud-run --service="$ADMIN_SERVICE" --region="$REGION" --format=json > "$OUT/admin-iap.json" 2>/dev/null \
  || echo '{}' > "$OUT/admin-iap.json"
gcloud storage buckets describe "gs://$ADMIN_BUCKET" --format=json > "$OUT/bucket.json"
gcloud storage buckets get-iam-policy "gs://$ADMIN_BUCKET" --format=json > "$OUT/bucket-iam.json"
gc projects get-iam-policy "$PROJECT_ID" --format=json > "$OUT/project-iam.json"
URL=$(python3 -c "import json;print(json.load(open('$OUT/admin-service.json'))['status'].get('url',''))")
REV=$(python3 -c "import json;print(json.load(open('$OUT/admin-service.json'))['status'].get('latestReadyRevisionName',''))")
rec url "\"$URL\""; rec revision "\"$REV\""
python3 - "$OUT/admin-service.json" "$ADMIN_SA" > "$OUT/admin-summary.json" <<'PY'
import json, sys
s = json.load(open(sys.argv[1])); t = s["spec"]["template"]; c = t["spec"]["containers"][0]
ann = s["metadata"].get("annotations", {}); tann = t["metadata"].get("annotations", {})
env = {e["name"]: e.get("value", "") for e in c.get("env", [])}
print(json.dumps({
  "iap": any("iap" in k and str(v).lower() == "true" for k, v in {**ann, **tann}.items()),
  "ingress": ann.get("run.googleapis.com/ingress"), "serviceAccountOk": t["spec"].get("serviceAccountName") == sys.argv[2],
  "maxInstances": tann.get("autoscaling.knative.dev/maxScale"), "role": env.get("ENGINE_ROLE"),
  "envKeys": sorted(env), "users": len([x for x in env.get("ADMIN_USERS", "").split(",") if x.strip()]),
}, indent=1))
PY
cat "$OUT/admin-summary.json"
J() { python3 -c "import json,sys;d=json.load(open('$1'));sys.exit(0 if ($2) else 1)"; }
check "IAP is enabled on $ADMIN_SERVICE" "J '$OUT/admin-summary.json' 'd[\"iap\"]'"
check "ENGINE_ROLE=admin, own service account, max 2 instances" \
  "J '$OUT/admin-summary.json' 'd[\"role\"]==\"admin\" and d[\"serviceAccountOk\"] and d[\"maxInstances\"]==\"2\"'"
check "no engine or relay secret in the admin service" \
  "J '$OUT/admin-summary.json' 'not set(d[\"envKeys\"]) & {\"ENGINE_API_KEY\",\"ENGINE_HMAC_SECRET\",\"RELAY_HMAC_SECRETS\"}'"
check "admin invokers = exactly the IAP service agent (no users, no allUsers)" \
  "J '$OUT/admin-invokers.json' '[m for b in d.get(\"bindings\",[]) for m in b[\"members\"]]==[\"serviceAccount:$IAP_AGENT\"]'"
check "IAP access only for named users (no allUsers / allAuthenticatedUsers / domains)" \
  "J '$OUT/admin-iap.json' 'all(m.startswith((\"user:\",\"group:\")) for b in d.get(\"bindings\",[]) for m in b[\"members\"])'"
check "bucket: public access prevention, uniform access, versioning" \
  "J '$OUT/bucket.json' 'd.get(\"public_access_prevention\")==\"enforced\" and d.get(\"uniform_bucket_level_access\") and d.get(\"versioning_enabled\")'"
check "bucket: no public members; admin SA has objectCreator + objectViewer only" \
  "J '$OUT/bucket-iam.json' 'not any(m in (\"allUsers\",\"allAuthenticatedUsers\") for b in d.get(\"bindings\",[]) for m in b[\"members\"]) and sorted(b[\"role\"] for b in d.get(\"bindings\",[]) if \"serviceAccount:$ADMIN_SA\" in b[\"members\"])==[\"roles/storage.objectCreator\",\"roles/storage.objectViewer\"]'"
check "admin SA has no project-level role" \
  "J '$OUT/project-iam.json' 'not any(\"serviceAccount:$ADMIN_SA\" in b[\"members\"] for b in d.get(\"bindings\",[]))'"
if [ -n "$URL" ]; then
  CODE=$(curl -s -o /dev/null -w '%{http_code}' "$URL/admin/")
  LOC=$(curl -s -o /dev/null -w '%{redirect_url}' "$URL/admin/")
  check "anonymous browser request is sent to Google sign-in or refused (got $CODE)" \
    "[ '$CODE' = 401 ] || [ '$CODE' = 403 ] || { [ '$CODE' = 302 ] && echo '$LOC' | grep -q 'accounts.google.com'; }"
  ID=$(gcloud auth print-identity-token 2>/dev/null || true)
  CODE2=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $ID" "$URL/admin/api/session")
  check "a Google ID token for another audience does not open the API (got $CODE2)" "[ '$CODE2' != 200 ]"
fi
engine_fingerprint "$OUT/engine-after.json"
check "engine $ENGINE_SERVICE unchanged (serving revision, image, config, IAM)" "cmp -s '$OUT/engine-before.json' '$OUT/engine-after.json'"
check "admin SA has no binding on the engine" \
  "J '$OUT/engine-after.json' 'not any(\"$ADMIN_SA\" in m for _,ms in d[\"bindings\"] for m in ms)'"
gc logging read "resource.type=\"cloud_run_revision\" AND resource.labels.service_name=\"$ADMIN_SERVICE\"" \
  --freshness=2h --limit=500 --format=json > "$OUT/admin-logs.json" 2>/dev/null || echo '[]' > "$OUT/admin-logs.json"
python3 - "$OUT/admin-logs.json" > "$OUT/log-scan.json" <<'PY'
import json, re, sys
logs = json.load(open(sys.argv[1])); texts = [e.get("textPayload", "") for e in logs]
print(json.dumps({"entries": len(texts), "started": any("admin service: auth=iap" in t for t in texts),
                  "withEmail": sum(1 for t in texts if re.search(r"[\w.+-]+@[\w-]+\.[\w.]+", t)),
                  "withPdfName": sum(1 for t in texts if ".pdf" in t.lower() and "/results/" not in t and "/file.pdf" not in t)}))
PY
cat "$OUT/log-scan.json"
check "service started in IAP mode; logs hold no email addresses or file names" \
  "J '$OUT/log-scan.json' 'd[\"started\"] and d[\"withEmail\"]==0 and d[\"withPdfName\"]==0'"
rec checks "\"$([ $FAIL = 0 ] && echo passed || echo FAILED)\""; rec finishedAt "\"$(date -u +%FT%TZ)\""
cat <<EOF

  Browser checklist (docs/14 §7), from an allowlisted Google account:
    open $URL  -> Google sign-in -> library with your email and role; upload the fictional example form
    (link on the Upload page); map a field; save; generate a test PDF; open the draft in a second browser profile.
  Record: $RECORD
EOF
[ $FAIL = 0 ] || die "Verification failed (see above). Rollback: bash rollback_admin.sh (the engine is not affected)."
ok "all automated checks passed"
