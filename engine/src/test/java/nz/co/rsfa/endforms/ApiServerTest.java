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
        assertEquals("{\"status\":\"ok\",\"templates\":1}", r.body());
    }

    @Test
    void generateReturnsFilledPdf() throws Exception {
        HttpResponse<byte[]> r = post("""
                {"requestId":"req-1","templateId":"SYNTH","templateVersion":"1",
                 "data":{"Applicant 1 Full Name":"Jane Example"}}""", KEY);
        assertEquals(200, r.statusCode());
        assertEquals("application/pdf", r.headers().firstValue("Content-Type").orElse(""));
        assertEquals("2", r.headers().firstValue("X-Fields-Filled").orElse(""));
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
