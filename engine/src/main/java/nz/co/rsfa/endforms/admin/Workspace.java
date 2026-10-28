package nz.co.rsfa.endforms.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Storage of the administration area (D-038, D-041) on a write-once {@link ObjectStore}: a local directory in
 * development, a private Cloud Storage bucket on Cloud Run. Every change is a new immutable object; nothing is
 * overwritten or deleted, so several Cloud Run instances can share the bucket safely.
 *
 * <pre>
 * templates/&lt;sha256&gt;.pdf                  uploaded blank templates (validated; never customer documents)
 * templates/&lt;sha256&gt;.json                 metadata: bank, name, version, file name, upload time and user, findings
 * drafts/&lt;id&gt;/events/&lt;000001&gt;.json        event log: create, save (with the mapping version), test, status
 * results/&lt;token&gt;.pdf|.json              synthetic test outputs (deleted after a day by a bucket lifecycle rule)
 * config/monday-schema.json               Monday column schema (uploaded by the deployment, read at start)
 * </pre>
 *
 * <p>Concurrency: an operation reads the draft's events, validates against the folded state (e.g. the expected base
 * version) and creates the next event object with create-if-absent. If another instance created that sequence number
 * first, the operation re-reads and re-validates; a stale base version then fails with {@link Conflict}. The event log
 * is the audit trail (field names and change types only; mapping versions are stored but never logged).
 *
 * <p>Status workflow: {@code draft} → {@code ready-for-review} → {@code approved} (needs a human approval record and a
 * passing test of that exact version). Saving a new version always returns the draft to {@code draft}. There is no
 * publish operation: deployment stays the reviewed allowlist + deployment procedure (docs/04).
 */
public final class Workspace {

    public static final Set<String> STATUSES = Set.of("draft", "ready-for-review", "approved");
    private static final Pattern SHA = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern DRAFT_ID = Pattern.compile("d-[0-9a-f]{12}");
    private static final Pattern TOKEN = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern EVENT = Pattern.compile("drafts/(d-[0-9a-f]{12})/events/(\\d{6})\\.json");
    private static final int RETRIES = 5;
    private final ObjectStore store;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();
    /** Immutable objects only (events, template metadata): safe to cache for the life of the instance. */
    private final Map<String, JsonNode> immutable = new ConcurrentHashMap<>();
    private final Map<String, byte[]> pdfCache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> e) {
            return size() > 16;
        }
    };

    public Workspace(ObjectStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /** Local development storage in a directory. */
    public Workspace(Path root, Clock clock) throws IOException {
        this(new ObjectStore.Local(root), clock);
    }

    public String storageKind() {
        return store.kind();
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

    /** Stores a validated blank template once (by SHA-256). A second upload of the same bytes keeps the first metadata. */
    public ObjectNode saveTemplate(byte[] pdf, TemplateAnalysis.Report report, String fileName, String bank,
                                   String templateName, String templateVersion, String actor) throws IOException {
        String sha = report.sha256();
        store.createIfAbsent("templates/" + sha + ".pdf", pdf, "application/pdf");
        ObjectNode meta = json.createObjectNode();
        meta.put("sha256", sha).put("fileName", safeText(fileName, 120)).put("bank", safeText(bank, 40))
                .put("templateName", safeText(templateName, 120)).put("templateVersion", safeText(templateVersion, 40))
                .put("bytes", pdf.length).put("pages", report.pages()).put("fieldCount", report.fields().size())
                .put("encrypted", report.encrypted()).put("uploadedAt", clock.instant().toString())
                .put("uploadedBy", safeText(actor, 80));
        meta.set("findings", json.valueToTree(report.findings()));
        if (!store.createIfAbsent("templates/" + sha + ".json", json.writeValueAsBytes(meta), "application/json")) {
            return templateMeta(sha).orElseThrow();
        }
        return meta;
    }

    public Optional<byte[]> templateBytes(String sha) throws IOException {
        if (sha == null || !SHA.matcher(sha).matches()) {
            return Optional.empty();
        }
        synchronized (pdfCache) {
            if (pdfCache.containsKey(sha)) {
                return Optional.of(pdfCache.get(sha));
            }
        }
        Optional<byte[]> b = store.get("templates/" + sha + ".pdf");
        b.ifPresent(x -> {
            synchronized (pdfCache) {
                pdfCache.put(sha, x);
            }
        });
        return b;
    }

    public Optional<ObjectNode> templateMeta(String sha) throws IOException {
        if (sha == null || !SHA.matcher(sha).matches()) {
            return Optional.empty();
        }
        return readImmutable("templates/" + sha + ".json").map(n -> (ObjectNode) n.deepCopy());
    }

    public List<ObjectNode> templates() throws IOException {
        List<ObjectNode> out = new ArrayList<>();
        for (String n : store.list("templates/")) {
            if (n.endsWith(".json")) {
                readImmutable(n).ifPresent(x -> out.add((ObjectNode) x.deepCopy()));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------- drafts

    public ObjectNode createDraft(String templateSha, String templateId, String templateVersion,
                                  JsonNode initialMapping, String actor, String origin) throws IOException, Refused {
        if (templateMeta(templateSha).isEmpty()) {
            throw new Refused("UNKNOWN_TEMPLATE", "upload the template first");
        }
        if (!templateId.matches("[A-Z0-9_]{3,60}") || !templateVersion.matches("[A-Za-z0-9._-]{1,30}")) {
            throw new Refused("INVALID_ID", "templateId: A-Z, 0-9 and _ (3-60); version: letters, digits, . _ - (1-30)");
        }
        ObjectNode mapping = normaliseMapping(initialMapping);
        for (int attempt = 0; attempt < RETRIES; attempt++) {
            byte[] b = new byte[6];
            random.nextBytes(b);
            String id = "d-" + HexFormat.of().formatHex(b);
            ObjectNode e = event(1, actor, "create", 1, changes(json.createArrayNode(), mapping.path("fields")),
                    "origin: " + safeText(origin, 120));
            e.put("draftId", id).put("templateSha256", templateSha).put("templateId", templateId)
                    .put("templateVersion", templateVersion).put("origin", safeText(origin, 120));
            e.set("mapping", mapping);
            if (writeEvent(id, 1, e)) {
                return fold(id).orElseThrow().state();
            }
        }
        throw new IOException("could not allocate a draft id");
    }

    public List<ObjectNode> drafts() throws IOException {
        Set<String> ids = new TreeSet<>();
        for (String n : store.list("drafts/")) {
            var m = EVENT.matcher(n);
            if (m.matches()) {
                ids.add(m.group(1));
            }
        }
        List<ObjectNode> out = new ArrayList<>();
        for (String id : ids) {
            fold(id).ifPresent(f -> out.add(f.state()));
        }
        return out;
    }

    public Optional<ObjectNode> state(String id) throws IOException {
        return fold(id).map(Folded::state);
    }

    public Optional<ObjectNode> mapping(String id, int version) throws IOException {
        return fold(id).map(f -> f.mappings().get(version)).map(m -> (ObjectNode) m.deepCopy());
    }

    /** Saves a new immutable version. {@code baseVersion} must be the current one (optimistic concurrency). */
    public ObjectNode save(String id, int baseVersion, JsonNode mapping, String actor, String note)
            throws IOException, Conflict, Refused {
        ObjectNode next = normaliseMapping(mapping);
        return append(id, f -> {
            int current = f.state().path("version").asInt();
            if (baseVersion != current) {
                throw new Conflict("the draft was changed (current version " + current + "); reload before saving");
            }
            JsonNode previous = f.mappings().get(current);
            ArrayNode changes = changes(previous.path("fields"), next.path("fields"));
            if (changes.isEmpty() && previous.path("validation").equals(next.path("validation"))) {
                return null; // nothing to save
            }
            String before = f.state().path("status").asText();
            ObjectNode e = event(f.events() + 1, actor, "save", current + 1, changes, safeText(note, 200)
                    + (before.equals("draft") ? "" : " (status " + before + " → draft: a new version needs a new review)"));
            e.set("mapping", next);
            return e;
        });
    }

    /** Records the result of a test generation of a version (codes only). */
    public void recordTest(String id, int version, boolean ok, int errors, int warnings, String pdfSha, String actor)
            throws IOException {
        try {
            append(id, f -> {
                ObjectNode e = event(f.events() + 1, actor, "test", version, json.createArrayNode(),
                        (ok ? "generated" : "refused") + ", " + errors + " errors, " + warnings + " warnings");
                ObjectNode t = e.putObject("test");
                t.put("version", version).put("ok", ok).put("errors", errors).put("warnings", warnings)
                        .put("pdfSha256", pdfSha).put("at", clock.instant().toString());
                return e;
            });
        } catch (Conflict | Refused e) {
            throw new IOException("could not record the test", e);
        }
    }

    /**
     * Status change. {@code approved} needs: status ready-for-review, a passing test of the current version, and an
     * approval record typed by a person (approvedBy, approvedOn not in the future, reference). Never set automatically.
     * Who may change which status is decided by the caller's role (AdminApi).
     */
    public ObjectNode setStatus(String id, int baseVersion, String to, JsonNode approval, String actor)
            throws IOException, Conflict, Refused {
        if (!STATUSES.contains(to)) {
            throw new Refused("INVALID_STATUS", "status must be draft, ready-for-review or approved; publishing is not "
                    + "part of the administration area");
        }
        return append(id, f -> {
            ObjectNode state = f.state();
            int v = state.path("version").asInt();
            if (baseVersion != v) {
                throw new Conflict("the draft was changed (current version " + v + ")");
            }
            String from = state.path("status").asText();
            ObjectNode e = event(f.events() + 1, actor, "status", v, json.createArrayNode(), from + " → " + to);
            e.put("from", from).put("to", to);
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
                } catch (RuntimeException ex) {
                    throw new Refused("INVALID_APPROVAL", "approvedOn must be an ISO date");
                }
                if (by.length() < 2 || ref.length() < 3 || on.isAfter(LocalDate.now(clock))) {
                    throw new Refused("INVALID_APPROVAL", "approvedBy, approvedOn (not in the future) and reference are required");
                }
                ObjectNode a = e.putObject("approval");
                a.put("approvedBy", safeText(by, 80)).put("approvedOn", on.toString()).put("reference", safeText(ref, 120))
                        .put("version", v).put("recordedBy", safeText(actor, 80)).put("recordedAt", clock.instant().toString())
                        .put("scope", "development approval of this mapping version; not a deployment approval");
            }
            return e;
        });
    }

    public List<JsonNode> audit(String id) throws IOException {
        List<JsonNode> out = new ArrayList<>();
        Optional<Folded> f = fold(id);
        if (f.isEmpty()) {
            return out;
        }
        for (JsonNode e : f.get().log()) {
            ObjectNode a = json.createObjectNode();
            a.put("at", e.path("at").asText()).put("actor", e.path("actor").asText()).put("action", e.path("action").asText())
                    .put("version", e.path("version").asInt());
            a.set("changes", e.path("changes"));
            if (e.hasNonNull("note")) {
                a.put("note", e.path("note").asText());
            }
            out.add(a);
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------- test results

    /** Stores a synthetic test output under a random token (shared by every instance; expires by lifecycle rule). */
    public void saveResult(String token, String templateSha, byte[] pdf) throws IOException {
        if (!TOKEN.matcher(token).matches() || !SHA.matcher(templateSha).matches()) {
            throw new IllegalArgumentException("invalid result token");
        }
        store.createIfAbsent("results/" + token + ".pdf", pdf, "application/pdf");
        store.createIfAbsent("results/" + token + ".json",
                json.writeValueAsBytes(json.createObjectNode().put("templateSha256", templateSha)
                        .put("at", clock.instant().toString())), "application/json");
    }

    public record Result(String templateSha256, byte[] pdf) {
    }

    public Optional<Result> result(String token) throws IOException {
        if (token == null || !TOKEN.matcher(token).matches()) {
            return Optional.empty();
        }
        Optional<byte[]> meta = store.get("results/" + token + ".json");
        Optional<byte[]> pdf = store.get("results/" + token + ".pdf");
        if (meta.isEmpty() || pdf.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Result(json.readTree(meta.get()).path("templateSha256").asText(), pdf.get()));
    }

    /** Raw configuration object (e.g. {@code config/monday-schema.json}), if present. */
    public Optional<byte[]> config(String name) throws IOException {
        return store.get("config/" + name);
    }

    // ------------------------------------------------------------------------------------------- event log

    /** Folded draft: current state, every mapping version, the raw events and their count. */
    private record Folded(ObjectNode state, Map<Integer, JsonNode> mappings, List<JsonNode> log, int events) {
    }

    @FunctionalInterface
    private interface EventMaker {
        /** @return the next event, or null when there is nothing to record */
        ObjectNode make(Folded current) throws Conflict, Refused, IOException;
    }

    private ObjectNode append(String id, EventMaker maker) throws IOException, Conflict, Refused {
        for (int attempt = 0; attempt < RETRIES; attempt++) {
            Folded f = fold(id).orElseThrow(() -> new Refused("UNKNOWN_DRAFT", "no such draft"));
            ObjectNode e = maker.make(f);
            if (e == null) {
                return f.state();
            }
            if (writeEvent(id, f.events() + 1, e)) {
                return fold(id).orElseThrow().state();
            }
            // another instance wrote this sequence number first: re-read and re-validate
        }
        throw new Conflict("the draft is being changed concurrently; reload and try again");
    }

    private boolean writeEvent(String id, int seq, ObjectNode e) throws IOException {
        e.put("seq", seq);
        String name = eventName(id, seq);
        boolean created = store.createIfAbsent(name, json.writeValueAsBytes(e), "application/json");
        if (created) {
            immutable.put(name, e.deepCopy());
        }
        return created;
    }

    private Optional<Folded> fold(String id) throws IOException {
        if (id == null || !DRAFT_ID.matcher(id).matches()) {
            return Optional.empty();
        }
        List<String> names = store.list("drafts/" + id + "/events/").stream().filter(n -> EVENT.matcher(n).matches()).toList();
        if (names.isEmpty()) {
            return Optional.empty();
        }
        ObjectNode state = null;
        Map<Integer, JsonNode> mappings = new HashMap<>();
        List<JsonNode> log = new ArrayList<>();
        int expected = 1;
        for (String n : names) {
            if (!n.equals(eventName(id, expected))) {
                throw new IOException("draft event log has a gap (" + id + ")");
            }
            JsonNode e = readImmutable(n).orElseThrow(() -> new IOException("event vanished"));
            log.add(e);
            if ((state == null) != e.path("action").asText().equals("create")) {
                throw new IOException("draft event log must start with exactly one create (" + id + ")");
            }
            switch (e.path("action").asText()) {
                case "create" -> {
                    state = json.createObjectNode();
                    state.put("draftId", id).put("templateSha256", e.path("templateSha256").asText())
                            .put("templateId", e.path("templateId").asText())
                            .put("templateVersion", e.path("templateVersion").asText()).put("status", "draft")
                            .put("version", 1).put("createdAt", e.path("at").asText())
                            .put("createdBy", e.path("actor").asText()).put("origin", e.path("origin").asText());
                    mappings.put(1, e.path("mapping"));
                }
                case "save" -> {
                    int v = e.path("version").asInt();
                    mappings.put(v, e.path("mapping"));
                    state.put("version", v).put("status", "draft").put("updatedAt", e.path("at").asText())
                            .put("updatedBy", e.path("actor").asText());
                    state.remove("approval");
                }
                case "test" -> state.set("lastTest", e.path("test").deepCopy());
                case "status" -> {
                    state.put("status", e.path("to").asText());
                    if (e.has("approval")) {
                        state.set("approval", e.path("approval").deepCopy());
                    } else {
                        state.remove("approval");
                    }
                }
                default -> throw new IOException("unknown draft event");
            }
            expected++;
        }
        return Optional.of(new Folded(state, mappings, log, names.size()));
    }

    private static String eventName(String id, int seq) {
        return String.format("drafts/%s/events/%06d.json", id, seq);
    }

    private Optional<JsonNode> readImmutable(String name) throws IOException {
        JsonNode cached = immutable.get(name);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<byte[]> b = store.get(name);
        if (b.isEmpty()) {
            return Optional.empty();
        }
        JsonNode n = json.readTree(b.get());
        immutable.put(name, n);
        return Optional.of(n);
    }

    private ObjectNode event(int seq, String actor, String action, int version, ArrayNode changes, String note) {
        ObjectNode e = json.createObjectNode();
        e.put("seq", seq).put("at", clock.instant().toString()).put("actor", safeText(actor, 120)).put("action", action)
                .put("version", version);
        e.set("changes", changes);
        if (note != null && !note.isBlank()) {
            e.put("note", note);
        }
        return e;
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
        Map<String, List<JsonNode>> b = byField(before), a = byField(after);
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

    private static Map<String, List<JsonNode>> byField(JsonNode fields) {
        Map<String, List<JsonNode>> m = new java.util.TreeMap<>();
        if (fields != null && fields.isArray()) {
            for (Iterator<JsonNode> it = fields.elements(); it.hasNext(); ) {
                JsonNode f = it.next();
                m.computeIfAbsent(f.path("pdfField").asText(), k -> new ArrayList<>()).add(f);
            }
        }
        return m;
    }

    static String safeText(String s, int max) {
        String t = s == null ? "" : s.replaceAll("[\\p{Cntrl}]", " ").trim();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
