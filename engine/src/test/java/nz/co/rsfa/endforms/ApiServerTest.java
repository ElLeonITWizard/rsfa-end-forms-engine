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

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiServerTest {

    static final String KEY = "test-key-0123456789-0123456789-abcdef";
    static final HttpClient HTTP = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
    static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    static Path dir;
    static ApiServer server;
    static String base;

    @BeforeAll
    static void start() throws Exception {
        byte[] template = SyntheticTemplate.build();
        Path templates = Files.createDirectories(dir.resolve("templates"));
        Path mappings = Files.createDirectories(dir.resolve("mappings"));
        Files.write(templates.resolve("synth.pdf"), template);
        Files.writeString(mappings.resolve("synth.json"), """
                {"schemaVersion":1,"templateId":"SYNTH","templateVersion":"1","templateFile":"synth.pdf",
                 "templateSha256":"%s",
                 "fields":[{"pdfField":"Full name","kind":"text","source":{"key":"Applicant 1 Full Name"},"required":true},
                           {"pdfField":"Date","kind":"text","source":{"generated":"today","format":"ddMMyy"}}]}"""
                .formatted(FormFiller.sha256(template)));
        Files.writeString(mappings.resolve("ghost.json"), """
                {"schemaVersion":1,"templateId":"GHOST","templateVersion":"1","templateFile":"missing.pdf",
                 "templateSha256":"%s","fields":[]}""".formatted(FormFiller.sha256(template)));
        Files.write(templates.resolve("tampered.pdf"), template);
        Files.writeString(mappings.resolve("tampered.json"), """
                {"schemaVersion":1,"templateId":"TAMPERED","templateVersion":"1","templateFile":"tampered.pdf",
                 "templateSha256":"%s","fields":[]}""".formatted("0".repeat(64)));
        TemplateCatalog catalog = TemplateCatalog.load(mappings, List.of(templates));
        server = new ApiServer(catalog, new FormFiller(z -> LocalDate.of(2026, 1, 2)), ApiServer.AuthMode.API_KEY, KEY);
        base = "http://127.0.0.1:" + server.start("127.0.0.1", 0);
    }

    @AfterAll
    static void stop() {
        server.stop();
    }

    static HttpResponse<byte[]> post(String body, String key) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + "/generate"))
                .POST(HttpRequest.BodyPublishers.ofString(body)).header("Content-Type", "application/json");
        if (key != null) {
            b.header("Authorization", "Bearer " + key);
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    void healthReportsNoData() throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/health")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode());
        // Only usable templates count: SYNTH has its PDF; GHOST (manifest without its template file) and
        // TAMPERED (template present, wrong SHA-256) do not.
        assertEquals("{\"status\":\"ok\",\"templates\":1,\"available\":[\"SYNTH@1\"]}", r.body());
    }

    @Test
    void generateReturnsFilledPdf() throws Exception {
        HttpResponse<byte[]> r = post("""
                {"requestId":"req-1","templateId":"SYNTH","templateVersion":"1",
                 "data":{"Applicant 1 Full Name":"Jane Example"}}""", KEY);
        assertEquals(200, r.statusCode());
        assertEquals("application/pdf", r.headers().firstValue("Content-Type").orElse(""));
        assertEquals("2", r.headers().firstValue("X-Fields-Filled").orElse(""));
        assertEquals("SYNTH@1", r.headers().firstValue("X-Template").orElse(""));
        assertTrue(r.headers().firstValue("X-Engine").orElse("").contains("deterministic-mapping"));
        assertTrue(r.headers().firstValue("X-Engine").orElse("").contains("no-ai"));
        try (PDDocument d = Loader.loadPDF(r.body())) {
            assertEquals("Jane Example", d.getDocumentCatalog().getAcroForm().getField("Full name").getValueAsString());
            assertEquals("020126", d.getDocumentCatalog().getAcroForm().getField("Date").getValueAsString());
        }
    }

    @Test
    void pinnedTodayOverridesClock() throws Exception {
        HttpResponse<byte[]> r = post("""
                {"requestId":"req-2","templateId":"SYNTH","templateVersion":"1","today":"2026-10-27",
                 "data":{"Applicant 1 Full Name":"Jane Example"}}""", KEY);
        try (PDDocument d = Loader.loadPDF(r.body())) {
            assertEquals("271026", d.getDocumentCatalog().getAcroForm().getField("Date").getValueAsString());
        }
    }

    @Test
    void validationFailureIsStructuredAndDoesNotEchoData() throws Exception {
        HttpResponse<byte[]> r = post("""
                {"requestId":"req-3","templateId":"SYNTH","templateVersion":"1","data":{"Other":"Zq-SECRET"}}""", KEY);
        assertEquals(422, r.statusCode());
        JsonNode body = JSON.readTree(r.body());
        assertEquals("VALIDATION_FAILED", body.get("code").asText());
        assertEquals("MISSING_REQUIRED_VALUE", body.get("issues").get(0).get("code").asText());
        assertFalse(new String(r.body()).contains("Zq-SECRET"));
    }

    @Test
    void rejectsBadAuthUnknownTemplateBadJsonAndOversizedBodies() throws Exception {
        String ok = "{\"templateId\":\"SYNTH\",\"templateVersion\":\"1\",\"data\":{}}";
        assertEquals(401, post(ok, null).statusCode());
        assertEquals(401, post(ok, KEY + "x").statusCode());
        assertEquals(404, post("{\"templateId\":\"NOPE\",\"templateVersion\":\"1\",\"data\":{}}", KEY).statusCode());
        assertEquals(400, post("{not json", KEY).statusCode());
        assertEquals(400, post("{\"templateId\":\"../etc\",\"templateVersion\":\"1\",\"data\":{}}", KEY).statusCode());
        assertEquals(400, post("{\"requestId\":\"bad id/..\",\"templateId\":\"SYNTH\",\"templateVersion\":\"1\",\"data\":{}}", KEY).statusCode());
        assertEquals(413, post("{\"pad\":\"" + "x".repeat(1024 * 1024) + "\"}", KEY).statusCode());
    }

    static HttpResponse<String> get(String path, String key) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path));
        if (key != null) {
            b.header("Authorization", "Bearer " + key);
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void servesDemoUiWithSecurityHeaders() throws Exception {
        HttpResponse<String> page = get("/", KEY);
        assertEquals(200, page.statusCode());
        assertTrue(page.headers().firstValue("Content-Type").orElse("").startsWith("text/html"));
        assertTrue(page.body().contains("RSFA End Forms Engine"));
        String csp = page.headers().firstValue("Content-Security-Policy").orElse("");
        assertTrue(csp.contains("default-src 'self'") && csp.contains("script-src 'self'") && csp.contains("frame-ancestors 'none'"));
        assertEquals("nosniff", page.headers().firstValue("X-Content-Type-Options").orElse(""));
        // Only same-origin assets: no CDN or other third-party URLs.
        assertFalse(page.body().contains("http://") || page.body().contains("https://"));
        for (String asset : List.of("/ui/app.js", "/ui/app.css", "/ui/forms.json")) {
            HttpResponse<String> r = get(asset, KEY);
            assertEquals(200, r.statusCode(), asset);
            assertFalse(r.body().contains("http://") || r.body().contains("https://"), asset);
        }
        assertEquals("text/javascript; charset=utf-8", get("/ui/app.js", KEY).headers().firstValue("Content-Type").orElse(""));
    }

    @Test
    void demoUiServesOnlyAllowlistedFilesAndKeepsAuth() throws Exception {
        assertEquals(404, get("/ui/", KEY).statusCode());
        assertEquals(404, get("/ui/../ApiServer.class", KEY).statusCode());
        assertEquals(404, get("/nz/co/rsfa/endforms/Main.class", KEY).statusCode());
        assertEquals(404, get("/favicon.ico", KEY).statusCode());
        // In API_KEY mode the UI is protected like the API (on Cloud Run, IAM protects everything).
        assertEquals(401, get("/", null).statusCode());
        assertEquals(401, get("/ui/app.js", null).statusCode());
        HttpResponse<String> post = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/"))
                .POST(HttpRequest.BodyPublishers.ofString("x")).header("Authorization", "Bearer " + KEY).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(405, post.statusCode());
    }

    @Test
    void platformIamIsOnlyAllowedOnCloudRun() {
        assertThrows(IllegalStateException.class,
                () -> ApiServer.requirePlatformForIam(ApiServer.AuthMode.PLATFORM_IAM, java.util.Map.of()));
        ApiServer.requirePlatformForIam(ApiServer.AuthMode.PLATFORM_IAM, java.util.Map.of("K_SERVICE", "rsfa-end-forms-engine"));
        ApiServer.requirePlatformForIam(ApiServer.AuthMode.API_KEY, java.util.Map.of());
    }

    @Test
    void unauthenticatedModeIsLoopbackOnly() throws IOException {
        TemplateCatalog empty = TemplateCatalog.load(Files.createDirectories(dir.resolve("empty")), List.of());
        ApiServer open = new ApiServer(empty, new FormFiller(), ApiServer.AuthMode.NONE, null);
        assertThrows(IllegalStateException.class, () -> open.start("0.0.0.0", 0));
        assertThrows(IllegalArgumentException.class,
                () -> new ApiServer(empty, new FormFiller(), ApiServer.AuthMode.API_KEY, "short"));
        assertTrue(true);
    }
}
