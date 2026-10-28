package nz.co.rsfa.endforms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import nz.co.rsfa.endforms.catalog.TemplateCatalog;
import nz.co.rsfa.endforms.fill.FillResult;
import nz.co.rsfa.endforms.fill.FormFiller;
import nz.co.rsfa.endforms.http.ApiServer;
import nz.co.rsfa.endforms.mapping.Manifest;
import nz.co.rsfa.endforms.pdf.TemplateInspector;
import nz.co.rsfa.endforms.relay.GcsReplayStore;
import nz.co.rsfa.endforms.relay.MetadataAccessTokenSource;
import nz.co.rsfa.endforms.relay.MetadataIdTokenSource;
import nz.co.rsfa.endforms.relay.ReplayStore;
import nz.co.rsfa.endforms.relay.RelayServer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CLI entry point.
 *
 * <pre>
 *   inspect  --pdf FILE
 *   fill     --manifest FILE --data FILE --out FILE [--templates DIR[:DIR]] [--today YYYY-MM-DD]
 *   render   --pdf FILE --out-dir DIR [--dpi N]   (PNG per page, for visual verification only)
 *   example-form [--out FILE]                    (fictional "Example Bank" form for admin demos and tests)
 *   serve    (configured by environment; see README)
 * </pre>
 * Exit codes: 0 ok, 1 usage, 2 I/O, 3 validation failed.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        System.exit(run(args));
    }

    static int run(String[] args) throws Exception {
        if (args.length == 0) {
            return usage();
        }
        Map<String, String> opt = options(Arrays.copyOfRange(args, 1, args.length));
        ObjectMapper json = TemplateCatalog.json().enable(SerializationFeature.INDENT_OUTPUT);
        switch (args[0]) {
            case "inspect" -> {
                if (!opt.containsKey("pdf")) return usage();
                System.out.println(json.writeValueAsString(TemplateInspector.inspect(Files.readAllBytes(Path.of(opt.get("pdf"))))));
                return 0;
            }
            case "fill" -> {
                return fill(opt, json);
            }
            case "example-form" -> { // fictional bank form for admin demos and browser tests (no real lender content)
                Path out = Path.of(opt.getOrDefault("out", "example-bank-form.pdf"));
                Files.write(out, nz.co.rsfa.endforms.admin.ExampleForm.build());
                System.out.println(out);
                return 0;
            }
            case "render" -> {
                if (!opt.containsKey("pdf") || !opt.containsKey("out-dir")) return usage();
                return render(Path.of(opt.get("pdf")), Path.of(opt.get("out-dir")), Float.parseFloat(opt.getOrDefault("dpi", "110")));
            }
            case "serve" -> {
                return serve();
            }
            default -> {
                return usage();
            }
        }
    }

    private static int fill(Map<String, String> opt, ObjectMapper json) throws Exception {
        if (!opt.containsKey("manifest") || !opt.containsKey("data") || !opt.containsKey("out")) {
            return usage();
        }
        Path manifestPath = Path.of(opt.get("manifest"));
        Manifest manifest = TemplateCatalog.readManifest(manifestPath);
        List<Path> dirs = dirs(opt.getOrDefault("templates", ".local/generated/templates"));
        TemplateCatalog catalog = TemplateCatalog.load(manifestPath.getParent(), dirs);
        byte[] template = catalog.templateBytes(manifest).orElse(null);
        if (template == null) {
            System.err.println("error: template " + manifest.templateFile() + " not found in " + dirs);
            return 2;
        }
        Path out = Path.of(opt.get("out")).toAbsolutePath().normalize();
        for (Path d : dirs) {
            if (out.startsWith(d.toAbsolutePath().normalize())) {
                System.err.println("error: refusing to write output inside a template directory");
                return 1;
            }
        }
        JsonNode data = json.readTree(Path.of(opt.get("data")).toFile());
        String today = opt.get("today");
        if (data != null && data.has("data") && data.get("data").isObject()) {
            // accept a full /generate request body; honour its pinned "today" like the HTTP API does
            if (today == null && data.hasNonNull("today")) {
                today = data.get("today").asText();
            }
            data = data.get("data");
        }
        String pinned = today;
        FormFiller filler = pinned != null
                ? new FormFiller(zone -> LocalDate.parse(pinned))
                : new FormFiller();
        FillResult r = filler.fill(template, manifest, data);
        if (r.ok()) {
            Files.createDirectories(out.getParent());
            Files.write(out, r.pdf());
        }
        Map<String, Object> report = Map.of(
                "ok", r.ok(),
                "template", manifest.key(),
                "output", r.ok() ? out.toString() : "",
                "filled", r.filled(),
                "skipped", r.skipped(),
                "issues", r.issues());
        System.out.println(json.writeValueAsString(report));
        return r.ok() ? 0 : 3;
    }

    private static int render(Path pdf, Path outDir, float dpi) throws Exception {
        Files.createDirectories(outDir);
        String stem = pdf.getFileName().toString().replaceAll("\\.pdf$", "").replaceAll("[^A-Za-z0-9._-]", "_");
        try (var doc = org.apache.pdfbox.Loader.loadPDF(pdf.toFile())) {
            var renderer = new org.apache.pdfbox.rendering.PDFRenderer(doc);
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                Path out = outDir.resolve(stem + "-p" + (i + 1) + ".png");
                javax.imageio.ImageIO.write(renderer.renderImageWithDPI(i, dpi), "png", out.toFile());
                System.out.println(out);
            }
        }
        return 0;
    }

    private static int serve() throws Exception {
        Map<String, String> env = System.getenv();
        String role = roleOf(env);
        if ("relay".equals(role)) {
            return relay(env);
        }
        String host = env.getOrDefault("ENGINE_BIND", "127.0.0.1");
        int port = Integer.parseInt(env.getOrDefault("PORT", "8080"));
        String apiKey = env.get("ENGINE_API_KEY");
        ApiServer.AuthMode auth = ApiServer.AuthMode.valueOf(
                env.getOrDefault("ENGINE_AUTH", apiKey != null ? "API_KEY" : "NONE"));
        ApiServer.requirePlatformForIam(auth, env);
        TemplateCatalog catalog = TemplateCatalog.load(Path.of(env.getOrDefault("ENGINE_MAPPINGS_DIR", "mappings")),
                dirs(env.getOrDefault("ENGINE_TEMPLATE_DIRS", ".local/generated/templates")));
        String hmac = env.get("ENGINE_HMAC_SECRET"); // optional request signature for /v1 (D-026); never logged
        ApiServer server = new ApiServer(catalog, new FormFiller(), auth, apiKey,
                hmac == null || hmac.isBlank() ? null : hmac, java.time.Clock.systemUTC());
        if ("local".equals(env.get("ENGINE_ADMIN"))) {
            server.withAdmin(admin(env, host, catalog));
        } else if (env.get("ENGINE_ADMIN") != null) {
            throw new IllegalStateException("ENGINE_ADMIN accepts only 'local'");
        }
        int bound = server.start(host, port);
        System.err.printf("end-forms-engine listening on %s:%d auth=%s templates=%d%n", host, bound, auth,
                catalog.manifests().size());
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        Thread.currentThread().join();
        return 0;
    }

    /**
     * Authenticated intermediary for Make (D-027): verifies the HMAC signature and calls the private engine with the
     * service account's Google ID token from the metadata server. Env: RELAY_TARGET_URL, RELAY_HMAC_SECRETS (1-2,
     * comma-separated, from Secret Manager), RELAY_RATE_PER_MINUTE (default 30), PORT, ENGINE_BIND,
     * RELAY_REPLAY_STORE ({@code gcs} with RELAY_REPLAY_BUCKET, or {@code memory}). On Cloud Run the per-instance
     * {@code memory} store is refused unless RELAY_SINGLE_INSTANCE=true acknowledges {@code --max-instances=1}.
     */
    private static int relay(Map<String, String> env) throws Exception {
        String target = env.get("RELAY_TARGET_URL");
        String secrets = env.get("RELAY_HMAC_SECRETS");
        if (target == null || secrets == null) {
            System.err.println("relay needs RELAY_TARGET_URL and RELAY_HMAC_SECRETS");
            return 2;
        }
        RelayServer.IdTokenSource tokens;
        String testToken = env.get("RELAY_TEST_ID_TOKEN");
        if (testToken != null) {
            if (env.get("K_SERVICE") != null) {
                throw new IllegalStateException("RELAY_TEST_ID_TOKEN is for local tests only and is refused on Cloud Run");
            }
            tokens = () -> testToken;
        } else {
            tokens = new MetadataIdTokenSource(target, java.time.Clock.systemUTC());
        }
        ReplayStore replay = replayStore(env);
        // Bounds how long a client may take to send its request (slow-body connections cannot hold the few slots).
        if (System.getProperty("sun.net.httpserver.maxReqTime") == null) {
            System.setProperty("sun.net.httpserver.maxReqTime", env.getOrDefault("RELAY_MAX_REQUEST_SECONDS", "15"));
        }
        RelayServer relay = new RelayServer(java.net.URI.create(target),
                Arrays.stream(secrets.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList(), tokens,
                java.time.Clock.systemUTC(), Integer.parseInt(env.getOrDefault("RELAY_RATE_PER_MINUTE", "30")), replay);
        String host = env.getOrDefault("ENGINE_BIND", "127.0.0.1");
        int bound = relay.start(host, Integer.parseInt(env.getOrDefault("PORT", "8080")));
        System.err.printf("end-forms relay listening on %s:%d%n", host, bound);
        Runtime.getRuntime().addShutdownHook(new Thread(relay::stop));
        Thread.currentThread().join();
        return 0;
    }

    /**
     * {@code relay} or {@code engine} (default). Fails closed on a misspelt role, and refuses to run the engine on a
     * Cloud Run service named like the relay: a forgotten ENGINE_ROLE there would expose the engine publicly.
     */
    /**
     * Local administration area (D-038): loopback only, never on Cloud Run. Env: ADMIN_WORKSPACE (default
     * .local/admin-workspace), ADMIN_SCHEMA (Monday column export, default the newest .local/reference/masterboard-columns-*.json),
     * ADMIN_ACTOR (name for the audit trail), ADMIN_REPO_ROOT (default ".").
     */
    static nz.co.rsfa.endforms.admin.AdminApi admin(Map<String, String> env, String host, TemplateCatalog catalog) throws IOException {
        nz.co.rsfa.endforms.admin.AdminApi.requireLocal(host, env);
        Path schemaFile = env.containsKey("ADMIN_SCHEMA") ? Path.of(env.get("ADMIN_SCHEMA")) : newestSchema(Path.of(".local/reference"));
        nz.co.rsfa.endforms.admin.MondaySchema schema = schemaFile == null ? nz.co.rsfa.endforms.admin.MondaySchema.empty()
                : nz.co.rsfa.endforms.admin.MondaySchema.load(schemaFile);
        java.time.Clock clock = java.time.Clock.systemUTC();
        System.err.printf("admin area enabled (local only): workspace=%s schema=%s columns=%d%n",
                env.getOrDefault("ADMIN_WORKSPACE", ".local/admin-workspace"), schema.source(), schema.columns().size());
        return new nz.co.rsfa.endforms.admin.AdminApi(
                new nz.co.rsfa.endforms.admin.Workspace(Path.of(env.getOrDefault("ADMIN_WORKSPACE", ".local/admin-workspace")), clock),
                catalog, schema, Path.of(env.getOrDefault("ADMIN_REPO_ROOT", ".")).toAbsolutePath().normalize(),
                new nz.co.rsfa.endforms.admin.MockSuggestionProvider(clock), clock, env.getOrDefault("ADMIN_ACTOR", "local-admin"));
    }

    private static Path newestSchema(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return null;
        }
        try (var s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().matches("masterboard-columns-.*\\.json")).sorted()
                    .reduce((a, b) -> b).orElse(null);
        }
    }

    static String roleOf(Map<String, String> env) {
        String role = env.getOrDefault("ENGINE_ROLE", "engine").trim();
        if (!role.equals("engine") && !role.equals("relay")) {
            throw new IllegalStateException("ENGINE_ROLE must be 'engine' or 'relay'");
        }
        String service = env.getOrDefault("K_SERVICE", "");
        if (role.equals("engine") && service.contains("relay")) {
            throw new IllegalStateException("Cloud Run service '" + service + "' looks like the relay but ENGINE_ROLE is not "
                    + "'relay'; refusing to start the engine there");
        }
        return role;
    }

    static ReplayStore replayStore(Map<String, String> env) {
        String kind = env.getOrDefault("RELAY_REPLAY_STORE", env.get("K_SERVICE") != null ? "gcs" : "memory");
        switch (kind) {
            case "gcs" -> {
                return new GcsReplayStore(java.net.URI.create("https://storage.googleapis.com"),
                        env.get("RELAY_REPLAY_BUCKET"), new MetadataAccessTokenSource(java.time.Clock.systemUTC()));
            }
            case "memory" -> {
                if (env.get("K_SERVICE") != null && !"true".equals(env.get("RELAY_SINGLE_INSTANCE"))) {
                    throw new IllegalStateException("the memory replay store detects replays only within one instance; on "
                            + "Cloud Run use RELAY_REPLAY_STORE=gcs or set RELAY_SINGLE_INSTANCE=true with --max-instances=1");
                }
                return new ReplayStore.Memory(10_000);
            }
            default -> throw new IllegalArgumentException("RELAY_REPLAY_STORE must be gcs or memory");
        }
    }

    private static List<Path> dirs(String spec) {
        return Arrays.stream(spec.split(File.pathSeparator)).filter(s -> !s.isBlank()).map(Path::of).toList();
    }

    private static Map<String, String> options(String[] a) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < a.length; i += 2) {
            if (a[i].startsWith("--")) {
                m.put(a[i].substring(2), a[i + 1]);
            }
        }
        return m;
    }

    private static int usage() {
        System.err.println("""
                usage:
                  inspect --pdf FILE
                  fill --manifest FILE --data FILE --out FILE [--templates DIR[:DIR]] [--today YYYY-MM-DD]
                  render --pdf FILE --out-dir DIR [--dpi N]
                  serve   (env: PORT, ENGINE_BIND, ENGINE_AUTH, ENGINE_API_KEY, ENGINE_HMAC_SECRET, ENGINE_MAPPINGS_DIR, ENGINE_TEMPLATE_DIRS)""");
        return 1;
    }
}
