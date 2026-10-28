# 10 — Make → relay → private engine: security architecture and infrastructure plan (iteration 8)

**Status:**
- The relay is implemented and tested **locally only**. Nothing exists in Google Cloud or Make.
- Engine revision `rsfa-end-forms-engine-00002-nan` is untouched.
- Every resource below needs RSFA's approval of the exact commands (CLAUDE.md rule 8).

Decisions: D-027 (relay over API Gateway), D-031 (durable replay store), D-032 (signing in Make).

```text
Make scenario ──HTTPS + HMAC──▶ rsfa-end-forms-relay (Cloud Run, public, ENGINE_ROLE=relay)
                                   │ 1 header format, timestamp ±300 s and Content-Length ≤ 1 MiB, before reading the body
                                   │ 2 HMAC-SHA256 over the exact bytes (constant time, 2 secrets during rotation)
                                   │ 3 body must be a JSON object
                                   │ 4 rate limit per instance, then the relay's ID token (both before step 5)
                                   │ 5 claim the signature once in Cloud Storage (create-if-absent, shared by all instances)
                                   ▼ Authorization: Bearer <Google ID token of rsfa-make-relay@, audience = engine URL>
                        rsfa-end-forms-engine (Cloud Run, --no-allow-unauthenticated, IAM run.invoker = relay SA)
```

## 1. How each hop authenticates

| # | Hop | Mechanism | Rejected with |
|---|---|---|---|
| 1 | Make → relay | `X-RSFA-Timestamp: <unix s>` and `X-RSFA-Signature: v1=<hex HMAC-SHA256(secret, timestamp + "." + raw body)>`. The relay recomputes it over the **exact received bytes** and compares with `MessageDigest.isEqual` (constant time) against every active secret (all are always computed) | 401 `MISSING_SIGNATURE` (header absent or not `v1=` + 64 hex), 401 `STALE_TIMESTAMP` (\|now − ts\| > 300 s, past or future), 401 `INVALID_SIGNATURE` |
| 2 | Relay checks | Body ≤ 1 MiB (checked before the signature); a JSON object; signature not seen before | 413 `PAYLOAD_TOO_LARGE`, 400 `MALFORMED_JSON`, 409 `REPLAYED_REQUEST` |
| 3 | Relay → engine | Google-signed **ID token** for the relay's service account, from the Cloud Run metadata server (`/identity?audience=<engine URL>`), cached until 5 min before expiry. No key file exists anywhere | — |
| 4 | Engine (Cloud Run IAM) | Cloud Run verifies the token before the request reaches the container: signature, audience = service URL, principal holds `roles/run.invoker` on this service. The engine keeps `ENGINE_AUTH=PLATFORM_IAM` and `--no-allow-unauthenticated` | 403 from Cloud Run, which the relay reports to Make as 502 `UPSTREAM_AUTH_FAILED` (a relay configuration fault, not retryable) |

Only `/health` (no data) and `POST /v1/masterboard/documents` exist on the relay. `/generate` and the demo page are not
reachable through it.

## 2. Replay, duplicates and idempotency

- **Replay (same signed request sent again):**
  - **Durable and shared:** `RELAY_REPLAY_STORE=gcs`. Each accepted signature becomes one Cloud Storage object
    `replay/<sha256(signature)>` created with `ifGenerationMatch=0`. Cloud Storage guarantees that only one create
    succeeds, so a copy arriving at **any** instance gets 412 → 409 `REPLAYED_REQUEST`.
  - **Tested:** two relays sharing the store (`RelayHardeningTest`):
    - a replay on the other instance → 409;
    - 12 concurrent copies across 2 instances → exactly 1 accepted.
  - The objects hold only an expiry second. A bucket lifecycle rule deletes them after 1 day; after 300 s the timestamp
    check refuses them anyway.
  - **Fails closed:** if Cloud Storage does not answer 200 or 412, the request is refused with 503
    `REPLAY_STORE_UNAVAILABLE` (retryable) and nothing is forwarded (tested).
  - `RELAY_REPLAY_STORE=memory` is **per instance**. The tests show its limitation: a replay on another instance is
    accepted. On Cloud Run the relay refuses to start with it unless `RELAY_SINGLE_INSTANCE=true` is set alongside
    `--max-instances=1` (tested). A full memory store refuses instead of evicting fresh entries.
- **What a 409 means (iteration 9):** the signature was already **forwarded to the engine** once.
  - Retryable refusals (429, 503 token or store) happen **before** the claim, so the same signed bytes may be resent
    after them (tested).
  - After the engine was contacted (502/504 or any engine answer), the signature is used up and a retry must be
    **re-signed** with a new timestamp. The Make design re-signs every attempt (docs/11).
  - Remaining edge case: Cloud Storage stores the record but its answer is lost (timeout). The relay returns 503, and
    an identical resend gets 409. A re-signed retry is unaffected.
- **Duplicates (the same business request, re-signed):** a retry is a new signature, so it is accepted on purpose.
  - Generation is side-effect free and deterministic: same input → same PDF bytes for non-encrypted templates, same
    `requestDigest` and file names for all.
  - **Duplicate filing is prevented in Make** by an idempotency record keyed by `idempotencyKey`, with upload
    `conflictBehavior = replace` (docs/11).
  - The relay deliberately stores **no responses**: they contain client data.
- **Why not dedupe in the relay by `idempotencyKey`:** it would need the relay to store or replay PDFs (client data)
  or block legitimate retries. Rejected (D-031).

## 3. Sizes, limits, timeouts, partial failures

| Limit | Value | Behaviour |
|---|---|---|
| Request | 1 MiB | 413 before any other check |
| Response | 32 MiB (Cloud Run's HTTP/1 limit) | larger → 502 `UPSTREAM_RESPONSE_TOO_LARGE`. The engine itself caps at 12 documents, 10 MiB per PDF, 20 MiB raw total |
| Upstream deadline | 90 s for the whole exchange, response body included (iteration 9); with the replay store and token calls the relay answers well inside Cloud Run's 120 s | 504 `UPSTREAM_TIMEOUT`, retryable (tested with a slow engine and with a stalled response body) |
| Slow clients | `sun.net.httpserver.maxReqTime` = 15 s (`RELAY_MAX_REQUEST_SECONDS`), set by the relay at start | the connection is closed |
| Rate | 30 per minute per instance (`RELAY_RATE_PER_MINUTE`) | 429 `RATE_LIMITED`, `Retry-After: 60`, retryable |
| Engine unreachable | — | 502 `UPSTREAM_UNAVAILABLE`, retryable (tested) |
| ID token unavailable | — | 503 `IDENTITY_TOKEN_UNAVAILABLE`, retryable (tested) |
| Engine refusals | 400 / 404 / 413 / 422 | passed through unchanged (tested: `UNKNOWN_BANK`, `MISSING_REQUIRED_VALUE`, `PRECONDITION_FAILED`) |
| Partial results | `failurePolicy: per-document` | 200 `partial` passed through: generated PDFs plus per-document errors (tested). Default `all-or-nothing` → 422, no PDFs |

**Not verified:** the maximum response size Make's HTTP module accepts on RSFA's plan. Real responses are small (BNZ
Boarder ≈ 140 KB base64), but this must be checked before larger forms are enabled.

## 4. Logging

- One line per request:
  `relay correlationId=<id or null> status=<http> outcome=<code> ms=<n>`.
  - The correlation id is taken from the parsed body only if it matches `[A-Za-z0-9._:-]{8,128}`.
  - No body, data value, header value, signature, secret or token is ever logged (asserted in `RelayHardeningTest`).
- Proposed log-based metrics (Cloud Logging, no extra service):
  - count of `outcome=INVALID_SIGNATURE|STALE_TIMESTAMP|REPLAYED_REQUEST` (attack or misconfiguration);
  - `status>=500`;
  - `outcome=upstream_auth_403` (the relay lost its invoker binding).

  Optionally, an email alert on any of them.

## 5. Secrets and rotation

- **Secret Manager** secret `rsfa-make-relay-hmac`, one version per secret value. The value is generated in Cloud Shell
  (`openssl rand -hex 32`); never in chat, git or a ticket.
- The relay reads it as an env var at start: `--set-secrets=RELAY_HMAC_SECRETS=rsfa-make-relay-hmac:latest`. Only the
  relay SA has `roles/secretmanager.secretAccessor` on that secret.
- **Rotation without downtime (two secrets accepted):**
  1. Add version N+1.
  2. Deploy the relay with `RELAY_HMAC_SECRETS` = `<new>,<old>`: two versions mounted as two env vars joined by a
     startup wrapper, or one secret version holding `new,old` (documented choice at deployment).
  3. Switch Make to the new value.
  4. Deploy the relay with `<new>` only.
  5. Disable the old version.

  Tested in `RelayServerTest` (two secrets) and `unsafeConfigurationsAreRefused` (at most two, each ≥ 32 characters).
- **Compromise response:** remove the relay's `allUsers` invoker binding. This stops all Make access at once without
  touching the engine. Then rotate.
- **Make side:** see docs/11 §2, which lists what is unverified and the mitigation ("Data is confidential").

## 6. Failure recovery

| Failure | Effect | Recovery |
|---|---|---|
| Relay down or misdeployed | Make gets a connection error or 5xx | Make retries with re-signing (docs/11). Roll back the relay revision (`update-traffic`); the engine is unaffected |
| Engine down | 502/504, retryable | Make retries. The engine rolls back independently (docs/04) |
| Cloud Storage unavailable | 503 `REPLAY_STORE_UNAVAILABLE`, retryable | Fail-closed by design. Emergency only: redeploy with `RELAY_REPLAY_STORE=memory`, `RELAY_SINGLE_INSTANCE=true`, `--max-instances=1` |
| Secret leaked | anyone can sign | Remove `allUsers` invoker on the relay, rotate the secret, review logs |
| Relay SA loses `run.invoker` | 502 `UPSTREAM_AUTH_FAILED` (non-retryable) | Restore the binding |

## 7. Minimum infrastructure (nothing created; commands to be prepared for approval)

| # | Resource | Configuration | IAM |
|---|---|---|---|
| 1 | API `secretmanager.googleapis.com` | enable | — |
| 2 | Secret `rsfa-make-relay-hmac` | automatic replication or `australia-southeast1`; 1 version | relay SA: `secretmanager.secretAccessor` on **this secret only** |
| 3 | Service account `rsfa-make-relay@<project>.iam.gserviceaccount.com` | no key, no project-level roles | — |
| 4 | Bucket `<project>-relay-replay` | `australia-southeast1`, uniform access, public access prevention **enforced**, lifecycle: delete objects older than 1 day (**age ≥ 1**: age 0 would let replays inside the window through), no versioning | relay SA: `roles/storage.objectCreator` on **this bucket only** (create; no read, list or delete) |
| 5 | Engine service `rsfa-end-forms-engine` | new revision 3 with `/v1` (no-traffic tag, verify, promote; docs/04). Allowlist unchanged unless RSFA approves more templates | add relay SA as `roles/run.invoker` on **this service only** |
| 6 | Relay service `rsfa-end-forms-relay` | same image; `ENGINE_ROLE=relay`, `RELAY_TARGET_URL=<engine URL>`, `RELAY_REPLAY_STORE=gcs`, `RELAY_REPLAY_BUCKET=<4>`, secret from 2 (**a different secret per environment**); service account 3; service name must contain `relay` (the image refuses to start the engine on such a service, iteration 9); `--allow-unauthenticated` (**the only public endpoint; RSFA decision**), ingress all, min 0, max 2, concurrency 4, timeout 120 s, 512 MiB, `australia-southeast1` | `allUsers` → `run.invoker` on the relay only |
| 7 | Logging | log-based metrics of §4; optional alert policy by email | — |

**New Google APIs:** one (Secret Manager). Cloud Storage, Cloud Run, Cloud Build and Artifact Registry are already used.

**Estimated cost at RSFA volumes (tens to hundreds of requests per month).** List prices; verify on the billing page.

| Item | Estimate |
|---|---|
| Relay on Cloud Run (scale to zero) | within the free tier |
| Secret Manager: 1 secret version (~US$0.06/month) plus accesses at start-up | cents |
| Cloud Storage: a few hundred tiny objects (Class A ops ~US$0.005 per 1,000, deleted after 1 day) | cents |
| Logging | within the free allotment |
| **Total** | **well under US$5/month** |

**Maintenance:**
- About 450 lines of tested relay code in the existing jar: no new build pipeline or runtime.
- One secret rotation procedure.
- One bucket lifecycle rule.
- API Gateway (D-026) would instead add three APIs and an OpenAPI config lifecycle, and still could not stop replays.

## 8. Deployment order and rollback (for the approval pack; iteration 9)

Each step is approved separately, and each has its own stop condition and rollback.

| # | Step | Verify before going on | Rollback |
|---|---|---|---|
| 0 | **Make `8731317` alias** (docs/09), independent of the cloud work | re-read diff = 7 lines; state unchanged; first natural run files lender PDFs | restore the rollback blueprint |
| 1 | **Engine revision 3** with the bundle of docs/04 (SHA-256 recorded there): `UPDATE_EXISTING=1 EXPECT_PREVIOUS_REVISION=rsfa-end-forms-engine-00002-nan bash deploy_poc.sh` | the script's checks on the candidate and the main URL, `/v1` included | `update-traffic --to-revisions=rsfa-end-forms-engine-00002-nan=100` |
| 2 | Secret Manager API, secret (test value), relay SA, replay bucket (lifecycle age 1 day), IAM: secret accessor, `objectCreator` on the bucket, **`run.invoker` on the engine for the relay SA** | `get-iam-policy` on each resource shows only these bindings; the engine still has no `allUsers` | remove the bindings; delete the bucket, SA and secret |
| 3 | Relay service **without** `allUsers` (`ENGINE_ROLE=relay`, `RELAY_REPLAY_STORE=gcs`, max 2, concurrency 4, timeout 120 s) | through `gcloud run services proxy`: `/health` = `"role":"relay","replayStore":"gcs"`; signed synthetic BNZ → 200; wrong secret → 401; identical resend → **409** (if 503, Cloud Storage answered 403: apply the M3 fix before going on); `/generate` → 404 | delete the relay service |
| 4 | Billing budget alert; log exclusion for relay 401/404/405/413 | alert listed; exclusion active | remove them |
| 5 | **Public exposure:** `allUsers` → `run.invoker` on the relay only (needs RSFA's explicit approval) | unsigned request from outside → 401; the engine URL from outside → 403 | remove the binding (instant; cuts Make off) |
| 6 | Make test scenario (docs/11), test SharePoint folder only, inactive between test sessions | T1–T13 as documented; no change in `8731317`/`9254149`/`8732008` history | deactivate and delete the scenario; remove test files |

**What stays untouched throughout:**
- Monday boards and columns;
- `9254149` and `8732008`;
- production SharePoint folders;
- MacroForge (still produces every form);
- the engine allowlist (BNZ only).

## 9. Independent security review (iteration 9, 2026-10-28)

**Method:** a separate reviewer read the relay code, the tests, the Make signer and §7 of this page, without this
page's conclusions. No HIGH-severity issue was found. Fixes were applied locally and tested (`RelayHardeningTest`, now
13 tests). **Nothing was deployed.**

| # | Finding | Severity | Status |
|---|---|---|---|
| M1 | Unauthenticated clients could make the relay read up to 1 MiB, and slow clients could hold the few instance slots (2 × 4) | MEDIUM | **Fixed:** headers (format, freshness) and `Content-Length` are checked before any body byte is read; request read time is bounded at 15 s. **Remaining, accepted with mitigation:** per-request charges and Cloud Run request logs for unauthenticated traffic are not capped by the relay. Mitigations: a billing budget alert; a log exclusion for relay 401/404/405/413 lines; max instances 2 caps instance cost. Cloud Armor would need a load balancer (more cost and complexity), so it is not proposed now |
| M2 | The replay record was written before the rate limit and the token fetch, so a retryable refusal consumed the signature | MEDIUM | **Fixed:** rate limit and token fetch now come first (tested: after a token failure, the same bytes are accepted; a 429 leaves no record). 409 is documented as "already forwarded once; re-sign to retry" |
| M3 | With `storage.objectCreator` only, Cloud Storage may answer **403** instead of 412 for an existing object | MEDIUM | **To verify before go-live** (deployment step 3 below): fail-closed either way, but a 403 would turn every replay into 503 retryable. If confirmed, either treat 403 on the precondition upload as replay, or grant `storage.objectUser` on the bucket |
| M4 | The upstream timeout covered only the response headers; a stalled body could exceed Cloud Run's 120 s | MEDIUM | **Fixed:** one 90 s deadline for the whole exchange, body included (tested with a stalled body) |
| L1 | A forgotten or misspelt `ENGINE_ROLE` on the relay service would start a public engine | LOW | **Fixed:** unknown roles are refused; the engine refuses to start on a Cloud Run service whose name contains `relay` (tested). After deployment, `/health` must report `"role":"relay"` |
| L2 | The signature does not cover host or path: a request captured on a test relay could be replayed to production within 300 s if both shared a secret | LOW | **Deployment rule:** a different secret per environment |
| L3 | Rotation: secrets in env vars are read at instance start; commas are not allowed; edge whitespace is trimmed | LOW | **Procedure:** pin secret version numbers and deploy a new relay revision at each rotation step; generate secrets with `openssl rand -hex 32` (no commas or spaces) |
| L4 | Failure causes were not logged | LOW | **Fixed:** the log outcome carries the exception class only (e.g. `token_unavailable:IOException`); never messages, tokens or values |
| L5 | The Make Code module receives the secret as an input | LOW | **Make setting:** "Data is confidential" must be on (docs/11 §2) |

**Confirmed OK by the review:**
- Cloud Storage create-if-absent request format and status handling.
- No automatic HTTP retries by the JDK client.
- Expiry consistent with the ±300 s window.
- No response storage (business idempotency stays in Make).
- HMAC over the exact received bytes with constant-time comparison.
- No SSRF: the upstream path is fixed; only the body, `Content-Type` and a validated correlation id are forwarded.
- No log injection.
