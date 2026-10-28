package nz.co.rsfa.endforms.relay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Authenticated intermediary between Make and the private engine (D-027, docs/07). The only component Make can reach.
 *
 * <ul>
 *   <li>Make authenticates every request with an HMAC-SHA256 signature over {@code timestamp + "." + body}
 *       ({@code X-RSFA-Timestamp}, {@code X-RSFA-Signature: v1=<hex>}); two secrets may be active for rotation.</li>
 *   <li>Replay protection: timestamp within ±{@value #MAX_SKEW_SECONDS}s and each signature accepted once, recorded
 *       in a {@link ReplayStore}: {@link GcsReplayStore} is shared by all instances; {@link ReplayStore.Memory} is
 *       correct only with a single instance. The store fails closed (503).</li>
 *   <li>The body must be a JSON object (checked after the signature, before anything is forwarded).</li>
 *   <li>The relay calls the engine with a Google-signed ID token for its own service account (Cloud Run metadata
 *       server), so the engine keeps {@code --no-allow-unauthenticated}; no key file exists anywhere.</li>
 *   <li>Header format, freshness and Content-Length are checked before the body is read; request ≤ 1 MiB,
 *       response ≤ 32 MiB, per-instance rate limit, one upstream deadline (body included) below Cloud Run's 120 s.</li>
 *   <li>The rate limit and the identity token come before the replay claim, so a retryable refusal never consumes
 *       the signature.</li>
 * </ul>
 * Never logs bodies, values, secrets or tokens: one line per request with correlation id, codes and timings.
 */
public final class RelayServer {

    private static final Logger LOG = Logger.getLogger(RelayServer.class.getName());
    static final String PATH = "/v1/masterboard/documents";
    static final int MAX_BODY_BYTES = 1024 * 1024;
    static final long MAX_RESPONSE_BYTES = 32L * 1024 * 1024;
    static final long MAX_SKEW_SECONDS = 300;
    /**
     * One deadline for the whole upstream exchange, response body included. With the replay store (≤ 10 s) and the
     * identity token (≤ 10 s) the relay answers well inside Cloud Run's 120 s request timeout.
     */
    static final Duration UPSTREAM_TIMEOUT = Duration.ofSeconds(90);
    private static final Pattern CORRELATION = Pattern.compile("[A-Za-z0-9._:-]{8,128}");
    private static final Pattern SIGNATURE = Pattern.compile("v1=[0-9a-fA-F]{64}");

    /** Supplies a Google-signed ID token whose audience is the engine URL. */
    public interface IdTokenSource {
        String token() throws IOException;
    }

    private final URI target;
    private final List<byte[]> secrets;
    private final IdTokenSource tokens;
    private final Clock clock;
    private final RateLimiter limiter;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY)
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final ReplayStore replay;
    private final ExecutorService upstreamCalls = Executors.newVirtualThreadPerTaskExecutor();
    private volatile Duration upstreamTimeout = UPSTREAM_TIMEOUT;
    private HttpServer server;

    /**
     * @param target  engine base URL (https, or http on loopback for local tests)
     * @param secrets HMAC secrets, current first; each at least 32 characters (rotation: add the new one, switch Make,
     *                then remove the old one)
     */
    public RelayServer(URI target, List<String> secrets, IdTokenSource tokens, Clock clock, int ratePerMinute) {
        this(target, secrets, tokens, clock, ratePerMinute, new ReplayStore.Memory(10_000));
    }

    /** @param replay where accepted signatures are recorded; {@link GcsReplayStore} whenever more than one instance may run */
    public RelayServer(URI target, List<String> secrets, IdTokenSource tokens, Clock clock, int ratePerMinute,
                       ReplayStore replay) {
        boolean loopback = "http".equals(target.getScheme())
                && ("127.0.0.1".equals(target.getHost()) || "localhost".equals(target.getHost()));
        if (!"https".equals(target.getScheme()) && !loopback) {
            throw new IllegalArgumentException("RELAY_TARGET_URL must be https (http only on loopback for tests)");
        }
        if (secrets.isEmpty() || secrets.size() > 2 || secrets.stream().anyMatch(s -> s == null || s.length() < 32)) {
            throw new IllegalArgumentException("RELAY_HMAC_SECRETS needs 1 or 2 secrets of at least 32 characters");
        }
        this.target = target;
        this.secrets = secrets.stream().map(s -> s.getBytes(StandardCharsets.UTF_8)).toList();
        this.tokens = tokens;
        this.clock = clock;
        this.limiter = new RateLimiter(ratePerMinute, clock);
        this.replay = replay;
    }

    /** Upstream timeout (default 110 s, below Cloud Run's request timeout); shorter values only for tests. */
    public RelayServer withUpstreamTimeout(Duration timeout) {
        this.upstreamTimeout = timeout;
        return this;
    }

    public int start(String host, int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        return server.getAddress().getPort();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
        upstreamCalls.shutdownNow();
    }

    private void handle(HttpExchange ex) throws IOException {
        long started = System.nanoTime();
        String cid = null;
        int status = 500;
        String outcome = "error";
        try (ex) {
            String path = ex.getRequestURI().getPath();
            if ("/health".equals(path) && "GET".equals(ex.getRequestMethod())) {
                status = 200;
                outcome = "health";
                send(ex, 200, json.createObjectNode().put("status", "ok").put("role", "relay").put("replayStore", replay.kind()));
                return;
            }
            if (!PATH.equals(path)) {
                status = 404;
                outcome = "not_found";
                send(ex, 404, error("NOT_FOUND", false));
                return;
            }
            if (!"POST".equals(ex.getRequestMethod())) {
                status = 405;
                outcome = "method";
                send(ex, 405, error("METHOD_NOT_ALLOWED", false));
                return;
            }
            String pre = checkHeaders(ex); // format and freshness only, before a single body byte is read
            if (pre != null) {
                status = 401;
                outcome = pre;
                send(ex, status, error(pre, false));
                return;
            }
            String length = ex.getRequestHeaders().getFirst("Content-Length");
            if (length != null && (!length.matches("[0-9]{1,10}") || Long.parseLong(length) > MAX_BODY_BYTES)) {
                status = 413;
                outcome = "too_large";
                send(ex, 413, error("PAYLOAD_TOO_LARGE", false));
                return;
            }
            byte[] body = readLimited(ex.getRequestBody());
            if (body == null) {
                status = 413;
                outcome = "too_large";
                send(ex, 413, error("PAYLOAD_TOO_LARGE", false));
                return;
            }
            String auth = authenticate(ex, body);
            if (auth != null) {
                status = 401;
                outcome = auth;
                send(ex, status, error(auth, false));
                return;
            }
            JsonNode parsed;
            try {
                parsed = json.readTree(body);
            } catch (IOException e) {
                parsed = null;
            }
            if (parsed == null || !parsed.isObject()) {
                status = 400;
                outcome = "malformed_json";
                send(ex, 400, error("MALFORMED_JSON", false));
                return;
            }
            String c = parsed.path("correlationId").asText("");
            cid = CORRELATION.matcher(c).matches() ? c : null;
            // Everything that can fail transiently without contacting the engine happens BEFORE the signature is
            // claimed, so a retryable answer never consumes the signature (a retry may resend the same bytes).
            if (!limiter.tryAcquire()) {
                status = 429;
                outcome = "rate_limited";
                ex.getResponseHeaders().set("Retry-After", "60");
                send(ex, 429, error("RATE_LIMITED", true));
                return;
            }
            String token;
            try {
                token = tokens.token();
            } catch (IOException | RuntimeException e) {
                status = 503;
                outcome = "token_unavailable:" + e.getClass().getSimpleName();
                send(ex, 503, error("IDENTITY_TOKEN_UNAVAILABLE", true));
                return;
            }
            String sig = ex.getRequestHeaders().getFirst("X-RSFA-Signature").toLowerCase(Locale.ROOT);
            long now = clock.instant().getEpochSecond();
            boolean first;
            try {
                first = replay.claim(sha256Hex(sig), now + 2 * MAX_SKEW_SECONDS, now);
            } catch (IOException | RuntimeException e) {
                status = 503;
                outcome = "replay_store_unavailable:" + e.getClass().getSimpleName();
                send(ex, 503, error("REPLAY_STORE_UNAVAILABLE", true));
                return;
            }
            if (!first) {
                status = 409;
                outcome = "REPLAYED_REQUEST";
                send(ex, 409, error("REPLAYED_REQUEST", false));
                return;
            }
            HttpRequest.Builder up = HttpRequest.newBuilder(target.resolve(PATH)).timeout(upstreamTimeout)
                    .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            if (cid != null) {
                up.header("X-Correlation-Id", cid);
            }
            Upstream res;
            Future<Upstream> call = upstreamCalls.submit(() -> exchange(up.build()));
            try {
                res = call.get(upstreamTimeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                call.cancel(true);
                status = 504;
                outcome = "upstream_timeout";
                send(ex, 504, error("UPSTREAM_TIMEOUT", true));
                return;
            } catch (ExecutionException e) {
                boolean timeout = e.getCause() instanceof HttpTimeoutException;
                status = timeout ? 504 : 502;
                outcome = (timeout ? "upstream_timeout:" : "upstream_unreachable:") + e.getCause().getClass().getSimpleName();
                send(ex, status, error(timeout ? "UPSTREAM_TIMEOUT" : "UPSTREAM_UNAVAILABLE", true));
                return;
            }
            if (res.status() == 401 || res.status() == 403) {
                status = 502;
                outcome = "upstream_auth_" + res.status();
                send(ex, 502, error("UPSTREAM_AUTH_FAILED", false)); // relay misconfiguration; never shown as Make's fault
                return;
            }
            byte[] payload = res.body();
            if (payload.length > MAX_RESPONSE_BYTES) {
                status = 502;
                outcome = "upstream_too_large";
                send(ex, 502, error("UPSTREAM_RESPONSE_TOO_LARGE", false));
                return;
            }
            status = res.status();
            outcome = "forwarded";
            var h = ex.getResponseHeaders();
            h.set("Content-Type", res.headers().firstValue("Content-Type").orElse("application/json"));
            res.headers().firstValue("X-Correlation-Id").ifPresent(v -> h.set("X-Correlation-Id", v));
            res.headers().firstValue("X-Engine").ifPresent(v -> h.set("X-Engine", v));
            h.set("X-Relay", "rsfa-end-forms-relay");
            h.set("Cache-Control", "no-store");
            ex.sendResponseHeaders(status, payload.length == 0 ? -1 : payload.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(payload);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            status = 500;
            outcome = "internal_" + e.getClass().getSimpleName();
        } finally {
            LOG.info(String.format("relay correlationId=%s status=%d outcome=%s ms=%d", cid, status, outcome,
                    (System.nanoTime() - started) / 1_000_000));
        }
    }

    /** Header format and freshness, checked before the body is read; null when acceptable. */
    private String checkHeaders(HttpExchange ex) {
        String ts = ex.getRequestHeaders().getFirst("X-RSFA-Timestamp");
        String sig = ex.getRequestHeaders().getFirst("X-RSFA-Signature");
        if (ts == null || sig == null || !ts.matches("[0-9]{1,12}") || !SIGNATURE.matcher(sig).matches()) {
            return "MISSING_SIGNATURE";
        }
        long now = clock.instant().getEpochSecond();
        return Math.abs(now - Long.parseLong(ts)) > MAX_SKEW_SECONDS ? "STALE_TIMESTAMP" : null;
    }

    /** Null when the signature matches the exact received bytes (headers already checked by {@link #checkHeaders}). */
    private String authenticate(HttpExchange ex, byte[] body) {
        String ts = ex.getRequestHeaders().getFirst("X-RSFA-Timestamp");
        String sig = ex.getRequestHeaders().getFirst("X-RSFA-Signature");
        byte[] given = sig.substring(3).toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        boolean ok = false;
        for (byte[] secret : secrets) {
            ok |= MessageDigest.isEqual(hmacHex(secret, ts, body).getBytes(StandardCharsets.UTF_8), given);
        }
        return ok ? null : "INVALID_SIGNATURE";
    }

    /** Upstream answer, fully read (bounded) inside the overall deadline. */
    private record Upstream(int status, java.net.http.HttpHeaders headers, byte[] body) {
    }

    private Upstream exchange(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<InputStream> r = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = r.body()) {
            if (r.statusCode() == 401 || r.statusCode() == 403) {
                return new Upstream(r.statusCode(), r.headers(), new byte[0]);
            }
            return new Upstream(r.statusCode(), r.headers(), in.readNBytes((int) MAX_RESPONSE_BYTES + 1));
        }
    }

    static String sha256Hex(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Lowercase hex HMAC-SHA256 of {@code timestamp + "." + body}; the same string Make must produce. */
    public static String hmacHex(byte[] secret, String timestamp, byte[] body) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(secret, "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private ObjectNode error(String code, boolean retryable) {
        return json.createObjectNode().put("status", "error").put("code", code).put("retryable", retryable)
                .put("source", "relay");
    }

    private void send(HttpExchange ex, int status, JsonNode body) throws IOException {
        byte[] b = json.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.getResponseHeaders().set("X-Relay", "rsfa-end-forms-relay");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    private static byte[] readLimited(InputStream in) throws IOException {
        byte[] buf = in.readNBytes(MAX_BODY_BYTES + 1);
        return buf.length > MAX_BODY_BYTES ? null : buf;
    }

    /** Fixed-window limiter per instance (max 2 instances on Cloud Run gives a hard ceiling of twice this rate). */
    static final class RateLimiter {
        private final int perMinute;
        private final Clock clock;
        private long window = -1;
        private int count;

        RateLimiter(int perMinute, Clock clock) {
            this.perMinute = perMinute;
            this.clock = clock;
        }

        synchronized boolean tryAcquire() {
            long w = clock.instant().getEpochSecond() / 60;
            if (w != window) {
                window = w;
                count = 0;
            }
            return ++count <= perMinute;
        }
    }
}
