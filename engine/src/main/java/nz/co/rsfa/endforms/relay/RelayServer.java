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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Authenticated intermediary between Make and the private engine (D-027, docs/07). The only component Make can reach.
 *
 * <ul>
 *   <li>Make authenticates every request with an HMAC-SHA256 signature over {@code timestamp + "." + body}
 *       ({@code X-RSFA-Timestamp}, {@code X-RSFA-Signature: v1=<hex>}); two secrets may be active for rotation.</li>
 *   <li>Replay protection: timestamp within ±{@value #MAX_SKEW_SECONDS}s and each signature accepted once per instance
 *       while it is fresh.</li>
 *   <li>The relay calls the engine with a Google-signed ID token for its own service account (Cloud Run metadata
 *       server), so the engine keeps {@code --no-allow-unauthenticated}; no key file exists anywhere.</li>
 *   <li>Request ≤ 1 MiB, response ≤ 32 MiB, per-instance rate limit, upstream timeout below Cloud Run's 120 s.</li>
 * </ul>
 * Never logs bodies, values, secrets or tokens: one line per request with correlation id, codes and timings.
 */
public final class RelayServer {

    private static final Logger LOG = Logger.getLogger(RelayServer.class.getName());
    static final String PATH = "/v1/masterboard/documents";
    static final int MAX_BODY_BYTES = 1024 * 1024;
    static final long MAX_RESPONSE_BYTES = 32L * 1024 * 1024;
    static final long MAX_SKEW_SECONDS = 300;
    static final Duration UPSTREAM_TIMEOUT = Duration.ofSeconds(110);
    private static final Pattern CORRELATION = Pattern.compile("\"correlationId\"\\s*:\\s*\"([A-Za-z0-9._:-]{8,128})\"");

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
    /** signature -> epoch second after which it may be forgotten (outside the skew window it is refused anyway). */
    private final Map<String, Long> seen = new LinkedHashMap<>(16, 0.75f, false);
    private HttpServer server;

    /**
     * @param target  engine base URL (https, or http on loopback for local tests)
     * @param secrets HMAC secrets, current first; each at least 32 characters (rotation: add the new one, switch Make,
     *                then remove the old one)
     */
    public RelayServer(URI target, List<String> secrets, IdTokenSource tokens, Clock clock, int ratePerMinute) {
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
                send(ex, 200, json.createObjectNode().put("status", "ok").put("role", "relay"));
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
            byte[] body = readLimited(ex.getRequestBody());
            if (body == null) {
                status = 413;
                outcome = "too_large";
                send(ex, 413, error("PAYLOAD_TOO_LARGE", false));
                return;
            }
            var m = CORRELATION.matcher(new String(body, 0, Math.min(body.length, 4096), StandardCharsets.UTF_8));
            cid = m.find() ? m.group(1) : null;
            String auth = authenticate(ex, body);
            if (auth != null) {
                status = "REPLAYED_REQUEST".equals(auth) ? 409 : 401;
                outcome = auth;
                send(ex, status, error(auth, false));
                return;
            }
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
                outcome = "token_unavailable";
                send(ex, 503, error("IDENTITY_TOKEN_UNAVAILABLE", true));
                return;
            }
            HttpRequest.Builder up = HttpRequest.newBuilder(target.resolve(PATH)).timeout(UPSTREAM_TIMEOUT)
                    .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            if (cid != null) {
                up.header("X-Correlation-Id", cid);
            }
            HttpResponse<InputStream> res;
            try {
                res = http.send(up.build(), HttpResponse.BodyHandlers.ofInputStream());
            } catch (HttpTimeoutException e) {
                status = 504;
                outcome = "upstream_timeout";
                send(ex, 504, error("UPSTREAM_TIMEOUT", true));
                return;
            } catch (IOException e) {
                status = 502;
                outcome = "upstream_unreachable";
                send(ex, 502, error("UPSTREAM_UNAVAILABLE", true));
                return;
            }
            if (res.statusCode() == 401 || res.statusCode() == 403) {
                res.body().close();
                status = 502;
                outcome = "upstream_auth_" + res.statusCode();
                send(ex, 502, error("UPSTREAM_AUTH_FAILED", false)); // relay misconfiguration; never shown as Make's fault
                return;
            }
            byte[] payload;
            try (InputStream in = res.body()) {
                payload = in.readNBytes((int) MAX_RESPONSE_BYTES + 1);
            }
            if (payload.length > MAX_RESPONSE_BYTES) {
                status = 502;
                outcome = "upstream_too_large";
                send(ex, 502, error("UPSTREAM_RESPONSE_TOO_LARGE", false));
                return;
            }
            status = res.statusCode();
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

    /** Null when authentic and fresh; otherwise the error code (no detail about which check failed beyond the code). */
    private String authenticate(HttpExchange ex, byte[] body) {
        String ts = ex.getRequestHeaders().getFirst("X-RSFA-Timestamp");
        String sig = ex.getRequestHeaders().getFirst("X-RSFA-Signature");
        if (ts == null || sig == null || !ts.matches("[0-9]{1,12}") || !sig.startsWith("v1=")) {
            return "MISSING_SIGNATURE";
        }
        long now = clock.instant().getEpochSecond();
        if (Math.abs(now - Long.parseLong(ts)) > MAX_SKEW_SECONDS) {
            return "STALE_TIMESTAMP";
        }
        byte[] given = sig.substring(3).toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        boolean ok = false;
        for (byte[] secret : secrets) {
            ok |= MessageDigest.isEqual(hmacHex(secret, ts, body).getBytes(StandardCharsets.UTF_8), given);
        }
        if (!ok) {
            return "INVALID_SIGNATURE";
        }
        synchronized (seen) {
            seen.values().removeIf(exp -> exp < now);
            while (seen.size() >= 10_000) { // bounded memory: the oldest entries are closest to expiry
                seen.remove(seen.keySet().iterator().next());
            }
            if (seen.putIfAbsent(sig, now + 2 * MAX_SKEW_SECONDS) != null) {
                return "REPLAYED_REQUEST";
            }
        }
        return null;
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
