# Decision log

Lightweight ADR log, newest last. Status: `proposed` | `accepted` | `superseded`.
"Accepted (POC)" means it is in use for the POC, not that it has been approved for production by RSFA.

---

### D-001 Deterministic mappings, no runtime AI
- **Date:** 2026-10-27 · **Status:** accepted (POC)
- **Context:** Bank forms carry legal declarations. RSFA has little tolerance for errors. MacroForge mappings are explicit.
- **Decision:** The engine fills only from explicit `pdfField ← source` rules in version-controlled manifests. There are no LLM
  calls in the engine. Upstream preprocessing (Make's OpenAI address split) is out of scope and unchanged.
- **Trade-offs:** Each new form needs mapping work. In return, output is reproducible and auditable.
- **Validation / next review:** at production cutover. Revisit only if RSFA wants assisted mapping *authoring* (never at runtime).

### D-002 Java 21 + Apache PDFBox 3 engine, minimal HTTP service ready for Cloud Run
- **Date:** 2026-10-27 · **Status:** accepted (POC)
- **Context:** The service needs mature AcroForm support (appearance generation, encryption, multi-widget buttons) and has to run in a container.
- **Decision:** PDFBox 3.0.8, Jackson 2.20.1 and the JDK's built-in `HttpServer` on virtual threads, with no web framework.
  A fat jar is built with Maven Shade. The service listens on `$PORT` and keeps no local state. Python is used only for the one-off importer.
- **Trade-offs:** JDK `HttpServer` is basic (no metrics or OpenAPI). That is enough for a few endpoints and easy to replace.
- **Validation / next review:** before production (load test with real template sizes; decide on framework only if needed).

### D-003 Keep Monday and Make; cut over in parallel
- **Date:** 2026-10-27 · **Status:** proposed
- **Context:** The adviser workflow must not change. Make `8731317` already builds the payload. MacroForge's `200 routed`
  response proves dispatch only.
- **Decision:** Make remains the orchestrator. Later, Make calls `POST /generate` per template **alongside** MacroForge,
  and outputs are compared before any switch. Monday status/SharePoint filing stay in Make.
- **Trade-offs:** Make gains a template-selection loop. The parallel run temporarily doubles processing.
- **Validation / next review:** parallel-run comparison plan, which needs RSFA approval and a test Masterboard item.

### D-004 No GUI initially
- **Date:** 2026-10-27 · **Status:** accepted (POC)
- **Decision:** Mappings are JSON files in git and are reviewed by pull request. There is no mapping UI, database, accounts or multi-tenancy.
- **Next review:** if non-developers must edit mappings regularly.

### D-005 Template identity by SHA-256; lender PDFs never in git
- **Date:** 2026-10-27 · **Status:** accepted (POC)
- **Decision:** Each manifest pins `templateSha256`, and filling fails on mismatch. Templates are named
  `<sha12>__<name>.pdf` and live in `.local/` (dev) or a private bucket (later). Manifests and synthetic fixtures are in git.

### D-006 Strict validation: errors produce no PDF; unsupported legacy rules are surfaced
- **Date:** 2026-10-27 · **Status:** accepted (POC)
- **Decision:** Any ERROR (missing required value, unknown option, unencodable text, ambiguous array value, etc.)
  means no output. Optional empty values are skipped. Unsupported legacy rules become warnings on every fill.
  Messages never include data values.

### D-007 Encrypted lender templates: preserve via incremental save (opt-in per manifest)
- **Date:** 2026-10-27 · **Status:** accepted (POC) for GHL N&P Trust
- **Context:** 7 ASB/GHL templates are owner-encrypted (AES-128, fill-in permitted, owner password unknown).
  PDFBox refuses a full save without removing the security or setting a new password.
- **Decision:** The default is `reject`. With `"encryption": "preserve"`, the engine checks the fill-in permission and uses
  `saveIncremental`. The original bytes and encryption stay unchanged (verified: identical prefix, still encrypted, values readable).
- **Trade-offs:** Files grow by the appended revision (GHL N&P Trust: 996,075 → 1,034,614 bytes). Removing the bank's restrictions was rejected.
- **Next review:** enable per template only after an output review.

### D-008 Reproduce MacroForge key normalisation (`macroforge-v1`)
- **Date:** 2026-10-27 · **Status:** accepted (POC)
- **Context:** Legacy mapping keys use `_` where Monday titles contain `/` or `.`. In the `.mforge` sample payload, 0 of 476 keys
  contain those characters, while 30 Make keys do.
- **Decision:** Manifests declare `sourceKeyNormalization: macroforge-v1`. The engine maps `/` and `.` to `_` on data keys.
  Keys that collide after this are merged into an array, which makes any rule reading them fail rather than pick one.
- **Next review:** once a direct Make → engine contract is designed, consider mapping by Monday column **ID** instead of title.

### D-009 Authentication pattern
- **Date:** 2026-10-27 · **Status:** accepted for the first deployment (human invokers via Cloud Run IAM); Make service-to-service still proposed
- **Decision:** On Cloud Run, deploy with `--no-allow-unauthenticated` and `ENGINE_AUTH=PLATFORM_IAM`. The caller presents a Google
  ID token for a dedicated service account. If Make cannot mint ID tokens, use `API_KEY` (≥32 chars from Secret Manager),
  ideally behind API Gateway. `NONE` is refused on non-loopback binds.
- **Next review:** before any deployment, which needs explicit approval.

### D-010 Templates on Cloud Run via read-only Cloud Storage volume
- **Date:** 2026-10-27 · **Status:** proposed for production; deferred for the POC by D-018
- **Decision:** Mount a private GCS bucket at `/templates` (Cloud Run volume mount) and set `ENGINE_TEMPLATE_DIRS=/templates`.
  The engine needs no storage SDK. Mappings are baked into the image.
- **Next review:** with D-009.

### D-011 Overlays: reproduce MacroForge `custom_fields` with an evidenced coordinate transform
- **Date:** 2026-10-27 · **Status:** accepted (POC); production use needs one comparison with a real MacroForge output
- **Context:**
  - 14 legacy overlays carry values that no AcroForm field holds, e.g. the BNZ Boarder customer names.
  - The export records no scale.
  - Hand-writing a field mapping (e.g. BNZ `Text 1`) would invent behaviour.
- **Decision:**
  - The importer converts each overlay rect with `pdf = rect / 1.2`, top-left origin, into PDF points.
    Evidence: 4 independent label anchors fit s = 1.194 ± 0.013, and all 14 overlays were visually reviewed (docs/03).
  - The engine draws single-line text into the page content using embedded Liberation Sans (bundled in PDFBox).
    Size: ≤10pt, ≤70% of the rect height, shrunk to fit the width. Below 6pt it is an error, never truncated.
- **Trade-offs:**
  - The legacy font and size are unknown, so the look may differ from MacroForge.
  - Page-content text is not editable as a form field. It is visible in all viewers.
  - Overlays on encrypted templates are refused until verified.
- **Validation / next review:** compare positions against one real MacroForge output before cutover.

### D-012 LineItems: generic `lineItem` source driven by the export's `line_items_configs`
- **Date:** 2026-10-27 · **Status:** accepted (POC)
- **Decision:**
  - `{"lineItem": {"array", "position" (1-based), "path"}}` reads element n of a data array.
  - The importer builds it only from `sub_mappings` (verified) and cross-checks against `line_item_instances`.
  - Names not produced by `sub_mappings` stay unresolved; there is no aliasing.
  - Conditions on `LineItems.*` without a row index stay unsupported.
- **Trade-offs:** 64 legacy line-item mappings (term/period fields) stay unfilled until the naming is confirmed.
- **Next review:** after a real MacroForge callback or output confirms the current subfield names.

### D-013 Constant "checked" boxes require explicit human approval
- **Date:** 2026-10-27 · **Status:** accepted
- **Context:**
  - 36 legacy rules tick a box regardless of data.
  - One is the BNZ Handover adviser certification ("I confirm I have recommended … as appropriate").
  - The brief forbids auto-consenting to legally meaningful statements.
- **Decision:** a constant `true` rule is applied only when the manifest rule has `"approved": true`. Otherwise the box
  stays Off with `CONSTANT_CHECK_NOT_APPROVED`. Constant `false` (legacy default Off) needs no approval.
- **Trade-offs:** the output differs from legacy until RSFA approves each tick. This is deliberate.
- **Correction (2026-10-27, iteration 3):**
  - The BNZ Handover certification "By ticking here, I confirm I have recommended …" is field **`Confirmation`** (page 1),
    not `Check Box 31` (page 4). `Confirmation` is pre-ticked in BNZ's blank template and passed through because no
    rule touched it.
  - **Superseded by D-016**: the boolean `approved` is replaced by a validated approval record, and template presets are cleared.

### D-014 Unicode text: check each field's own font, fall back to an embedded Unicode font by manifest policy
- **Date:** 2026-10-27 · **Status:** accepted (POC)
- **Context:**
  - Bank templates use subset or WinAnsi/MacRoman fonts that cannot encode te reo Māori macrons.
  - Dropping or transliterating characters would corrupt names and addresses.
- **Decision:**
  - The engine checks encodability before writing.
  - With `fontFallback: unicode` (the importer's default, reviewable per manifest), only the affected field is re-pointed
    at a fully embedded Liberation Sans, with a `FONT_FALLBACK` warning.
  - With `none` the fill fails with `UNENCODABLE_TEXT`.
- **Trade-offs:** that field's typeface differs from the bank's font. Adds ~400 KB per affected PDF (full embedding is required for AcroForm appearances).

### D-015 Container: fonts and build inputs
- **Date:** 2026-10-27 · **Status:** accepted (POC)
- **Decision:**
  - Filling needs no system fonts: Liberation Sans comes from the PDFBox jar, and banks embed or reference standard-14 fonts.
  - The runtime image still installs `fontconfig` + `fonts-urw-base35`, so the `render` command (and any future thumbnails or flattening) draws ZapfDingbats check marks.
  - The Dockerfile has a `runtime-prebuilt` target for verifying with a host-built jar.
  - Optional `BUILD_IMAGE`, `APT_MIRROR`, `MAVEN_EXTRA_OPTS` and a BuildKit secret `build_ca` support TLS-intercepting proxies without leaving anything in the image.
- **Validation:** see the Docker verification table in docs/02.

### D-016 Declarations are never certified by default
- **Date:** 2026-10-27 · **Status:** accepted · supersedes the `approved` flag of D-013
- **Context:**
  - BNZ Handover's blank template ships with the adviser certification `Confirmation` already ticked.
  - The engine left untouched fields as the template had them, so the output certified on the adviser's behalf.
  - A bare `"approved": true` is not a validated authorization either: anyone editing JSON can set it.
- **Decision:**
  1. Every checkbox or radio that is on in the template, and that no rule of this fill selected or forced Off, is reset to
     Off (`TEMPLATE_PRESET_CLEARED`). The importer lists such fields (`TEMPLATE_PRESET_BUTTON`, `presetOn`).
  2. A constant tick needs `approval: {approvedBy, approvedOn, reference}`. The engine validates it: all present, and
     `approvedOn` an ISO date not in the future. If absent, the tick is not applied (warning). If malformed, or `approved: true` alone,
     the fill fails (`INVALID_APPROVAL`).
- **Evidence:** in 25 templates, 2 widgets are preset (BNZ Handover `Confirmation`, WBC Refix `Check Box1`). Both are now cleared.
- **Trade-offs:** outputs differ from legacy where legacy relied on template presets. Recording approvals is manual by design.

### D-017 Comb fields: own appearance, centred glyphs, measured placeholder knockout
- **Date:** 2026-10-27 · **Status:** accepted
- **Context:**
  - BNZ date boxes (`DD|MM|YY`) print faint placeholder letters in each cell.
  - PDFBox's comb appearance drew digits over them, so `Date 3` on BNZ Handover page 4 looked misaligned or struck through.
- **Decision:** for comb fields, the engine writes its own appearance:
  - each character is centred in its cell (glyph width, cap height), in the field's font, size and colour;
  - placeholders are knocked out only in cells that receive a character, using the cell background **measured from
    the blank template's rendering**;
  - if the samples disagree, there is no knockout and a `COMB_BACKGROUND_NOT_UNIFORM` warning;
  - values are never altered.
- **Validation:** synthetic test (placeholder pixels 0, digit ink centred within 1 pt) and BNZ Handover `Date 3`
  (0 placeholder pixels in cell interiors; the rest are neutral digit anti-aliasing).

### D-018 First Cloud Run deployment: staged context, baked allowlisted templates, IAM-only access
- **Date:** 2026-10-27 · **Status:** accepted: approved by RSFA (Leonardo Melman, 2026-10-27, `RSFA-END-FORMS-POC-2026-10-27`); execution pending (D-019)
- **Decision:**
  - Build from a context staged by `tools/stage_deploy.py`: allowlist, SHA-256, human approval, blank-PDF check and
    content scan, with the output inside `.local/`.
  - Cloud Build runs regionally with a dedicated build SA (Artifact Registry writer on one repository, read access to the
    private source bucket with a 7-day lifecycle, log writer).
  - Deploy by image digest, with `--no-allow-unauthenticated`, `ENGINE_AUTH=PLATFORM_IAM` (only allowed when `K_SERVICE` is set),
    a runtime SA with no roles, and the requested sizing.
  - Base images are pinned by digest via `mirror.gcr.io`.
- **Trade-offs:** templates change only with a new image. The blank BNZ form is stored in a private registry.
  Make cannot call it yet (D-009).
- **Validation:** local Cloud Run emulation (PLATFORM_IAM + K_SERVICE, 1 CPU / 1 GiB, read-only FS):
  - ready in 0.7 s;
  - `/generate` 200 in 0.9 s cold / 0.2 s warm, about 100 MiB;
  - output byte-identical to host;
  - 0 data values in logs.

### D-019 Deployment executed by one verified script; the deployer's own `gcloud` login provides credentials
- **Date:** 2026-10-27 · **Status:** accepted
- **Context:**
  - RSFA asked for an end-to-end deployment with minimal manual work.
  - The Claude Code cloud session has no Google Cloud credentials (`CREDENTIALS_MISSING`).
  - Service-account keys are forbidden.
  - Cloud Run IAM accepts only ID tokens, which a bare access token cannot provide.
- **Decision:** `deploy/deploy_poc.sh` automates every step (preflight, infrastructure, build SA, Cloud Build with a Docker
  fallback, deploy, caller-only invoker, verification, record). It runs where the deployer is logged in, preferably
  Cloud Shell, from a bundle made by `deploy/make_bundle.sh`.
  - Invoker is granted only to `gcloud config get-value account`.
  - The script refuses an existing service unless `UPDATE_EXISTING=1`.
  - The fill CLI now honours a request's pinned `today`, so the bundle's expected PDF SHA-256 equals the HTTP output.
  - Dockerfile: removed `required=false` from the secret mounts (it is the default) for compatibility with Cloud Build's
    Docker 20.10 + BuildKit builder.
- **Validation:** run end-to-end in this environment with a recording `gcloud` stub, the real POC image and an authenticating
  front proxy. All checks passed, 0 forbidden commands, and the rerun stopped safely.

### D-020 Demo page: static files served by the engine, same authentication, no browser-side mapping
- **Date:** 2026-10-27 · **Status:** accepted (POC demo)
- **Context:** RSFA needs a visual demo for Rod. Constraints:
  - no framework, npm, CDN, extra cloud service or paid dependency;
  - no new public endpoint;
  - Cloud Run IAM stays the only gate;
  - no mapping logic in the browser;
  - synthetic data only.
- **Decision:**
  - `ApiServer` serves a fixed allowlist of 4 classpath files (`/`, `/ui/app.css`, `/ui/app.js`, `/ui/forms.json`).
    Any other path returns 404, and only `GET` is accepted.
  - The files carry a strict CSP (`'self'` only, `frame-src blob:` for the preview), `nosniff`, `no-referrer` and `no-store`.
  - The page uses the same `authorised()` check as `/generate`. On Cloud Run, IAM protects page and API alike.
  - `forms.json` lists the UI fields. Its keys must equal the manifest's data keys (`DemoUiFormsTest`).
    Its sample is the synthetic fixture.
  - The browser sends the existing `/generate` schema. Empty fields are omitted, so the engine reports
    `SOURCE_KEY_ABSENT` warnings and leaves those boxes blank.
  - `/generate` successes add `X-Template` and `X-Engine` (`…; deterministic-mapping; pdfbox; no-ai`). The page shows
    them with the SHA-256 it computes.
  - Demo access to Cloud Run is `gcloud run services proxy`. It signs requests with the caller's own login and serves
    page and API from one localhost origin. No tokens are handled.
  - `tools/stage_deploy.py` refuses a context if:
    - a UI asset is missing;
    - an asset references another origin;
    - `forms.json` offers a template that is not allowlisted;
    - a staged `.html`, `.js` or `.css` file contains webhook/payload markers.
  - `deploy_poc.sh` checks that the page is rejected without a token and served with one.
- **Trade-offs:**
  - In `API_KEY` mode, a browser cannot load the page (no way to send the header). The local demo therefore uses
    `ENGINE_AUTH=NONE`, which is loopback-only.
  - Health is still unauthenticated in non-Cloud-Run modes (unchanged).
  - Headless browsers do not render the PDF preview. Real Chrome/Edge do (verified with headed Chromium under Xvfb).
- **Validation:**
  - `tests/ui/demo_smoke.js` (20 checks: sample → edit → generate → download → field/overlay values, no ticks, no
    signatures, SHA shown = file SHA, unedited sample = fixture SHA `2ae7bfac…`, visible `UNENCODABLE_TEXT` error that
    does not echo the value, no horizontal scroll at 390 px, no console errors).
  - Passed on the host (`ENGINE_AUTH=NONE`), in the POC image (`PLATFORM_IAM` + `K_SERVICE`), and through a test-only
    IAM stand-in plus a `gcloud run services proxy` emulator.
  - The real `gcloud` proxy and Cloud Run were **not** exercised: no Google Cloud credentials here.

### D-021 Docker build behind a TLS-intercepting proxy: apt reads a world-readable copy of the CA
- **Date:** 2026-10-27 · **Status:** accepted
- **Context:**
  - BuildKit mounts secrets as root-only `0400`. apt downloads as the unprivileged `_apt` user, so it could not read
    `/run/secrets/build_ca` ("Could not load certificates … Permission denied").
  - Earlier local images had come from cached layers.
- **Decision:**
  - When the `build_ca` secret is present, copy it to `/tmp/build-ca.crt` with mode 0644 for that single `RUN`.
  - Delete the copy and the apt config in the same `RUN`, so no CA is left in any layer (checked: none in `/tmp` or `apt.conf.d`).
  - Builds without the secret (Cloud Build) are unchanged.
