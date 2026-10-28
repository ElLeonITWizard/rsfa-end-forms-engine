package nz.co.rsfa.endforms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import nz.co.rsfa.endforms.catalog.TemplateCatalog;
import nz.co.rsfa.endforms.fill.FormFiller;
import nz.co.rsfa.endforms.http.ApiServer;
import nz.co.rsfa.endforms.relay.RelayServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.InetSocketAddress;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Local proof of concept of the recommended Make authentication architecture (D-027): Make-style signed request →
 * relay (HMAC, replay, rate limit) → IAM stand-in that requires the relay's ID token (as Cloud Run IAM would) → engine.
 */
class RelayServerTest {

    static final HttpClient HTTP = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
    static final ObjectMapper JSON = new ObjectMapper();
    static final String CURRENT = "relay-test-secret-current-0123456789abcdef";
    static final String PREVIOUS = "relay-test-secret-previous-0123456789abcdef";
    static final Instant NOW = Instant.parse("2026-10-28T02:00:00Z");
    static final String TOKEN = "test-id-token-for-relay-sa";

    @TempDir
    static Path dir;
    static ApiServer engine;
    static HttpServer iam;
    static RelayServer relay;
    static RelayServer limited;
    static String relayBase;
    static String limitedBase;
    static final AtomicInteger iamRejected = new AtomicInteger();

    @BeforeAll
    static void start() throws Exception {
        byte[] template = SyntheticTemplate.build();
        Path templates = Files.createDirectories(dir.resolve("templates"));
        Path mappings = Files.createDirectories(dir.resolve("mappings"));
        Files.write(templates.resolve("synth.pdf"), template);
        Files.writeString(mappings.resolve("a.json"), """
                {"schemaVersion":1,"templateId":"SYNTH","templateVersion":"1","templateFile":"synth.pdf","templateSha256":"%s",
                 "fields":[{"pdfField":"Full name","kind":"text","source":{"key":"Applicant 1 Full Name"},"required":true}]}"""
                .formatted(FormFiller.sha256(template)));
        engine = new ApiServer(TemplateCatalog.load(mappings, List.of(templates)), new FormFiller(), ApiServer.AuthMode.NONE, null);
        int enginePort = engine.start("127.0.0.1", 0);
        // stand-in for Cloud Run IAM: only the relay's identity token gets through
        iam = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        iam.createContext("/", ex -> {
            try (ex) {
                if (!("Bearer " + TOKEN).equals(ex.getRequestHeaders().getFirst("Authorization"))) {
                    iamRejected.incrementAndGet();
                    ex.sendResponseHeaders(403, -1);
                    return;
                }
                HttpResponse<byte[]> r = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + enginePort
                                + ex.getRequestURI().getPath())).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(ex.getRequestBody().readAllBytes())).build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                r.headers().firstValue("X-Correlation-Id").ifPresent(v -> ex.getResponseHeaders().set("X-Correlation-Id", v));
                ex.getResponseHeaders().set("Content-Type", "application/json");
                ex.sendResponseHeaders(r.statusCode(), r.body().length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(r.body());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        iam.start();
        URI target = URI.create("http://127.0.0.1:" + iam.getAddress().getPort());
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        relay = new RelayServer(target, List.of(CURRENT, PREVIOUS), () -> TOKEN, clock, 1000);
        relayBase = "http://127.0.0.1:" + relay.start("127.0.0.1", 0);
        limited = new RelayServer(target, List.of(CURRENT), () -> TOKEN, clock, 2);
        limitedBase = "http://127.0.0.1:" + limited.start("127.0.0.1", 0);
    }

    @AfterAll
    static void stop() {
        relay.stop();
        limited.stop();
        iam.stop(0);
        engine.stop();
    }

    static int seq = 0;

    static String body() {
        return """
                {"contractVersion":"masterboard.v1","correlationId":"mb-5026186805-1000000009-relay%04d",
                 "idempotencyKey":"1000000009-20261028T020000Z","source":{"boardId":"5026186805","itemId":"1000000009"},
                 "today":"2026-10-28","selection":{"templates":[{"templateId":"SYNTH","templateVersion":"1"}]},
                 "data":{"Applicant 1 Full Name":"Jane Example"}}""".formatted(++seq);
    }

    /** Exactly what the Make scenario computes: sha256(timestamp + "." + body; "hex"; secret). */
    static String sign(String secret, String ts, String body) {
        return "v1=" + RelayServer.hmacHex(secret.getBytes(StandardCharsets.UTF_8), ts, body.getBytes(StandardCharsets.UTF_8));
    }

    static HttpResponse<String> post(String base, String body, Map<String, String> headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + "/v1/masterboard/documents"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(b::header);
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static Map<String, String> signed(String secret, long ts, String body) {
        return Map.of("X-RSFA-Timestamp", Long.toString(ts), "X-RSFA-Signature", sign(secret, Long.toString(ts), body));
    }

    @Test
    void signedRequestReachesThePrivateEngineWithTheRelayIdentity() throws Exception {
        String body = body();
        HttpResponse<String> r = post(relayBase, body, signed(CURRENT, NOW.getEpochSecond(), body));
        assertEquals(200, r.statusCode(), r.body());
        JsonNode b = JSON.readTree(r.body());
        assertEquals("complete", b.get("status").asText());
        assertTrue(b.get("documents").get(0).has("pdfBase64"));
        assertEquals("rsfa-end-forms-relay", r.headers().firstValue("X-Relay").orElse(""));
        assertTrue(r.headers().firstValue("X-Correlation-Id").orElse("").startsWith("mb-5026186805-1000000009-relay"));
        // rotation: the previous secret is still accepted until it is removed
        String b2 = body();
        assertEquals(200, post(relayBase, b2, signed(PREVIOUS, NOW.getEpochSecond() - 60, b2)).statusCode());
    }

    @Test
    void unsignedTamperedStaleAndReplayedRequestsNeverReachTheEngine() throws Exception {
        int before = iamRejected.get();
        String body = body();
        long ts = NOW.getEpochSecond();
        assertEquals("MISSING_SIGNATURE", JSON.readTree(post(relayBase, body, Map.of()).body()).get("code").asText());
        HttpResponse<String> tampered = post(relayBase, body.replace("Jane", "Jake"), signed(CURRENT, ts, body));
        assertEquals(401, tampered.statusCode());
        assertEquals("INVALID_SIGNATURE", JSON.readTree(tampered.body()).get("code").asText());
        assertEquals("INVALID_SIGNATURE", JSON.readTree(post(relayBase, body,
                signed("someone-else-secret-0123456789-0123456789", ts, body)).body()).get("code").asText());
        HttpResponse<String> stale = post(relayBase, body, signed(CURRENT, ts - 301, body));
        assertEquals(401, stale.statusCode());
        assertEquals("STALE_TIMESTAMP", JSON.readTree(stale.body()).get("code").asText());
        assertEquals(200, post(relayBase, body, signed(CURRENT, ts, body)).statusCode());
        HttpResponse<String> replay = post(relayBase, body, signed(CURRENT, ts, body));
        assertEquals(409, replay.statusCode());
        assertEquals("REPLAYED_REQUEST", JSON.readTree(replay.body()).get("code").asText());
        assertFalse(JSON.readTree(replay.body()).get("retryable").asBoolean());
        assertFalse(replay.body().contains("Jane"), "relay errors never echo the payload");
        assertEquals(before, iamRejected.get(), "nothing unauthenticated was forwarded");
    }

    @Test
    void rateLimitWrongIdentityAndOversizedBodiesAreHandled() throws Exception {
        long ts = NOW.getEpochSecond();
        int codes429 = 0;
        for (int i = 0; i < 4; i++) {
            String body = body();
            HttpResponse<String> r = post(limitedBase, body, signed(CURRENT, ts, body));
            if (r.statusCode() == 429) {
                codes429++;
                assertTrue(JSON.readTree(r.body()).get("retryable").asBoolean());
                assertEquals("60", r.headers().firstValue("Retry-After").orElse(""));
            }
        }
        assertEquals(2, codes429, "2 per minute allowed");
        // a relay whose identity is not an invoker: the engine (IAM) rejects it; Make sees a non-retryable relay fault
        RelayServer wrong = new RelayServer(URI.create("http://127.0.0.1:" + iam.getAddress().getPort()), List.of(CURRENT),
                () -> "not-an-invoker", Clock.fixed(NOW, ZoneOffset.UTC), 10);
        String base = "http://127.0.0.1:" + wrong.start("127.0.0.1", 0);
        try {
            String body = body();
            HttpResponse<String> r = post(base, body, signed(CURRENT, ts, body));
            assertEquals(502, r.statusCode());
            assertEquals("UPSTREAM_AUTH_FAILED", JSON.readTree(r.body()).get("code").asText());
        } finally {
            wrong.stop();
        }
        String big = "{\"pad\":\"" + "x".repeat(1024 * 1024) + "\"}";
        assertEquals(413, post(relayBase, big, signed(CURRENT, ts, big)).statusCode());
        assertEquals(200, HTTP.send(HttpRequest.newBuilder(URI.create(relayBase + "/health")).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(404, HTTP.send(HttpRequest.newBuilder(URI.create(relayBase + "/generate"))
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString()).statusCode(),
                "the relay exposes only the Make contract");
    }

    @Test
    void unsafeConfigurationsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new RelayServer(URI.create("http://engine.example.org"),
                List.of(CURRENT), () -> TOKEN, Clock.systemUTC(), 10), "plain http to a remote engine");
        assertThrows(IllegalArgumentException.class, () -> new RelayServer(URI.create("https://engine.example.org"),
                List.of("short"), () -> TOKEN, Clock.systemUTC(), 10));
        assertThrows(IllegalArgumentException.class, () -> new RelayServer(URI.create("https://engine.example.org"),
                List.of(CURRENT, PREVIOUS, CURRENT + "x"), () -> TOKEN, Clock.systemUTC(), 10), "at most two secrets");
    }
}
