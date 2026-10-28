package nz.co.rsfa.endforms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import nz.co.rsfa.endforms.catalog.TemplateCatalog;
import nz.co.rsfa.endforms.fill.FormFiller;
import nz.co.rsfa.endforms.http.ApiServer;
import nz.co.rsfa.endforms.relay.GcsReplayStore;
import nz.co.rsfa.endforms.relay.RelayServer;
import nz.co.rsfa.endforms.relay.ReplayStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Iteration 8 hardening of the relay (D-031): replay protection shared across instances (Cloud Storage
 * create-if-absent, emulated here with the same precondition semantics), fail-closed store, malformed JSON, strict
 * signature format, timeouts, unavailable engine or identity, pass-through of engine refusals and partial results,
 * and logs without data, secrets or tokens.
 */
class RelayHardeningTest {

    static final HttpClient HTTP = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
    static final ObjectMapper JSON = new ObjectMapper();
    static final String SECRET = "relay-hardening-secret-0123456789abcdef";
    static final Instant NOW = Instant.parse("2026-10-28T02:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    static final String TOKEN = "test-id-token-for-relay-sa";
    static final String GCS_TOKEN = "test-access-token-for-replay-bucket";

    @TempDir
    static Path dir;
    static ApiServer engine;
    static HttpServer iam;
    static HttpServer gcs;
    static URI target;
    static URI gcsBase;
    static final AtomicInteger forwarded = new AtomicInteger();
    static final Map<String, String> objects = new ConcurrentHashMap<>();
    static final AtomicBoolean gcsDown = new AtomicBoolean();
    static final List<String> logs = new CopyOnWriteArrayList<>();
    static final List<RelayServer> started = new ArrayList<>();
    static int seq = 0;

    @BeforeAll
    static void start() throws Exception {
        byte[] template = SyntheticTemplate.build();
        Path templates = Files.createDirectories(dir.resolve("templates"));
        Path mappings = Files.createDirectories(dir.resolve("mappings"));
        Files.write(templates.resolve("synth.pdf"), template);
        String sha = FormFiller.sha256(template);
        Files.writeString(mappings.resolve("a.json"), """
                {"schemaVersion":1,"templateId":"SYNTH","templateVersion":"1","templateFile":"synth.pdf","templateSha256":"%s",
                 "fields":[{"pdfField":"Full name","kind":"text","source":{"key":"Applicant 1 Full Name"},"required":true}]}"""
                .formatted(sha));
        Files.writeString(mappings.resolve("b.json"), """
                {"schemaVersion":1,"templateId":"SYNTH_B","templateVersion":"1","templateFile":"synth.pdf","templateSha256":"%s",
                 "fields":[{"pdfField":"Full name","kind":"text","source":{"key":"Applicant 2 Full Name"},"required":true}]}"""
                .formatted(sha));
        engine = new ApiServer(TemplateCatalog.load(mappings, List.of(templates)), new FormFiller(), ApiServer.AuthMode.NONE, null);
        int enginePort = engine.start("127.0.0.1", 0);
        iam = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        iam.createContext("/", ex -> {
            try (ex) {
                if (!("Bearer " + TOKEN).equals(ex.getRequestHeaders().getFirst("Authorization"))) {
                    ex.sendResponseHeaders(403, -1);
                    return;
                }
                forwarded.incrementAndGet();
                HttpResponse<byte[]> r = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + enginePort
                                + ex.getRequestURI().getPath())).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(ex.getRequestBody().readAllBytes())).build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                ex.getResponseHeaders().set("Content-Type", "application/json");
                ex.sendResponseHeaders(r.statusCode(), r.body().length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(r.body());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        iam.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        iam.start();
        target = URI.create("http://127.0.0.1:" + iam.getAddress().getPort());
        // Cloud Storage JSON API stand-in: media upload with ifGenerationMatch=0 is an atomic create-if-absent
        gcs = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        gcs.createContext("/upload/storage/v1/b/", ex -> {
            try (ex) {
                String q = ex.getRequestURI().getRawQuery();
                boolean authorised = ("Bearer " + GCS_TOKEN).equals(ex.getRequestHeaders().getFirst("Authorization"));
                if (gcsDown.get()) {
                    ex.sendResponseHeaders(503, -1);
                } else if (!authorised) {
                    ex.sendResponseHeaders(401, -1);
                } else if (!q.contains("ifGenerationMatch=0") || !q.contains("uploadType=media")) {
                    ex.sendResponseHeaders(400, -1);
                } else {
                    String name = q.replaceAll(".*name=([^&]+).*", "$1");
                    String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.US_ASCII);
                    ex.sendResponseHeaders(objects.putIfAbsent(name, body) == null ? 200 : 412, -1);
                }
            }
        });
        gcs.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        gcs.start();
        gcsBase = URI.create("http://127.0.0.1:" + gcs.getAddress().getPort());
        Logger.getLogger(RelayServer.class.getName()).addHandler(new Handler() {
            @Override
            public void publish(LogRecord r) {
                logs.add(r.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
    }

    @AfterAll
    static void stop() {
        started.forEach(RelayServer::stop);
        iam.stop(0);
        gcs.stop(0);
        engine.stop();
    }

    static String base(RelayServer r) throws IOException {
        started.add(r);
        return "http://127.0.0.1:" + r.start("127.0.0.1", 0);
    }

    static RelayServer gcsRelay() {
        return new RelayServer(target, List.of(SECRET), () -> TOKEN, CLOCK, 1000,
                new GcsReplayStore(gcsBase, "rsfa-relay-replay-test", () -> GCS_TOKEN));
    }

    static String body(String selection, String data, String options) {
        return """
                {"contractVersion":"masterboard.v1","correlationId":"mb-5026186805-1000000009-hard%04d",
                 "idempotencyKey":"1000000009-20261028T020000Z-%d","source":{"boardId":"5026186805","itemId":"1000000009"},
                 "today":"2026-10-28","selection":%s,"options":%s,"data":%s}"""
                .formatted(++seq, seq, selection, options, data);
    }

    static String synthBody() {
        return body("{\"templates\":[{\"templateId\":\"SYNTH\",\"templateVersion\":\"1\"}]}",
                "{\"Applicant 1 Full Name\":\"Jane Example\"}", "{}");
    }

    static Map<String, String> signed(long ts, String body) {
        return Map.of("X-RSFA-Timestamp", Long.toString(ts), "X-RSFA-Signature",
                "v1=" + RelayServer.hmacHex(SECRET.getBytes(StandardCharsets.UTF_8), Long.toString(ts),
                        body.getBytes(StandardCharsets.UTF_8)));
    }

    static HttpResponse<String> post(String base, String body, Map<String, String> headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + "/v1/masterboard/documents"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(b::header);
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static String code(HttpResponse<String> r) throws IOException {
        return JSON.readTree(r.body()).path("code").asText();
    }

    @Test
    void replayIsDetectedAcrossInstancesWithTheSharedStoreButNotWithMemory() throws Exception {
        String a = base(gcsRelay());
        String b = base(gcsRelay());
        String body = synthBody();
        Map<String, String> h = signed(NOW.getEpochSecond(), body);
        assertEquals(200, post(a, body, h).statusCode());
        HttpResponse<String> replay = post(b, body, h);
        assertEquals(409, replay.statusCode(), "the second instance sees the first instance's claim");
        assertEquals("REPLAYED_REQUEST", code(replay));
        assertTrue(objects.keySet().stream().allMatch(k -> k.matches("replay%2F[0-9a-f]{64}")),
                "objects are named by a digest only");
        assertTrue(objects.values().stream().allMatch(v -> v.matches("[0-9]+")), "objects hold only an expiry second");
        // the documented limitation of the per-instance store
        String m1 = base(new RelayServer(target, List.of(SECRET), () -> TOKEN, CLOCK, 1000, new ReplayStore.Memory(100)));
        String m2 = base(new RelayServer(target, List.of(SECRET), () -> TOKEN, CLOCK, 1000, new ReplayStore.Memory(100)));
        String body2 = synthBody();
        Map<String, String> h2 = signed(NOW.getEpochSecond(), body2);
        assertEquals(200, post(m1, body2, h2).statusCode());
        assertEquals(200, post(m2, body2, h2).statusCode(), "memory store: a replay on another instance is NOT detected");
        assertEquals(409, post(m1, body2, h2).statusCode(), "memory store: same instance detects it");
    }

    @Test
    void concurrentCopiesOfOneSignedRequestAreAcceptedExactlyOnce() throws Exception {
        String a = base(gcsRelay());
        String b = base(gcsRelay());
        String body = synthBody();
        Map<String, String> h = signed(NOW.getEpochSecond(), body);
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            String target = i % 2 == 0 ? a : b;
            calls.add(() -> post(target, body, h).statusCode());
        }
        int ok = 0;
        int replayed = 0;
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Future<Integer> f : pool.invokeAll(calls)) {
                int s = f.get();
                ok += s == 200 ? 1 : 0;
                replayed += s == 409 ? 1 : 0;
            }
        }
        assertEquals(1, ok);
        assertEquals(11, replayed);
    }

    @Test
    void theReplayStoreFailsClosed() throws Exception {
        String a = base(gcsRelay());
        int before = forwarded.get();
        gcsDown.set(true);
        try {
            String body = synthBody();
            HttpResponse<String> r = post(a, body, signed(NOW.getEpochSecond(), body));
            assertEquals(503, r.statusCode());
            assertEquals("REPLAY_STORE_UNAVAILABLE", code(r));
            assertTrue(JSON.readTree(r.body()).get("retryable").asBoolean());
        } finally {
            gcsDown.set(false);
        }
        String wrongToken = base(new RelayServer(target, List.of(SECRET), () -> TOKEN, CLOCK, 1000,
                new GcsReplayStore(gcsBase, "rsfa-relay-replay-test", () -> "not-a-storage-token")));
        String body = synthBody();
        assertEquals(503, post(wrongToken, body, signed(NOW.getEpochSecond(), body)).statusCode());
        ReplayStore.Memory tiny = new ReplayStore.Memory(1);
        String full = base(new RelayServer(target, List.of(SECRET), () -> TOKEN, CLOCK, 1000, tiny));
        String b1 = synthBody();
        assertEquals(200, post(full, b1, signed(NOW.getEpochSecond(), b1)).statusCode());
        String b2 = synthBody();
        HttpResponse<String> r = post(full, b2, signed(NOW.getEpochSecond(), b2));
        assertEquals(503, r.statusCode(), "a full memory store refuses instead of evicting fresh entries");
        assertEquals(before + 1, forwarded.get(), "nothing was forwarded while the store could not answer");
        assertThrows(IllegalArgumentException.class, () -> new GcsReplayStore(URI.create("http://storage.example.org"),
                "bucket-name", () -> GCS_TOKEN), "plain http to a remote store");
        assertThrows(IllegalArgumentException.class, () -> new GcsReplayStore(gcsBase, "Bad/Bucket", () -> GCS_TOKEN));
    }

    @Test
    void malformedBodiesAndSignaturesAreRefusedBeforeTheEngine() throws Exception {
        String a = base(gcsRelay());
        int before = forwarded.get();
        long ts = NOW.getEpochSecond();
        for (String bad : List.of("{\"contractVersion\":", "[1,2,3]", "\"text\"", "")) {
            HttpResponse<String> r = post(a, bad, signed(ts, bad));
            assertEquals(400, r.statusCode(), bad);
            assertEquals("MALFORMED_JSON", code(r));
            assertFalse(JSON.readTree(r.body()).get("retryable").asBoolean());
        }
        String body = synthBody();
        String good = signed(ts, body).get("X-RSFA-Signature");
        for (String sig : List.of(good.substring(0, 40), good + "00", "v2=" + good.substring(3), good.substring(3),
                "v1=" + "z".repeat(64))) {
            HttpResponse<String> r = post(a, body, Map.of("X-RSFA-Timestamp", Long.toString(ts), "X-RSFA-Signature", sig));
            assertEquals(401, r.statusCode(), sig);
            assertEquals("MISSING_SIGNATURE", code(r));
        }
        for (String t : List.of("-5", "1.5", "0x10", ts + "s", "9999999999999")) {
            assertEquals(401, post(a, body, Map.of("X-RSFA-Timestamp", t, "X-RSFA-Signature", good)).statusCode(), t);
        }
        HttpResponse<String> future = post(a, body, signed(ts + 301, body));
        assertEquals("STALE_TIMESTAMP", code(future), "future timestamps beyond the window are refused too");
        assertEquals(200, post(a, body, signed(ts + 300, body)).statusCode(), "the window edge is inclusive");
        String b2 = synthBody();
        String sig2 = signed(ts, b2).get("X-RSFA-Signature");
        assertEquals(200, post(a, b2, Map.of("X-RSFA-Timestamp", Long.toString(ts),
                "X-RSFA-Signature", "v1=" + sig2.substring(3).toUpperCase())).statusCode(), "hex case does not matter");
        assertEquals(409, post(a, b2, Map.of("X-RSFA-Timestamp", Long.toString(ts), "X-RSFA-Signature", sig2)).statusCode(),
                "changing the hex case is not a new request");
        assertEquals(before + 2, forwarded.get());
    }

    @Test
    void engineAndIdentityFailuresAreRetryableAndTimeoutsAreBounded() throws Exception {
        int closedPort;
        try (ServerSocket s = new ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }
        String down = base(new RelayServer(URI.create("http://127.0.0.1:" + closedPort), List.of(SECRET), () -> TOKEN, CLOCK,
                1000, new ReplayStore.Memory(100)));
        String body = synthBody();
        HttpResponse<String> r = post(down, body, signed(NOW.getEpochSecond(), body));
        assertEquals(502, r.statusCode());
        assertEquals("UPSTREAM_UNAVAILABLE", code(r));
        assertTrue(JSON.readTree(r.body()).get("retryable").asBoolean());

        HttpServer slow = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        slow.createContext("/", ex -> {
            try (ex) {
                Thread.sleep(3000);
                ex.sendResponseHeaders(200, -1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        slow.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        slow.start();
        try {
            String s = base(new RelayServer(URI.create("http://127.0.0.1:" + slow.getAddress().getPort()), List.of(SECRET),
                    () -> TOKEN, CLOCK, 1000, new ReplayStore.Memory(100)).withUpstreamTimeout(Duration.ofMillis(300)));
            String b2 = synthBody();
            long t0 = System.nanoTime();
            HttpResponse<String> t = post(s, b2, signed(NOW.getEpochSecond(), b2));
            assertEquals(504, t.statusCode());
            assertEquals("UPSTREAM_TIMEOUT", code(t));
            assertTrue(JSON.readTree(t.body()).get("retryable").asBoolean());
            assertTrue((System.nanoTime() - t0) / 1_000_000 < 2500, "the relay does not wait for the slow engine");
        } finally {
            slow.stop(0);
        }

        String noToken = base(new RelayServer(target, List.of(SECRET), () -> {
            throw new IOException("metadata server unreachable");
        }, CLOCK, 1000, new ReplayStore.Memory(100)));
        String b3 = synthBody();
        HttpResponse<String> n = post(noToken, b3, signed(NOW.getEpochSecond(), b3));
        assertEquals(503, n.statusCode());
        assertEquals("IDENTITY_TOKEN_UNAVAILABLE", code(n));
    }

    @Test
    void engineRefusalsAndPartialResultsPassThroughUnchanged() throws Exception {
        String a = base(gcsRelay());
        long ts = NOW.getEpochSecond();
        String unknown = body("{\"banks\":[\"Westpac\"]}", "{\"Applicant 1 Full Name\":\"Jane Example\"}", "{}");
        HttpResponse<String> u = post(a, unknown, signed(ts, unknown));
        assertEquals(422, u.statusCode());
        assertEquals("UNKNOWN_BANK", code(u));
        String missing = body("{\"templates\":[{\"templateId\":\"SYNTH\",\"templateVersion\":\"1\"}]}", "{}", "{}");
        HttpResponse<String> m = post(a, missing, signed(ts, missing));
        assertEquals(422, m.statusCode());
        assertTrue(m.body().contains("MISSING_REQUIRED_VALUE"));
        String both = "{\"templates\":[{\"templateId\":\"SYNTH\",\"templateVersion\":\"1\"},"
                + "{\"templateId\":\"SYNTH_B\",\"templateVersion\":\"1\"}]}";
        String allOrNothing = body(both, "{\"Applicant 1 Full Name\":\"Jane Example\"}", "{}");
        HttpResponse<String> aon = post(a, allOrNothing, signed(ts, allOrNothing));
        assertEquals(422, aon.statusCode(), "default policy: no partial filing");
        assertFalse(aon.body().contains("pdfBase64"));
        String perDoc = body(both, "{\"Applicant 1 Full Name\":\"Jane Example\"}", "{\"failurePolicy\":\"per-document\"}");
        HttpResponse<String> p = post(a, perDoc, signed(ts, perDoc));
        assertEquals(200, p.statusCode());
        JsonNode pj = JSON.readTree(p.body());
        assertEquals("partial", pj.get("status").asText());
        assertTrue(pj.get("documents").get(0).has("pdfBase64"));
        assertEquals("MISSING_REQUIRED_VALUE", pj.get("documents").get(1).get("errors").get(0).get("code").asText());
    }

    @Test
    void logsCarryNoDataSecretsOrTokens() throws Exception {
        String a = base(gcsRelay());
        logs.clear();
        String body = synthBody();
        post(a, body, signed(NOW.getEpochSecond(), body));
        post(a, body, signed(NOW.getEpochSecond(), body));
        post(a, "{\"x\":\"Jane Example\"", signed(NOW.getEpochSecond(), "{\"x\":\"Jane Example\""));
        assertTrue(logs.size() >= 3);
        for (String line : logs) {
            assertTrue(line.startsWith("relay correlationId="), line);
            for (String secret : List.of("Jane", SECRET, TOKEN, GCS_TOKEN, "v1=")) {
                assertFalse(line.contains(secret), "log line leaks " + secret.substring(0, 3));
            }
        }
        assertTrue(logs.stream().anyMatch(l -> l.contains("outcome=REPLAYED_REQUEST")));
        assertTrue(logs.stream().anyMatch(l -> l.contains("outcome=malformed_json")));
    }

    @Test
    void theDocumentedTestVectorIsWhatTheRelayVerifies() throws Exception {
        JsonNode v = JSON.readTree(Files.readString(Path.of("..", "contracts", "hmac-test-vector.json")));
        assertEquals(v.get("expectedSignatureHex").asText(), RelayServer.hmacHex(
                v.get("secret").asText().getBytes(StandardCharsets.UTF_8), v.get("timestamp").asText(),
                v.get("body").asText().getBytes(StandardCharsets.UTF_8)));
        assertEquals(v.get("timestamp").asText() + "." + v.get("body").asText(), v.get("signingInput").asText());
    }

    @Test
    void cloudRunRefusesThePerInstanceStoreWithoutAnExplicitSingleInstanceSetting() {
        assertThrows(IllegalStateException.class, () -> Main.replayStore(Map.of("K_SERVICE", "rsfa-end-forms-relay",
                "RELAY_REPLAY_STORE", "memory")));
        assertEquals("memory", Main.replayStore(Map.of("K_SERVICE", "rsfa-end-forms-relay", "RELAY_REPLAY_STORE", "memory",
                "RELAY_SINGLE_INSTANCE", "true")).kind());
        assertThrows(IllegalArgumentException.class, () -> Main.replayStore(Map.of("K_SERVICE", "rsfa-end-forms-relay")),
                "on Cloud Run the default is the shared store, which needs a bucket");
        assertEquals("gcs", Main.replayStore(Map.of("K_SERVICE", "x", "RELAY_REPLAY_BUCKET", "rsfa-relay-replay")).kind());
        assertEquals("memory", Main.replayStore(Map.of()).kind(), "local default");
        assertThrows(IllegalArgumentException.class, () -> Main.replayStore(Map.of("RELAY_REPLAY_STORE", "redis")));
    }
}
