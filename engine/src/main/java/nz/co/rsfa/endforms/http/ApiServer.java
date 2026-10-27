package nz.co.rsfa.endforms.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import nz.co.rsfa.endforms.catalog.TemplateCatalog;
import nz.co.rsfa.endforms.fill.FillResult;
import nz.co.rsfa.endforms.fill.FormFiller;
import nz.co.rsfa.endforms.fill.Issue;
import nz.co.rsfa.endforms.mapping.Manifest;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Minimal HTTP boundary: {@code GET /health}, {@code POST /generate} and the static demo page ({@code GET /}, D-020).
 *
 * <p>Never logs request bodies, data values or PDFs: one line per request with ids, codes and counts.
 */
public final class ApiServer {

    private static final Logger LOG = Logger.getLogger(ApiServer.class.getName());
    static final int MAX_BODY_BYTES = 1024 * 1024;
    /** Identifies how PDFs are produced: version-controlled field mappings, PDFBox, no AI/LLM at runtime. */
    static final String ENGINE_ID = "rsfa-end-forms-engine; deterministic-mapping; pdfbox; no-ai";
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern TEMPLATE_PART = Pattern.compile("[A-Za-z0-9._-]{1,80}");

    /** How callers are authenticated. */
    public enum AuthMode {
        /** Shared bearer key from the environment (local/dev, or behind a secret manager). */
        API_KEY,
        /** Rely on the platform (Cloud Run IAM, --no-allow-unauthenticated) to authenticate callers. */
        PLATFORM_IAM,
        /** No authentication: only permitted when bound to loopback. */
        NONE
    }

    private final TemplateCatalog catalog;
    private final FormFiller filler;
    private final AuthMode auth;
    private final byte[] apiKey;
    private final ObjectMapper json = TemplateCatalog.json();
    private HttpServer server;

    public ApiServer(TemplateCatalog catalog, FormFiller filler, AuthMode auth, String apiKey) {
        this.catalog = catalog;
        this.filler = filler;
        this.auth = auth;
        this.apiKey = apiKey == null ? null : apiKey.getBytes(StandardCharsets.UTF_8);
        if (auth == AuthMode.API_KEY && (apiKey == null || apiKey.length() < 32)) {
            throw new IllegalArgumentException("API_KEY auth requires ENGINE_API_KEY of at least 32 characters");
        }
    }

    /**
     * PLATFORM_IAM trusts Cloud Run's IAM invoker check, which only exists on Cloud Run. Cloud Run always sets
     * K_SERVICE; without it the service would be unauthenticated, so startup is refused.
     */
    public static void requirePlatformForIam(AuthMode auth, java.util.Map<String, String> env) {
        if (auth == AuthMode.PLATFORM_IAM && (env.get("K_SERVICE") == null || env.get("K_SERVICE").isBlank())) {
            throw new IllegalStateException("ENGINE_AUTH=PLATFORM_IAM is only allowed on Cloud Run (K_SERVICE not set); "
                    + "use API_KEY locally");
        }
    }

    public int start(String host, int port) throws IOException {
        InetSocketAddress addr = new InetSocketAddress(host, port);
        if (auth == AuthMode.NONE && !addr.getAddress().isLoopbackAddress()) {
            throw new IllegalStateException("refusing to serve unauthenticated on a non-loopback address");
        }
        server = HttpServer.create(addr, 0);
        server.createContext("/health", this::health);
        server.createContext("/generate", this::generate);
        server.createContext("/", this::ui);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        return server.getAddress().getPort();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** Demo UI: a fixed allowlist of bundled static files. Same authentication as the API; no data, no secrets. */
    private static final java.util.Map<String, String[]> UI_FILES = java.util.Map.of(
            "/", new String[] {"/ui/index.html", "text/html; charset=utf-8"},
            "/ui/app.css", new String[] {"/ui/app.css", "text/css; charset=utf-8"},
            "/ui/app.js", new String[] {"/ui/app.js", "text/javascript; charset=utf-8"},
            "/ui/forms.json", new String[] {"/ui/forms.json", "application/json; charset=utf-8"});

    private void ui(HttpExchange ex) throws IOException {
        try (ex) {
            String[] file = UI_FILES.get(ex.getRequestURI().getPath());
            if (file == null) {
                sendJson(ex, 404, error(null, "NOT_FOUND", List.of()));
                return;
            }
            if (!"GET".equals(ex.getRequestMethod())) {
                sendJson(ex, 405, error(null, "METHOD_NOT_ALLOWED", List.of()));
                return;
            }
            if (!authorised(ex)) {
                sendJson(ex, 401, error(null, "UNAUTHORISED", List.of()));
                return;
            }
            byte[] body;
            try (InputStream in = ApiServer.class.getResourceAsStream(file[0])) {
                if (in == null) {
                    sendJson(ex, 404, error(null, "NOT_FOUND", List.of()));
                    return;
                }
                body = in.readAllBytes();
            }
            var h = ex.getResponseHeaders();
            h.set("Content-Type", file[1]);
            h.set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
                    + "frame-src blob:; object-src blob:; base-uri 'none'; form-action 'none'; frame-ancestors 'none'");
            h.set("X-Content-Type-Options", "nosniff");
            h.set("Referrer-Policy", "no-referrer");
            h.set("Cache-Control", "no-store");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        }
    }

    private void health(HttpExchange ex) throws IOException {
        try (ex) {
            if (!"GET".equals(ex.getRequestMethod())) {
                sendJson(ex, 405, error(null, "METHOD_NOT_ALLOWED", List.of()));
                return;
            }
            ObjectNode n = json.createObjectNode().put("status", "ok").put("templates", catalog.manifests().size());
            sendJson(ex, 200, n);
        }
    }

    private void generate(HttpExchange ex) throws IOException {
        long started = System.nanoTime();
        String requestId = null;
        String templateKey = "-";
        int status = 500;
        int filled = 0;
        int warnings = 0;
        try (ex) {
            if (!"POST".equals(ex.getRequestMethod())) {
                status = 405;
                sendJson(ex, status, error(null, "METHOD_NOT_ALLOWED", List.of()));
                return;
            }
            if (!authorised(ex)) {
                status = 401;
                sendJson(ex, status, error(null, "UNAUTHORISED", List.of()));
                return;
            }
            Optional<byte[]> body = readLimited(ex.getRequestBody());
            if (body.isEmpty()) {
                status = 413;
                sendJson(ex, status, error(null, "PAYLOAD_TOO_LARGE", List.of()));
                return;
            }
            JsonNode req;
            try {
                req = json.readTree(body.get());
            } catch (IOException e) {
                status = 400;
                sendJson(ex, status, error(null, "INVALID_JSON", List.of()));
                return;
            }
            requestId = text(req, "requestId");
            if (requestId == null) {
                requestId = UUID.randomUUID().toString();
            }
            String templateId = text(req, "templateId");
            String templateVersion = text(req, "templateVersion");
            JsonNode data = req == null ? null : req.get("data");
            if (!REQUEST_ID.matcher(requestId).matches() || templateId == null || templateVersion == null
                    || !TEMPLATE_PART.matcher(templateId).matches() || !TEMPLATE_PART.matcher(templateVersion).matches()
                    || data == null || !data.isObject()) {
                status = 400;
                sendJson(ex, status, error(REQUEST_ID.matcher(requestId).matches() ? requestId : null, "INVALID_REQUEST", List.of()));
                return;
            }
            templateKey = templateId + "@" + templateVersion;
            FormFiller f = filler;
            String today = text(req, "today");
            if (today != null) {
                try {
                    LocalDate pinned = LocalDate.parse(today);
                    f = new FormFiller(zone -> pinned);
                } catch (DateTimeParseException e) {
                    status = 400;
                    sendJson(ex, status, error(requestId, "INVALID_REQUEST", List.of()));
                    return;
                }
            }
            Optional<Manifest> manifest = catalog.manifest(templateId, templateVersion);
            if (manifest.isEmpty()) {
                status = 404;
                sendJson(ex, status, error(requestId, "UNKNOWN_TEMPLATE", List.of()));
                return;
            }
            Optional<byte[]> template = catalog.templateBytes(manifest.get());
            if (template.isEmpty()) {
                status = 503;
                sendJson(ex, status, error(requestId, "TEMPLATE_UNAVAILABLE", List.of()));
                return;
            }
            FillResult result = f.fill(template.get(), manifest.get(), data);
            filled = result.filled().size();
            warnings = (int) result.issues().stream().filter(i -> i.severity() == Issue.Severity.WARNING).count();
            if (!result.ok()) {
                status = 422;
                sendJson(ex, status, error(requestId, "VALIDATION_FAILED", result.issues()));
                return;
            }
            status = 200;
            String filename = (templateId + "_" + requestId + ".pdf").replaceAll("[^A-Za-z0-9._-]", "_");
            ex.getResponseHeaders().set("Content-Type", "application/pdf");
            ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + filename + "\"");
            ex.getResponseHeaders().set("X-Request-Id", requestId);
            ex.getResponseHeaders().set("X-Fields-Filled", Integer.toString(filled));
            ex.getResponseHeaders().set("X-Fill-Warnings", Integer.toString(warnings));
            ex.getResponseHeaders().set("X-Template", templateKey);
            ex.getResponseHeaders().set("X-Engine", ENGINE_ID);
            ex.sendResponseHeaders(200, result.pdf().length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(result.pdf());
            }
        } catch (RuntimeException e) {
            status = 500;
            LOG.warning("generate failed requestId=" + requestId + " error=" + e.getClass().getSimpleName());
            sendJson(ex, status, error(requestId, "INTERNAL_ERROR", List.of()));
        } finally {
            LOG.info(String.format("generate requestId=%s template=%s status=%d filled=%d warnings=%d ms=%d",
                    requestId, templateKey, status, filled, warnings, (System.nanoTime() - started) / 1_000_000));
        }
    }

    private boolean authorised(HttpExchange ex) {
        if (auth != AuthMode.API_KEY) {
            return true;
        }
        String h = ex.getRequestHeaders().getFirst("Authorization");
        if (h == null || !h.startsWith("Bearer ")) {
            return false;
        }
        return MessageDigest.isEqual(apiKey, h.substring(7).getBytes(StandardCharsets.UTF_8));
    }

    private static Optional<byte[]> readLimited(InputStream in) throws IOException {
        byte[] buf = in.readNBytes(MAX_BODY_BYTES + 1);
        return buf.length > MAX_BODY_BYTES ? Optional.empty() : Optional.of(buf);
    }

    private ObjectNode error(String requestId, String code, List<Issue> issues) {
        ObjectNode n = json.createObjectNode().put("status", "error").put("code", code);
        if (requestId != null) {
            n.put("requestId", requestId);
        }
        n.set("issues", json.valueToTree(issues));
        return n;
    }

    private void sendJson(HttpExchange ex, int status, JsonNode body) throws IOException {
        byte[] b = json.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        return v == null || !v.isTextual() ? null : v.asText();
    }
}
