#!/usr/bin/env bash
# Rollback of the relay (docs/15 §8). Plan by default; APPLY=1 executes.
#   CLOSE=1 APPLY=1 bash rollback_relay.sh    # instant: remove public access (allUsers) — Make is cut off, nothing deleted
#   REMOVE=1 APPLY=1 bash rollback_relay.sh   # also: remove the relay SA's invoker on the engine, delete the relay
#                                             # service, disable the SA. Keeps the secret and the replay bucket.
#   PURGE=1 APPLY=1 bash rollback_relay.sh    # REMOVE + delete the secret and the replay bucket (irreversible)
# The engine's revision, image, env and traffic are never changed; only the relay SA's invoker binding is removed.
set -euo pipefail
export CLOUDSDK_CORE_DISABLE_PROMPTS=1
PROJECT_ID=${PROJECT_ID:-rsfa-end-forms-engine}
REGION=${REGION:-australia-southeast1}
RELAY_SERVICE=${RELAY_SERVICE:-rsfa-end-forms-relay}
RELAY_SA=${RELAY_SA_NAME:-rsfa-end-forms-relay}@${PROJECT_ID}.iam.gserviceaccount.com
SECRET_ID=${SECRET_ID:-rsfa-make-relay-hmac}
REPLAY_BUCKET=${REPLAY_BUCKET:-${PROJECT_ID}-relay-replay}
ENGINE_SERVICE=${ENGINE_SERVICE:-rsfa-end-forms-engine}
APPLY=${APPLY:-0}
ok()  { printf '  [ok] %s\n' "$*"; }
die() { printf '\n  [BLOCKER] %s\n\n' "$*" >&2; exit 1; }
gc()  { gcloud --project="$PROJECT_ID" "$@"; }
run() { local shown=("$@"); [ "$1" != gc ] || shown=(gcloud "--project=$PROJECT_ID" "${@:2}")
        printf '  %s %s\n' "$([ "$APPLY" = 1 ] && echo '$' || echo '[plan]')" "${shown[*]}"; [ "$APPLY" != 1 ] || "$@"; }
case "$RELAY_SERVICE" in *relay*) ;; *) die "RELAY_SERVICE must contain 'relay'." ;; esac
[ "$RELAY_SERVICE" != "$ENGINE_SERVICE" ] || die "RELAY_SERVICE must not be the engine service."
case "$RELAY_SA" in *relay*@*) ;; *) die "RELAY_SA_NAME must contain 'relay'." ;; esac
case "$REPLAY_BUCKET" in *relay*) ;; *) die "REPLAY_BUCKET must contain 'relay'." ;; esac
[ "${CLOSE:-0}${REMOVE:-0}${PURGE:-0}" != 000 ] || die "Choose CLOSE=1, REMOVE=1 or PURGE=1."
echo "=== mode: $([ "$APPLY" = 1 ] && echo APPLY || echo PLAN) ==="
run gc run services remove-iam-policy-binding "$RELAY_SERVICE" --region="$REGION" --member=allUsers --role=roles/run.invoker
if [ "${REMOVE:-0}" = 1 ] || [ "${PURGE:-0}" = 1 ]; then
  run gc run services remove-iam-policy-binding "$ENGINE_SERVICE" --region="$REGION" \
    --member="serviceAccount:$RELAY_SA" --role=roles/run.invoker
  run gc run services delete "$RELAY_SERVICE" --region="$REGION" --quiet
  run gc iam service-accounts disable "$RELAY_SA"
fi
if [ "${PURGE:-0}" = 1 ]; then
  run gc secrets delete "$SECRET_ID" --quiet
  run gcloud storage rm --recursive "gs://$REPLAY_BUCKET" --project="$PROJECT_ID"
fi
ok "done (engine revision, image, env and traffic untouched)"
