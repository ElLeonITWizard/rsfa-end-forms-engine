# 13 — Administration platform: local MVP, AI suggestions, production design and private access

Status 2026-10-28 (iteration 10). **Part A runs locally only. Parts C and D are designs: no Google Cloud resource,
endpoint, AI service or identity provider has been created or changed.** Production stays revision
`rsfa-end-forms-engine-00005-rih` (BNZ Boarder only, IAM required; docs/04).

## 1. What exists now (local MVP, D-038)

`ENGINE_ADMIN=local java -jar engine/target/end-forms-engine.jar serve` adds `/admin/` to the local server. The jar
refuses the admin area when `K_SERVICE` is set (any Cloud Run container) or the host is not loopback. Any other value
of `ENGINE_ADMIN` stops the server. Without the variable, `/admin/` returns 404, and Cloud Run never sets it.

| Feature | What it does | Where |
|---|---|---|
| Template library | repository manifests, uploads and drafts with one status each: `imported`, `blocked`, `ready-for-review`, `approved` (allowlist entry with an RSFA approval record), `deployed` (from `deploy/deployment-state.json`), `uploaded`, `draft` | `AdminApi` `/admin/api/library` |
| Upload validation | type (`%PDF-`), size ≤ 15 MiB, password/owner encryption, corrupt structure, AcroForm present, XFA, **scanned/flattened** (no fields + images / text only), **customer values** (any filled field refuses the upload), **signed** signature fields, preset ticks (warning), duplicate names | `TemplateAnalysis` |
| Field inspector | page render with field boxes (mapped / unmapped / never filled), name, kind, options, export values, geometry, maxLen, comb, multi-line, read-only, required, printed label (text next to the widget) | UI + `/templates/{sha}` |
| Mapping editor | per field: Monday column (stable **column ID**; the title is shown and is the current runtime key), condition (`equals` / `includes` / `present` / `absent` with the column's Monday labels), derived key (8731317 GPT/date splits, always warned), `today` with format, text constant; closed-option validation; problems list | `DraftCompiler` |
| Refusals | signature and push-button targets, constant ticks (need a D-016 approval record), two unconditional rules for one field, unknown columns or PDF fields, kind mismatches, unknown export values | `DraftCompiler` errors |
| Test generation | fictional values only (synthetic generator), the unchanged `FormFiller`, preview, download, filled / blank / unmapped counts, side-by-side with the blank template, SHA-256 | `/drafts/{id}/test` |
| Drafts | save creates version *n+1* (`v<n>.json`), stale saves refused (409), any save returns the draft to `draft`, audit trail (`audit.jsonl`, field names and change types only, never values) | `Workspace` |
| Review | `draft → ready-for-review → approved`; approval needs a passing test of the **same version** and a human record (approvedBy, approvedOn ≤ today, reference, confirmation); it is recorded as a *local development approval, not a deployment approval*. **There is no publish action.** | `Workspace.setStatus` |
| Suggestions | `mapping-suggestions.v1` with a **mock** provider (word-overlap heuristic, banner "MOCK … not an AI model"); accept/reject one by one into the unsaved draft | `MockSuggestionProvider` |

Storage is a local directory (`.local/admin-workspace` by default, git-ignored): `templates/<sha256>.pdf|.json`,
`drafts/<id>/state.json`, `v<n>.json` and `audit.jsonl`. The actor name comes from `ADMIN_ACTOR`. **There is no login:**
the local MVP relies on loopback only.

### Mock or simplified today

- suggestions (mock heuristic, not AI);
- storage (local directory);
- identity (`ADMIN_ACTOR` environment variable);
- the Monday schema (a saved export, `.local/reference/masterboard-columns-<date>.json`, not a live read);
- "deployed" (read from `deploy/deployment-state.json`, recorded by hand from RSFA's deployment record).

## 2. AI mapping suggestions (Part B, D-039)

**Contract:** `contracts/mapping-suggestions.v1.schema.json`, mirrored by `admin/MappingSuggestions.java`. The live
mock response validates against it.

```text
Request  { contractVersion, templateKey, dataPolicy: "structure-only",
           fields[{name, kind, options, label, protectedField, declarationLike, alreadyMapped}],
           columns[{id, title, type, labels}], references[{templateKey, pdfField, label, sourceKey}] }
Response { contractVersion, provider{id, isMock, model, notice}, generatedAt,
           proposals[{pdfField, sourceType: column|condition, columnId, columnTitle, operator, value, option,
                      confidence, evidence[], ambiguities[], alternatives[]}], refused[{pdfField, reason}], unmatched[] }
```

Rules (enforced by the platform, not only by the provider):

1. **Structure only.** The request contains template structure, printed labels, the Monday column schema and imported
   reference mappings. No data values, no item IDs and no PDF bytes. The PDF stays local.
2. **Proposals only.** Each proposal shows its confidence, evidence and ambiguities, plus alternatives. A person
   accepts or rejects each one; accepting edits the unsaved draft, and the normal compile, test and approval gates
   still apply. Confidence is never a permission to apply.
3. **No runtime role.** Suggestions run at design time. The engine stays deterministic, with no model at runtime.
4. **Never for signatures or declarations.** Protected and declaration-like fields are refused by the provider and,
   independently, by `DraftCompiler` (constant ticks, protected targets). The UI warns on declaration-like fields.
5. **Mock labelling.** `provider.isMock` must be shown. The mock's notice and banner say "not an AI model".

**Adding a real provider (not done; needs RSFA decisions):** implement `SuggestionProvider` in a new class, selected
by configuration, and keep the mock as the default. Decisions needed:

- which provider, and its data-processing terms: even structure-only data reveals lender form structure and Monday
  column names, which is not client data but is business information;
- region;
- cost ceiling;
- logging of prompts and responses (structure only).

Validate every response against the schema and drop proposals whose column ID or PDF field does not exist. No paid AI
resource has been created.

## 3. Production storage and publication (Part C, design only)

### Requirements

- template PDFs: immutable, by SHA-256;
- draft and approved mappings with versions;
- an audit trail;
- an approval workflow with two people (author ≠ approver);
- publish and rollback;
- admin authentication and authorisation;
- no client data;
- low cost;
- RSFA's small operations team.

### Options compared

| | A. GCS bucket + Git publication (recommended) | B. Firestore + GCS | C. Cloud SQL | D. SharePoint (Microsoft 365) |
|---|---|---|---|---|
| Templates | GCS, object name = SHA-256, versioning + retention | GCS | GCS | SharePoint library |
| Drafts / versions | GCS objects `drafts/<id>/v<n>.json` (create-if-absent `ifGenerationMatch=0`, as the relay replay store) | Firestore documents, transactions | tables | list items / files |
| Audit | append-only objects + Cloud Audit Logs | Firestore collection | table | version history |
| Publication | **pull request** adding the approved manifest + allowlist entry; the existing staging/deploy pipeline (docs/04) builds the next revision | same | same | same, plus export |
| Rollback | `gcloud run services update-traffic` to the previous revision (as today) + revert PR | same | same | same |
| Cost (estimate, verify) | cents per month at this volume | free tier likely sufficient | ≥ ~10 USD/month for the smallest instance | in the existing licence |
| Fit | reuses the relay's GCS pattern; no new database; Git stays the source of truth for production | richer queries, more moving parts | over-sized | familiar to RSFA, but a second integration surface and no create-if-absent primitive |

**Recommendation (D-040): A.** Production mappings remain **files in Git** (`mappings/`, reviewed by PR) and the
**allowlist remains the gate** (`deploy/poc-allowlist.json` with an RSFA approval record). The admin service writes
drafts, test results and audit to a private, versioned GCS bucket. "Publish" means opening a pull request with the
compiled manifest, the template SHA and the approval record. It never means writing into the running engine. A
deployment remains RSFA's explicit action (docs/04). Nothing new is required at runtime: the engine keeps reading
manifests baked into its image.

### Approval workflow (production)

1. `draft`: the author maps fields and tests with synthetic data.
2. `ready-for-review`: the version is frozen.
3. `approved`: approved by a **different person** holding the approver role. The approval record names the version
   and the test SHA.
4. `publication requested`: a PR is opened. CI runs the Java tests, the PDF checks and the blank-template check.
5. `deployed`: RSFA runs `deploy_poc.sh` (docs/04) and `deployment-state.json` is updated from the deployment record.

Any edit after step 2 returns the draft to `draft` (as the local MVP does).

## 4. Permanent private browser access (Part D, plan only; nothing changed)

**Problem:** the demo page and any admin UI need a browser sign-in. The engine must stay IAM-private for Make's relay
(D-027), and `gcloud run services proxy` is not a daily tool for advisers.

### Evaluated: IAP directly on Cloud Run (no load balancer)

Google documents enabling IAP on a Cloud Run service. As summarised in search results (the docs host is blocked from
this environment): deploy with `--iap`, then grant the IAP service agent `service-<PROJECT_NUMBER>@gcp-sa-iap.iam.gserviceaccount.com`
`roles/run.invoker` on the service. Users receive `roles/iap.httpsResourceAccessor`.

The documented limitations that matter here:

- IAP replaces the caller's identity, so **services that rely on their own authentication may fail** behind it;
- IAP cannot be configured on both a load balancer and the service;
- the default is in-organisation Google identities. External identities (for example Microsoft Entra ID through
  Identity Platform) need a custom OAuth client and a sign-in page, and Identity Platform must be in the same project.

**TODO: verify** all of this against
<https://cloud.google.com/run/docs/securing/identity-aware-proxy-cloud-run> before any change, including whether the
feature is GA or preview, and whether RSFA's Google organisation tier supports it.

Core IAP use is free for Google Cloud resources, per Google's pricing page. Identity Platform bills per monthly active
user beyond a free tier. **TODO: verify** both prices.

### Recommendation: a separate admin service behind IAP; the engine unchanged

```text
browser (RSFA staff) ──IAP sign-in──> rsfa-end-forms-admin (Cloud Run, --iap, ingress all, no allUsers)
                                          │ ID token of its own service account (roles/run.invoker on the engine)
                                          ▼
Make ──HMAC──> relay (D-027) ──ID token──> rsfa-end-forms-engine (IAM only, unchanged: /generate, /v1, /health)
```

- **The engine stays exactly as it is.** IAM stays required and IAP is not added, because IAP would replace the
  relay's service-account identity. `/generate` and `/v1` remain reachable only with an invoker identity.
- **The admin service** serves the admin UI (and later the demo page), stores drafts in the bucket (Part C), and calls
  the engine for test PDFs with its own ID token. Admin roles live in the admin service: `viewer`, `author` and
  `approver`, mapped from IAP-verified email addresses (the `X-Goog-Authenticated-User-Email` header plus verification
  of the IAP JWT `x-goog-iap-jwt-assertion`).
- **Identities.** Option 1: Google identities of RSFA's Workspace/Cloud Identity users (simplest, free). Option 2:
  Microsoft Entra ID through Identity Platform (RSFA works in Microsoft 365); this needs a sign-in page, an OIDC app
  registration in Entra, and per-MAU cost. **Decision for RSFA.**

### Steps (for RSFA to run later; draft, not executed)

1. Enable `iap.googleapis.com`; create the IAP service agent (`gcloud beta services identity create --service=iap.googleapis.com`).
2. Deploy `rsfa-end-forms-admin` with `--no-allow-unauthenticated --iap`, a dedicated service account and
   `ENGINE_URL` set to the engine.
3. Grant `roles/run.invoker` on the admin service to the IAP service agent; grant `roles/iap.httpsResourceAccessor`
   to named RSFA users or a group.
4. Grant `roles/run.invoker` on the **engine** to the admin service account (in addition to the existing invokers).
5. Verify: anonymous → sign-in redirect; signed-in user without the role → 403; with the role → UI; the engine without
   a token → 403 (unchanged); Make relay `/v1` → 200 (unchanged).

**Rollback:**

- delete the admin service, or remove `--iap` and the IAP bindings;
- remove the admin service account's invoker binding on the engine;
- the engine revision is unaffected throughout.

**Cost:** one more Cloud Run service with zero minimum instances (expected near zero at this usage), plus a GCS bucket.
IAP itself has no charge for Cloud Run (to verify). Identity Platform per MAU applies only with Entra.

## 5. Open decisions

- **Identity provider** for staff sign-in: Google identities vs Microsoft Entra ID (Identity Platform).
- **Who holds `author` and `approver`**, and whether two-person approval is mandatory.
- **AI provider**, if any, and its data terms (structure-only is still business information).
- **Whether the demo page moves** to the admin service, leaving the engine API-only.
- **Live Monday schema reads** from the admin service (needs a read-only Monday token) vs periodic exports.
- **Retention period** for drafts and audit records.
