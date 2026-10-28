package nz.co.rsfa.endforms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import nz.co.rsfa.endforms.catalog.TemplateCatalog;
import nz.co.rsfa.endforms.fill.FormFiller;
import nz.co.rsfa.endforms.http.ApiServer;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Make integration contract, POST /v1/masterboard/documents (docs/07), on synthetic templates only. */
class MasterboardApiTest {

    static final HttpClient HTTP = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
    static final ObjectMapper JSON = new ObjectMapper();
    static final String SECRET = "test-hmac-secret-0123456789-0123456789";
    static final Instant NOW = Instant.parse("2026-10-27T03:00:00Z");

    @TempDir
    static Path dir;
    static ApiServer open;
    static ApiServer signed;
    static String base;
    static String signedBase;

    @BeforeAll
    static void start() throws Exception {
        byte[] template = SyntheticTemplate.build();
        Path templates = Files.createDirectories(dir.resolve("templates"));
        Path mappings = Files.createDirectories(dir.resolve("mappings"));
        Files.write(templates.resolve("synth.pdf"), template);
        String sha = FormFiller.sha256(template);
        Files.writeString(mappings.resolve("a.json"), """
                {"schemaVersion":1,"templateId":"SYNTH","templateVersion":"1","templateFile":"synth.pdf","templateSha256":"%s",
                 "fields":[{"pdfField":"Full name","kind":"text","source":{"key":"Applicant 1 Full Name"},"required":true},
                           {"pdfField":"Date","kind":"text","source":{"generated":"today","format":"ddMMyy"}}]}""".formatted(sha));
        Files.writeString(mappings.resolve("b.json"), """
                {"schemaVersion":1,"templateId":"SYNTH_B","templateVersion":"1","templateFile":"synth.pdf","templateSha256":"%s",
                 "fields":[{"pdfField":"Postcode","kind":"text","source":{"key":"Postcode"},"required":true}]}""".formatted(sha));
        TemplateCatalog catalog = TemplateCatalog.load(mappings, List.of(templates));
        open = new ApiServer(catalog, new FormFiller(), ApiServer.AuthMode.NONE, null);
        base = "http://127.0.0.1:" + open.start("127.0.0.1", 0);
        signed = new ApiServer(catalog, new FormFiller(), ApiServer.AuthMode.NONE, null, SECRET, Clock.fixed(NOW, ZoneOffset.UTC));
        signedBase = "http://127.0.0.1:" + signed.start("127.0.0.1", 0);
    }

    @AfterAll
    static void stop() {
        open.stop();
        signed.stop();
    }

    static String request(String selection, String data, String options) {
        return """
                {"contractVersion":"masterboard.v1","correlationId":"mb-5026186805-1234567890-exec42",
                 "idempotencyKey":"1234567890-2026-10-27T03:00:00Z","source":{"boardId":"5026186805","itemId":"1234567890"},
                 "today":"2026-10-27","selection":%s,"options":%s,"data":%s}""".formatted(selection, options, data);
    }

    static final String BOTH = "{\"templates\":[{\"templateId\":\"SYNTH\",\"templateVersion\":\"1\"},"
            + "{\"templateId\":\"SYNTH_B\",\"templateVersion\":\"1\"}]}";
    static final String JANE = "{\"Applicant 1 Full Name\":\"Jane Example\",\"Postcode\":\"0999\"}";

    static HttpResponse<String> post(String url, String body, Map<String, String> headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url + "/v1/masterboard/documents"))
                .POST(HttpRequest.BodyPublishers.ofString(body)).header("Content-Type", "application/json");
        headers.forEach(b::header);
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> post(String body) throws Exception {
        return post(base, body, Map.of());
    }

    @Test
    void generatesSeveralFormsFromOneRecordDeterministically() throws Exception {
        HttpResponse<String> r = post(request(BOTH, JANE, "{}"));
        assertEquals(200, r.statusCode(), r.body());
        assertEquals("mb-5026186805-1234567890-exec42", r.headers().firstValue("X-Correlation-Id").orElse(""));
        JsonNode b = JSON.readTree(r.body());
        assertEquals("complete", b.get("status").asText());
        assertEquals(2, b.get("documents").size());
        JsonNode d = b.get("documents").get(0);
        assertEquals("generated", d.get("status").asText());
        assertTrue(d.get("fileName").asText().matches("1234567890_SYNTH_1_[0-9a-f]{8}\\.pdf"), d.get("fileName").asText());
        byte[] pdf = Base64.getDecoder().decode(d.get("pdfBase64").asText());
        assertEquals(d.get("sha256").asText(), FormFiller.sha256(pdf));
        assertEquals(d.get("sizeBytes").asInt(), pdf.length);
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertEquals("Jane Example", doc.getDocumentCatalog().getAcroForm().getField("Full name").getValueAsString());
            assertEquals("271026", doc.getDocumentCatalog().getAcroForm().getField("Date").getValueAsString(), "today pinned by the request");
        }
        // a retry with the same content (keys in another order, new correlationId) gives the same digest and documents
        String reordered = request(BOTH, "{\"Postcode\":\"0999\",\"Applicant 1 Full Name\":\"Jane Example\"}", "{}")
                .replace("exec42", "exec43");
        JsonNode again = JSON.readTree(post(reordered).body());
        assertEquals(b.get("requestDigest"), again.get("requestDigest"));
        assertEquals(d.get("sha256"), again.get("documents").get(0).get("sha256"));
        assertEquals(d.get("fileName"), again.get("documents").get(0).get("fileName"));
        JsonNode dry = JSON.readTree(post(request(BOTH, JANE, "{\"includePdf\":false}")).body());
        assertFalse(dry.get("documents").get(0).has("pdfBase64"), "includePdf=false validates without returning PDFs");
        assertEquals(d.get("sha256"), dry.get("documents").get(0).get("sha256"));
    }

    @Test
    void allOrNothingWithholdsEveryPdfWhenOneFormFails() throws Exception {
        HttpResponse<String> r = post(request(BOTH, "{\"Applicant 1 Full Name\":\"Jane Example\",\"Postcode\":\"Zq-SECRET\\nX\"}", "{}"));
        assertEquals(422, r.statusCode());
        JsonNode b = JSON.readTree(r.body());
        assertEquals("failed", b.get("status").asText());
        assertFalse(b.get("retryable").asBoolean());
        assertEquals("withheld", b.get("documents").get(0).get("status").asText());
        assertFalse(r.body().contains("pdfBase64"));
        assertEquals("VALIDATION_FAILED", b.get("documents").get(1).get("code").asText());
        assertEquals("TEXT_TOO_LONG", b.get("documents").get(1).get("errors").get(0).get("code").asText());
        assertFalse(r.body().contains("Zq-SECRET"), "responses never echo data values");

        JsonNode partial = JSON.readTree(post(request(BOTH, "{\"Applicant 1 Full Name\":\"Jane Example\"}",
                "{\"failurePolicy\":\"per-document\"}")).body());
        assertEquals("partial", partial.get("status").asText());
        assertTrue(partial.get("documents").get(0).has("pdfBase64"));
        assertEquals("MISSING_REQUIRED_VALUE", partial.get("documents").get(1).get("errors").get(0).get("code").asText());
    }

    @Test
    void bankSelectionUsesTheRoutingTableAndReportsWhatMacroForgeMustStillProduce() throws Exception {
        JsonNode b = JSON.readTree(post(request("{\"banks\":[\"BNZ\"]}", JANE, "{}")).body());
        // BNZ Boarder is the only 'tested' BNZ form; this synthetic catalog does not hold it
        assertEquals(1, b.get("documents").size());
        assertEquals("BNZ_BOARDER_FORM", b.get("documents").get(0).get("templateId").asText());
        assertEquals("UNKNOWN_TEMPLATE", b.get("documents").get(0).get("code").asText());
        List<String> legacy = b.get("notMigrated").findValuesAsText("legacyPdfName");
        assertTrue(legacy.containsAll(List.of("BNZ_Handover_8 24.pdf", "BNZ Gifting declaration template.pdf", "All Banks_DOAD.pdf")));
        assertFalse(legacy.contains("BNZ_BoarderForm_2.0_8 24.pdf"));

        JsonNode asb = JSON.readTree(post(request("{\"banks\":[\"ASB\"]}", JANE, "{}")).body());
        assertEquals("NOTHING_TO_GENERATE", asb.get("code").asText(), "candidates need includeCandidates=true");
        JsonNode asbCandidates = JSON.readTree(post(request("{\"banks\":[\"ASB\"]}", JANE, "{\"includeCandidates\":true}")).body());
        assertEquals(List.of("GHL_NP_INDIVIDUAL_FORM", "GHL_NP_TRUST_FORM"),
                asbCandidates.get("documents").findValuesAsText("templateId"), "blocked ASB Boarder is never routed");

        HttpResponse<String> unknown = post(request("{\"banks\":[\"Westpac\"]}", JANE, "{}"));
        assertEquals(422, unknown.statusCode());
        assertEquals("UNKNOWN_BANK", JSON.readTree(unknown.body()).get("code").asText());
    }

    @Test
    void rejectsInvalidContractsWithStructuredNonRetryableErrors() throws Exception {
        for (String bad : List.of(
                request(BOTH, JANE, "{}").replace("masterboard.v1", "masterboard.v0"),
                request(BOTH, JANE, "{}").replace("\"today\":\"2026-10-27\",", ""),
                request(BOTH, JANE, "{}").replace("\"itemId\":\"1234567890\"", "\"itemId\":\"12a\""),
                request(BOTH, JANE, "{}").replace("mb-5026186805-1234567890-exec42", "short"),
                request("{\"banks\":[\"BNZ\"],\"templates\":[]}", JANE, "{}"),
                request(BOTH, JANE, "{\"failurePolicy\":\"maybe\"}"))) {
            HttpResponse<String> r = post(bad);
            assertEquals(400, r.statusCode(), bad);
            assertFalse(JSON.readTree(r.body()).get("retryable").asBoolean());
        }
        StringBuilder many = new StringBuilder("{\"templates\":[");
        for (int i = 0; i < 13; i++) {
            many.append(i > 0 ? "," : "").append("{\"templateId\":\"T").append(i).append("\",\"templateVersion\":\"1\"}");
        }
        assertEquals("TOO_MANY_DOCUMENTS", JSON.readTree(post(request(many + "]}", JANE, "{}")).body()).get("code").asText());
    }

    static String sign(String ts, String body) throws Exception {
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "v1=" + HexFormat.of().formatHex(mac.doFinal((ts + "." + body).getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void optionalRequestSignatureIsVerifiedWhenConfigured() throws Exception {
        String body = request(BOTH, JANE, "{}");
        String ts = Long.toString(NOW.getEpochSecond());
        assertEquals(401, post(signedBase, body, Map.of()).statusCode());
        assertEquals(200, post(signedBase, body, Map.of("X-RSFA-Timestamp", ts, "X-RSFA-Signature", sign(ts, body))).statusCode());
        String old = Long.toString(NOW.getEpochSecond() - 301);
        assertEquals(401, post(signedBase, body, Map.of("X-RSFA-Timestamp", old, "X-RSFA-Signature", sign(old, body))).statusCode(),
                "replayed/stale timestamp");
        HttpResponse<String> tampered = post(signedBase, body.replace("Jane", "Jake"),
                Map.of("X-RSFA-Timestamp", ts, "X-RSFA-Signature", sign(ts, body)));
        assertEquals(401, tampered.statusCode());
        assertEquals("INVALID_SIGNATURE", JSON.readTree(tampered.body()).get("code").asText());
        assertEquals(200, HTTP.send(HttpRequest.newBuilder(URI.create(signedBase + "/health")).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode(), "the signature applies to /v1 only");
    }
}
