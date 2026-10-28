#!/usr/bin/env bash
# Rollback of the private administration service (D-041, docs/14 §6). RSFA runs this in Cloud Shell.
# Default is a PLAN (prints the commands). APPLY=1 executes. The engine service is never touched: it is only read
# before and after to prove it is unchanged.
#
#   TO_REVISION=rsfa-end-forms-admin-00001-abc APPLY=1 bash rollback_admin.sh   # a bad admin revision: traffic back
#   REMOVE=1 APPLY=1 bash rollback_admin.sh                                     # close the URL: delete the admin service
#   REMOVE=1 DELETE_DATA=1 APPLY=1 bash rollback_admin.sh                        # also delete the bucket (irreversible)
#
# REMOVE deletes: the admin Cloud Run service (its URL and IAP access go with it) and the admin SA's bucket bindings,
# then disables (not deletes) the admin service account. It keeps: the bucket and its data (unless DELETE_DATA=1),
# the IAP API, the OAuth client and consent screen (harmless; delete them in the console if wanted), the image.
set -euo pipefail
PROJECT_ID=${PROJECT_ID:-rsfa-end-forms-engine}
REGION=${REGION:-australia-southeast1}
ADMIN_SERVICE=${ADMIN_SERVICE:-rsfa-end-forms-admin}
ADMIN_SA=${ADMIN_SA_NAME:-rsfa-end-forms-admin}@${PROJECT_ID}.iam.gserviceaccount.com
ADMIN_BUCKET=${ADMIN_BUCKET:-${PROJECT_ID}-admin}
ENGINE_SERVICE=${ENGINE_SERVICE:-rsfa-end-forms-engine}   # READ ONLY
APPLY=${APPLY:-0}
OUT=${OUT:-$PWD/admin-rollback-output}
mkdir -p "$OUT"
step() { printf '\n=== %s ===\n' "$*"; }
ok()   { printf '  [ok] %s\n' "$*"; }
die()  { printf '\n  [BLOCKER] %s\n\n' "$*" >&2; exit 1; }
gc()   { gcloud --project="$PROJECT_ID" "$@"; }
run()  { local shown=("$@"); [ "$1" != gc ] || shown=(gcloud "--project=$PROJECT_ID" "${@:2}")
         printf '  %s %s\n' "$([ "$APPLY" = 1 ] && echo '$' || echo '[plan]')" "${shown[*]}"; [ "$APPLY" != 1 ] || "$@"; }
engine_state() {
  { gc run services describe "$ENGINE_SERVICE" --region="$REGION" --format='json(status.traffic,status.latestReadyRevisionName,spec.template)'
    gc run services get-iam-policy "$ENGINE_SERVICE" --region="$REGION" --format='json(bindings)'; } > "$1"
}

step "Preflight (read-only) — mode: $([ "$APPLY" = 1 ] && echo APPLY || echo PLAN)"
[ -n "${TO_REVISION:-}" ] || [ "${REMOVE:-0}" = 1 ] || die "Choose TO_REVISION=<earlier admin revision> or REMOVE=1."
[ "${DELETE_DATA:-0}" != 1 ] || [ "${REMOVE:-0}" = 1 ] || die "DELETE_DATA=1 requires REMOVE=1."
engine_state "$OUT/engine-before.json"
ok "engine $ENGINE_SERVICE state saved (it will not be modified)"
gc run services describe "$ADMIN_SERVICE" --region="$REGION" >/dev/null 2>&1 || die "$ADMIN_SERVICE does not exist: nothing to roll back."

if [ -n "${TO_REVISION:-}" ]; then
  step "Traffic back to $TO_REVISION"
  case "$TO_REVISION" in "$ADMIN_SERVICE"-*) ;; *) die "TO_REVISION must be a revision of $ADMIN_SERVICE." ;; esac
  gc run revisions describe "$TO_REVISION" --region="$REGION" >/dev/null 2>&1 || die "Revision $TO_REVISION not found."
  run gc run services update-traffic "$ADMIN_SERVICE" --region="$REGION" --to-revisions="$TO_REVISION=100"
fi

if [ "${REMOVE:-0}" = 1 ]; then
  step "Remove the admin service (closes the private URL)"
  run gc run services delete "$ADMIN_SERVICE" --region="$REGION" --quiet
  for role in roles/storage.objectCreator roles/storage.objectViewer; do
    run gcloud storage buckets remove-iam-policy-binding "gs://$ADMIN_BUCKET" --project="$PROJECT_ID" \
      --member="serviceAccount:$ADMIN_SA" --role="$role"
  done
  run gc iam service-accounts disable "$ADMIN_SA"
  if [ "${DELETE_DATA:-0}" = 1 ]; then
    echo "  DELETE_DATA=1: the bucket, every uploaded template, draft, audit record and version will be deleted."
    run gcloud storage rm --recursive "gs://$ADMIN_BUCKET" --project="$PROJECT_ID"
  else
    ok "bucket gs://$ADMIN_BUCKET kept (templates, drafts and audit history remain; versioned)"
  fi
fi

step "Engine check (read-only)"
engine_state "$OUT/engine-after.json"
cmp -s "$OUT/engine-before.json" "$OUT/engine-after.json" || die "The engine state changed during the rollback: investigate."
ok "engine $ENGINE_SERVICE unchanged"
