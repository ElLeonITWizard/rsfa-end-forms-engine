# 14 — Private administration service: deployment preparation (Cloud Run + IAP)

Status 2026-10-28 (iteration 11). **Prepared and tested locally; NOT deployed.**
- No Google Cloud resource, permission or setting has been created or changed.
- Make, Monday and SharePoint are untouched.
- The engine `rsfa-end-forms-engine` (revision `rsfa-end-forms-engine-00005-rih`, `PLATFORM_IAM`) is unchanged and is never written to by the scripts below.
- Decisions: D-041, D-042.

**Goal of the first release:** open a permanent private URL, sign in with Google, upload a synthetic blank bank PDF,
map it, test it, and reopen the saved draft from another browser session. Synthetic documents only; authorised
administrators only; no publication to the engine; mock suggestions only.

## 1. Architecture

```text
browser ──HTTPS──> https://rsfa-end-forms-admin-….run.app  (default run.app URL; no custom domain, no load balancer)
                     │ Identity-Aware Proxy (IAP, directly on Cloud Run): Google sign-in; only users with
                     │ roles/iap.httpsResourceAccessor pass; adds the signed header x-goog-iap-jwt-assertion
                     ▼ IAP service agent holds roles/run.invoker on THIS service only (IAM check stays on)
              Cloud Run service rsfa-end-forms-admin  (ENGINE_ROLE=admin, same jar, own image)
                     │ 1. verifies the IAP JWT (ES256, issuer, audience = this service, expiry)
                     │ 2. ADMIN_USERS allowlist → role (viewer | author | approver | admin), checked on every request
                     │ 3. writes need the page's X-RSFA-Admin marker (cross-site request guard)
                     │ 4. fills synthetic test PDFs in-process (no call to the engine)
                     ▼ service account rsfa-end-forms-admin: objectCreator + objectViewer on ONE bucket, nothing else
              gs://rsfa-end-forms-engine-admin  (private, versioned, uniform access, public access prevention)
                     templates/<sha256>.pdf|.json · drafts/<id>/events/000001.json… · results/<token>.* · config/monday-schema.json

Make ──(future)──> relay ──ID token──> rsfa-end-forms-engine (IAM only, UNCHANGED; no IAP, no admin access)
```

### Why a separate service rather than IAP on the engine

IAP replaces the caller's identity for the service it protects. The engine's only callers are IAM identities (RSFA, and later the
Make relay's service account, D-027): IAP on the engine would break that path. The admin service has no `/generate`,
`/v1` or demo page; the engine has no `/admin` (it is local-only there, D-038). Neither holds credentials for the other.

### Identity (requirement 2)

The application does not rely on IAP alone. Every request must carry a valid IAP assertion **and** match the
`ADMIN_USERS` allowlist:
- `IapVerifier` checks ES256 with IAP's published keys (cached one hour, refetched on an unknown key id, fail-closed);
  the issuer `https://cloud.google.com/iap`; the audience `/projects/<number>/locations/australia-southeast1/services/rsfa-end-forms-admin`;
  `exp` and `iat` with 30 seconds of skew; and an email.
- `alg: none`, HS256, other keys and tokens for other services are refused.
- The unsigned `X-Goog-Authenticated-User-Email` header is never trusted.
- `ADMIN_USERS` (`email:role,...`) is checked server-side on every request. A Google account that passes IAP but is not
  listed gets a refusal page with no data.

| Role | Can |
|---|---|
| `viewer` | read the library, drafts, test results and audit trail |
| `author` | viewer + upload, create and save drafts, generate tests, mock suggestions, ready-for-review / back to draft |
| `approver` | viewer + record or revoke the approval of a reviewed version (`ADMIN_SEPARATE_APPROVER=true`: not the person who saved it) |
| `admin` | everything above |

The local development identity (`ENGINE_ADMIN=local`, `ADMIN_ACTOR`) is unchanged and remains loopback-only.

### Storage and concurrency (requirements 4 and 6)

- Everything the application writes is **write-once**.
- Each draft is an event log (`create`, `save` with the mapping version, `test`, `status`). Each event is a new object
  created with `ifGenerationMatch=0`.
- When two instances race for the same sequence number, exactly one wins. The other re-reads and re-validates, so a
  stale save gets **409** and is never merged silently.
- Consequences:
  - the log is the audit trail and cannot be rewritten by the application;
  - the service account needs **no overwrite or delete permission**;
  - test outputs live in the bucket (`results/`, deleted after a day by a lifecycle rule), so any instance can serve them.
- The bucket is versioned: anything RSFA overwrites or deletes by hand is kept as a non-current version.
- **No automatic deletion of drafts, approvals or audit history** until RSFA confirms a retention policy (D-043). The
  only lifecycle rules delete synthetic test outputs under `results/` after a day. The deploy verification fails if
  any rule could reach another prefix.

### Logs (requirement 5)

Logs carry the route (IDs masked), the status, a refusal code and a short user hash (`user=u-1a2b3c4d`). They never
include:
- emails or tokens;
- file names or template content;
- mapping configuration or test values.

PDFBox is limited to severe messages. The service holds **no secrets**: its storage token comes from the metadata
server.

### Synthetic only, no publication, mock suggestions (requirements 7–9)

| Requirement | How it is enforced |
|---|---|
| Uploads are blank or synthetic | needs the "no client data" confirmation; the PDF is still refused when it has filled values, signatures, encryption or no fields (D-038) |
| Test values are fictional | the server accepts only the generated fictional value, the column's Monday labels, labels the draft tests, or empty (`NON_SYNTHETIC_VALUE`) |
| No publication | the workflow stops at `approved`, labelled a development approval; there is no publication status, endpoint or engine credential |
| No real AI | `ADMIN_SUGGESTIONS` accepts only `mock`; the UI shows "MOCK … not an AI model" |

### Fail-closed configuration

The admin role refuses to start in any of these cases:
- it runs on a Cloud Run service whose name does not contain `admin`;
- emulation overrides are present on Cloud Run;
- an engine or relay secret is present;
- `ADMIN_USERS` is empty or has no admin;
- `IAP_AUDIENCE` or `ADMIN_BUCKET` is missing;
- the schema is not in the bucket.

The engine role also refuses to start on a service named like the admin service.

## 2. Google Cloud facts, and how each was established

**Update 2026-10-28 (pre-deployment review):** RSFA reports that it has verified the current official documentation:
direct IAP on Cloud Run is supported, including projects without an organization, and initial setup may need the
console and OAuth configuration. Its docs host is still blocked from this environment, so the items the scripts
depend on were re-checked against **snippets of the official pages** via search. Confirmed:
- `gcloud run deploy … --no-allow-unauthenticated --iap` for a new service;
- IAP's service agent needs `roles/run.invoker` (the console grants it automatically; gcloud users grant it, as
  `deploy_admin.sh` does);
- users need `roles/iap.httpsResourceAccessor`;
- a project without an organization can see a warning on first enablement (the console path);
- the Cloud Run audience format;
- the `x-goog-iap-jwt-assertion` header and ES256 keys at `public_key-jwk` (rotated periodically);
- the unsigned `x-goog-authenticated-user-*` headers can be forged if IAP is bypassed.

The official page shows `gcloud beta run services update --iap` for an existing service. The GA
`gcloud run services update` in gcloud 570 also has `--[no-]iap` (CLI), and `deploy_admin.sh` uses that. The preflight
also requires `gcloud beta`, for `services identity create`.

This environment cannot reach `docs.cloud.google.com` or `www.gstatic.com` (network policy). Facts below are from the
**locally installed gcloud CLI (570.0.0) help**, marked CLI, or from **search summaries of the official pages**, marked
search. **Verify every search-marked item against the official page before running the deployment.**

| Fact | Source |
|---|---|
| `gcloud run deploy … --[no-]iap` enables or disables IAP on a service | CLI |
| `gcloud iap web add-iam-policy-binding --resource-type=cloud-run --service=S --region=R` (region required) | CLI |
| Direct IAP on Cloud Run (no load balancer) is **GA** (release note 2026-03-13; Preview since 2025-04-07) | search |
| Since GA, Cloud Run checks `run.routes.invoke` for IAP's calls: the IAP service agent `service-<number>@gcp-sa-iap.iam.gserviceaccount.com` needs `roles/run.invoker` | search |
| Users need `roles/iap.httpsResourceAccessor` on the IAP resource | search |
| IAP cannot be configured on both a load balancer and the service | search |
| **Project without an organization:** the Google-managed OAuth client is not available, so a custom OAuth client is needed | search |
| **Project without an organization:** OAuth clients cannot be created programmatically; the first enablement is done in the Cloud Run console, which creates the client | search |
| **Project without an organization:** needs `roles/iap.settingsAdmin` and `roles/oauthconfig.editor`; requests fail with HTTP 502 "Empty Google Account OAuth client ID(s)/secret(s)" until a client is configured | search |
| IAP's OAuth redirect URI is `https://iap.googleapis.com/v1/oauth/clientIds/<CLIENT_ID>:handleRedirect` (only when creating a client by hand) | search |
| The IAP JWT header is `x-goog-iap-jwt-assertion`, ES256, issuer `https://cloud.google.com/iap`, Cloud Run audience `/projects/<number>/locations/<region>/services/<service>` | search |
| The key set is at `https://www.gstatic.com/iap/verify/public_key-jwk` (as listed in the Spring Cloud GCP reference) | search |
| IAP has **no charge** for Cloud Run, "with some exceptions" (Chrome Enterprise Premium features) | search |
| The default `run.app` URL works with IAP. **No custom domain or load balancer is needed** for the first release. | search + design |

Two items are **verified only at first deployment**:
- the exact annotation the deploy script reads to confirm IAP is on (any annotation containing `iap` = `true`);
- whether IAP sign-in works while the OAuth consent screen is in **Testing** status. If it does not, set it to In
  production: no verification is needed for the basic `openid email` scopes. **TODO: verify.**

Sources:
- [Configure IAP for Cloud Run](https://cloud.google.com/run/docs/securing/identity-aware-proxy-cloud-run)
- [Enable IAP for Cloud Run](https://cloud.google.com/iap/docs/enabling-cloud-run)
- [Custom OAuth clients](https://cloud.google.com/iap/docs/custom-oauth-configuration)
- [Signed headers](https://cloud.google.com/iap/docs/signed-headers-howto)
- [IAP pricing](https://cloud.google.com/iap/pricing)
- [Cloud Run release notes](https://cloud.google.com/run/docs/release-notes)
- [IAP FAQ](https://cloud.google.com/iap/docs/faq)

## 3. Resource and IAM inventory

| Item | Created? | Detail |
|---|---|---|
| API `iap.googleapis.com` | **new** | the others (run, artifactregistry, cloudbuild, iam, storage, logging) are already enabled for the engine |
| Service account `rsfa-end-forms-admin@rsfa-end-forms-engine.iam.gserviceaccount.com` | **new** | **no project roles** |
| Bucket `gs://rsfa-end-forms-engine-admin` | **new** | australia-southeast1, Standard, uniform access, public access prevention, versioning, lifecycle `deploy/admin/bucket-lifecycle.json`: **only `results/`** (synthetic test outputs) after 1 day; drafts, approvals, audit and versions are never auto-deleted (D-043) |
| Bucket IAM | **new** | admin SA: `roles/storage.objectCreator` + `roles/storage.objectViewer` (no overwrite, no delete) |
| Cloud Run service `rsfa-end-forms-admin` | **new** | `--no-allow-unauthenticated --ingress=all --iap`, 1 vCPU, 1 GiB, min 0, max 2, concurrency 10, timeout 60 s, request-based billing |
| Service IAM | **new** | `roles/run.invoker`: the IAP service agent only |
| IAP IAM (resource-type cloud-run) | **new** | `roles/iap.httpsResourceAccessor`: each `ADMIN_USERS` email (`user:`) |
| Deployer → admin SA | **new** | `roles/iam.serviceAccountUser` on that SA only (to deploy it) |
| OAuth consent screen and IAP OAuth client | **new, console** | only without an organization (§4) |
| Image `…/rsfa-end-forms/end-forms-admin@sha256:…` | **new** | in the existing Artifact Registry repo |
| Artifact Registry `rsfa-end-forms`, build SA `rsfa-end-forms-build`, `gs://rsfa-end-forms-engine-build-src` | reused, unchanged | the build uses the source prefix `source-admin/` |
| Engine service, engine runtime SA, engine IAM, engine revision `00005-rih` | **unchanged** | read before and after; the deploy fails if anything differs |
| Make, Monday, SharePoint, Secret Manager, load balancer, Identity Platform | not used | — |

Deployer roles needed (Owner covers them all):
- `run.admin`, `iam.serviceAccountAdmin`, `storage.admin`, `serviceusage.serviceUsageAdmin`;
- `cloudbuild.builds.editor`, `iap.admin`, `logging.viewer`;
- without an organization, also `iap.settingsAdmin` and `oauthconfig.editor`.

## 4. First-time IAP setup (once)

1. **Google accounts:** every person in `ADMIN_USERS` signs in with a **Google account** whose email is exactly the
   listed one. A Microsoft 365 address can be registered as a Google account ("use my current email address").
   Microsoft Entra ID sign-in is a later option (Identity Platform or Workforce Identity Federation) and is not part of
   this release.
2. **Run the deployment** (§5). If the project has a Google organization, the script enables IAP directly
   (`--iap`).
3. **No organization (detected by the script):** the script deploys the service closed (IAM only, no IAP), prints this
   step and stops. In the Google Cloud console:
   1. *Google Auth Platform → Branding*: app name "RSFA End Forms Admin", support email. *Audience*: External,
      Testing, and add each `ADMIN_USERS` email as a test user. That makes a second allowlist.
   2. *Cloud Run → rsfa-end-forms-admin → Security*: tick **Identity-Aware Proxy (IAP)**, then **Save**. IAP creates
      the custom OAuth client.
4. **Continue:** `ADMIN_USERS='…' APPLY=1 PHASE=access bash deploy_admin.sh`. This creates the IAP service agent,
   grants it invoker on the admin service, grants each user IAP access, keeps `--iap` on, and runs the verification.

## 5. Deployment (RSFA runs it; this environment never does)

```bash
# here (repository, clean commit): stage and bundle
python3 tools/stage_admin.py --schema .local/reference/masterboard-columns-2026-10-27.json --out .local/deploy-admin
(cd engine && mvn -B package) && bash deploy/admin/make_admin_bundle.sh
# Cloud Shell: upload .local/deploy-admin/rsfa-end-forms-admin-bundle.tar.gz, check its SHA-256, then
tar -xzf rsfa-end-forms-admin-bundle.tar.gz && cd admin-bundle
ADMIN_USERS='name@example.org:admin' bash deploy_admin.sh             # PLAN: read-only checks + the exact commands
ADMIN_USERS='name@example.org:admin' APPLY=1 bash deploy_admin.sh     # deploy (stops for §4.3 without an organization)
ADMIN_USERS='name@example.org:admin' APPLY=1 PHASE=access bash deploy_admin.sh
PHASE=verify bash deploy_admin.sh                                      # any time, read-only
```

**Preflight (all read-only), which blocks before any change:**
- gcloud supports `--iap`; the account and project are valid; organization detected;
- deployer permissions present;
- `ADMIN_USERS` valid (roles, duplicates, at least one admin);
- the engine serves exactly `rsfa-end-forms-engine-00005-rih`, and its fingerprint is saved: traffic, image, service
  account, env, annotations, IAM;
- the admin SA has no binding on the engine;
- the staged context checksums match, it holds no PDF or export, and its file times are after 1980;
- the sanitised schema is present;
- the reused build SA and repository exist;
- the bucket is new or in this project;
- the admin service is new, or updated only with `UPDATE_EXISTING=1`.

Changing users later: re-run with the new `ADMIN_USERS` (`UPDATE_EXISTING=1`, then `PHASE=access` for IAP access).
To remove someone, remove their IAP binding too:
`gcloud iap web remove-iam-policy-binding --resource-type=cloud-run --service=rsfa-end-forms-admin --region=australia-southeast1 --member=user:EMAIL --role=roles/iap.httpsResourceAccessor`.

### Pre-deployment safety review (2026-10-28)

| Requirement | How it holds | Evidence |
|---|---|---|
| Deployment and rollback cannot alter the engine or `00005-rih` | Every write targets `$ADMIN_SERVICE`, `$ADMIN_SA` or `$ADMIN_BUCKET`. These names must contain `admin`, differ from the engine and the build-source bucket, or both scripts stop. The engine is only described (`describe`, `get-iam-policy`), fingerprinted before and after, and any difference fails the run. Deploy also stops unless the engine serves exactly `00005-rih`. | `test_admin_names_can_never_point_at_the_engine`, `test_scripts_never_write_to_the_engine_service`, `test_wrong_engine_revision…`, `test_apply_creates_only_admin_resources…` |
| A dry run changes nothing | Default mode is PLAN. Every mutating command is printed as `[plan] …` and not run. `CLOUDSDK_CORE_DISABLE_PROMPTS=1` means no "enable API?" prompt can be answered. | `test_plan_is_the_default_and_changes_nothing`, `test_prompts_are_disabled…`; simulated plan with these settings: 15 gcloud calls, 0 mutating |
| Unsigned IAP headers and unauthorised identities are rejected | JWT verified (signature, issuer, audience, expiry; plain email characters only); `ADMIN_USERS` checked per request; the unsigned email header alone gives 401 | `AdminCloudTest` (10 refusal cases); private e2e: direct, forged and unsigned → 401, IAP-only account → refusal page |
| The initial administrator email matches the Google identity | IAP presents the Google account's email. The preflight compares the authenticated gcloud account with `ADMIN_USERS` (`[ok] … is in ADMIN_USERS`). A signed-in but unlisted user sees "You are signed in as …" (shown only to that user; never logged). | `test_prompts_are_disabled_and_the_identity_is_matched`; `AdminCloudTest` |
| Test PDFs, drafts and audit records cannot be publicly accessed | Bucket: public access prevention enforced, uniform access, no public member; only the admin SA (create + read) and project owners. Results, drafts and audit are served only through the authenticated, allowlisted API; Cloud Run invoker = the IAP agent only. | verification step 9 (`deploy_admin.sh`), `test_verification_fails_on_a_public_invoker` |
| No automatic deletion of approval or audit history | The lifecycle touches only `results/`; versioning keeps overwritten or deleted objects | `test_lifecycle_never_deletes_drafts_approvals_or_audit` + verification check |

### Dry run in Cloud Shell (exact procedure; read-only)

1. Download the bundle `rsfa-end-forms-admin-bundle.tar.gz` and its `.sha256` (delivered in the session; §Prepared
   bundle). In Cloud Shell, use *More (⋮) → Upload* for both files.
2. Run:

```bash
gcloud config set project rsfa-end-forms-engine
gcloud auth list                                  # the active account must be tech@rsfa.co.nz
sha256sum -c rsfa-end-forms-admin-bundle.tar.gz.sha256      # expect: rsfa-end-forms-admin-bundle.tar.gz: OK
tar -xzf rsfa-end-forms-admin-bundle.tar.gz && cd admin-bundle
less deploy_admin.sh                              # optional: read the script before running it
ADMIN_USERS='tech@rsfa.co.nz:admin' SEPARATE_APPROVER=false bash deploy_admin.sh 2>&1 | tee admin-plan.txt
```

**What the dry run does.** It only runs read-only calls:
- `projects describe`, the testIamPermissions query, `run services describe` and `get-iam-policy` (engine);
- `iam service-accounts describe`, `artifacts repositories describe`, `storage buckets describe`.

It writes only local files in `./admin-deploy-output/`:
- the engine fingerprint;
- `admin-env.yaml` (mode 600);
- the record.

**What to check in `admin-plan.txt`:**
- every change line starts with `[plan]`;
- `engine rsfa-end-forms-engine serves rsfa-end-forms-engine-00005-rih`;
- `tech@rsfa.co.nz is in ADMIN_USERS`;
- the organization line;
- the resource names in §3.

A simulated run with these settings (stubbed gcloud) produced exactly the commands of §3: 15 read calls, 0 changes.

### Prepared bundle (2026-10-28, superseded below)

| | |
|---|---|
| Bundle | `.local/deploy-admin/rsfa-end-forms-admin-bundle.tar.gz` (not committed; regenerate with the commands above) |
| SHA-256 | `ef877463a7778b8712beb15986dbb4c0dce417d3efbf457c76a95f08a8c11ad8` (built twice, identical) |
| Built from | commit `946b5fb`; staged context 66 files, identical to the commit (the bundle script refuses a stale context); no PDF except the fictional `example-bank-form.pdf` |
| Sanitised schema | 401 columns, SHA-256 `8be789c7…` |
| Cloud Build rehearsal | the extracted bundle built with `deploy/admin/Dockerfile --target admin --no-cache` (all unit tests ran inside the build): **passed**. That image then passed the 42-check private end-to-end test. Local note: this environment's proxy refuses plain-HTTP apt, so the rehearsal used `--build-arg APT_MIRROR=https://archive.ubuntu.com/ubuntu` (Cloud Build does not need it). |

### Prepared bundle for the dry run (current)

| | |
|---|---|
| File | `rsfa-end-forms-admin-bundle.tar.gz` (205 KB; not committed). Delivered as a download in the session; regenerate with `tools/stage_admin.py` + `deploy/admin/make_admin_bundle.sh` |
| SHA-256 | `aa3281c4b8d829b839f1779ec07692829cf86787cb54aa98c832912e01726c61` (built twice, identical) |
| Built from | commit `45621bb`; staged context 66 files, identical to the commit; no PDF except the fictional `example-bank-form.pdf` |
| Sanitised schema | 401 columns, SHA-256 `8be789c7a2bc594f4af038e71a62dfa9fb444a97da1b53234a9fb02d54077277` |
| Rehearsal | Cloud Build-style build from the extracted archive (`--target admin --no-cache`, unit tests ran inside: 28 s step): **passed**. That image passed the 42-check private e2e. |

## 6. Rollback

`deploy/admin/rollback_admin.sh` is a plan by default; `APPLY=1` executes. The engine is only read, before and after.
- **A bad admin revision:** `TO_REVISION=rsfa-end-forms-admin-0000N-xxx APPLY=1 bash rollback_admin.sh` moves the
  traffic back.
- **Close the private URL:** `REMOVE=1 APPLY=1 bash rollback_admin.sh`.
  - Deletes the admin service (the URL and IAP access go with it) and the SA's bucket bindings.
  - Disables the SA.
  - Keeps the bucket, its data and its versions.
- **Delete the data too:** add `DELETE_DATA=1` (irreversible).
- **Left in place (harmless, no cost):** the IAP API, the OAuth client and consent screen, the image.
- **The engine needs no rollback:** nothing in this release changes it.

## 7. Validation checklist

**Automated by `deploy_admin.sh` (step 9; record in `admin-deploy-output/admin-deployment-record.json`):**
- [ ] IAP enabled on `rsfa-end-forms-admin`; `ENGINE_ROLE=admin`; own SA; max 2 instances; no engine or relay secret
- [ ] invokers of the admin service = exactly the IAP service agent (no user, no `allUsers`)
- [ ] IAP access only for named `user:`/`group:` members
- [ ] bucket: public access prevention enforced, uniform access, versioning; no public member; the admin SA has exactly objectCreator + objectViewer
- [ ] admin SA has no project-level role and no binding on the engine
- [ ] anonymous `GET /admin/` → redirect to Google sign-in (or 401/403), never 200
- [ ] a Google ID token for another audience does not open `/admin/api/session`
- [ ] **engine fingerprint identical** before and after (serving revision `00005-rih`, image, config, IAM)
- [ ] logs show `admin service: auth=iap` and no email address or file name

**Manual, in a browser (RSFA, 10 minutes):**
- [ ] **allowlisted admin:** open the URL → Google sign-in → library with your email, the `admin` chip, the badge
  "Private test environment · synthetic documents only · not production", BNZ Boarder `Deployed`
- [ ] **upload:** *Download a fictional test form* → upload it (the button stays disabled until "no client data" is
  ticked) → accepted
- [ ] **map and test:** create the draft → map *Customer full name* → save (version 2) → Test: fictional values (text
  read-only) → generate → preview and download
- [ ] **reopen from another session:** a second browser profile or device with the same or another allowlisted
  account opens the draft at version 2 with the mapping; History shows who did what
- [ ] **unauthorised accounts:** a Google account without IAP access → Google/IAP "You don't have access"; an account
  with IAP access but not in `ADMIN_USERS` → the "not on the administration allowlist" page
- [ ] **engine:** still answers as before through its own access path (docs/04), e.g. `/health` with your ID token

## 8. Production-like local environment and tests (iteration 11)

`bash tests/admin/run_private_env.sh up` sets up the following; nothing leaves the machine:
- builds the **real admin image** from the staged context (`deploy/admin/Dockerfile`, target `admin-prebuilt`);
- runs **two instances** (read-only root filesystem, non-root user 10001);
- runs a **fake Cloud Storage JSON API** (`tests/admin/fake_gcs.js`: create-if-absent, counts any overwrite attempt);
- runs an **IAP stand-in** (`tests/admin/iap_standin.js`: sign-in form, IAP access list, ES256 assertions with its own
  key, strips client `x-goog-*` headers, round-robins the instances).

`node tests/admin/private_admin_e2e.js` (42 checks, passed 2026-10-28):

| Area | What is checked |
|---|---|
| Sign-in and IAP | anonymous → sign-in; no IAP access → IAP refusal; IAP but not allowlisted → refusal page, no data |
| Goal workflow | identity, role and synthetic badge shown; BNZ deployed; download the fictional form; upload blocked until confirmed; accepted; map; save v2; read-only fictional values; test PDF; served by both instances |
| Second session | the approver reopens v2 with the mapping and cannot edit; the approval is recorded; the audit names the verified emails |
| Refusals | viewer: no upload, server refuses writes; cross-site write → 403; publication unavailable; expired sign-in → reload banner |
| Bypassing IAP | direct to an instance without an assertion → 401; forged assertion → 401; unsigned email header → 401 |
| Storage | no overwrite ever; one gap-free event log of 5 events; no emails or file names in object names |
| Logs | users only as `u-<hash>`; no emails, file names or test values |

Screenshots: `.local/output/private-admin/` (not committed).

| Unit tests | Count | Covers |
|---|---|---|
| `AdminCloudTest` (Java) | 9 | IAP verification (missing, malformed, wrong key or kid, HS256, `alg none`, wrong audience or issuer, expired, future, unsigned header, not allowlisted, case-insensitive email); roles and the cross-site guard; two instances on one fake bucket (simultaneous saves → exactly one 200 and one 409; results served cross-instance; audit actors; no values; pagination); 6 concurrent writers → gap-free log; synthetic-only uploads and values; refused uploads store nothing; separate approver; storage outage → 503; logs; unsafe configurations; local store create-once |
| `test_admin_deploy.py` (Python, stubbed gcloud/curl) | 10 | the plan changes nothing; apply creates only admin resources and never writes to the engine; the no-org stop; access phase + verification pass; a public invoker fails verification; wrong engine revision or bad `ADMIN_USERS` stop before any change; rollback is a plan and keeps data; schema sanitising; no PDF in the context |

## 9. Expected operating cost (estimates; verify against the pricing pages)

| Item | Expected |
|---|---|
| Cloud Run admin service (min 0, request-based) | ~10 active hours a month ≈ 36,000 vCPU-s + 36,000 GiB-s: **< US$2/month**, often within the free tier shared with the engine |
| Cloud Storage (Standard, Sydney) | < 1 GB of templates, drafts and audit: **cents/month**; operations negligible; soft delete (7 days) adds little |
| Artifact Registry | ~0.25 GB per admin image beyond the 0.5 GB free tier: **~US$0.03/image/month** (prune old images) |
| Cloud Build | a few builds a month: within the free build minutes or **~US$0.10/build** |
| IAP | **no charge** for Cloud Run (search; verify) |
| Cloud Logging | well inside the free 50 GiB/project/month |
| OAuth client and consent screen | free |
| **Total** | **≈ US$0–5/month** |

Paid features **avoided**:
- a load balancer and custom domain (≈ US$18+/month);
- Identity Platform (per active user);
- Chrome Enterprise Premium (context-aware access);
- minimum instances, Cloud Armor, CMEK;
- Secret Manager (the admin service has no secret).

Suggested: a free budget alert on the billing account.

## 10. What RSFA must approve or decide before deploying

See §5 and the final report of iteration 11. In short:
1. **Approve the exact plan output** (`deploy_admin.sh` without `APPLY`). It names the resources above, in project
   `rsfa-end-forms-engine`, region `australia-southeast1`.
2. **Name the administrators:** `ADMIN_USERS` emails (Google accounts) and roles; at least one `admin`.
3. **One person with Owner** (or the roles in §3) runs the deployment and, without an organization, the console step
   in §4.3.
4. **Defaults to confirm:**
   - bucket name `rsfa-end-forms-engine-admin`;
   - retention: synthetic test outputs 1 day; everything else kept indefinitely (no automatic deletion) until RSFA sets a policy;
   - `SEPARATE_APPROVER=false` (one admin can approve their own version in this test environment).
