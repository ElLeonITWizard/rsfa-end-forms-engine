# 04 — First Cloud Run deployment (POC), runbook

**Status (2026-10-27): approved by RSFA, NOT yet deployed.**
- RSFA approved the deployment and the template allowlist entry (Leonardo Melman, 2026-10-27, `RSFA-END-FORMS-POC-2026-10-27`).
- Blocker: the Claude Code cloud session that prepared it has **no Google Cloud credentials** (`CREDENTIALS_MISSING`).
  Nothing was created in the project.
- No Make, Monday or SharePoint change is part of this deployment.

## Fastest path: one script (recommended)

`deploy/deploy_poc.sh` performs every step below, in order:
1. preflight: account, project, billing, a permission test, and integrity of the staged context;
2. minimal infrastructure;
3. the least-privilege build service account;
4. the Cloud Build image build, with an automatic fallback to a local Docker build if needed;
5. the Cloud Run deployment with `--no-allow-unauthenticated`;
6. `run.invoker` for **the identity `gcloud` is logged in as, and nobody else**;
7. verification: 403 without a token, authenticated `/health` and `/generate`, PDF field and declaration checks,
   byte-identity with the local build, and logs free of payload values.

It writes `cloudrun-output/deployment-record.json`. It is idempotent, stops at the first blocker with the exact fix,
and refuses to touch an existing service unless `UPDATE_EXISTING=1` is set.

It was tested end-to-end (2026-10-27) in this environment:
- with a recording `gcloud` stub and the real POC image behind an authenticating front proxy;
- 31 commands issued, none forbidden (no `--allow-unauthenticated`, keys, Owner or Editor);
- every verification passed;
- a second run stopped without changes.

**Option A: Cloud Shell (no credentials leave Google; recommended).**
1. Build the bundle from the repo on a machine with the private `.local/` files:
   `python3 tools/stage_deploy.py --templates .local/generated/templates --out .local/deploy/context && (cd engine && mvn -B package) && bash deploy/make_bundle.sh`
   The bundle `.local/deploy/rsfa-end-forms-poc-bundle.tar.gz` contains the staged context (1 blank template), the
   script, the PDF verifier, the synthetic fixture and the expected PDF SHA-256.
2. In https://shell.cloud.google.com (logged in as the deployer): upload the bundle, then run
   `tar -xzf rsfa-end-forms-poc-bundle.tar.gz && cd bundle && gcloud config set project rsfa-end-forms-engine && bash deploy_poc.sh`
3. Hand `cloudrun-output/deployment-record.json` and `cloudrun-output/BNZ_Boarder_cloudrun.pdf` back so the record
   below can be filled in and committed.

**Option B: a Claude Code session with credentials.**
- Add a short-lived OAuth access token to the environment settings as `CLOUDSDK_AUTH_ACCESS_TOKEN`
  (created by the deployer with `gcloud auth print-access-token`; it expires in about 1 hour). Start a new session.
- Never paste tokens into chat, and do not create service-account keys.
- Limitation: an access token cannot mint the **ID token** that Cloud Run IAM requires. A session with only an access
  token can therefore create and deploy, but the authenticated `/health` and `/generate` checks must then be run by the
  deployer: `gcloud run services proxy` or the curl commands in section 4.

## Demo page access (iteration 5)

The image now also serves the demo page at `/` (D-020). Cloud Run IAM protects it exactly like `/health` and
`/generate`, and no public endpoint is added. To open it, run the following as an identity that has `run.invoker`
(after deployment, only the deployer):

```bash
gcloud run services proxy rsfa-end-forms-engine --region=australia-southeast1 --project=rsfa-end-forms-engine --port=8080
# then open http://localhost:8080/  (Cloud Shell: Web Preview -> port 8080)
```

- `deploy_poc.sh` step 8 checks the page: 401/403 without a token, 200 with one.
- Rebuild the bundle before running the script, so that the staged context contains the page.
- A service deployed before iteration 5 has no page. Replacing its revision (`UPDATE_EXISTING=1`) needs RSFA's
  explicit approval.
- Status: **not exercised against real Cloud Run** (no credentials here). The flow was tested with the POC image behind
  an IAM stand-in plus a `gcloud run services proxy` emulator (docs/02). The meeting walk-through is in
  `docs/05-demo-script.md`.

## Deployment record

| Item | Value |
|---|---|
| Deployed | **not yet** |
| Service URL | pending |
| Revision | pending |
| Image digest | pending |
| Invoker | the deployer's own identity only (not recorded here) |
| Verification | pending (expected PDF SHA-256 for `tests/fixtures/bnz-boarder.request.json`: `2ae7bfacf48b8ee8e63aaf9ce28997650abd2c07b59ddbeade9928ce3269d6f6`) |

The manual runbook follows. The script runs exactly these steps.

Goal: a **private** Cloud Run service that generates the BNZ Boarder PDF from a synthetic JSON payload,
without MacroForge. Commands were written for and checked against `gcloud` 570.0.0 (flag names verified offline).

## Architecture of this deployment

```text
laptop (repo + private .local/)                      Google Cloud project rsfa-end-forms-engine, australia-southeast1
  tools/stage_deploy.py ──► .local/deploy/context ──► gs://rsfa-end-forms-engine-build-src  (private, 7-day lifecycle)
   (allowlist + SHA + approval + blank check)              │
                                                           ▼  Cloud Build (regional), SA rsfa-end-forms-build
                                                       Dockerfile target `poc`: Maven tests ► jar ► JRE 21 + fonts
                                                           │                    + 1 allowlisted template
                                                           ▼
                                        Artifact Registry  rsfa-end-forms  (Docker, private)
                                                           │  deploy by digest
                                                           ▼
                                        Cloud Run  rsfa-end-forms-engine  (IAM required, ENGINE_AUTH=PLATFORM_IAM,
                                                   SA rsfa-end-forms-runtime, 1 vCPU / 1 GiB, 0–2 instances,
                                                   concurrency 1, timeout 120 s, request-based billing)
```

What enters the image:
- the engine jar (built and unit-tested inside the build);
- `mappings/bnz/BNZ_BOARDER_FORM@2.0_8-24.json`;
- the blank BNZ Boarder PDF (SHA-256 `7982ae8c…0509795`).

Nothing else from `.local/` is uploaded. The `.mforge`, client data, other templates and fixtures are all excluded.
`STAGED_FILES.txt` lists every uploaded file with its SHA-256.

## Variables (set once per shell)

```bash
export PROJECT_ID=rsfa-end-forms-engine
export REGION=australia-southeast1
export SERVICE=rsfa-end-forms-engine
export RUNTIME_SA=rsfa-end-forms-runtime@${PROJECT_ID}.iam.gserviceaccount.com
export BUILD_SA_NAME=rsfa-end-forms-build
export BUILD_SA=${BUILD_SA_NAME}@${PROJECT_ID}.iam.gserviceaccount.com
export AR_REPO=rsfa-end-forms
export SRC_BUCKET=gs://${PROJECT_ID}-build-src
export IMAGE_BASE=${REGION}-docker.pkg.dev/${PROJECT_ID}/${AR_REPO}/end-forms-engine
export DEPLOYER="user:YOUR_GOOGLE_ACCOUNT_EMAIL"     # the person running these commands and testing
```

## 0. Prerequisites (check, do not assume)

| Need | Check |
|---|---|
| `gcloud` installed and logged in | `gcloud auth list` |
| Billing enabled on the project | `gcloud billing projects describe $PROJECT_ID` |
| Runtime SA exists | `gcloud iam service-accounts describe $RUNTIME_SA --project=$PROJECT_ID` |
| Deployer roles (one-time setup) | Owner, **or**: `roles/serviceusage.serviceUsageAdmin`, `roles/artifactregistry.admin`, `roles/iam.serviceAccountAdmin`, `roles/resourcemanager.projectIamAdmin`, `roles/storage.admin`, `roles/cloudbuild.builds.editor`, `roles/run.admin` |
| Local: Java 21, Maven, Python 3 + `pip install -r tools/requirements.txt`, the `.mforge` in `.local/reference/`, templates extracted (`import_mforge.py extract`) | README quickstart |
| The approval in `deploy/poc-allowlist.json` is filled in by RSFA (approvedBy, approvedOn, reference) | `tools/stage_deploy.py` refuses otherwise |

## 1. One-time project setup

```bash
gcloud config set project $PROJECT_ID

# APIs
gcloud services enable run.googleapis.com artifactregistry.googleapis.com cloudbuild.googleapis.com \
  iam.googleapis.com storage.googleapis.com --project=$PROJECT_ID

# Private Docker repository (Artifact Registry repositories are private by default)
gcloud artifacts repositories create $AR_REPO --repository-format=docker --location=$REGION \
  --description="RSFA End Forms Engine images (private)" --project=$PROJECT_ID

# Private, regional bucket for uploaded build sources, auto-deleted after 7 days
gcloud storage buckets create $SRC_BUCKET --location=$REGION --uniform-bucket-level-access \
  --public-access-prevention --project=$PROJECT_ID
gcloud storage buckets update $SRC_BUCKET --lifecycle-file=deploy/build-src-lifecycle.json

# Dedicated, least-privilege build service account
gcloud iam service-accounts create $BUILD_SA_NAME --display-name="RSFA End Forms Cloud Build" --project=$PROJECT_ID
gcloud artifacts repositories add-iam-policy-binding $AR_REPO --location=$REGION \
  --member=serviceAccount:$BUILD_SA --role=roles/artifactregistry.writer --project=$PROJECT_ID
gcloud storage buckets add-iam-policy-binding $SRC_BUCKET \
  --member=serviceAccount:$BUILD_SA --role=roles/storage.objectViewer
gcloud projects add-iam-policy-binding $PROJECT_ID \
  --member=serviceAccount:$BUILD_SA --role=roles/logging.logWriter --condition=None

# The deployer may run builds as the build SA and deploy revisions running as the runtime SA
gcloud iam service-accounts add-iam-policy-binding $BUILD_SA --member=$DEPLOYER \
  --role=roles/iam.serviceAccountUser --project=$PROJECT_ID
gcloud iam service-accounts add-iam-policy-binding $RUNTIME_SA --member=$DEPLOYER \
  --role=roles/iam.serviceAccountUser --project=$PROJECT_ID
```

The runtime SA needs **no project roles** for this POC: there is no Cloud Storage, Secret Manager or outbound call.
Check that it has none:
```bash
gcloud projects get-iam-policy $PROJECT_ID --flatten="bindings[].members" \
  --filter="bindings.members:serviceAccount:$RUNTIME_SA" --format="value(bindings.role)"
```

## 2. Build (per release)

```bash
git status --short                                   # must be clean; deploy only committed code
python3 -m unittest discover -s tests/importer -v
(cd engine && mvn -B package)                        # all Java tests incl. legacy templates must pass

python3 tools/stage_deploy.py --templates .local/generated/templates --out .local/deploy/context
test ! -e .local/deploy/context/UNAPPROVED_LOCAL_TEST_ONLY && echo "context approved"
cat .local/deploy/context/STAGED_FILES.txt           # review: 1 manifest, 1 template, engine sources

export TAG=poc-$(git rev-parse --short HEAD)
export IMAGE=$IMAGE_BASE:$TAG
gcloud builds submit .local/deploy/context \
  --config=.local/deploy/context/cloudbuild.yaml \
  --substitutions=_IMAGE=$IMAGE \
  --region=$REGION \
  --service-account=projects/$PROJECT_ID/serviceAccounts/$BUILD_SA \
  --gcs-source-staging-dir=$SRC_BUCKET/source \
  --project=$PROJECT_ID

export IMAGE_DIGEST=$(gcloud artifacts docker images describe $IMAGE \
  --format='value(image_summary.fully_qualified_digest)')
echo $IMAGE_DIGEST                                   # deploy this immutable reference
```

Fallback if Cloud Build rejects the BuildKit `RUN --mount` syntax (to be confirmed on the first run). Build locally
with Docker and push. This needs `roles/artifactregistry.writer` for the deployer on the repository:
```bash
gcloud auth configure-docker ${REGION}-docker.pkg.dev
docker build -f engine/Dockerfile --target poc -t $IMAGE .local/deploy/context
docker push $IMAGE
```

## 3. Deploy (authentication required)

```bash
gcloud run deploy $SERVICE \
  --image=$IMAGE_DIGEST \
  --region=$REGION --project=$PROJECT_ID \
  --service-account=$RUNTIME_SA \
  --no-allow-unauthenticated \
  --ingress=all \
  --cpu=1 --memory=1Gi \
  --min-instances=0 --max-instances=2 \
  --concurrency=1 --timeout=120 \
  --cpu-throttling \
  --set-env-vars=ENGINE_AUTH=PLATFORM_IAM \
  --labels=app=rsfa-end-forms-engine,stage=poc
```

Notes:
- `--cpu-throttling` is request-based billing.
- `--no-allow-unauthenticated` together with the default invoker IAM check (never pass `--no-invoker-iam-check`)
  keeps authentication mandatory.
- `ENGINE_AUTH=PLATFORM_IAM` is accepted by the engine only when Cloud Run's `K_SERVICE` is set. Started anywhere
  else, it refuses to run.

Grant the tester permission to call it, and confirm that nobody else can:
```bash
gcloud run services add-iam-policy-binding $SERVICE --region=$REGION --project=$PROJECT_ID \
  --member=$DEPLOYER --role=roles/run.invoker
gcloud run services get-iam-policy $SERVICE --region=$REGION --project=$PROJECT_ID   # no allUsers / allAuthenticatedUsers
```

## 4. Verify with authenticated requests (fictional data only)

```bash
export URL=$(gcloud run services describe $SERVICE --region=$REGION --project=$PROJECT_ID --format='value(status.url)')

curl -s -o /dev/null -w '%{http_code}\n' "$URL/health"          # expect 403: no token, rejected by Cloud Run

export TOKEN=$(gcloud auth print-identity-token)
curl -s -H "Authorization: Bearer $TOKEN" "$URL/health"          # {"status":"ok","templates":1}

curl -s -o .local/output/cloudrun_BNZ_Boarder.pdf -D - \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  --data @tests/fixtures/bnz-boarder.request.json "$URL/generate"
# expect: HTTP/2 200, content-type application/pdf, x-fields-filled: 9, x-fill-warnings: 0
```

Alternative without handling tokens: `gcloud run services proxy` authenticates as you and listens on localhost.
```bash
gcloud run services proxy $SERVICE --region=$REGION --project=$PROJECT_ID --port=8081
curl -s localhost:8081/health
curl -s -o out.pdf -H "Content-Type: application/json" --data @tests/fixtures/bnz-boarder.request.json localhost:8081/generate
```

Acceptance:
1. 403 without a token.
2. 200 `/health` with a token.
3. `/generate` returns the BNZ Boarder PDF with 7 fields and both customer-name overlays. The `today` value
   in the fixture pins `Date 1`/`Date 2` to 271026.
4. The PDF is byte-identical to the local `.local/output/BNZ_BoarderForm_POC.pdf` built from the same commit
   (verified in the local Cloud Run emulation).
5. The logs show one line per request and no data values:
   `gcloud run services logs read $SERVICE --region=$REGION --limit=20`.

## 5. Rollback and emergency stop

| Situation | Command |
|---|---|
| First deployment misbehaves (nothing to roll back to) | `gcloud run services delete $SERVICE --region=$REGION --project=$PROJECT_ID` |
| A later revision misbehaves | `gcloud run revisions list --service=$SERVICE --region=$REGION` then `gcloud run services update-traffic $SERVICE --region=$REGION --to-revisions=<GOOD_REVISION>=100` |
| Stop all access immediately, keep the service | `gcloud run services remove-iam-policy-binding $SERVICE --region=$REGION --member=$DEPLOYER --role=roles/run.invoker` (repeat for every invoker) |
| Remove an image | `gcloud artifacts docker images delete $IMAGE_DIGEST --delete-tags` |
| Remove everything created here | delete the service, `gcloud artifacts repositories delete $AR_REPO --location=$REGION`, `gcloud storage rm -r $SRC_BUCKET`, `gcloud iam service-accounts delete $BUILD_SA`, then remove the IAM bindings granted in step 1 |

## Costs (qualitative)

There is no idle cost for the service (min 0, request-based billing). Small costs:
- Artifact Registry storage (about 0.5 GB per image; delete old images);
- Cloud Build minutes per release;
- per-request CPU and memory while `/generate` runs (measured locally at about 0.2 s warm, about 0.9 s on a cold first request).

## Remaining limitations of this deployment

- Only the BNZ Boarder template is available. Other templates return 404.
- Service-to-service authentication for Make is **not set up** (D-009): calling from Make needs a design decision
  (Google ID token from a service account, or API Gateway). There is no Make integration in this deployment.
- Templates are baked into the image (D-018). Changing a template means a new image. A private GCS volume (D-010) is the production direction.
- Equivalence with MacroForge is not proven. Overlay font and size are engine policy.
- The image base digests are pinned (2026-10-27). Refresh them deliberately for security patches (rebuild and redeploy).
