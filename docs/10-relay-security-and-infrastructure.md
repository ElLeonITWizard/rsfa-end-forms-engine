# 10 — Make → relay → private engine: security architecture and infrastructure plan (iteration 8)

**Status:**
- The relay is implemented and tested **locally only**. Nothing exists in Google Cloud or Make.
- Engine revision `rsfa-end-forms-engine-00002-nan` is untouched.
- Every resource below needs RSFA's approval of the exact commands (CLAUDE.md rule 8).

Decisions: D-027 (relay over API Gateway), D-031 (durable replay store), D-032 (signing in Make).

```text
Make scenario ──HTTPS + HMAC──▶ rsfa-end-forms-relay (Cloud Run, public, ENGINE_ROLE=relay)
                                   │ 1 verify timestamp ±300 s + HMAC-SHA256 (constant time, 2 secrets during rotation)
                                   │ 2 body must be a JSON object (≤ 1 MiB)
                                   │ 3 claim the signature once in Cloud Storage (create-if-absent, shared by all instances)
                                   │ 4 rate limit per instance
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
| Upstream timeout | 110 s (Cloud Run request timeout 120 s) | 504 `UPSTREAM_TIMEOUT`, retryable (tested with a slow stand-in and a 300 ms limit) |
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
| 4 | Bucket `<project>-relay-replay` | `australia-southeast1`, uniform access, public access prevention **enforced**, lifecycle: delete objects older than 1 day, no versioning | relay SA: `roles/storage.objectCreator` on **this bucket only** (create; no read, list or delete) |
| 5 | Engine service `rsfa-end-forms-engine` | new revision 3 with `/v1` (no-traffic tag, verify, promote; docs/04). Allowlist unchanged unless RSFA approves more templates | add relay SA as `roles/run.invoker` on **this service only** |
| 6 | Relay service `rsfa-end-forms-relay` | same image; `ENGINE_ROLE=relay`, `RELAY_TARGET_URL=<engine URL>`, `RELAY_REPLAY_STORE=gcs`, `RELAY_REPLAY_BUCKET=<4>`, secret from 2; service account 3; `--allow-unauthenticated` (**the only public endpoint; RSFA decision**), ingress all, min 0, max 2, concurrency 4, timeout 120 s, 512 MiB, `australia-southeast1` | `allUsers` → `run.invoker` on the relay only |
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

## 8. Deployment order and rollback (for the approval pack)

1. Engine revision 3 (no traffic) → verify `/health` and `/v1` with `gcloud run services proxy` → promote.
   - **Rollback:** `update-traffic --to-revisions=rsfa-end-forms-engine-00002-nan=100`.
2. API, secret, SA, bucket and IAM bindings (§7, rows 1–5).
   - **Rollback:** delete them in reverse order; nothing depends on them yet.
3. Relay deployed **without** `allUsers`. Verify through `gcloud run services proxy`:
   - `/health` shows `"replayStore":"gcs"`;
   - a signed synthetic request with `tools/make_like_client.py` returns 200;
   - wrong secret → 401; replay → 409.
4. RSFA approves exposure → add `allUsers` invoker on the relay.
   - **Rollback:** remove the binding (instant).
5. Make test scenario (docs/11), test SharePoint folder only.
