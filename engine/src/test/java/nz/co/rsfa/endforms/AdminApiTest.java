package nz.co.rsfa.endforms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import nz.co.rsfa.endforms.admin.AdminApi;
import nz.co.rsfa.endforms.admin.ExampleForm;
import nz.co.rsfa.endforms.admin.MockSuggestionProvider;
import nz.co.rsfa.endforms.admin.MondaySchema;
import nz.co.rsfa.endforms.admin.TemplateAnalysis;
import nz.co.rsfa.endforms.admin.Workspace;
import nz.co.rsfa.endforms.catalog.TemplateCatalog;
import nz.co.rsfa.endforms.fill.FormFiller;
import nz.co.rsfa.endforms.http.ApiServer;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Local administration area (D-038): upload validation, inspection, drafts, test generation, mock suggestions. */
class AdminApiTest {

    static final HttpClient HTTP = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
    static final ObjectMapper JSON = new ObjectMapper();
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-28T03:00:00Z"), ZoneOffset.UTC);

    @TempDir
    static Path dir;
    static ApiServer server;
    static String base;
    static byte[] example;
    static Path workspaceDir;

    /** Fictional Monday schema: IDs, titles, types and labels only. */
    static final String SCHEMA = """
            {"board":{"columns":[
             {"id":"text_name1","title":"Applicant 1 Full Name","type":"text"},
             {"id":"text_name2","title":"Applicant 2 Full Name","type":"text"},
             {"id":"text_postal","title":"Applicant 1 Postal Address","type":"text"},
             {"id":"text_city","title":"Applicant 1 Residential City/Town","type":"text"},
             {"id":"numbers_loan","title":"Total loan amount","type":"numbers"},
             {"id":"dropdown_freq","title":"Repayment Frequency","type":"dropdown","settings":{"labels":[
               {"id":1,"label":"Weekly"},{"id":2,"label":"Fortnightly"},{"id":3,"label":"Monthly"}]}},
             {"id":"dropdown_use","title":"Account use","type":"dropdown","settings":{"labels":[
               {"id":1,"label":"Everyday banking"},{"id":2,"label":"Savings"}]}},
             {"id":"text_dup_a","title":"Duplicate title","type":"text"},
             {"id":"text_dup_b","title":"Duplicate title","type":"text"}]}}""";

    @BeforeAll
    static void start() throws Exception {
        example = ExampleForm.build();
        Path schema = Files.writeString(dir.resolve("schema.json"), SCHEMA);
        Path mappings = Files.createDirectories(dir.resolve("mappings"));
        Path repo = Files.createDirectories(dir.resolve("repo"));
        workspaceDir = dir.resolve("ws");
        AdminApi admin = new AdminApi(new Workspace(workspaceDir, CLOCK), TemplateCatalog.load(mappings, List.of()),
                MondaySchema.load(schema), repo, new MockSuggestionProvider(CLOCK), CLOCK, "Test Admin");
        server = new ApiServer(TemplateCatalog.load(mappings, List.of()), new FormFiller(), ApiServer.AuthMode.NONE, null)
                .withAdmin(admin);
        base = "http://127.0.0.1:" + server.start("127.0.0.1", 0) + "/admin/api";
    }

    @AfterAll
    static void stop() {
        server.stop();
    }

    static HttpResponse<String> send(String method, String path, byte[] body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body))
                .header("X-RSFA-Admin", "1"); // the page's same-origin marker (cross-site write guard)
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static JsonNode json(String method, String path, Object body) throws Exception {
        HttpResponse<String> r = send(method, path, body == null ? null : JSON.writeValueAsBytes(body));
        JsonNode n = JSON.readTree(r.body());
        ((com.fasterxml.jackson.databind.node.ObjectNode) n).put("_status", r.statusCode());
        return n;
    }

    static JsonNode upload(byte[] pdf, String name) throws Exception {
        HttpResponse<String> r = send("POST", "/templates?fileName=" + name + "&bank=Other&templateName=Example%20Form", pdf);
        JsonNode n = JSON.readTree(r.body());
        ((com.fasterxml.jackson.databind.node.ObjectNode) n).put("_status", r.statusCode());
        return n;
    }

    static List<String> codes(JsonNode report) {
        return JSON.convertValue(report.path("analysis").path("findings").findValuesAsText("code"), List.class);
    }

    // ------------------------------------------------------------------------------------------ upload validation

    @Test
    void uploadAcceptsOnlyBlankFillableForms() throws Exception {
        JsonNode ok = upload(example, "example.pdf");
        assertEquals(201, ok.path("_status").asInt());
        assertTrue(ok.path("stored").asBoolean());
        assertEquals(13, ok.path("analysis").path("fields").size());

        record Case(byte[] pdf, String code) {
        }
        List<Case> refused = List.of(
                new Case("not a pdf".getBytes(), "NOT_A_PDF"),
                new Case("%PDF-1.7 garbage".getBytes(), "CORRUPT_PDF"),
                new Case(userPassword(example), "PASSWORD_PROTECTED"),
                new Case(textOnly(), "NO_FORM_FIELDS"),
                new Case(imageOnly(), "SCANNED_OR_FLATTENED"),
                new Case(edit(example, d -> setText(d, "CustName", "Jane Example")), "FIELD_HAS_VALUE"),
                new Case(edit(example, AdminApiTest::sign), "SIGNED"));
        for (Case c : refused) {
            JsonNode r = upload(c.pdf(), "x.pdf");
            assertEquals(422, r.path("_status").asInt(), c.code());
            assertFalse(r.path("stored").asBoolean());
            assertTrue(codes(r).contains(c.code()), c.code() + " " + codes(r));
            assertFalse(r.toString().contains("Jane Example"), "findings never echo a value");
        }
        assertEquals(1, Files.list(workspaceDir.resolve("templates")).filter(p -> p.toString().endsWith(".pdf")).count(),
                "nothing refused was stored");
        byte[] big = new byte[(int) TemplateAnalysis.MAX_UPLOAD_BYTES + 10];
        System.arraycopy("%PDF-1.7".getBytes(), 0, big, 0, 8);
        assertTrue(codes(upload(big, "big.pdf")).contains("TOO_LARGE"));
        JsonNode warn = upload(edit(example, d -> ((PDCheckBox) d.getDocumentCatalog().getAcroForm().getField("UseSavings")).check()), "tick.pdf");
        assertEquals(201, warn.path("_status").asInt(), "a preset tick is a warning (the engine clears it)");
        assertTrue(codes(warn).contains("PRESET_TICK"));
        JsonNode enc = upload(ownerPassword(example), "enc.pdf");
        assertEquals(201, enc.path("_status").asInt());
        assertTrue(codes(enc).contains("OWNER_ENCRYPTED"));
    }

    // ------------------------------------------------------------------------------------------ field inspection

    @Test
    void inspectorReportsTypesOptionsGeometryLabelsAndProtectedFields() throws Exception {
        TemplateAnalysis.Report r = TemplateAnalysis.analyse(example);
        Map<String, TemplateAnalysis.Field> f = new java.util.HashMap<>();
        r.fields().forEach(x -> f.put(x.name(), x));
        assertEquals("text", f.get("CustName").kind());
        assertEquals("Customer full name", f.get("CustName").label());
        assertEquals("Postcode", f.get("PCode").label(), "overlapping label regions each see their own text");
        assertEquals("Everyday banking", f.get("UseEveryday").label(), "checkbox labels are read on the right");
        assertEquals("radio", f.get("RepayFreq").kind());
        assertEquals(List.of("Fortnightly", "Monthly", "Weekly"), f.get("RepayFreq").options());
        assertEquals(3, f.get("RepayFreq").widgets().size());
        assertEquals("combo", f.get("Purpose").kind());
        assertEquals(List.of("Home loan", "Refinance", "Top-up"), f.get("Purpose").options());
        assertTrue(f.get("CustSignature").protectedField());
        TemplateAnalysis.Widget w = f.get("CustName").widgets().get(0);
        assertEquals(160, w.x(), 0.01);
        assertEquals(842 - 735, w.y(), 0.5, "top-left origin for the browser overlay");
        assertTrue(TemplateAnalysis.declarationLike(f.get("DeclTick")));
        assertTrue(TemplateAnalysis.declarationLike(f.get("DateSigned")));
        assertFalse(TemplateAnalysis.declarationLike(f.get("CustName")));
        JsonNode up = upload(example, "example.pdf");
        String sha = up.path("analysis").path("sha256").asText();
        HttpResponse<String> png = send("GET", "/templates/" + sha + "/pages/0.png?dpi=72", null);
        assertEquals(200, png.statusCode());
        assertEquals(404, send("GET", "/templates/" + sha + "/pages/7.png", null).statusCode());
        assertEquals(404, send("GET", "/templates/" + "0".repeat(64), null).statusCode());
    }

    // ------------------------------------------------------------------------------------------ drafts and mapping

    static String newDraft() throws Exception {
        String sha = upload(example, "example.pdf").path("analysis").path("sha256").asText();
        JsonNode d = json("POST", "/drafts", Map.of("templateSha256", sha, "templateId", "EXAMPLE_FORM", "templateVersion", "1"));
        assertEquals(201, d.path("_status").asInt());
        return d.path("draftId").asText();
    }

    static Map<String, Object> rule(String field, Map<String, Object> source) {
        return Map.of("pdfField", field, "source", source);
    }

    static final List<Map<String, Object>> GOOD = List.of(
            rule("CustName", Map.of("type", "column", "columnId", "text_name1")),
            rule("PostalAddr", Map.of("type", "column", "columnId", "text_postal")),
            rule("LoanAmt", Map.of("type", "column", "columnId", "numbers_loan")),
            Map.of("pdfField", "RepayFreq", "option", "Weekly", "source", Map.of("type", "condition", "columnId", "dropdown_freq",
                    "operator", "equals", "value", "Weekly")),
            Map.of("pdfField", "RepayFreq", "option", "Monthly", "source", Map.of("type", "condition", "columnId", "dropdown_freq",
                    "operator", "equals", "value", "Monthly")),
            rule("UseEveryday", Map.of("type", "condition", "columnId", "dropdown_use", "operator", "includes", "value", "Everyday banking")),
            rule("UseSavings", Map.of("type", "condition", "columnId", "dropdown_use", "operator", "includes", "value", "Savings")));

    @Test
    void compilerRefusesUnsafeOrAmbiguousMappings() throws Exception {
        String id = newDraft();
        List<Map<String, Object>> bad = List.of(
                rule("CustSignature", Map.of("type", "constant", "text", "x")),
                rule("DeclTick", Map.of("type", "constant", "text", "Yes")),
                rule("NoSuchField", Map.of("type", "column", "columnId", "text_name1")),
                rule("CustName", Map.of("type", "column", "columnId", "text_name1")),
                rule("CustName", Map.of("type", "column", "columnId", "text_name2")),
                rule("City", Map.of("type", "column", "columnId", "gone_column", "columnTitle", "Old title")),
                rule("PCode", Map.of("type", "column", "columnId", "text_dup_a")),
                rule("UseSavings", Map.of("type", "column", "columnId", "text_name1")),
                rule("DateSigned", Map.of("type", "today", "format", "dd/MM/yyyy")),
                rule("UseEveryday", Map.of("type", "condition", "columnId", "dropdown_use", "operator", "equals", "value", "Renamed label")),
                rule("CustNo", Map.of("type", "derived", "key", "1st Property Address Street")));
        JsonNode d = json("PUT", "/drafts/" + id, Map.of("baseVersion", 1, "mapping", Map.of("fields", bad)));
        assertEquals(200, d.path("_status").asInt());
        Map<String, String> got = new java.util.TreeMap<>();
        d.path("problems").forEach(p -> got.put(p.path("code").asText(), p.path("severity").asText()));
        assertEquals(Map.of("PROTECTED_FIELD", "error", "CONSTANT_TICK", "error", "UNKNOWN_PDF_FIELD", "error",
                "CONFLICTING_RULES", "error", "UNKNOWN_COLUMN", "error", "DUPLICATE_TITLE", "warning", "KIND_MISMATCH", "error",
                "TODAY_NEAR_DECLARATION", "warning", "LABEL_NOT_IN_MONDAY", "warning", "DERIVED_KEY", "warning"), got);
        JsonNode t = json("POST", "/drafts/" + id + "/test", Map.of("data", Map.of(), "today", "2026-10-28"));
        assertFalse(t.path("ok").asBoolean());
        assertEquals("compile", t.path("stage").asText(), "nothing is generated while a mapping has errors");
    }

    @Test
    void draftsAreVersionedConflictSafeAuditedAndNeverPublished() throws Exception {
        String id = newDraft();
        JsonNode v2 = json("PUT", "/drafts/" + id, Map.of("baseVersion", 1, "mapping", Map.of("fields", GOOD), "note", "first mapping"));
        assertEquals(2, v2.path("state").path("version").asInt());
        assertEquals(409, json("PUT", "/drafts/" + id, Map.of("baseVersion", 1, "mapping", Map.of("fields", List.of()))).path("_status").asInt());
        assertEquals(7, json("GET", "/drafts/" + id + "/versions/2", null).path("fields").size());
        assertEquals(0, json("GET", "/drafts/" + id + "/versions/1", null).path("fields").size(), "versions are immutable");
        // reopen from disk with a fresh workspace instance
        Workspace reopened = new Workspace(workspaceDir, CLOCK);
        assertEquals(2, reopened.state(id).orElseThrow().path("version").asInt());
        // approval gates
        assertEquals("NOT_READY", status(id, 2, "approved", approval("Test Person", "2026-10-28", "REF-1")).path("code").asText());
        assertEquals("ready-for-review", status(id, 2, "ready-for-review", null).path("state").path("status").asText());
        assertEquals("NOT_TESTED", status(id, 2, "approved", approval("Test Person", "2026-10-28", "REF-1")).path("code").asText());
        JsonNode test = json("POST", "/drafts/" + id + "/test", Map.of("today", "2026-10-28", "data",
                Map.of("text_name1", "Jane Example", "text_postal", "12 Example Street", "numbers_loan", "250.00",
                        "dropdown_freq", "Weekly", "dropdown_use", "Everyday banking, Savings")));
        assertTrue(test.path("ok").asBoolean(), test.toString());
        assertEquals("INVALID_APPROVAL", status(id, 2, "approved", approval("", "2026-10-28", "REF-1")).path("code").asText());
        assertEquals("INVALID_APPROVAL", status(id, 2, "approved", approval("Test Person", "2099-01-01", "REF-1")).path("code").asText());
        JsonNode ok = status(id, 2, "approved", approval("Test Person (synthetic)", "2026-10-28", "TEST-REF-1"));
        assertEquals("approved", ok.path("state").path("status").asText());
        assertEquals(2, ok.path("state").path("approval").path("version").asInt());
        assertEquals("INVALID_STATUS", status(id, 2, "deployed", null).path("code").asText(), "no publish operation");
        assertEquals("INVALID_STATUS", status(id, 2, "published", null).path("code").asText());
        // a new version always returns to draft and drops the approval
        JsonNode v3 = json("PUT", "/drafts/" + id, Map.of("baseVersion", 2, "mapping", Map.of("fields", GOOD.subList(0, 3))));
        assertEquals("draft", v3.path("state").path("status").asText());
        assertTrue(v3.path("state").path("approval").isMissingNode());
        String audit = v3.path("audit").toString();
        for (String action : List.of("create", "save", "status", "test")) {
            assertTrue(audit.contains("\"action\":\"" + action + "\""), action);
        }
        assertTrue(audit.contains("\"actor\":\"Test Admin\""));
        assertFalse(audit.contains("Jane") || audit.contains("Example Street") || audit.contains("250.00"),
                "the audit trail holds field names and codes, never values");
    }

    static JsonNode status(String id, int v, String to, Map<String, String> approval) throws Exception {
        Map<String, Object> b = new java.util.HashMap<>(Map.of("baseVersion", v, "status", to));
        if (approval != null) {
            b.put("approval", approval);
        }
        return json("POST", "/drafts/" + id + "/status", b);
    }

    static Map<String, String> approval(String by, String on, String ref) {
        return Map.of("approvedBy", by, "approvedOn", on, "reference", ref);
    }

    // ------------------------------------------------------------------------------------------ test generation

    @Test
    void syntheticTestGenerationFillsOnlyMappedFieldsAndNeverSignsOrDeclares() throws Exception {
        String id = newDraft();
        json("PUT", "/drafts/" + id, Map.of("baseVersion", 1, "mapping", Map.of("fields", GOOD,
                "validation", Map.of("closedOptions", List.of(Map.of("columnId", "dropdown_freq", "multiSelect", false))))));
        JsonNode syn = json("POST", "/drafts/" + id + "/synthetic", Map.of());
        assertEquals("Jane Example", syn.path("data").path("text_name1").asText());
        assertEquals("Weekly", syn.path("data").path("dropdown_freq").asText(), "conditions get their own label");
        JsonNode t = json("POST", "/drafts/" + id + "/test", Map.of("data", syn.path("data"), "today", "2026-10-28"));
        assertTrue(t.path("ok").asBoolean(), t.toString());
        assertEquals(List.of("CustName", "PostalAddr", "LoanAmt", "RepayFreq", "UseEveryday", "UseSavings"),
                JSON.convertValue(t.path("filled"), List.class));
        assertTrue(t.path("unmapped").toString().contains("CustSignature"));
        assertEquals(List.of("CustSignature"), JSON.convertValue(t.path("protectedFields"), List.class));
        HttpResponse<byte[]> pdf = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/results/" + t.path("resultId").asText()
                + ".pdf")).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, pdf.statusCode());
        try (PDDocument d = Loader.loadPDF(pdf.body())) {
            PDAcroForm f = d.getDocumentCatalog().getAcroForm();
            assertEquals("Jane Example", f.getField("CustName").getValueAsString());
            assertEquals("Weekly", f.getField("RepayFreq").getValueAsString());
            assertEquals("Off", f.getField("DeclTick").getValueAsString(), "declarations are never ticked");
            assertNull(f.getField("CustSignature").getCOSObject().getDictionaryObject(COSName.V), "signatures never filled");
            assertEquals("", f.getField("DateSigned").getValueAsString(), "unmapped fields stay blank");
        }
        // a drifted Monday label is refused, not silently left blank
        JsonNode drift = json("POST", "/drafts/" + id + "/test", Map.of("today", "2026-10-28",
                "data", Map.of("text_name1", "Jane Example", "dropdown_freq", "Quarterly")));
        assertFalse(drift.path("ok").asBoolean());
        assertTrue(drift.path("issues").toString().contains("UNMAPPED_OPTION"));
        assertFalse(drift.path("issues").toString().contains("Quarterly"), "issues never echo values");
        assertEquals(400, json("POST", "/drafts/" + id + "/test", Map.of("data", Map.of("unknown_col", "x"))).path("_status").asInt());
        // same data, same bytes (deterministic)
        JsonNode t2 = json("POST", "/drafts/" + id + "/test", Map.of("data", syn.path("data"), "today", "2026-10-28"));
        assertEquals(t.path("pdfSha256").asText(), t2.path("pdfSha256").asText());
    }

    // ------------------------------------------------------------------------------------------ suggestions

    @Test
    void suggestionsAreLabelledMockStructureOnlyAndNeverCoverDeclarations() throws Exception {
        String id = newDraft();
        JsonNode s = json("POST", "/drafts/" + id + "/suggestions", Map.of());
        assertEquals("mapping-suggestions.v1", s.path("contractVersion").asText());
        assertTrue(s.path("provider").path("isMock").asBoolean());
        assertTrue(s.path("provider").path("notice").asText().contains("not an AI model"));
        java.util.Set<String> refused = new java.util.HashSet<>(s.path("refused").findValuesAsText("pdfField"));
        assertTrue(refused.containsAll(List.of("CustSignature", "DeclTick", "DateSigned")), refused.toString());
        java.util.Set<String> proposed = new java.util.HashSet<>(s.path("proposals").findValuesAsText("pdfField"));
        assertFalse(proposed.stream().anyMatch(refused::contains));
        JsonNode name = null;
        for (JsonNode p : s.path("proposals")) {
            if (p.path("pdfField").asText().equals("CustName")) {
                name = p;
            }
            assertTrue(p.path("confidence").asDouble() > 0 && p.path("confidence").asDouble() <= 0.9);
            assertTrue(p.path("evidence").size() > 0);
        }
        assertEquals("text_name1", name.path("columnId").asText());
        assertTrue(name.path("ambiguities").toString().contains("Applicant 1 vs Applicant 2"));
        JsonNode use = null;
        for (JsonNode p : s.path("proposals")) {
            if (p.path("pdfField").asText().equals("UseEveryday")) {
                use = p;
            }
        }
        assertEquals("condition", use.path("sourceType").asText());
        assertEquals("Everyday banking", use.path("value").asText());
        // nothing is applied: the draft is unchanged
        assertEquals(1, json("GET", "/drafts/" + id, null).path("state").path("version").asInt());
    }

    // ------------------------------------------------------------------------------------------ enablement

    @Test
    void theAdministrationAreaIsOffByDefaultAndLocalOnly() throws Exception {
        ApiServer plain = new ApiServer(TemplateCatalog.load(Files.createDirectories(dir.resolve("m2")), List.of()),
                new FormFiller(), ApiServer.AuthMode.NONE, null);
        int port = plain.start("127.0.0.1", 0);
        try {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/admin/")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(404, r.statusCode(), "the deployed engine never registers the admin routes");
        } finally {
            plain.stop();
        }
        assertThrows(IllegalStateException.class, () -> AdminApi.requireLocal("127.0.0.1", Map.of("K_SERVICE", "rsfa-end-forms-engine")));
        assertThrows(IllegalStateException.class, () -> AdminApi.requireLocal("0.0.0.0", Map.of()));
        AdminApi.requireLocal("127.0.0.1", Map.of());
        HttpResponse<String> page = HTTP.send(HttpRequest.newBuilder(URI.create(base.replace("/api", "/"))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, page.statusCode());
        assertTrue(page.headers().firstValue("Content-Security-Policy").orElse("").contains("script-src 'self'"));
        assertFalse(page.body().contains("http://") || page.body().contains("https://"), "no CDN or external asset");
    }

    // ------------------------------------------------------------------------------------------ fixtures

    interface Edit {
        void apply(PDDocument d) throws IOException;
    }

    static byte[] edit(byte[] pdf, Edit e) throws IOException {
        try (PDDocument d = Loader.loadPDF(pdf)) {
            e.apply(d);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            d.save(out);
            return out.toByteArray();
        }
    }

    static void setText(PDDocument d, String field, String value) throws IOException {
        ((PDTextField) d.getDocumentCatalog().getAcroForm().getField(field)).setValue(value);
    }

    static void sign(PDDocument d) {
        COSDictionary v = new COSDictionary();
        v.setName(COSName.TYPE, "Sig");
        d.getDocumentCatalog().getAcroForm().getField("CustSignature").getCOSObject().setItem(COSName.V, v);
    }

    static byte[] userPassword(byte[] pdf) throws IOException {
        return protect(pdf, "user-secret");
    }

    static byte[] ownerPassword(byte[] pdf) throws IOException {
        return protect(pdf, "");
    }

    static byte[] protect(byte[] pdf, String user) throws IOException {
        try (PDDocument d = Loader.loadPDF(pdf)) {
            StandardProtectionPolicy p = new StandardProtectionPolicy("owner-secret", user, new AccessPermission());
            p.setEncryptionKeyLength(128);
            d.protect(p);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            d.save(out);
            return out.toByteArray();
        }
    }

    static byte[] textOnly() throws IOException {
        try (PDDocument d = new PDDocument()) {
            PDPage p = new PDPage(PDRectangle.A4);
            d.addPage(p);
            try (PDPageContentStream cs = new PDPageContentStream(d, p)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText("A printed form without any fillable fields, used only for testing.");
                cs.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            d.save(out);
            return out.toByteArray();
        }
    }

    static byte[] imageOnly() throws IOException {
        try (PDDocument d = new PDDocument()) {
            PDPage p = new PDPage(PDRectangle.A4);
            d.addPage(p);
            BufferedImage img = new BufferedImage(60, 80, BufferedImage.TYPE_INT_RGB);
            try (PDPageContentStream cs = new PDPageContentStream(d, p)) {
                cs.drawImage(LosslessFactory.createFromImage(d, img), 0, 0, 595, 842);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            d.save(out);
            return out.toByteArray();
        }
    }

    static Consumer<PDDocument> noop() {
        return d -> { };
    }
}
