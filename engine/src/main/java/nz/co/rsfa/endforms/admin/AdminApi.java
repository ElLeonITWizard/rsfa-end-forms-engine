package nz.co.rsfa.endforms.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import nz.co.rsfa.endforms.catalog.TemplateCatalog;
import nz.co.rsfa.endforms.fill.FillResult;
import nz.co.rsfa.endforms.fill.FormFiller;
import nz.co.rsfa.endforms.fill.Issue;
import nz.co.rsfa.endforms.mapping.FieldRule;
import nz.co.rsfa.endforms.mapping.Manifest;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Local administration area (D-038): template library, upload and validation, field inspector, mapping editor, test
 * generation with synthetic data, drafts with versions and audit trail, and (mock) mapping suggestions.
 *
 * <p>Enabled only with {@code ENGINE_ADMIN=local} on a loopback address outside Cloud Run ({@link #requireLocal}); the
 * deployed engine never registers these routes. Nothing here publishes a template or changes the allowlist.
 * Logs carry route, status and codes only.
 */
public final class AdminApi {

    private static final Logger LOG = Logger.getLogger(AdminApi.class.getName());
    private static final Pattern TEMPLATE = Pattern.compile("/admin/api/templates/([0-9a-f]{64})(/pages/(\\d{1,3})\\.png|/file\\.pdf)?");
    private static final Pattern DRAFT = Pattern.compile("/admin/api/drafts/(d-[0-9a-f]{12})(/[a-z-]+(/\\d{1,4})?)?");
    private static final Pattern RESULT = Pattern.compile("/admin/api/results/([0-9a-f]{32})(\\.pdf|/pages/(\\d{1,3})\\.png|/template/pages/(\\d{1,3})\\.png)");
    private static final Map<String, String[]> STATIC = Map.of(
            "/admin/", new String[] {"/ui/admin/index.html", "text/html; charset=utf-8"},
            "/admin/admin.css", new String[] {"/ui/admin/admin.css", "text/css; charset=utf-8"},
            "/admin/admin.js", new String[] {"/ui/admin/admin.js", "text/javascript; charset=utf-8"});
    private static final int MAX_JSON = 2 * 1024 * 1024;

    private final Workspace workspace;
    private final TemplateCatalog catalog;
    private final MondaySchema schema;
    private final Path repoRoot;
    private final MappingSuggestions.SuggestionProvider suggestions;
    private final Clock clock;
    private final String actor;
    private final ObjectMapper json = TemplateCatalog.json();
    private final Map<String, TemplateAnalysis.Report> analyses = new HashMap<>();
    private final Map<String, byte[]> renders = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> e) {
            return size() > 96;
        }
    };
    private final Map<String, TestRun> results = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, TestRun> e) {
            return size() > 20;
        }
    };

    private record TestRun(String templateSha, byte[] pdf) {
    }

    /**
     * @param repoRoot repository root, for {@code curation/}, {@code deploy/poc-allowlist.json} and
     *                 {@code deploy/deployment-state.json} (library statuses)
     * @param actor    name recorded in the audit trail (local development: from ADMIN_ACTOR)
     */
    public AdminApi(Workspace workspace, TemplateCatalog catalog, MondaySchema schema, Path repoRoot,
                    MappingSuggestions.SuggestionProvider suggestions, Clock clock, String actor) {
        this.workspace = workspace;
        this.catalog = catalog;
        this.schema = schema;
        this.repoRoot = repoRoot;
        this.suggestions = suggestions;
        this.clock = clock;
        this.actor = Workspace.safeText(actor, 80);
    }

    /** Refuses to enable the administration area anywhere but a loopback address outside Cloud Run. */
    public static void requireLocal(String bindHost, Map<String, String> env) {
        if (env.get("K_SERVICE") != null) {
            throw new IllegalStateException("ENGINE_ADMIN is for local development only and is refused on Cloud Run");
        }
        if (!java.net.InetAddress.getLoopbackAddress().getHostAddress().equals(bindHost) && !"127.0.0.1".equals(bindHost)
                && !"localhost".equals(bindHost)) {
            throw new IllegalStateException("ENGINE_ADMIN needs a loopback bind address");
        }
    }

    public void handle(HttpExchange ex, boolean authorised) throws IOException {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        int status = 500;
        try {
            if (!authorised) {
                status = send(ex, 401, err("UNAUTHORISED", "authentication required"));
                return;
            }
            if (path.equals("/admin")) {
                ex.getResponseHeaders().set("Location", "/admin/");
                ex.sendResponseHeaders(302, -1);
                status = 302;
                return;
            }
            if (STATIC.containsKey(path)) {
                status = staticFile(ex, STATIC.get(path));
                return;
            }
            status = route(ex, path, method);
        } catch (Workspace.Conflict e) {
            status = send(ex, 409, err("VERSION_CONFLICT", e.getMessage()));
        } catch (Workspace.Refused e) {
            status = send(ex, 422, err(e.code, e.getMessage()));
        } catch (BadRequest e) {
            status = send(ex, 400, err("BAD_REQUEST", e.getMessage()));
        } catch (RuntimeException e) {
            status = send(ex, 500, err("INTERNAL", e.getClass().getSimpleName()));
        } finally {
            ex.close(); // after the catch blocks: try-with-resources would close the exchange before an error is sent
            LOG.info(String.format("admin %s %s status=%d", method, path.replaceAll("[0-9a-f]{32,64}", "<id>"), status));
        }
    }

    private static final class BadRequest extends RuntimeException {
        BadRequest(String m) {
            super(m);
        }
    }

    private int route(HttpExchange ex, String path, String method) throws IOException, Workspace.Conflict, Workspace.Refused {
        if (path.equals("/admin/api/library") && method.equals("GET")) {
            return send(ex, 200, library());
        }
        if (path.equals("/admin/api/schema") && method.equals("GET")) {
            ObjectNode o = json.createObjectNode();
            o.put("source", schema.source());
            o.set("columns", json.valueToTree(schema.columns()));
            return send(ex, 200, o);
        }
        if (path.equals("/admin/api/templates") && method.equals("POST")) {
            return upload(ex);
        }
        Matcher t = TEMPLATE.matcher(path);
        if (t.matches() && method.equals("GET")) {
            String sha = t.group(1);
            Optional<byte[]> pdf = workspace.templateBytes(sha);
            if (pdf.isEmpty()) {
                return send(ex, 404, err("NOT_FOUND", "no such template"));
            }
            if (t.group(3) != null) {
                return png(ex, sha, pdf.get(), Integer.parseInt(t.group(3)), dpi(ex));
            }
            if (t.group(2) != null) {
                return bytes(ex, pdf.get(), "application/pdf");
            }
            ObjectNode o = json.createObjectNode();
            o.set("meta", workspace.templateMeta(sha).orElseThrow());
            o.set("analysis", json.valueToTree(analysis(sha, pdf.get())));
            return send(ex, 200, o);
        }
        if (path.equals("/admin/api/drafts") && method.equals("POST")) {
            JsonNode b = body(ex);
            ObjectNode state = workspace.createDraft(b.path("templateSha256").asText(), b.path("templateId").asText(),
                    b.path("templateVersion").asText(), null, actor, "new draft");
            return send(ex, 201, state);
        }
        if (path.equals("/admin/api/drafts/from-manifest") && method.equals("POST")) {
            return fromManifest(ex, body(ex));
        }
        Matcher d = DRAFT.matcher(path);
        if (d.matches()) {
            String id = d.group(1);
            String sub = d.group(2) == null ? "" : d.group(2).replaceAll("/\\d+$", "");
            ObjectNode state = workspace.state(id).orElse(null);
            if (state == null) {
                return send(ex, 404, err("NOT_FOUND", "no such draft"));
            }
            switch (sub + " " + method) {
                case " GET" -> {
                    return send(ex, 200, draftView(state));
                }
                case " PUT" -> {
                    JsonNode b = body(ex);
                    ObjectNode s = workspace.save(id, b.path("baseVersion").asInt(-1), b.path("mapping"), actor,
                            b.path("note").asText(""));
                    return send(ex, 200, draftView(s));
                }
                case "/versions GET" -> {
                    int v = Integer.parseInt(d.group(3).substring(1));
                    Optional<ObjectNode> m = workspace.mapping(id, v);
                    return m.isPresent() ? send(ex, 200, m.get()) : send(ex, 404, err("NOT_FOUND", "no such version"));
                }
                case "/status POST" -> {
                    JsonNode b = body(ex);
                    ObjectNode s = workspace.setStatus(id, b.path("baseVersion").asInt(-1), b.path("status").asText(),
                            b.path("approval").isObject() ? b.path("approval") : null, actor);
                    return send(ex, 200, draftView(s));
                }
                case "/synthetic POST" -> {
                    return send(ex, 200, synthetic(state));
                }
                case "/test POST" -> {
                    return send(ex, 200, test(state, body(ex)));
                }
                case "/suggestions POST" -> {
                    return send(ex, 200, json.valueToTree(suggest(state)));
                }
                default -> {
                    return send(ex, 404, err("NOT_FOUND", "unknown draft operation"));
                }
            }
        }
        Matcher r = RESULT.matcher(path);
        if (r.matches() && method.equals("GET")) {
            TestRun run;
            synchronized (results) {
                run = results.get(r.group(1));
            }
            if (run == null) {
                return send(ex, 404, err("NOT_FOUND", "test result expired; generate again"));
            }
            if (r.group(2).equals(".pdf")) {
                return bytes(ex, run.pdf(), "application/pdf");
            }
            if (r.group(3) != null) {
                return png(ex, "result-" + r.group(1), run.pdf(), Integer.parseInt(r.group(3)), dpi(ex));
            }
            byte[] tpl = workspace.templateBytes(run.templateSha()).orElseThrow();
            return png(ex, run.templateSha(), tpl, Integer.parseInt(r.group(4)), dpi(ex));
        }
        return send(ex, 404, err("NOT_FOUND", "unknown admin route"));
    }

    // ------------------------------------------------------------------------------------------- operations

    private int upload(HttpExchange ex) throws IOException {
        Map<String, String> q = query(ex);
        byte[] pdf = ex.getRequestBody().readNBytes((int) TemplateAnalysis.MAX_UPLOAD_BYTES + 1);
        TemplateAnalysis.Report report = TemplateAnalysis.analyse(pdf);
        ObjectNode o = json.createObjectNode();
        o.set("analysis", json.valueToTree(report));
        if (!report.accepted()) {
            o.put("stored", false);
            return send(ex, 422, o);
        }
        String bank = q.getOrDefault("bank", "").trim();
        String name = q.getOrDefault("templateName", "").trim();
        if (bank.isEmpty() || name.isEmpty()) {
            throw new BadRequest("bank and templateName are required");
        }
        synchronized (analyses) {
            analyses.put(report.sha256(), report);
        }
        o.set("meta", workspace.saveTemplate(pdf, report, q.getOrDefault("fileName", "template.pdf"), bank, name,
                q.getOrDefault("templateVersion", ""), actor));
        o.put("stored", true);
        return send(ex, 201, o);
    }

    private TemplateAnalysis.Report analysis(String sha, byte[] pdf) {
        synchronized (analyses) {
            return analyses.computeIfAbsent(sha, k -> TemplateAnalysis.analyse(pdf));
        }
    }

    /** Library: repository manifests (read-only, with their review status) and local drafts and uploads. */
    private ObjectNode library() throws IOException {
        ObjectNode o = json.createObjectNode();
        ArrayNode items = o.putArray("items");
        JsonNode allow = readRepoJson("deploy/poc-allowlist.json");
        JsonNode deployed = readRepoJson("deploy/deployment-state.json");
        for (Manifest m : catalog.manifests()) {
            ObjectNode it = items.addObject();
            it.put("kind", "manifest").put("templateId", m.templateId()).put("templateVersion", m.templateVersion())
                    .put("bank", bankOf(m.templateId())).put("name", m.templateId());
            String status = "imported";
            String detail = "imported from MacroForge; not individually reviewed";
            JsonNode review = readRepoJson("curation/" + m.key() + ".json").path("review");
            if (!review.isMissingNode()) {
                String rs = review.path("status").asText();
                status = rs.startsWith("ready") ? "ready-for-review" : rs.equals("blocked") ? "blocked" : "imported";
                detail = rs.startsWith("ready") ? "ready for RSFA acceptance testing; not approved"
                        : rs.equals("blocked") ? "blocked by an open RSFA decision (docs/06)" : detail;
            }
            for (JsonNode a : allow.path("templates")) {
                if (a.path("templateId").asText().equals(m.templateId()) && a.path("templateVersion").asText().equals(m.templateVersion())
                        && a.path("approval").path("approvedBy").asText("").length() > 1) {
                    status = "approved";
                    detail = "approved for the POC allowlist (" + a.path("approval").path("reference").asText() + ")";
                    for (JsonNode dt : deployed.path("templates")) {
                        if (dt.asText().equals(m.key())) {
                            status = "deployed";
                            detail = "deployed: revision " + deployed.path("revision").asText() + " (" + deployed.path("recordedOn").asText() + ")";
                        }
                    }
                }
            }
            it.put("status", status).put("statusDetail", detail);
            int mapped = (int) m.fields().stream().map(FieldRule::pdfField).distinct().count();
            it.put("mappedFields", mapped).put("overlays", m.overlays() == null ? 0 : m.overlays().size());
            Optional<byte[]> tpl = catalog.templateBytes(m);
            if (tpl.isPresent()) {
                it.put("templateFields", analysis(m.templateSha256(), tpl.get()).fields().size());
            }
            it.put("templateAvailable", tpl.isPresent());
        }
        for (ObjectNode s : workspace.drafts()) {
            ObjectNode it = items.addObject();
            ObjectNode meta = workspace.templateMeta(s.path("templateSha256").asText()).orElse(json.createObjectNode());
            it.put("kind", "draft").put("draftId", s.path("draftId").asText()).put("templateId", s.path("templateId").asText())
                    .put("templateVersion", s.path("templateVersion").asText()).put("bank", meta.path("bank").asText())
                    .put("name", meta.path("templateName").asText()).put("status", s.path("status").asText())
                    .put("version", s.path("version").asInt()).put("statusDetail", s.path("status").asText().equals("approved")
                            ? "approved locally (" + s.path("approval").path("reference").asText() + "); not deployed"
                            : "local draft; not approved, not deployed");
            JsonNode mp = workspace.mapping(s.path("draftId").asText(), s.path("version").asInt()).orElseThrow();
            it.put("mappedFields", (int) java.util.stream.StreamSupport.stream(mp.path("fields").spliterator(), false)
                    .map(f -> f.path("pdfField").asText()).distinct().count());
            it.put("templateFields", meta.path("fieldCount").asInt());
            it.put("templateSha256", s.path("templateSha256").asText());
        }
        for (ObjectNode meta : workspace.templates()) {
            boolean hasDraft = workspace.drafts().stream().anyMatch(s -> s.path("templateSha256").asText().equals(meta.path("sha256").asText()));
            if (!hasDraft) {
                ObjectNode it = items.addObject();
                it.put("kind", "template").put("templateSha256", meta.path("sha256").asText()).put("bank", meta.path("bank").asText())
                        .put("name", meta.path("templateName").asText()).put("templateVersion", meta.path("templateVersion").asText())
                        .put("status", "uploaded").put("statusDetail", "uploaded; no mapping draft yet")
                        .put("mappedFields", 0).put("templateFields", meta.path("fieldCount").asInt());
            }
        }
        o.put("schemaSource", schema.source());
        return o;
    }

    /** Copies a repository manifest into a new draft: template from the catalog, keys resolved to column IDs. */
    private int fromManifest(HttpExchange ex, JsonNode b) throws IOException, Workspace.Refused {
        Manifest m = catalog.manifest(b.path("templateId").asText(), b.path("templateVersion").asText())
                .orElseThrow(() -> new Workspace.Refused("UNKNOWN_TEMPLATE", "no such manifest"));
        byte[] pdf = catalog.templateBytes(m).orElseThrow(() -> new Workspace.Refused("TEMPLATE_UNAVAILABLE",
                "the blank template is not available locally (.local/generated/templates)"));
        TemplateAnalysis.Report report = analysis(m.templateSha256(), pdf);
        workspace.saveTemplate(pdf, report, m.templateFile(), bankOf(m.templateId()), m.templateId(), m.templateVersion(), actor);
        ObjectNode mapping = json.createObjectNode();
        ArrayNode fields = mapping.putArray("fields");
        int unresolved = 0;
        for (FieldRule r : m.fields()) {
            FieldRule.Source s = r.source();
            ObjectNode f = fields.addObject();
            f.put("pdfField", r.pdfField());
            if (r.option() != null) {
                f.put("option", r.option());
            }
            ObjectNode src = f.putObject("source");
            if (s.key() != null) {
                if (column(src, s.key()) == 0) {
                    src.put("type", "column");
                } else { // not a Monday column: a key 8731317 derives (GPT address split, split dates)
                    src.removeAll();
                    src.put("type", "derived").put("key", s.key());
                    unresolved++;
                }
            } else if (s.when() != null) {
                src.put("type", "condition").put("operator", s.when().operator().name());
                if (s.when().value() != null) {
                    src.put("value", s.when().value());
                }
                unresolved += column(src, s.when().key());
            } else if ("today".equals(s.generated())) {
                src.put("type", "today").put("format", s.format());
            } else if (s.constant() != null && s.constant().isTextual()) {
                src.put("type", "constant").put("text", s.constant().asText());
            } else {
                fields.remove(fields.size() - 1); // line items and constant ticks are not editable here; kept in the manifest
            }
        }
        ObjectNode state = workspace.createDraft(m.templateSha256(), m.templateId(), "draft-" + m.templateVersion(),
                mapping, actor, "copied from manifest " + m.key() + (unresolved > 0 ? " (" + unresolved + " keys are not "
                        + "Monday columns: kept as derived keys)" : "") + (m.overlays() == null || m.overlays().isEmpty() ? ""
                        : "; overlays not copied"));
        return send(ex, 201, state);
    }

    private int column(ObjectNode src, String key) {
        Optional<MondaySchema.Column> c = schema.byTitle(key);
        if (c.isEmpty()) {
            c = schema.columns().stream().filter(x -> x.title().replace('/', '_').replace('.', '_').equals(key)).findFirst();
        }
        src.put("columnTitle", key);
        c.ifPresent(x -> src.put("columnId", x.id()));
        return c.isPresent() ? 0 : 1;
    }

    private ObjectNode draftView(ObjectNode state) throws IOException {
        String id = state.path("draftId").asText();
        ObjectNode mapping = workspace.mapping(id, state.path("version").asInt()).orElseThrow();
        byte[] pdf = workspace.templateBytes(state.path("templateSha256").asText()).orElseThrow();
        DraftCompiler.Result compiled = new DraftCompiler().compile(state, mapping, analysis(state.path("templateSha256").asText(), pdf), schema);
        ObjectNode o = json.createObjectNode();
        o.set("state", state);
        o.set("mapping", mapping);
        o.set("problems", json.valueToTree(compiled.problems()));
        o.set("audit", json.valueToTree(workspace.audit(id)));
        o.set("meta", workspace.templateMeta(state.path("templateSha256").asText()).orElse(json.createObjectNode()));
        return o;
    }

    /** Fictional values for every mapped column (never real records). */
    private ObjectNode synthetic(ObjectNode state) throws IOException {
        ObjectNode mapping = workspace.mapping(state.path("draftId").asText(), state.path("version").asInt()).orElseThrow();
        ObjectNode data = json.createObjectNode();
        for (JsonNode f : mapping.path("fields")) {
            JsonNode s = f.path("source");
            String id = s.path("columnId").asText("");
            if (id.isEmpty() || data.has(id)) {
                continue;
            }
            Optional<MondaySchema.Column> c = schema.column(id);
            if (s.path("type").asText().equals("condition")) {
                // multi-select (includes): every label the draft tests, joined as Monday's text is; otherwise the first
                List<String> labels = new ArrayList<>();
                boolean multi = true;
                for (JsonNode g : mapping.path("fields")) {
                    JsonNode gs = g.path("source");
                    if (gs.path("columnId").asText("").equals(id) && gs.path("type").asText().equals("condition")) {
                        multi &= gs.path("operator").asText().equals("includes");
                        if (!gs.path("value").asText("").isEmpty() && !labels.contains(gs.path("value").asText())) {
                            labels.add(gs.path("value").asText());
                        }
                    }
                }
                data.put(id, labels.isEmpty() ? "Yes" : multi ? String.join(", ", labels) : labels.get(0));
            } else {
                data.put(id, fictional(c.map(MondaySchema.Column::title).orElse(id), c.map(MondaySchema.Column::type).orElse("text"),
                        c.map(MondaySchema.Column::labels).orElse(List.of())));
            }
        }
        ObjectNode o = json.createObjectNode();
        o.put("today", LocalDate.now(clock).toString()).put("notice", "fictional values generated for testing only");
        o.set("data", data);
        return o;
    }

    static String fictional(String title, String type, List<String> labels) {
        String t = title.toLowerCase(java.util.Locale.ROOT);
        if (!labels.isEmpty()) return labels.get(0);
        if (type.equals("date")) return "01112026";
        if (type.equals("numbers") || t.contains("amount") || t.contains("value")) return "250.00";
        if (t.contains("postcode") || t.contains("zip")) return "0999";
        if (t.contains("city") || t.contains("town") || t.contains("suburb")) return "Testville";
        if (t.contains("address") || t.contains("street")) return "12 Example Street";
        if (t.contains("email")) return "jane.example@example.invalid";
        if (t.contains("phone") || t.contains("mobile")) return "021 000 0000";
        if (t.contains("number")) return "100200300";
        if (t.contains("applicant 2") || t.contains("second")) return "John Sample";
        if (t.contains("name")) return "Jane Example";
        return "Fictional value";
    }

    /** Compiles the current version, fills it with the given synthetic data, records the outcome (codes only). */
    private ObjectNode test(ObjectNode state, JsonNode body) throws IOException {
        String id = state.path("draftId").asText();
        int version = state.path("version").asInt();
        ObjectNode mapping = workspace.mapping(id, version).orElseThrow();
        String sha = state.path("templateSha256").asText();
        byte[] template = workspace.templateBytes(sha).orElseThrow();
        TemplateAnalysis.Report report = analysis(sha, template);
        DraftCompiler.Result compiled = new DraftCompiler().compile(state, mapping, report, schema);
        ObjectNode o = json.createObjectNode();
        o.put("version", version);
        o.set("problems", json.valueToTree(compiled.problems()));
        if (!compiled.ok()) {
            o.put("ok", false).put("stage", "compile");
            workspace.recordTest(id, version, false, (int) compiled.problems().stream().filter(p -> p.severity().equals("error")).count(),
                    0, null, actor);
            return o;
        }
        ObjectNode data = json.createObjectNode();
        JsonNode in = body.path("data");
        List<String> unknown = new ArrayList<>();
        in.fieldNames().forEachRemaining(colId -> {
            Optional<MondaySchema.Column> c = schema.column(colId);
            if (c.isEmpty()) {
                unknown.add(colId);
            } else {
                data.set(c.get().title(), in.get(colId));
            }
        });
        if (!unknown.isEmpty()) {
            throw new BadRequest("unknown column ids in the test data: " + unknown.size());
        }
        String today = body.path("today").asText(LocalDate.now(clock).toString());
        LocalDate day;
        try {
            day = LocalDate.parse(today);
        } catch (RuntimeException e) {
            throw new BadRequest("today must be YYYY-MM-DD");
        }
        Manifest manifest = json.treeToValue(compiled.manifest(), Manifest.class);
        FillResult r = new FormFiller(z -> day).fill(template, manifest, data);
        int errors = (int) r.issues().stream().filter(i -> i.severity() == Issue.Severity.ERROR).count();
        int warnings = r.issues().size() - errors;
        o.put("ok", r.ok()).put("stage", "fill");
        o.set("issues", json.valueToTree(r.issues()));
        o.set("filled", json.valueToTree(r.filled()));
        o.set("skipped", json.valueToTree(r.skipped()));
        List<String> mappedNames = manifest.fields().stream().map(FieldRule::pdfField).distinct().toList();
        o.set("unmapped", json.valueToTree(report.fields().stream().map(TemplateAnalysis.Field::name)
                .filter(n -> !mappedNames.contains(n)).sorted().toList()));
        o.set("protectedFields", json.valueToTree(report.fields().stream().filter(TemplateAnalysis.Field::protectedField)
                .map(TemplateAnalysis.Field::name).sorted().toList()));
        String pdfSha = null;
        if (r.ok()) {
            pdfSha = FormFiller.sha256(r.pdf());
            byte[] token = new byte[16];
            new java.security.SecureRandom().nextBytes(token);
            String tok = java.util.HexFormat.of().formatHex(token);
            synchronized (results) {
                results.put(tok, new TestRun(sha, r.pdf()));
            }
            o.put("resultId", tok).put("pdfSha256", pdfSha).put("pages", report.pages());
        }
        workspace.recordTest(id, version, r.ok(), errors, warnings, pdfSha, actor);
        return o;
    }

    private MappingSuggestions.Response suggest(ObjectNode state) throws IOException {
        String sha = state.path("templateSha256").asText();
        TemplateAnalysis.Report report = analysis(sha, workspace.templateBytes(sha).orElseThrow());
        ObjectNode mapping = workspace.mapping(state.path("draftId").asText(), state.path("version").asInt()).orElseThrow();
        java.util.Set<String> mapped = new java.util.HashSet<>();
        mapping.path("fields").forEach(f -> mapped.add(f.path("pdfField").asText()));
        List<MappingSuggestions.TemplateField> fields = report.fields().stream()
                .map(f -> new MappingSuggestions.TemplateField(f.name(), f.kind(), f.options(), f.label(), f.protectedField(),
                        TemplateAnalysis.declarationLike(f), mapped.contains(f.name()))).toList();
        List<MappingSuggestions.SchemaColumn> cols = schema.columns().stream()
                .map(c -> new MappingSuggestions.SchemaColumn(c.id(), c.title(), c.type(), c.labels())).toList();
        List<MappingSuggestions.Reference> refs = new ArrayList<>();
        for (Manifest m : catalog.manifests()) {
            Optional<byte[]> tpl = catalog.templateBytes(m);
            Map<String, String> labels = new HashMap<>();
            tpl.ifPresent(b -> analysis(m.templateSha256(), b).fields().forEach(f -> labels.put(f.name(), f.label())));
            for (FieldRule r : m.fields()) {
                if (r.source() != null && r.source().key() != null) {
                    refs.add(new MappingSuggestions.Reference(m.key(), r.pdfField(), labels.getOrDefault(r.pdfField(), ""), r.source().key()));
                }
            }
        }
        return suggestions.suggest(new MappingSuggestions.Request(MappingSuggestions.CONTRACT,
                state.path("templateId").asText(), "structure-only", fields, cols, refs));
    }

    // ------------------------------------------------------------------------------------------- helpers

    private JsonNode readRepoJson(String rel) {
        Path p = repoRoot.resolve(rel).normalize();
        if (!p.startsWith(repoRoot) || !Files.isRegularFile(p)) {
            return json.createObjectNode();
        }
        try {
            return json.readTree(p.toFile());
        } catch (IOException e) {
            return json.createObjectNode();
        }
    }

    private static String bankOf(String templateId) {
        int i = templateId.indexOf('_');
        return i > 0 ? templateId.substring(0, i) : templateId;
    }

    private int png(HttpExchange ex, String key, byte[] pdf, int page, float dpi) throws IOException {
        String k = key + "/" + page + "/" + dpi;
        byte[] out;
        synchronized (renders) {
            out = renders.get(k);
        }
        if (out == null) {
            try (PDDocument doc = Loader.loadPDF(pdf)) {
                if (page < 0 || page >= doc.getNumberOfPages()) {
                    return send(ex, 404, err("NOT_FOUND", "no such page"));
                }
                BufferedImage img = new PDFRenderer(doc).renderImageWithDPI(page, dpi);
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                ImageIO.write(img, "png", b);
                out = b.toByteArray();
            }
            synchronized (renders) {
                renders.put(k, out);
            }
        }
        return bytes(ex, out, "image/png");
    }

    private static float dpi(HttpExchange ex) {
        String q = ex.getRequestURI().getRawQuery();
        if (q != null && q.matches("dpi=\\d{2,3}")) {
            return Math.max(48, Math.min(200, Integer.parseInt(q.substring(4))));
        }
        return 96;
    }

    private JsonNode body(HttpExchange ex) throws IOException {
        byte[] b = ex.getRequestBody().readNBytes(MAX_JSON + 1);
        if (b.length > MAX_JSON) {
            throw new BadRequest("body too large");
        }
        try {
            JsonNode n = json.readTree(b);
            if (n == null || !n.isObject()) {
                throw new BadRequest("a JSON object is required");
            }
            return n;
        } catch (IOException e) {
            throw new BadRequest("malformed JSON");
        }
    }

    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> q = new HashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw != null) {
            for (String part : raw.split("&")) {
                int i = part.indexOf('=');
                if (i > 0) {
                    q.put(URLDecoder.decode(part.substring(0, i), StandardCharsets.UTF_8),
                            URLDecoder.decode(part.substring(i + 1), StandardCharsets.UTF_8));
                }
            }
        }
        return q;
    }

    private ObjectNode err(String code, String message) {
        return json.createObjectNode().put("status", "error").put("code", code).put("message", message);
    }

    private int staticFile(HttpExchange ex, String[] file) throws IOException {
        if (!ex.getRequestMethod().equals("GET")) {
            return send(ex, 405, err("METHOD_NOT_ALLOWED", "GET only"));
        }
        byte[] b;
        try (InputStream in = AdminApi.class.getResourceAsStream(file[0])) {
            if (in == null) {
                return send(ex, 404, err("NOT_FOUND", "missing asset"));
            }
            b = in.readAllBytes();
        }
        return bytes(ex, b, file[1]);
    }

    private int bytes(HttpExchange ex, byte[] body, String type) throws IOException {
        var h = ex.getResponseHeaders();
        h.set("Content-Type", type);
        h.set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data: blob:; "
                + "frame-src 'self' blob:; object-src 'self' blob:; base-uri 'none'; form-action 'none'; frame-ancestors 'self'");
        h.set("X-Content-Type-Options", "nosniff");
        h.set("Referrer-Policy", "no-referrer");
        h.set("Cache-Control", "no-store");
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
        return 200;
    }

    private int send(HttpExchange ex, int status, JsonNode body) throws IOException {
        byte[] b = json.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
        return status;
    }

    static String base64(byte[] b) {
        return Base64.getEncoder().encodeToString(b);
    }
}
