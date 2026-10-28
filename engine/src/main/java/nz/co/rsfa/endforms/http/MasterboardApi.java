package nz.co.rsfa.endforms.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import nz.co.rsfa.endforms.catalog.TemplateCatalog;
import nz.co.rsfa.endforms.fill.FillResult;
import nz.co.rsfa.endforms.fill.FormFiller;
import nz.co.rsfa.endforms.fill.Issue;
import nz.co.rsfa.endforms.mapping.Manifest;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * {@code POST /v1/masterboard/documents}: the integration contract for Make (docs/07). One Masterboard record in, the
 * selected lender forms out, as JSON with base64 PDFs.
 *
 * <p>Stateless and deterministic: the same {@code selection}, {@code today} and {@code data} always produce the same
 * documents and the same {@code requestDigest}, so retries are safe and idempotency of side effects (SharePoint
 * filing, Monday updates) is keyed by Make on {@code idempotencyKey}. Issues and logs carry keys and codes only.
 */
final class MasterboardApi {

    static final String CONTRACT = "masterboard.v1";
    static final int MAX_DOCUMENTS = 12;
    static final int MAX_DOCUMENT_BYTES = 10 * 1024 * 1024;
    /** Raw PDF bytes per response; base64 adds a third, which stays under Cloud Run's 32 MiB response limit. */
    static final int MAX_RESPONSE_PDF_BYTES = 20 * 1024 * 1024;
    static final Pattern ID = Pattern.compile("[A-Za-z0-9._:-]{8,128}");
    static final Pattern DIGITS = Pattern.compile("[0-9]{1,20}");
    static final Pattern TEMPLATE_PART = Pattern.compile("[A-Za-z0-9._-]{1,80}");

    /** HTTP status plus JSON body. */
    record Outcome(int status, ObjectNode body, String correlationId, int generated, int failed) {
    }

    private record Selected(String templateId, String templateVersion, String bank) {
        String key() {
            return templateId + "@" + templateVersion;
        }
    }

    private final TemplateCatalog catalog;
    private final ObjectMapper json;
    private final JsonNode routing;

    MasterboardApi(TemplateCatalog catalog, ObjectMapper json) {
        this.catalog = catalog;
        this.json = json;
        try (InputStream in = MasterboardApi.class.getResourceAsStream("/routing/masterboard-v1.json")) {
            if (in == null) {
                throw new IllegalStateException("routing table missing from the build");
            }
            this.routing = json.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    Outcome handle(JsonNode req) {
        String correlationId = text(req, "correlationId");
        String cid = correlationId != null && ID.matcher(correlationId).matches() ? correlationId : null;
        if (req == null || !req.isObject() || !CONTRACT.equals(text(req, "contractVersion"))) {
            return error(400, "UNSUPPORTED_CONTRACT", cid, "contractVersion must be " + CONTRACT);
        }
        String idempotencyKey = text(req, "idempotencyKey");
        JsonNode source = req.path("source");
        String itemId = text(source, "itemId");
        if (cid == null || idempotencyKey == null || !ID.matcher(idempotencyKey).matches()
                || itemId == null || !DIGITS.matcher(itemId).matches()
                || text(source, "boardId") == null || !DIGITS.matcher(text(source, "boardId")).matches()) {
            return error(400, "INVALID_REQUEST", cid,
                    "correlationId and idempotencyKey ([A-Za-z0-9._:-]{8,128}) and numeric source.boardId/itemId are required");
        }
        LocalDate today;
        try {
            today = LocalDate.parse(String.valueOf(text(req, "today")));
        } catch (DateTimeParseException e) {
            return error(400, "INVALID_REQUEST", cid, "today (YYYY-MM-DD, the date the run was requested) is required");
        }
        JsonNode data = req.get("data");
        if (data == null || !data.isObject()) {
            return error(400, "INVALID_REQUEST", cid, "data must be the Masterboard result object");
        }
        JsonNode options = req.path("options");
        String policy = options.path("failurePolicy").asText("all-or-nothing");
        if (!policy.equals("all-or-nothing") && !policy.equals("per-document")) {
            return error(400, "INVALID_REQUEST", cid, "options.failurePolicy must be all-or-nothing or per-document");
        }
        boolean includePdf = options.path("includePdf").asBoolean(true);
        boolean includeCandidates = options.path("includeCandidates").asBoolean(false);

        ArrayNode notMigrated = json.createArrayNode();
        List<Selected> selected = new ArrayList<>();
        JsonNode selection = req.path("selection");
        boolean byBank = selection.has("banks");
        if (byBank == selection.has("templates")) {
            return error(400, "INVALID_SELECTION", cid, "selection needs exactly one of banks or templates");
        }
        if (byBank) {
            JsonNode banks = selection.get("banks");
            if (!banks.isArray() || banks.isEmpty()) {
                return error(400, "INVALID_SELECTION", cid, "selection.banks must be a non-empty array of bank labels");
            }
            for (JsonNode b : banks) {
                JsonNode entries = routing.path("banks").get(b.asText());
                if (!b.isTextual() || entries == null) {
                    return error(422, "UNKNOWN_BANK", cid, "selection.banks contains a label that is not a known bank "
                            + "(expected one of " + String.join(", ", iterable(routing.path("banks").fieldNames())) + ")");
                }
                for (JsonNode e : entries) {
                    String status = e.path("status").asText();
                    boolean testing = status.equals("candidate") || status.equals("acceptance");
                    if (status.equals("tested") || (testing && includeCandidates)) {
                        selected.add(new Selected(e.get("templateId").asText(), e.get("templateVersion").asText(), b.asText()));
                    } else {
                        notMigrated.add(json.createObjectNode().put("bank", b.asText())
                                .put("legacyPdfName", e.path("legacyPdfName").asText()).put("status", status)
                                .put("reason", reason(status)));
                    }
                }
            }
            for (JsonNode e : routing.path("always")) {
                notMigrated.add(json.createObjectNode().put("bank", "(always)")
                        .put("legacyPdfName", e.path("legacyPdfName").asText()).put("status", e.path("status").asText())
                        .put("reason", reason(e.path("status").asText())));
            }
        } else {
            JsonNode templates = selection.get("templates");
            if (!templates.isArray() || templates.isEmpty()) {
                return error(400, "INVALID_SELECTION", cid, "selection.templates must be a non-empty array");
            }
            for (JsonNode t : templates) {
                String id = text(t, "templateId");
                String version = text(t, "templateVersion");
                if (id == null || version == null || !TEMPLATE_PART.matcher(id).matches() || !TEMPLATE_PART.matcher(version).matches()) {
                    return error(400, "INVALID_SELECTION", cid, "each template needs templateId and templateVersion");
                }
                selected.add(new Selected(id, version, null));
            }
        }
        Map<String, Selected> unique = new LinkedHashMap<>();
        selected.forEach(s -> unique.putIfAbsent(s.key(), s));
        if (unique.size() > MAX_DOCUMENTS) {
            return error(400, "TOO_MANY_DOCUMENTS", cid, "at most " + MAX_DOCUMENTS + " documents per request");
        }
        if (unique.isEmpty()) {
            Outcome o = error(422, "NOTHING_TO_GENERATE", cid,
                    "no selected form is migrated to the engine; MacroForge remains responsible (see notMigrated)");
            o.body().set("notMigrated", notMigrated);
            return o;
        }

        String digest = FormFiller.sha256(canonical(json.createObjectNode()
                .put("contractVersion", CONTRACT).put("today", today.toString())
                .<ObjectNode>set("templates", json.valueToTree(unique.keySet())).set("data", data))
                .getBytes(StandardCharsets.UTF_8));
        FormFiller filler = new FormFiller(zone -> today);
        ArrayNode documents = json.createArrayNode();
        Map<ObjectNode, byte[]> pdfs = new LinkedHashMap<>();
        int generated = 0;
        int failed = 0;
        boolean retryable = false;
        for (Selected s : unique.values()) {
            ObjectNode d = documents.addObject().put("templateId", s.templateId()).put("templateVersion", s.templateVersion());
            if (s.bank() != null) {
                d.put("bank", s.bank());
            }
            Optional<Manifest> manifest = catalog.manifest(s.templateId(), s.templateVersion());
            if (manifest.isEmpty()) {
                fail(d, "UNKNOWN_TEMPLATE", false, List.of());
                failed++;
                continue;
            }
            Optional<byte[]> template;
            try {
                template = catalog.templateBytes(manifest.get());
            } catch (IOException e) {
                template = Optional.empty();
            }
            if (template.isEmpty()) {
                fail(d, "TEMPLATE_UNAVAILABLE", true, List.of());
                retryable = true;
                failed++;
                continue;
            }
            FillResult r = filler.fill(template.get(), manifest.get(), data);
            d.set("warnings", json.valueToTree(r.issues().stream().filter(i -> i.severity() == Issue.Severity.WARNING).toList()));
            if (!r.ok()) {
                fail(d, "VALIDATION_FAILED", false, r.errors());
                failed++;
                continue;
            }
            if (r.pdf().length > MAX_DOCUMENT_BYTES) {
                fail(d, "DOCUMENT_TOO_LARGE", false, List.of());
                failed++;
                continue;
            }
            String file = (itemId + "_" + s.templateId() + "_" + s.templateVersion() + "_" + digest.substring(0, 8) + ".pdf")
                    .replaceAll("[^A-Za-z0-9._-]", "_");
            d.put("status", "generated").put("fileName", file).put("contentType", "application/pdf")
                    .put("sizeBytes", r.pdf().length).put("sha256", FormFiller.sha256(r.pdf()))
                    .put("fieldsFilled", r.filled().size());
            pdfs.put(d, r.pdf());
            generated++;
        }
        boolean withhold = failed > 0 && policy.equals("all-or-nothing");
        long total = pdfs.values().stream().mapToLong(b -> b.length).sum();
        if (includePdf && !withhold && total > MAX_RESPONSE_PDF_BYTES) {
            return error(413, "RESPONSE_TOO_LARGE", cid,
                    "documents exceed the response limit; request fewer templates per call or includePdf=false");
        }
        for (var e : pdfs.entrySet()) {
            if (withhold) {
                e.getKey().put("status", "withheld");
            } else if (includePdf) {
                e.getKey().put("pdfBase64", Base64.getEncoder().encodeToString(e.getValue()));
            }
        }
        String status = failed == 0 ? "complete" : withhold || generated == 0 ? "failed" : "partial";
        ObjectNode body = json.createObjectNode().put("contractVersion", CONTRACT).put("status", status)
                .put("correlationId", cid).put("idempotencyKey", idempotencyKey).put("requestDigest", digest)
                .put("engine", ApiServer.ENGINE_ID).put("today", today.toString())
                .put("retryable", failed > 0 && retryable);
        body.set("documents", documents);
        body.set("notMigrated", notMigrated);
        int http = status.equals("failed") ? (retryable ? 503 : 422) : 200;
        return new Outcome(http, body, cid, generated, failed);
    }

    private void fail(ObjectNode d, String code, boolean retryable, List<Issue> issues) {
        d.put("status", "failed").put("code", code).put("retryable", retryable);
        d.set("errors", json.valueToTree(issues));
    }

    private Outcome error(int status, String code, String cid, String message) {
        ObjectNode body = json.createObjectNode().put("contractVersion", CONTRACT).put("status", "error").put("code", code)
                .put("retryable", status >= 500).put("message", message);
        if (cid != null) {
            body.put("correlationId", cid);
        }
        return new Outcome(status, body, cid, 0, 0);
    }

    private static String reason(String status) {
        return switch (status) {
            case "candidate", "acceptance" -> "not yet accepted by RSFA; request with options.includeCandidates=true to test it";
            case "blocked" -> "open RSFA decision on this form (docs/06); MacroForge remains responsible";
            case "partial", "untested" -> "engine mapping not fully tested; MacroForge remains responsible";
            default -> "not migrated to the engine; MacroForge remains responsible";
        };
    }

    /** Compact JSON with object keys sorted recursively, so the digest does not depend on key order. */
    static String canonical(JsonNode n) {
        if (n.isObject()) {
            Map<String, JsonNode> sorted = new TreeMap<>();
            n.properties().forEach(e -> sorted.put(e.getKey(), e.getValue()));
            StringBuilder b = new StringBuilder("{");
            sorted.forEach((k, v) -> b.append(b.length() > 1 ? "," : "").append(quote(k)).append(':').append(canonical(v)));
            return b.append('}').toString();
        }
        if (n.isArray()) {
            StringBuilder b = new StringBuilder("[");
            n.forEach(v -> b.append(b.length() > 1 ? "," : "").append(canonical(v)));
            return b.append(']').toString();
        }
        return n.toString();
    }

    private static String quote(String s) {
        return com.fasterxml.jackson.databind.node.TextNode.valueOf(s).toString();
    }

    private static Iterable<String> iterable(java.util.Iterator<String> it) {
        List<String> out = new ArrayList<>();
        it.forEachRemaining(out::add);
        return out;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        return v == null || !v.isTextual() ? null : v.asText();
    }
}
