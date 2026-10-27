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

import java.io.File;
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
        String host = env.getOrDefault("ENGINE_BIND", "127.0.0.1");
        int port = Integer.parseInt(env.getOrDefault("PORT", "8080"));
        String apiKey = env.get("ENGINE_API_KEY");
        ApiServer.AuthMode auth = ApiServer.AuthMode.valueOf(
                env.getOrDefault("ENGINE_AUTH", apiKey != null ? "API_KEY" : "NONE"));
        ApiServer.requirePlatformForIam(auth, env);
        TemplateCatalog catalog = TemplateCatalog.load(Path.of(env.getOrDefault("ENGINE_MAPPINGS_DIR", "mappings")),
                dirs(env.getOrDefault("ENGINE_TEMPLATE_DIRS", ".local/generated/templates")));
        ApiServer server = new ApiServer(catalog, new FormFiller(), auth, apiKey);
        int bound = server.start(host, port);
        System.err.printf("end-forms-engine listening on %s:%d auth=%s templates=%d%n", host, bound, auth,
                catalog.manifests().size());
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        Thread.currentThread().join();
        return 0;
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
                  serve   (env: PORT, ENGINE_BIND, ENGINE_AUTH, ENGINE_API_KEY, ENGINE_MAPPINGS_DIR, ENGINE_TEMPLATE_DIRS)""");
        return 1;
    }
}
