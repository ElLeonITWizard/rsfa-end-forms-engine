package nz.co.rsfa.endforms.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Development storage for the administration area (D-038): a directory, by default under the git-ignored
 * {@code .local/}. Production storage is designed in docs/13 (Cloud Storage); nothing here is deployed.
 *
 * <pre>
 * templates/&lt;sha256&gt;.pdf            uploaded blank templates (validated; never customer documents)
 * templates/&lt;sha256&gt;.json           metadata: bank, name, version, original file name, upload time, findings
 * drafts/&lt;id&gt;/state.json            status, current version, approval record, last successful test
 * drafts/&lt;id&gt;/v&lt;n&gt;.json              immutable mapping versions
 * drafts/&lt;id&gt;/audit.jsonl           append-only audit trail (who, when, what; field names only, never values)
 * </pre>
 *
 * Status workflow: {@code draft} → {@code ready-for-review} → {@code approved} (needs a human approval record and a
 * passing test of that exact version). Saving a new version always returns the draft to {@code draft}. There is no
 * publish operation: deployment stays the reviewed allowlist + deployment procedure (docs/04).
 */
public final class Workspace {

    public static final Set<String> STATUSES = Set.of("draft", "ready-for-review", "approved");
    private static final Pattern SHA = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern DRAFT_ID = Pattern.compile("d-[0-9a-f]{12}");
    private final Path root;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final SecureRandom random = new SecureRandom();

    public Workspace(Path root, Clock clock) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        this.clock = clock;
        Files.createDirectories(this.root.resolve("templates"));
        Files.createDirectories(this.root.resolve("drafts"));
    }

    public static class Conflict extends Exception {
        public Conflict(String message) {
            super(message);
        }
    }

    public static class Refused extends Exception {
        public final String code;

        public Refused(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    // ------------------------------------------------------------------------------------------- templates

    public synchronized ObjectNode saveTemplate(byte[] pdf, TemplateAnalysis.Report report, String fileName, String bank,
                                                String templateName, String templateVersion, String actor) throws IOException {
        Path pdfPath = root.resolve("templates").resolve(report.sha256() + ".pdf");
        if (!Files.exists(pdfPath)) {
            write(pdfPath, pdf);
        }
        ObjectNode meta = json.createObjectNode();
        meta.put("sha256", report.sha256()).put("fileName", safeText(fileName, 120)).put("bank", safeText(bank, 40))
                .put("templateName", safeText(templateName, 120)).put("templateVersion", safeText(templateVersion, 40))
                .put("bytes", pdf.length).put("pages", report.pages()).put("fieldCount", report.fields().size())
                .put("encrypted", report.encrypted()).put("uploadedAt", clock.instant().toString())
                .put("uploadedBy", safeText(actor, 80));
        meta.set("findings", json.valueToTree(report.findings()));
        write(root.resolve("templates").resolve(report.sha256() + ".json"), json.writeValueAsBytes(meta));
        return meta;
    }

    public Optional<byte[]> templateBytes(String sha) throws IOException {
        if (sha == null || !SHA.matcher(sha).matches()) {
            return Optional.empty();
        }
        Path p = root.resolve("templates").resolve(sha + ".pdf");
        return Files.isRegularFile(p) ? Optional.of(Files.readAllBytes(p)) : Optional.empty();
    }

    public Optional<ObjectNode> templateMeta(String sha) throws IOException {
        if (sha == null || !SHA.matcher(sha).matches()) {
            return Optional.empty();
        }
        Path p = root.resolve("templates").resolve(sha + ".json");
        return Files.isRegularFile(p) ? Optional.of((ObjectNode) json.readTree(p.toFile())) : Optional.empty();
    }

    public List<ObjectNode> templates() throws IOException {
        List<ObjectNode> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(root.resolve("templates"))) {
            for (Path p : s.filter(f -> f.getFileName().toString().endsWith(".json")).sorted().toList()) {
                out.add((ObjectNode) json.readTree(p.toFile()));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------- drafts

    public synchronized ObjectNode createDraft(String templateSha, String templateId, String templateVersion,
                                               JsonNode initialMapping, String actor, String origin) throws IOException, Refused {
        if (templateMeta(templateSha).isEmpty()) {
            throw new Refused("UNKNOWN_TEMPLATE", "upload the template first");
        }
        if (!templateId.matches("[A-Z0-9_]{3,60}") || !templateVersion.matches("[A-Za-z0-9._-]{1,30}")) {
            throw new Refused("INVALID_ID", "templateId: A-Z, 0-9 and _ (3-60); version: letters, digits, . _ - (1-30)");
        }
        String id;
        do {
            byte[] b = new byte[6];
            random.nextBytes(b);
            id = "d-" + HexFormat.of().formatHex(b);
        } while (Files.exists(draftDir(id)));
        Files.createDirectories(draftDir(id));
        ObjectNode state = json.createObjectNode();
        state.put("draftId", id).put("templateSha256", templateSha).put("templateId", templateId)
                .put("templateVersion", templateVersion).put("status", "draft").put("version", 1)
                .put("createdAt", clock.instant().toString()).put("createdBy", safeText(actor, 80))
                .put("origin", safeText(origin, 120));
        ObjectNode mapping = normaliseMapping(initialMapping);
        write(draftDir(id).resolve("v1.json"), json.writeValueAsBytes(mapping));
        write(draftDir(id).resolve("state.json"), json.writeValueAsBytes(state));
        audit(id, actor, "create", 1, changes(json.createObjectNode().putArray("fields"), mapping.path("fields")),
                "origin: " + safeText(origin, 120));
        return state;
    }

    public List<ObjectNode> drafts() throws IOException {
        List<ObjectNode> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(root.resolve("drafts"))) {
            for (Path d : s.filter(Files::isDirectory).sorted().toList()) {
                Path st = d.resolve("state.json");
                if (Files.isRegularFile(st)) {
                    out.add((ObjectNode) json.readTree(st.toFile()));
                }
            }
        }
        return out;
    }

    public Optional<ObjectNode> state(String id) throws IOException {
        if (id == null || !DRAFT_ID.matcher(id).matches() || !Files.isRegularFile(draftDir(id).resolve("state.json"))) {
            return Optional.empty();
        }
        return Optional.of((ObjectNode) json.readTree(draftDir(id).resolve("state.json").toFile()));
    }

    public Optional<ObjectNode> mapping(String id, int version) throws IOException {
        if (state(id).isEmpty()) {
            return Optional.empty();
        }
        Path p = draftDir(id).resolve("v" + version + ".json");
        return Files.isRegularFile(p) ? Optional.of((ObjectNode) json.readTree(p.toFile())) : Optional.empty();
    }

    /** Saves a new immutable version. {@code baseVersion} must be the current one (optimistic concurrency). */
    public synchronized ObjectNode save(String id, int baseVersion, JsonNode mapping, String actor, String note)
            throws IOException, Conflict, Refused {
        ObjectNode state = state(id).orElseThrow(() -> new Refused("UNKNOWN_DRAFT", "no such draft"));
        int current = state.path("version").asInt();
        if (baseVersion != current) {
            throw new Conflict("the draft was changed (current version " + current + "); reload before saving");
        }
        ObjectNode next = normaliseMapping(mapping);
        ObjectNode previous = mapping(id, current).orElseThrow();
        ArrayNode changes = changes(previous.path("fields"), next.path("fields"));
        if (changes.isEmpty() && previous.path("validation").equals(next.path("validation"))) {
            return state;
        }
        int v = current + 1;
        write(draftDir(id).resolve("v" + v + ".json"), json.writeValueAsBytes(next));
        String before = state.path("status").asText();
        state.put("version", v).put("status", "draft").put("updatedAt", clock.instant().toString())
                .put("updatedBy", safeText(actor, 80));
        state.remove("approval");
        write(draftDir(id).resolve("state.json"), json.writeValueAsBytes(state));
        audit(id, actor, "save", v, changes, safeText(note, 200)
                + (before.equals("draft") ? "" : " (status " + before + " → draft: a new version needs a new review)"));
        return state;
    }

    /** Records the result of a test generation of a version (codes only). */
    public synchronized void recordTest(String id, int version, boolean ok, int errors, int warnings, String pdfSha,
                                        String actor) throws IOException {
        ObjectNode state = state(id).orElseThrow();
        ObjectNode t = state.putObject("lastTest");
        t.put("version", version).put("ok", ok).put("errors", errors).put("warnings", warnings)
                .put("pdfSha256", pdfSha).put("at", clock.instant().toString());
        write(draftDir(id).resolve("state.json"), json.writeValueAsBytes(state));
        audit(id, actor, "test", version, json.createArrayNode(), (ok ? "generated" : "refused") + ", " + errors
                + " errors, " + warnings + " warnings");
    }

    /**
     * Status change. {@code approved} needs: status ready-for-review, a passing test of the current version, and an
     * approval record typed by a person (approvedBy, approvedOn not in the future, reference). Never set automatically.
     */
    public synchronized ObjectNode setStatus(String id, int baseVersion, String to, JsonNode approval, String actor)
            throws IOException, Conflict, Refused {
        ObjectNode state = state(id).orElseThrow(() -> new Refused("UNKNOWN_DRAFT", "no such draft"));
        if (!STATUSES.contains(to)) {
            throw new Refused("INVALID_STATUS", "status must be draft, ready-for-review or approved; publishing is not "
                    + "part of the administration area");
        }
        int v = state.path("version").asInt();
        if (baseVersion != v) {
            throw new Conflict("the draft was changed (current version " + v + ")");
        }
        String from = state.path("status").asText();
        if (to.equals("approved")) {
            if (!from.equals("ready-for-review")) {
                throw new Refused("NOT_READY", "only a draft marked ready for review can be approved");
            }
            JsonNode t = state.path("lastTest");
            if (t.path("version").asInt() != v || !t.path("ok").asBoolean()) {
                throw new Refused("NOT_TESTED", "run a successful test generation of this version first");
            }
            String by = approval == null ? "" : approval.path("approvedBy").asText("").trim();
            String ref = approval == null ? "" : approval.path("reference").asText("").trim();
            LocalDate on;
            try {
                on = LocalDate.parse(approval == null ? "" : approval.path("approvedOn").asText(""));
            } catch (RuntimeException e) {
                throw new Refused("INVALID_APPROVAL", "approvedOn must be an ISO date");
            }
            if (by.length() < 2 || ref.length() < 3 || on.isAfter(LocalDate.now(clock))) {
                throw new Refused("INVALID_APPROVAL", "approvedBy, approvedOn (not in the future) and reference are required");
            }
            ObjectNode a = state.putObject("approval");
            a.put("approvedBy", safeText(by, 80)).put("approvedOn", on.toString()).put("reference", safeText(ref, 120))
                    .put("version", v).put("recordedBy", safeText(actor, 80)).put("recordedAt", clock.instant().toString())
                    .put("scope", "local development approval of this mapping version; not a deployment approval");
        } else {
            state.remove("approval");
        }
        state.put("status", to);
        write(draftDir(id).resolve("state.json"), json.writeValueAsBytes(state));
        audit(id, actor, "status", v, json.createArrayNode(), from + " → " + to);
        return state;
    }

    public List<JsonNode> audit(String id) throws IOException {
        List<JsonNode> out = new ArrayList<>();
        if (state(id).isEmpty()) {
            return out;
        }
        Path p = draftDir(id).resolve("audit.jsonl");
        if (Files.isRegularFile(p)) {
            for (String line : Files.readAllLines(p)) {
                if (!line.isBlank()) {
                    out.add(json.readTree(line));
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------- internals

    private Path draftDir(String id) {
        return root.resolve("drafts").resolve(id);
    }

    private ObjectNode normaliseMapping(JsonNode in) {
        ObjectNode m = json.createObjectNode();
        ArrayNode fields = m.putArray("fields");
        if (in != null && in.path("fields").isArray()) {
            in.path("fields").forEach(f -> fields.add(f.deepCopy()));
        }
        m.set("validation", in != null && in.path("validation").isObject() ? in.path("validation").deepCopy()
                : json.createObjectNode());
        return m;
    }

    /** Field-level change list: names and change type only, never configuration values. */
    private ArrayNode changes(JsonNode before, JsonNode after) {
        ArrayNode out = json.createArrayNode();
        java.util.Map<String, List<JsonNode>> b = byField(before), a = byField(after);
        Set<String> names = new TreeSet<>(b.keySet());
        names.addAll(a.keySet());
        for (String n : names) {
            String change = !b.containsKey(n) ? "added" : !a.containsKey(n) ? "removed" : b.get(n).equals(a.get(n)) ? null : "modified";
            if (change != null) {
                out.addObject().put("pdfField", n).put("change", change);
            }
        }
        return out;
    }

    private static java.util.Map<String, List<JsonNode>> byField(JsonNode fields) {
        java.util.Map<String, List<JsonNode>> m = new java.util.TreeMap<>();
        if (fields != null && fields.isArray()) {
            for (Iterator<JsonNode> it = fields.elements(); it.hasNext(); ) {
                JsonNode f = it.next();
                m.computeIfAbsent(f.path("pdfField").asText(), k -> new ArrayList<>()).add(f);
            }
        }
        return m;
    }

    private void audit(String id, String actor, String action, int version, ArrayNode changes, String note) throws IOException {
        ObjectNode e = json.createObjectNode();
        e.put("at", clock.instant().toString()).put("actor", safeText(actor, 80)).put("action", action)
                .put("version", version);
        e.set("changes", changes);
        if (note != null && !note.isBlank()) {
            e.put("note", note);
        }
        Files.writeString(draftDir(id).resolve("audit.jsonl"), new ObjectMapper().writeValueAsString(e) + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static void write(Path target, byte[] bytes) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, bytes);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static String safeText(String s, int max) {
        String t = s == null ? "" : s.replaceAll("[\\p{Cntrl}]", " ").trim();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
