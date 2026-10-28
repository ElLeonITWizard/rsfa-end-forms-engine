package nz.co.rsfa.endforms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import nz.co.rsfa.endforms.admin.AdminAccess;
import nz.co.rsfa.endforms.admin.AdminServer;
import nz.co.rsfa.endforms.admin.AdminService;
import nz.co.rsfa.endforms.admin.ExampleForm;
import nz.co.rsfa.endforms.admin.IapVerifier;
import nz.co.rsfa.endforms.admin.ObjectStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
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
 * The private admin service (ENGINE_ROLE=admin, D-041) as deployed, minus Google: IAP-signed identities from a test
 * key, the Cloud Storage JSON API from an in-memory fake, and two service instances sharing one bucket.
 */
class AdminCloudTest {

    static final HttpClient HTTP = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
    static final ObjectMapper JSON = new ObjectMapper();
    static final Instant NOW = Instant.parse("2026-10-29T01:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    static final String AUDIENCE = "/projects/123456789012/locations/australia-southeast1/services/rsfa-end-forms-admin";
    static final String BUCKET = "rsfa-admin-test";
    static final String TOKEN = "test-access-token";
    static final String ADMIN = "admin@firbot.example.org";
    static final String AUTHOR = "author@firbot.example.org";
    static final String APPROVER = "approver@rsfa.example.org";
    static final String VIEWER = "viewer@rsfa.example.org";
    static final String USERS = ADMIN + ":admin," + AUTHOR + ":author," + APPROVER + ":approver," + VIEWER + ":viewer";
    static final String SCHEMA = """
            {"board":{"columns":[
             {"id":"text_name","title":"Applicant 1 Full Name","type":"text"},
             {"id":"text_addr","title":"Postal Address","type":"text"},
             {"id":"status_freq","title":"Repayment Frequency","type":"status","settings":{"labels":{"0":"Weekly","1":"Fortnightly","2":"Monthly"}}}]}}""";

    @TempDir
    static Path dir;
    static FakeGcs gcs;
    static KeyPair iapKey;
    static KeyPair otherKey;
    static HttpServer keyServer;
    static AdminServer a;
    static AdminServer b;
    static String baseA;
    static String baseB;
    static final List<String> logs = Collections.synchronizedList(new ArrayList<>());
    static final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord r) {
            logs.add(String.valueOf(r.getMessage()));
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };
    static final Logger adminLog = Logger.getLogger("nz.co.rsfa.endforms.admin.AdminApi");

    @BeforeAll
    static void start() throws Exception {
        adminLog.addHandler(capture);
        Files.createDirectories(dir.resolve("no-mappings"));
        gcs = new FakeGcs();
        gcs.objects.put("config/monday-schema.json", SCHEMA.getBytes(StandardCharsets.UTF_8));
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        iapKey = g.generateKeyPair();
        otherKey = g.generateKeyPair();
        keyServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        keyServer.createContext("/jwk", ex -> reply(ex, 200, jwks("k1", (ECPublicKey) iapKey.getPublic())));
        keyServer.start();
        a = new AdminServer(AdminService.build(env(Map.of()), CLOCK));
        b = new AdminServer(AdminService.build(env(Map.of()), CLOCK));
        baseA = "http://127.0.0.1:" + a.start("127.0.0.1", 0);
        baseB = "http://127.0.0.1:" + b.start("127.0.0.1", 0);
    }

    @AfterAll
    static void stop() {
        a.stop();
        b.stop();
        keyServer.stop(0);
        gcs.server.stop(0);
        adminLog.removeHandler(capture);
    }

    static Map<String, String> env(Map<String, String> extra) {
        Map<String, String> e = new HashMap<>(Map.of("ADMIN_EMULATION", "1", "ADMIN_USERS", USERS, "IAP_AUDIENCE", AUDIENCE,
                "ADMIN_BUCKET", BUCKET, "ADMIN_GCS_ENDPOINT", gcs.base(), "ADMIN_GCS_TOKEN", TOKEN,
                "IAP_KEYS_URL", "http://127.0.0.1:" + keyServer.getAddress().getPort() + "/jwk",
                "ENGINE_MAPPINGS_DIR", dir.resolve("no-mappings").toString()));
        e.putAll(extra);
        return e;
    }

    // ------------------------------------------------------------------------------------------- IAP identity

    static String jwt(KeyPair key, String kid, String alg, Map<String, Object> claims) throws Exception {
        String h = b64(JSON.writeValueAsBytes(Map.of("alg", alg, "kid", kid, "typ", "JWT")));
        String p = b64(JSON.writeValueAsBytes(claims));
        Signature s = Signature.getInstance("SHA256withECDSAinP1363Format");
        s.initSign(key.getPrivate());
        s.update((h + "." + p).getBytes(StandardCharsets.US_ASCII));
        return h + "." + p + "." + b64(s.sign());
    }

    static Map<String, Object> claims(String email) {
        Map<String, Object> c = new HashMap<>();
        c.put("iss", IapVerifier.ISSUER);
        c.put("aud", AUDIENCE);
        c.put("email", email);
        c.put("sub", "accounts.google.com:" + Math.abs(email.hashCode()));
        c.put("iat", NOW.getEpochSecond() - 60);
        c.put("exp", NOW.getEpochSecond() + 540);
        return c;
    }

    static String token(String email) throws Exception {
        return jwt(iapKey, "k1", "ES256", claims(email));
    }

    static HttpResponse<byte[]> call(String base, String method, String path, String jwt, byte[] body,
                                     Map<String, String> headers) throws Exception {
        HttpRequest.Builder r = HttpRequest.newBuilder(URI.create(base + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        if (jwt != null) {
            r.header(IapVerifier.HEADER, jwt);
        }
        headers.forEach(r::header);
        return HTTP.send(r.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    /** A browser-like call from the admin page: IAP assertion + same-origin marker. */
    static JsonNode as(String base, String email, String method, String path, Object body) throws Exception {
        byte[] bytes = body == null ? null : body instanceof byte[] x ? x : JSON.writeValueAsBytes(body);
        HttpResponse<byte[]> r = call(base, method, path, token(email), bytes, Map.of("X-RSFA-Admin", "1"));
        JsonNode n = r.body().length > 0 && r.body()[0] == '{' ? JSON.readTree(r.body()) : JSON.createObjectNode();
        ((ObjectNode) n).put("_status", r.statusCode());
        return n;
    }

    static String code(HttpResponse<byte[]> r) throws IOException {
        return JSON.readTree(r.body()).path("code").asText();
    }

    @Test
    void onlyAValidIapAssertionForAnAllowlistedUserIsAccepted() throws Exception {
        String path = "/admin/api/session";
        assertEquals(401, call(baseA, "GET", path, null, null, Map.of()).statusCode(), "no assertion");
        assertEquals("IAP_MISSING", code(call(baseA, "GET", path, null, null, Map.of())));
        assertEquals("IAP_MISSING", code(call(baseA, "GET", path, null, null,
                Map.of("X-Goog-Authenticated-User-Email", "accounts.google.com:" + ADMIN))), "the unsigned header is never trusted");
        assertEquals("IAP_MALFORMED", code(call(baseA, "GET", path, "not.a-jwt", null, Map.of())));
        assertEquals("IAP_BAD_SIGNATURE", code(call(baseA, "GET", path, jwt(otherKey, "k1", "ES256", claims(ADMIN)), null, Map.of())),
                "signed by another key under IAP's key id");
        assertEquals("IAP_UNKNOWN_KEY", code(call(baseA, "GET", path, jwt(iapKey, "k9", "ES256", claims(ADMIN)), null, Map.of())));
        assertEquals("IAP_BAD_ALGORITHM", code(call(baseA, "GET", path, jwt(iapKey, "k1", "HS256", claims(ADMIN)), null, Map.of())));
        String[] parts = token(ADMIN).split("\\.");
        String none = b64("{\"alg\":\"none\",\"kid\":\"k1\"}".getBytes(StandardCharsets.UTF_8)) + "." + parts[1] + ".";
        assertEquals("IAP_BAD_ALGORITHM", code(call(baseA, "GET", path, none, null, Map.of())), "alg none is refused");
        Map<String, Object> c = claims(ADMIN);
        c.put("aud", "/projects/123456789012/locations/australia-southeast1/services/rsfa-end-forms-engine");
        assertEquals("IAP_BAD_AUDIENCE", code(call(baseA, "GET", path, jwt(iapKey, "k1", "ES256", c), null, Map.of())),
                "a token for another IAP-protected service is refused");
        c = claims(ADMIN);
        c.put("iss", "https://accounts.google.com");
        assertEquals("IAP_BAD_ISSUER", code(call(baseA, "GET", path, jwt(iapKey, "k1", "ES256", c), null, Map.of())));
        c = claims(ADMIN);
        c.put("exp", NOW.getEpochSecond() - 120);
        assertEquals("IAP_EXPIRED", code(call(baseA, "GET", path, jwt(iapKey, "k1", "ES256", c), null, Map.of())));
        c = claims(ADMIN);
        c.put("iat", NOW.getEpochSecond() + 600);
        assertEquals("IAP_NOT_YET_VALID", code(call(baseA, "GET", path, jwt(iapKey, "k1", "ES256", c), null, Map.of())));
        HttpResponse<byte[]> page = call(baseA, "GET", "/admin/", token("other.account@example.com"), null, Map.of());
        assertEquals(403, page.statusCode());
        assertTrue(new String(page.body(), StandardCharsets.UTF_8).contains("signed in as other.account@example.com"),
                "the refusal page tells a signed-in user which Google account they used (wrong-account diagnosis)");
        HttpResponse<byte[]> escaped = call(baseA, "GET", "/admin/", token("x<b>@example.com"), null, Map.of());
        assertEquals(401, escaped.statusCode(), "a malformed email claim is refused before any page is built");
        HttpResponse<byte[]> stranger = call(baseA, "GET", path, token("someone@example.com"), null, Map.of());
        assertEquals(403, stranger.statusCode(), "signed in with Google but not on the allowlist");
        assertEquals("NOT_AUTHORISED_USER", code(stranger));
        JsonNode s = as(baseA, ADMIN.toUpperCase(java.util.Locale.ROOT), "GET", path, null);
        assertEquals(200, s.path("_status").asInt(), "emails compare case-insensitively");
        assertEquals("admin", s.path("role").asText());
        assertEquals("iap", s.path("authentication").asText());
        assertEquals("gcs", s.path("storage").asText());
        assertTrue(s.path("syntheticOnly").asBoolean());
        assertEquals("mock", s.path("suggestions").asText());
        assertEquals("disabled", s.path("publication").asText());
        assertEquals(302, call(baseA, "GET", "/", null, null, Map.of()).statusCode(), "/ redirects to the admin page");
        assertEquals(404, call(baseA, "POST", "/generate", token(ADMIN), "{}".getBytes(), Map.of()).statusCode(),
                "the admin service has no engine endpoints");
        assertEquals(404, call(baseA, "POST", "/v1/masterboard/documents", token(ADMIN), "{}".getBytes(), Map.of()).statusCode());
    }

    @Test
    void rolesAndTheCrossSiteGuardAreEnforcedServerSide() throws Exception {
        byte[] pdf = ExampleForm.build();
        String up = "/admin/api/templates?bank=Other&templateName=Example&confirmNoClientData=true";
        assertEquals("ROLE_NOT_PERMITTED", as(baseA, VIEWER, "POST", up, pdf).path("code").asText(), "viewer cannot upload");
        assertEquals("ROLE_NOT_PERMITTED", as(baseA, APPROVER, "POST", up, pdf).path("code").asText(), "approver cannot upload");
        HttpResponse<byte[]> noMarker = call(baseA, "POST", up, token(AUTHOR), pdf, Map.of());
        assertEquals(403, noMarker.statusCode());
        assertEquals("CROSS_SITE_REFUSED", code(noMarker), "a cross-site form post carries the IAP cookie but not the marker");
        HttpResponse<byte[]> crossSite = call(baseA, "POST", up, token(AUTHOR), pdf, Map.of("X-RSFA-Admin", "1",
                "Sec-Fetch-Site", "cross-site"));
        assertEquals("CROSS_SITE_REFUSED", code(crossSite));
        String sha = as(baseA, AUTHOR, "POST", up, pdf).path("analysis").path("sha256").asText();
        String id = draft(baseA, AUTHOR, sha);
        assertEquals(200, as(baseB, VIEWER, "GET", "/admin/api/drafts/" + id, null).path("_status").asInt(), "viewer reads");
        assertEquals("ROLE_NOT_PERMITTED", as(baseB, VIEWER, "PUT", "/admin/api/drafts/" + id,
                Map.of("baseVersion", 1, "mapping", mapping())).path("code").asText(), "viewer cannot save");
        assertEquals("ROLE_NOT_PERMITTED", as(baseB, APPROVER, "PUT", "/admin/api/drafts/" + id,
                Map.of("baseVersion", 1, "mapping", mapping())).path("code").asText(), "approver cannot edit");
        assertEquals(200, as(baseB, AUTHOR, "PUT", "/admin/api/drafts/" + id, Map.of("baseVersion", 1, "mapping", mapping()))
                .path("_status").asInt());
        assertEquals(200, test(baseA, AUTHOR, id).path("_status").asInt());
        assertEquals(200, status(baseB, AUTHOR, id, 2, "ready-for-review", null).path("_status").asInt());
        assertEquals("ROLE_NOT_PERMITTED", status(baseB, AUTHOR, id, 2, "approved", approval()).path("code").asText(),
                "an author cannot approve");
        assertEquals("ROLE_NOT_PERMITTED", status(baseB, VIEWER, id, 2, "approved", approval()).path("code").asText());
        JsonNode ok = status(baseA, APPROVER, id, 2, "approved", approval());
        assertEquals("approved", ok.path("state").path("status").asText());
        assertEquals(APPROVER, ok.path("state").path("approval").path("recordedBy").asText(), "recorded by the verified identity");
        assertEquals("ROLE_NOT_PERMITTED", status(baseB, AUTHOR, id, 2, "draft", null).path("code").asText(),
                "only an approver revokes an approval");
        assertEquals("INVALID_STATUS", status(baseA, ADMIN, id, 2, "deployed", null).path("code").asText(),
                "there is no publication status");
    }

    @Test
    void draftsPersistAcrossInstancesWithVersionConflictsAndAnImmutableAudit() throws Exception {
        String sha = upload(baseA, AUTHOR);
        String id = draft(baseA, AUTHOR, sha);
        // another instance (another browser session) reopens the saved draft
        JsonNode reopened = as(baseB, ADMIN, "GET", "/admin/api/drafts/" + id, null);
        assertEquals(1, reopened.path("state").path("version").asInt());
        // both instances save on version 1 at once: exactly one wins, the other gets 409
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Integer>> f = new ArrayList<>();
        for (String base : List.of(baseA, baseB)) {
            f.add(pool.submit(() -> {
                go.await();
                return as(base, AUTHOR, "PUT", "/admin/api/drafts/" + id, Map.of("baseVersion", 1, "mapping", mapping(),
                        "note", base.equals(baseA) ? "from A" : "from B")).path("_status").asInt();
            }));
        }
        go.countDown();
        List<Integer> codes = new ArrayList<>();
        for (Future<Integer> x : f) {
            codes.add(x.get());
        }
        pool.shutdown();
        codes.sort(null);
        assertEquals(List.of(200, 409), codes, "one save wins; the stale one is refused, never merged silently");
        assertEquals(409, as(baseA, AUTHOR, "PUT", "/admin/api/drafts/" + id, Map.of("baseVersion", 1, "mapping",
                Map.of("fields", List.of()))).path("_status").asInt(), "a stale base version is refused afterwards too");
        JsonNode st = as(baseA, ADMIN, "GET", "/admin/api/drafts/" + id, null);
        assertEquals(2, st.path("state").path("version").asInt());
        // test generated on A is served by B (shared result storage)
        JsonNode t = test(baseA, AUTHOR, id);
        assertTrue(t.path("ok").asBoolean(), t.toString());
        HttpResponse<byte[]> pdf = call(baseB, "GET", "/admin/api/results/" + t.path("resultId").asText() + ".pdf", token(VIEWER),
                null, Map.of());
        assertEquals(200, pdf.statusCode());
        assertEquals("%PDF-", new String(pdf.body(), 0, 5, StandardCharsets.US_ASCII));
        // audit: who and what, never values; event objects are created once and never overwritten
        JsonNode audit = as(baseB, VIEWER, "GET", "/admin/api/drafts/" + id, null).path("audit");
        List<String> actions = new ArrayList<>();
        audit.forEach(e -> actions.add(e.path("action").asText() + ":" + e.path("actor").asText()));
        assertEquals(List.of("create:" + AUTHOR, "save:" + AUTHOR, "test:" + AUTHOR), actions);
        String auditText = audit.toString();
        assertFalse(auditText.contains("Jane Example") || auditText.contains("Example Street"), "no values in the audit");
        assertTrue(gcs.overwriteAttempts.get() == 0, "every write is create-if-absent");
        assertTrue(gcs.preconditionFailures.get() >= 1, "the losing instance hit the precondition, not a lost update");
        List<String> events = gcs.names("drafts/" + id + "/events/");
        assertEquals(List.of("000001.json", "000002.json", "000003.json"), events.stream().map(n -> n.substring(n.lastIndexOf('/') + 1)).toList());
        assertTrue(gcs.names("results/").size() >= 2, "test outputs live in the bucket (lifecycle-deleted)");
    }

    @Test
    void manyConcurrentWritersProduceAGapFreeLog() throws Exception {
        String id = draft(baseA, AUTHOR, upload(baseA, AUTHOR));
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> f = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String base = i % 2 == 0 ? baseA : baseB;
            f.add(pool.submit(() -> {
                go.await();
                return test(base, AUTHOR, id).path("_status").asInt(); // tests append events without a base version
            }));
        }
        go.countDown();
        for (Future<Integer> x : f) {
            assertEquals(200, x.get());
        }
        pool.shutdown();
        List<String> events = gcs.names("drafts/" + id + "/events/");
        assertEquals(7, events.size(), "create + 6 tests, none lost");
        for (int i = 0; i < events.size(); i++) {
            assertTrue(events.get(i).endsWith(String.format("%06d.json", i + 1)), "no gap in the event log");
        }
    }

    @Test
    void syntheticOnlyUploadsAndTestValuesAndInvalidPdfsStoreNothing() throws Exception {
        int before = gcs.names("templates/").size();
        JsonNode noConfirm = as(baseA, AUTHOR, "POST", "/admin/api/templates?bank=Other&templateName=X", ExampleForm.build());
        assertEquals(403, noConfirm.path("_status").asInt());
        assertEquals("CONFIRMATION_REQUIRED", noConfirm.path("code").asText());
        JsonNode notPdf = as(baseA, AUTHOR, "POST", "/admin/api/templates?bank=Other&templateName=X&confirmNoClientData=true",
                "Jane Example, 12 Example Street".getBytes(StandardCharsets.UTF_8));
        assertEquals(422, notPdf.path("_status").asInt());
        assertEquals(before, gcs.names("templates/").size(), "refused uploads leave nothing in the bucket");
        String id = draft(baseA, AUTHOR, upload(baseA, AUTHOR));
        assertEquals(200, as(baseA, AUTHOR, "PUT", "/admin/api/drafts/" + id, Map.of("baseVersion", 1, "mapping", mapping()))
                .path("_status").asInt());
        JsonNode typed = as(baseA, AUTHOR, "POST", "/admin/api/drafts/" + id + "/test",
                Map.of("data", Map.of("text_name", "Real Person Name", "status_freq", "Weekly")));
        assertEquals(403, typed.path("_status").asInt());
        assertEquals("NON_SYNTHETIC_VALUE", typed.path("code").asText());
        assertFalse(typed.toString().contains("Real Person"), "the refusal names the column, not the value");
        JsonNode labels = as(baseA, AUTHOR, "POST", "/admin/api/drafts/" + id + "/test",
                Map.of("data", Map.of("text_name", "Jane Example", "status_freq", "Monthly")));
        assertTrue(labels.path("ok").asBoolean(), "generated values and Monday labels are accepted");
    }

    @Test
    void separateApproverPolicyAndStorageOutagesFailClosed() throws Exception {
        AdminServer strict = new AdminServer(AdminService.build(env(Map.of("ADMIN_SEPARATE_APPROVER", "true")), CLOCK));
        String base = "http://127.0.0.1:" + strict.start("127.0.0.1", 0);
        try {
            String id = draft(base, ADMIN, upload(base, ADMIN));
            as(base, ADMIN, "PUT", "/admin/api/drafts/" + id, Map.of("baseVersion", 1, "mapping", mapping()));
            test(base, ADMIN, id);
            status(base, ADMIN, id, 2, "ready-for-review", null);
            assertEquals("SEPARATE_APPROVER_REQUIRED", status(base, ADMIN, id, 2, "approved", approval()).path("code").asText());
            assertEquals("approved", status(base, APPROVER, id, 2, "approved", approval()).path("state").path("status").asText());
            gcs.failing.set(true);
            JsonNode down = as(base, ADMIN, "GET", "/admin/api/library", null);
            assertEquals(503, down.path("_status").asInt());
            assertEquals("STORAGE_UNAVAILABLE", down.path("code").asText());
        } finally {
            gcs.failing.set(false);
            strict.stop();
        }
    }

    @Test
    void logsCarryRoutesStatusesCodesAndHashedUsersOnly() throws Exception {
        String id = draft(baseA, AUTHOR, upload(baseA, AUTHOR));
        as(baseA, AUTHOR, "PUT", "/admin/api/drafts/" + id, Map.of("baseVersion", 1, "mapping", mapping()));
        test(baseA, AUTHOR, id);
        call(baseA, "GET", "/admin/api/session", token("someone@example.com"), null, Map.of());
        String all = String.join("\n", logs);
        assertTrue(all.contains("status=201") && all.contains("code=NOT_AUTHORISED_USER"), all);
        call(baseA, "GET", "/admin/", token("other.account@example.com"), null, Map.of());
        for (String secret : List.of(ADMIN, AUTHOR, "someone@example.com", "other.account@example.com", "Jane Example", "example-bank-form", "Example Street",
                token(AUTHOR).substring(0, 30), id)) {
            assertFalse(all.contains(secret), "log contains " + secret);
        }
        assertTrue(all.matches("(?s).*user=u-[0-9a-f]{8}.*"), "users appear as a short hash");
    }

    @Test
    void unsafeConfigurationsAreRefused() {
        assertThrows(IllegalStateException.class, () -> AdminService.build(env(Map.of("K_SERVICE", "rsfa-end-forms-admin")), CLOCK),
                "emulation overrides on Cloud Run");
        Map<String, String> cloud = new HashMap<>(Map.of("K_SERVICE", "rsfa-end-forms-engine", "ADMIN_USERS", USERS,
                "IAP_AUDIENCE", AUDIENCE, "ADMIN_BUCKET", BUCKET));
        assertThrows(IllegalStateException.class, () -> AdminService.build(cloud, CLOCK), "admin role on the engine service");
        Map<String, String> local = env(Map.of());
        local.remove("ADMIN_EMULATION");
        assertThrows(IllegalStateException.class, () -> AdminService.build(local, CLOCK), "off Cloud Run without emulation");
        assertThrows(IllegalStateException.class, () -> AdminService.build(env(Map.of("ENGINE_HMAC_SECRET", "x".repeat(40))), CLOCK));
        assertThrows(IllegalStateException.class, () -> AdminService.build(env(Map.of("ADMIN_SUGGESTIONS", "openai")), CLOCK));
        assertThrows(IllegalArgumentException.class, () -> AdminService.build(env(Map.of("ADMIN_USERS", AUTHOR + ":author")), CLOCK),
                "no administrator");
        assertThrows(IllegalArgumentException.class, () -> AdminService.build(env(Map.of("ADMIN_USERS", ADMIN + ":owner")), CLOCK));
        assertThrows(IllegalArgumentException.class, () -> AdminService.build(env(Map.of("IAP_AUDIENCE", "rsfa-end-forms-admin")), CLOCK));
        assertThrows(IllegalStateException.class, () -> AdminService.build(env(Map.of("ADMIN_BUCKET", "")), CLOCK));
        assertThrows(IllegalStateException.class, () -> Main.roleOf(Map.of("K_SERVICE", "rsfa-end-forms-admin")),
                "a forgotten ENGINE_ROLE on the admin service would run the engine there");
        assertEquals("admin", Main.roleOf(Map.of("ENGINE_ROLE", "admin", "K_SERVICE", "rsfa-end-forms-admin")));
        Map<String, String> users = AdminAccess.parseUsers(" A@Example.org:admin , b@example.org:viewer ");
        assertEquals(Map.of("a@example.org", "admin", "b@example.org", "viewer"), users);
    }

    @Test
    void localObjectStoreCreatesEachNameExactlyOnce() throws Exception {
        ObjectStore store = new ObjectStore.Local(dir.resolve("local-store"));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> f = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            byte[] body = ("writer " + i).getBytes(StandardCharsets.UTF_8);
            f.add(pool.submit(() -> {
                go.await();
                return store.createIfAbsent("drafts/d-000000000000/events/000001.json", body, "application/json");
            }));
        }
        go.countDown();
        int wins = 0;
        for (Future<Boolean> x : f) {
            wins += x.get() ? 1 : 0;
        }
        pool.shutdown();
        assertEquals(1, wins);
        assertEquals(List.of("drafts/d-000000000000/events/000001.json"), store.list("drafts/"));
        assertThrows(IllegalArgumentException.class, () -> store.get("../etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> store.get("Templates/X.pdf"));
    }

    // ------------------------------------------------------------------------------------------- helpers

    static String upload(String base, String email) throws Exception {
        JsonNode r = as(base, email, "POST", "/admin/api/templates?bank=Other&templateName=Example%20Bank&confirmNoClientData=true"
                + "&fileName=example-bank-form.pdf", ExampleForm.build());
        assertTrue(r.path("_status").asInt() == 201, r.toString());
        return r.path("analysis").path("sha256").asText();
    }

    static String draft(String base, String email, String sha) throws Exception {
        JsonNode r = as(base, email, "POST", "/admin/api/drafts", Map.of("templateSha256", sha, "templateId", "EXAMPLE_BANK",
                "templateVersion", "2026-10"));
        assertEquals(201, r.path("_status").asInt(), r.toString());
        return r.path("draftId").asText();
    }

    static Map<String, Object> mapping() {
        return Map.of("fields", List.of(
                Map.of("pdfField", "CustName", "source", Map.of("type", "column", "columnId", "text_name")),
                Map.of("pdfField", "RepayFreq", "option", "Monthly", "source", Map.of("type", "condition", "columnId",
                        "status_freq", "operator", "equals", "value", "Monthly"))));
    }

    static JsonNode test(String base, String email, String id) throws Exception {
        JsonNode syn = as(base, email, "POST", "/admin/api/drafts/" + id + "/synthetic", Map.of());
        return as(base, email, "POST", "/admin/api/drafts/" + id + "/test", Map.of("data", syn.path("data"), "today", "2026-10-29"));
    }

    static JsonNode status(String base, String email, String id, int v, String to, Map<String, String> approval) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("baseVersion", v, "status", to));
        if (approval != null) {
            body.put("approval", approval);
        }
        return as(base, email, "POST", "/admin/api/drafts/" + id + "/status", body);
    }

    static Map<String, String> approval() {
        return Map.of("approvedBy", "Test Approver (synthetic)", "approvedOn", "2026-10-29", "reference", "TEST-CLOUD");
    }

    static String b64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static String jwks(String kid, ECPublicKey k) {
        return "{\"keys\":[{\"kty\":\"EC\",\"crv\":\"P-256\",\"alg\":\"ES256\",\"kid\":\"" + kid + "\",\"x\":\""
                + b64(fixed(k.getW().getAffineX().toByteArray())) + "\",\"y\":\"" + b64(fixed(k.getW().getAffineY().toByteArray())) + "\"}]}";
    }

    static byte[] fixed(byte[] v) {
        byte[] out = new byte[32];
        int n = Math.min(32, v.length);
        System.arraycopy(v, v.length - n, out, 32 - n, n);
        return out;
    }

    static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    /**
     * In-memory Cloud Storage JSON API: media upload with {@code ifGenerationMatch=0} (412 when the object exists),
     * media download, and paginated listing by prefix (page size 2 to exercise {@code pageToken}). Requires the bearer
     * token; counts overwrite attempts (uploads without the precondition), which must never happen.
     */
    static final class FakeGcs {
        final Map<String, byte[]> objects = new ConcurrentHashMap<>();
        final AtomicInteger overwriteAttempts = new AtomicInteger();
        final AtomicInteger preconditionFailures = new AtomicInteger();
        final AtomicBoolean failing = new AtomicBoolean();
        final HttpServer server;

        FakeGcs() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.setExecutor(Executors.newFixedThreadPool(8));
            server.start();
        }

        String base() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        List<String> names(String prefix) {
            return new TreeMap<>(objects).keySet().stream().filter(n -> n.startsWith(prefix)).toList();
        }

        void handle(HttpExchange ex) throws IOException {
            try (ex) {
                if (failing.get()) {
                    reply(ex, 500, "{}");
                    return;
                }
                if (!("Bearer " + TOKEN).equals(ex.getRequestHeaders().getFirst("Authorization"))) {
                    reply(ex, 401, "{}");
                    return;
                }
                String path = ex.getRequestURI().getRawPath();
                Map<String, String> q = query(ex.getRequestURI().getRawQuery());
                String bucketPrefix = "/storage/v1/b/" + BUCKET + "/o";
                if (ex.getRequestMethod().equals("POST") && path.equals("/upload" + bucketPrefix)) {
                    byte[] body = ex.getRequestBody().readAllBytes();
                    if (!"0".equals(q.get("ifGenerationMatch"))) {
                        overwriteAttempts.incrementAndGet();
                        reply(ex, 400, "{}");
                        return;
                    }
                    if (objects.putIfAbsent(q.get("name"), body) != null) {
                        preconditionFailures.incrementAndGet();
                        reply(ex, 412, "{}");
                        return;
                    }
                    reply(ex, 200, "{}");
                } else if (ex.getRequestMethod().equals("GET") && path.startsWith(bucketPrefix + "/") && "media".equals(q.get("alt"))) {
                    String name = URLDecoder.decode(path.substring(bucketPrefix.length() + 1), StandardCharsets.UTF_8);
                    byte[] o = objects.get(name);
                    if (o == null) {
                        reply(ex, 404, "{}");
                        return;
                    }
                    ex.sendResponseHeaders(200, o.length);
                    ex.getResponseBody().write(o);
                } else if (ex.getRequestMethod().equals("GET") && path.equals(bucketPrefix)) {
                    List<String> all = names(q.getOrDefault("prefix", ""));
                    int from = Integer.parseInt(q.getOrDefault("pageToken", "0"));
                    int to = Math.min(all.size(), from + 2);
                    ObjectNode res = JSON.createObjectNode();
                    var items = res.putArray("items");
                    all.subList(from, to).forEach(n -> items.addObject().put("name", n));
                    if (to < all.size()) {
                        res.put("nextPageToken", Integer.toString(to));
                    }
                    reply(ex, 200, res.toString());
                } else {
                    reply(ex, 404, "{}");
                }
            }
        }

        static Map<String, String> query(String raw) {
            Map<String, String> q = new HashMap<>();
            if (raw != null) {
                for (String p : raw.split("&")) {
                    int i = p.indexOf('=');
                    if (i > 0) {
                        q.put(URLDecoder.decode(p.substring(0, i), StandardCharsets.UTF_8), URLDecoder.decode(p.substring(i + 1), StandardCharsets.UTF_8));
                    }
                }
            }
            return q;
        }
    }
}
