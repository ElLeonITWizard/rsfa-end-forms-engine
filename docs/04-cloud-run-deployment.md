# 04 — First Cloud Run deployment (POC), runbook

**Status (2026-10-27, iteration 6): revision `rsfa-end-forms-engine-00002-nan` (demo page) is LIVE.** RSFA deployed it
with the revision-2 bundle; this is reported by RSFA and not re-checked from this repository. Its deployment record is
still to be handed back. Revision 1 (`00001-6qm`) remains the rollback target.

**Iterations 6 and 7 change nothing in Google Cloud.** Revision `00002-nan` stays untouched.
- GHL Individual and GHL Trust are ready for acceptance testing locally; ASB and ANZ Boarder are blocked (docs/06).
- `deploy/poc-allowlist.json` still approves only BNZ Boarder.
- The `/v1/masterboard/documents` endpoint exists only in the source tree, not in the live revision.
- A future revision follows the same `UPDATE_EXISTING=1` no-traffic candidate procedure below, after RSFA approves the
  exact bundle, and adding any template to the allowlist needs RSFA's approval record.

**Proposed next deployment (iterations 7–8, not prepared as commands yet; needs RSFA approval).** Full resource list,
IAM, costs and rollback: [docs/10 §7–8](10-relay-security-and-infrastructure.md). Iteration 8 adds the replay bucket
(`<project>-relay-replay`, the relay SA has `storage.objectCreator` on it only) and deploys the relay first **without**
`allUsers` so it can be verified through `gcloud run services proxy`.
1. Engine revision 3 with `/v1/masterboard/documents`, same image procedure (`UPDATE_EXISTING=1`, no-traffic tag,
   verify, promote). The allowlist stays BNZ-only unless RSFA records an approval for GHL Individual/Trust after
   acceptance testing. Rollback: `update-traffic --to-revisions=rsfa-end-forms-engine-00002-nan=100`.
2. Secret Manager API, one HMAC secret, service account `rsfa-make-relay@` (secret accessor on that secret; run invoker
   on the engine only).
3. Relay service `rsfa-end-forms-relay` from the same image (`ENGINE_ROLE=relay`); the only public endpoint
   (`--allow-unauthenticated`), so it is created only after RSFA approves the exposure and Make's HMAC is verified.
4. Isolated Make test scenario T1–T9 (docs/07 section 4), test SharePoint location only.

History:
- RSFA approved the deployment and the template allowlist entry (Leonardo Melman, 2026-10-27, `RSFA-END-FORMS-POC-2026-10-27`).
- RSFA ran `deploy_poc.sh` from Cloud Shell. See "Deployment record" below; it is taken from the deployer's
  `deployment-record.json`.
- RSFA authorised preparing a second revision of the same service to add the demo page, with rollback to revision 1
  kept available. See "Revision 2" below. The Claude Code session has no Google Cloud credentials and runs nothing in the cloud.
- No Make, Monday or SharePoint change is part of either revision.

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
- Revision 1 has no page. Revision 2 adds it (prepared; see "Revision 2" above).
- Status: **not exercised against real Cloud Run** (no credentials here). The flow was tested with the POC image behind
  an IAM stand-in plus a `gcloud run services proxy` emulator (docs/02). The meeting walk-through is in
  `docs/05-demo-script.md`.

## Deployment record

Revision 1. Source: `cloudrun-output/deployment-record.json` returned by the deployer
(started 2026-10-27T21:49:43Z, finished 21:55:03Z). Classification: confirmed from the deployer's record; not
re-checked from this repository.

| Item | Value |
|---|---|
| Deployed | **yes**, 2026-10-27, by `deploy_poc.sh` (first-time mode), built with Cloud Build |
| Service URL | `https://rsfa-end-forms-engine-zjsgxv2kxq-ts.a.run.app` (IAM required; 403 without a token) |
| Revision | `rsfa-end-forms-engine-00001-6qm` |
| Image digest | `australia-southeast1-docker.pkg.dev/rsfa-end-forms-engine/rsfa-end-forms/end-forms-engine@sha256:ccf077f73ad0a9bdf6f93252d8a43a074368da7810b7fd736e3c03dbc8e248b4` |
| Configuration | ingress all; SA `rsfa-end-forms-runtime@…`; 1 vCPU; 1 GiB; min 0; max 2; concurrency 1; timeout 120 s; request-based billing; `ENGINE_AUTH=PLATFORM_IAM` |
| Invoker | the deployer's own identity only (not recorded here) |
| Verification | **Passed**: unauthenticated `/health` 403; authenticated `/health` 200; `/generate` 200; PDF SHA-256 `2ae7bfacf48b8ee8e63aaf9ce28997650abd2c07b59ddbeade9928ce3269d6f6` = local build; 7 fields + 2 overlays; no ticks, no signature values; logs: 16 entries, 0 fixture values |
| Demo page | not in revision 1 (it predates commit `900187f`) |

## Revision 2: add the demo page (prepared 2026-10-27; deployed by RSFA as `00002-nan`, reported)

**What changes:** only the image. The new image contains:
- the demo page (`900187f`);
- `/health` counting usable templates only (D-022);
- the build fix from D-021.

The configuration, IAM, runtime SA, template and fixture are unchanged.

**How `deploy_poc.sh` deploys it (`UPDATE_EXISTING=1`, D-022):**
1. **Before any change**, it refuses unless all of these hold:
   - the serving revision is `EXPECT_PREVIOUS_REVISION`;
   - the live configuration equals the documented one;
   - the invoker policy is exactly the current account.
2. It builds the new image with Cloud Build. Tests run inside the build.
3. It deploys with `--no-traffic --tag=candidate`. Revision 1 keeps 100% of traffic. It then checks that:
   - the configuration is identical;
   - the IAM policy is unchanged.
4. It verifies the **candidate URL** (`https://candidate---…`, which is also IAM-protected):
   - 403 without a token;
   - `/health` = `{"status":"ok","templates":1,"available":["BNZ_BOARDER_FORM@2.0_8-24"]}`;
   - `/generate` 200, with the PDF checks and the exact SHA;
   - the demo page 403 without a token and 200 with one.
5. Only then it runs `update-traffic --to-latest` (100% to revision 2) and removes the `candidate` tag. It then re-checks
   the main URL: page 403/200, `/health`, and a `/generate` whose PDF is byte-identical.
6. It runs the log check (no fixture values) and writes the record with `previousRevision` and the exact `rollback` command.

If any step before 5 fails, revision 1 is still serving all traffic and nothing needs to be rolled back.
Optional clean-up: `gcloud run services update-traffic rsfa-end-forms-engine --region=australia-southeast1 --remove-tags=candidate`.

**Tested here (2026-10-27)**, with a recording `gcloud` stub and two real containers behind IAM stand-ins:
- Revision 1 was the old image without the page. Revision 2 was the image built from the bundle's own context.
- Passed end to end. 33 `gcloud` calls; the only deploy call used `--no-traffic --tag=candidate` and kept every
  configuration flag; no forbidden command.
- After promotion, the browser smoke test passed through the main URL (20/20) via the `gcloud run services proxy` emulator.
- Negative cases all stopped safely:
  - broken candidate: stopped before promotion; 0 traffic changes;
  - wrong `EXPECT_PREVIOUS_REVISION`: stopped before any change;
  - live configuration differs: stopped before any change;
  - `UPDATE_EXISTING` missing: refused.
- **Not tested against real Cloud Run** (no credentials here).

**Bundle:** `rsfa-end-forms-poc-bundle-rev2.tar.gz`.
- Built from the commit that adds this section. The SHA-256 is given with the hand-over, not stored here, because the
  archive is rebuilt per commit.
- Contents:
  - the staged context (26 files);
  - one template: the approved blank BNZ Boarder `7982ae8c…0509795`, checked against the allowlist SHA and as blank;
  - the script, the PDF verifier, the synthetic fixture, and the expected PDF SHA.
- Sensitive-data scan: 771 values from the legacy export's sensitive sections and from the Make output bundle were
  compared against every bundle file, including PDF text and field values. Only counts were printed. The only matches
  were generic words and column/field names (schema labels). No client value is present.

**Cloud Shell commands (as the deployer, the identity that is the current invoker):**

```bash
# 1. Upload rsfa-end-forms-poc-bundle-rev2.tar.gz (Cloud Shell: More (⋮) > Upload), then check it:
cd ~ && echo "<SHA-256 from the hand-over>  rsfa-end-forms-poc-bundle-rev2.tar.gz" | sha256sum -c -
# 2. Unpack into a fresh folder (does not touch the revision-1 bundle folder)
rm -rf ~/rev2 && mkdir ~/rev2 && tar -xzf ~/rsfa-end-forms-poc-bundle-rev2.tar.gz -C ~/rev2 && cd ~/rev2/bundle
gcloud config set project rsfa-end-forms-engine
gcloud config get-value account          # must be the deployer that is the service's invoker
# 3. Deploy revision 2 (verified on a no-traffic candidate before it receives traffic)
UPDATE_EXISTING=1 EXPECT_PREVIOUS_REVISION=rsfa-end-forms-engine-00001-6qm bash deploy_poc.sh 2>&1 | tee deploy-rev2.log
```

**Post-deployment verification (in `~/rev2/bundle`):**

```bash
S=rsfa-end-forms-engine; R=australia-southeast1
gcloud run services describe $S --region=$R --format='yaml(status.latestReadyRevisionName,status.traffic)'  # new revision = 100%
gcloud run revisions list --service=$S --region=$R                    # 00001-6qm still listed (rollback target)
gcloud run services get-iam-policy $S --region=$R                     # one invoker: you; no allUsers
URL=$(gcloud run services describe $S --region=$R --format='value(status.url)')
TOKEN=$(gcloud auth print-identity-token)
curl -s -o /dev/null -w 'UI without token: %{http_code}\n' "$URL/"                                  # 403
curl -s -o /dev/null -w 'UI with token: %{http_code} %{content_type}\n' -H "Authorization: Bearer $TOKEN" "$URL/"  # 200 text/html
curl -s -H "Authorization: Bearer $TOKEN" "$URL/health"; echo         # {"status":"ok","templates":1,"available":["BNZ_BOARDER_FORM@2.0_8-24"]}
curl -s -o rev2.pdf -D - -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  --data @fixture.request.json "$URL/generate" | grep -i -E '^(HTTP|x-)'          # 200, x-fields-filled: 9, x-engine: …no-ai
cloudrun-output/venv/bin/python verify_pdf.py rev2.pdf expected_pdf_sha256.txt   # "ok": true, matchesLocalBuild true
# Visual demo: keep this running, then Web Preview > Preview on port 8080 (badge: "Engine online · 1 form ready")
gcloud run services proxy $S --region=$R --port=8080
```

**Rollback to revision 1** (instant; revision 2 stays deployed but receives no traffic):

```bash
gcloud run services update-traffic rsfa-end-forms-engine --region=australia-southeast1 --project=rsfa-end-forms-engine \
  --to-revisions=rsfa-end-forms-engine-00001-6qm=100
gcloud run services describe rsfa-end-forms-engine --region=australia-southeast1 --format='yaml(status.traffic)'  # 00001-6qm: 100
curl -s -H "Authorization: Bearer $(gcloud auth print-identity-token)" "$URL/health"; echo   # {"status":"ok","templates":1} (revision-1 format)
# Return to revision 2 later:  gcloud run services update-traffic rsfa-end-forms-engine --region=australia-southeast1 --to-latest
```

Hand back `~/rev2/bundle/cloudrun-output/deployment-record.json` so this section can record revision 2.

The manual runbook follows. The script runs exactly these steps.

Goal: a **private** Cloud Run service that generates the BNZ Boarder PDF from a synthetic JSON payload,
without MacroForge. Commands were written for and checked against `gcloud` 570.0.0 (flag names verified offline).

## Revision 3: add `/v1/masterboard/documents` (prepared 2026-10-28, iteration 9; deployment approved 2026-10-28, to be run by RSFA from Cloud Shell)

**What changes compared to `00002-nan`:**
- The image adds the Make contract endpoint `POST /v1/masterboard/documents` (D-025) and the relay code.
  - The relay code is dormant: it only runs with `ENGINE_ROLE=relay`, which this service does not set.
- **Unchanged:**
  - allowlist: BNZ Boarder only, approval `RSFA-END-FORMS-POC-2026-10-27`;
  - demo page: offers only templates `/health` reports usable, i.e. BNZ Boarder;
  - `ENGINE_AUTH=PLATFORM_IAM`, `--no-allow-unauthenticated`;
  - invoker: the deployer only (the relay SA is **not** added here);
  - runtime SA, CPU, memory, instances, concurrency and timeout (the script refuses if the live config differs).
- `/v1` in this image can generate only BNZ Boarder. A bank selection that routes other forms is refused with 422
  `UNKNOWN_TEMPLATE` and no PDF. Forms MacroForge must still produce are listed in `notMigrated`.

**Package** (built from commit `80affdd`, which includes the relay review fixes; reproducible: a rebuild gives the same SHA-256):

| Item | Value |
|---|---|
| Bundle | `.local/deploy/rsfa-end-forms-bundle.tar.gz` (git-ignored; sent separately) |
| SHA-256 | `6b0e7015164b49c0f6b54c4035a7563e46ebef715d173b2398a6c8291826303d` |
| Contents | `deploy_poc.sh`, `verify_pdf.py`, `BUNDLE_INFO.json` (commit and per-file SHA-256), staged context (36 files: engine sources and tests, BNZ manifest, the blank allowlisted BNZ template), `/generate` fixture, 3 `/v1` requests (fictional) |
| Expected PDFs | `/generate` `2ae7bfacf48b8ee8e63aaf9ce28997650abd2c07b59ddbeade9928ce3269d6f6` (unchanged from revisions 1–2); `/v1` `e08a79e00e8702eb1acf56a055870ff6730826ea04bc5bfafb16c02a71088d78` (same data, `today` 2026-10-28) |

**What the script verifies, on the no-traffic candidate URL and again on the main URL after promotion:**
- Everything revision 2 checked:
  - unauthenticated `/health` and `/` → 401/403;
  - authenticated `/health` lists only BNZ Boarder;
  - `/generate` PDF checked field by field and byte-identical;
  - demo page served with a token;
  - no fixture values in the logs.
- **New for `/v1`:**
  - unauthenticated → 401/403;
  - BNZ request → 200 `complete`, one document, PDF SHA-256 equals `expected_v1_pdf_sha256.txt`, 6 `notMigrated`;
  - unknown bank → 422 `UNKNOWN_BANK`;
  - ASB with candidates → 422 `UNKNOWN_TEMPLATE` and no PDF.
- **Update mode now requires** `EXPECT_PREVIOUS_REVISION`. Any failure after traffic moved prints the rollback
  command.

**Local rehearsal (2026-10-28):**
- **Image build:** built from the bundle's own staged context (commit `80affdd`) with the Cloud Build target (`poc`);
  the Maven tests ran inside the build. 59 ran; 18 were skipped (legacy-template and demo-form tests need files outside the context, by design).
  - The first rehearsal **failed**: a test read `contracts/`, which is not in the context. Fixed in `d35d5d5`.
- **Image run:** with `K_SERVICE` and `ENGINE_AUTH=PLATFORM_IAM` behind a local IAM stand-in. All checks above passed:
  - the script's own `v1_checks` function was executed;
  - `verify_pdf.py` → `ok`;
  - container logs contain no data values.
- **Negative rehearsal:** a wrong expected `/v1` digest stops the script with `BLOCKER`.
- **Not rehearsed:** real Cloud Build, Artifact Registry, Cloud Run IAM and traffic tagging. These only run in RSFA's
  project.

**Command (Cloud Shell, after RSFA approves this exact bundle and SHA-256):**
```bash
sha256sum -c rsfa-end-forms-bundle.tar.gz.sha256 && tar -xzf rsfa-end-forms-bundle.tar.gz && cd bundle
UPDATE_EXISTING=1 EXPECT_PREVIOUS_REVISION=rsfa-end-forms-engine-00002-nan bash deploy_poc.sh
```

**Rollback:**
```bash
gcloud run services update-traffic rsfa-end-forms-engine --region=australia-southeast1 \
  --project=rsfa-end-forms-engine --to-revisions=rsfa-end-forms-engine-00002-nan=100
```
Revision 3 is created with no traffic. If any check fails before promotion, `00002-nan` keeps 100% and nothing needs
rolling back. The candidate tag can be removed with
`gcloud run services update-traffic rsfa-end-forms-engine --region=australia-southeast1 --remove-tags=candidate`.

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
curl -s -H "Authorization: Bearer $TOKEN" "$URL/health"          # {"status":"ok","templates":1,"available":["BNZ_BOARDER_FORM@2.0_8-24"]} (revision 1: {"status":"ok","templates":1})

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
