package nz.co.rsfa.endforms.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import nz.co.rsfa.endforms.mapping.Manifest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns an administration draft into an engine manifest (schemaVersion 1), the same format {@code FormFiller} already
 * fills, and reports every problem instead of guessing (D-038).
 *
 * <p>Draft field entry: {@code {"pdfField", "kind", "option"?, "required"?, "source": {...}}}; source types:
 * <ul>
 *   <li>{@code column}: {@code columnId} (stable Monday column ID) → value of that column; the runtime key is the
 *       column's current title (what Make sends today);</li>
 *   <li>{@code condition}: {@code columnId}, {@code operator} (equals | includes | present | absent), {@code value} →
 *       ticks a checkbox / selects a radio option;</li>
 *   <li>{@code derived}: {@code key}, a key that {@code 8731317} computes (GPT address split, split dates), not a
 *       Monday column; always reported as a warning because no schema can check it;</li>
 *   <li>{@code today}: {@code format} (java.time pattern), zone Pacific/Auckland;</li>
 *   <li>{@code constant}: fixed text (text fields only; a constant tick needs an approval record and is refused).</li>
 * </ul>
 * Refused (errors): signature or push-button targets, unknown PDF fields, kind mismatches, constant ticks, two
 * unconditional rules for one field, unknown columns. Warnings: duplicate column titles, labels missing from the
 * Monday options, {@code today} near a declaration (review required).
 */
public final class DraftCompiler {

    public record Problem(String severity, String code, String pdfField, String message) {
    }

    public record Result(ObjectNode manifest, List<Problem> problems, Map<String, String> runtimeKeyByColumn) {
        public boolean ok() {
            return problems.stream().noneMatch(p -> p.severity().equals("error"));
        }
    }

    private static final Set<String> OPERATORS = Set.of("equals", "includes", "present", "absent");
    private final ObjectMapper json = new ObjectMapper();

    public Result compile(ObjectNode state, JsonNode mapping, TemplateAnalysis.Report template, MondaySchema schema) {
        List<Problem> problems = new ArrayList<>();
        Map<String, TemplateAnalysis.Field> fields = new HashMap<>();
        template.fields().forEach(f -> fields.putIfAbsent(f.name(), f));
        Map<String, String> runtimeKeys = new java.util.TreeMap<>();
        ObjectNode m = json.createObjectNode();
        m.put("schemaVersion", Manifest.SUPPORTED_SCHEMA_VERSION)
                .put("templateId", state.path("templateId").asText())
                .put("templateVersion", state.path("templateVersion").asText())
                .put("templateFile", state.path("templateSha256").asText() + ".pdf")
                .put("templateSha256", state.path("templateSha256").asText())
                .put("encryption", template.encrypted() ? "preserve" : "reject")
                .put("sourceKeyNormalization", "none")
                .put("fontFallback", "unicode");
        ObjectNode prov = m.putObject("provenance");
        prov.put("source", "admin-draft").put("draftId", state.path("draftId").asText())
                .put("draftVersion", state.path("version").asInt()).put("status", state.path("status").asText())
                .put("notApprovedForProduction", true);
        ArrayNode out = m.putArray("fields");
        Map<String, Integer> unconditional = new HashMap<>();
        Set<String> conditionKeys = new LinkedHashSet<>();
        for (JsonNode f : mapping.path("fields")) {
            String name = f.path("pdfField").asText("");
            TemplateAnalysis.Field tf = fields.get(name);
            if (tf == null) {
                problems.add(new Problem("error", "UNKNOWN_PDF_FIELD", name, "the template has no such field"));
                continue;
            }
            if (tf.protectedField()) {
                problems.add(new Problem("error", "PROTECTED_FIELD", name, "signature and push-button fields are never filled"));
                continue;
            }
            String kind = tf.kind();
            JsonNode src = f.path("source");
            String type = src.path("type").asText("");
            ObjectNode rule = json.createObjectNode();
            rule.put("pdfField", name).put("kind", kind);
            if (f.path("required").asBoolean(false)) {
                rule.put("required", true);
            }
            ObjectNode s = rule.putObject("source");
            switch (type) {
                case "column" -> {
                    if (!kind.equals("text") && !kind.equals("combo") && !kind.equals("list")) {
                        problems.add(new Problem("error", "KIND_MISMATCH", name, "a " + kind + " needs a condition, not a column value"));
                        continue;
                    }
                    String key = runtimeKey(src.path("columnId").asText(null), src.path("columnTitle").asText(null), name, schema,
                            problems, runtimeKeys);
                    if (key == null) {
                        continue;
                    }
                    s.put("key", key);
                    unconditional.merge(name, 1, Integer::sum);
                }
                case "condition" -> {
                    if (!kind.equals("checkbox") && !kind.equals("radio")) {
                        problems.add(new Problem("error", "KIND_MISMATCH", name, "conditions select checkbox or radio states"));
                        continue;
                    }
                    String op = src.path("operator").asText("equals");
                    if (!OPERATORS.contains(op)) {
                        problems.add(new Problem("error", "INVALID_OPERATOR", name, "operator must be equals, includes, present or absent"));
                        continue;
                    }
                    String key = runtimeKey(src.path("columnId").asText(null), src.path("columnTitle").asText(null), name, schema,
                            problems, runtimeKeys);
                    if (key == null) {
                        continue;
                    }
                    String value = src.path("value").asText("");
                    if ((op.equals("equals") || op.equals("includes")) && value.isEmpty()) {
                        problems.add(new Problem("error", "MISSING_VALUE", name, "the condition needs the Monday label it tests"));
                        continue;
                    }
                    schema.column(src.path("columnId").asText(null)).ifPresent(c -> {
                        if (!c.labels().isEmpty() && (op.equals("equals") || op.equals("includes")) && !c.labels().contains(value)) {
                            problems.add(new Problem("warning", "LABEL_NOT_IN_MONDAY", name,
                                    "the label is not one of the column's current Monday options (renamed?)"));
                        }
                    });
                    String option = f.path("option").asText("");
                    if (kind.equals("radio") || tf.options().size() > 1) {
                        if (option.isEmpty() || !tf.options().contains(option)) {
                            problems.add(new Problem("error", "UNKNOWN_OPTION", name, "choose one of the field's export values"));
                            continue;
                        }
                        rule.put("option", option);
                    }
                    ObjectNode when = s.putObject("when");
                    when.put("key", key).put("operator", op);
                    if (!value.isEmpty()) {
                        when.put("value", value);
                    }
                    if (op.equals("equals") || op.equals("includes")) {
                        conditionKeys.add(key);
                    }
                }
                case "derived" -> {
                    if (!kind.equals("text")) {
                        problems.add(new Problem("error", "KIND_MISMATCH", name, "a derived key fills text fields only"));
                        continue;
                    }
                    String key = src.path("key").asText("").trim();
                    if (key.isEmpty() || key.length() > 200) {
                        problems.add(new Problem("error", "MISSING_KEY", name, "enter the derived key name"));
                        continue;
                    }
                    s.put("key", key);
                    problems.add(new Problem("warning", "DERIVED_KEY", name, "not a Monday column: produced by Make 8731317 "
                            + "(e.g. GPT address split); check it exists in the payload"));
                    unconditional.merge(name, 1, Integer::sum);
                }
                case "today" -> {
                    if (!kind.equals("text")) {
                        problems.add(new Problem("error", "KIND_MISMATCH", name, "dates go into text fields"));
                        continue;
                    }
                    String fmt = src.path("format").asText("dd/MM/yyyy");
                    try {
                        java.time.format.DateTimeFormatter.ofPattern(fmt);
                    } catch (IllegalArgumentException e) {
                        problems.add(new Problem("error", "INVALID_FORMAT", name, "invalid date pattern"));
                        continue;
                    }
                    s.put("generated", "today").put("format", fmt).put("zone", "Pacific/Auckland");
                    if (TemplateAnalysis.declarationLike(tf)) {
                        problems.add(new Problem("warning", "TODAY_NEAR_DECLARATION", name,
                                "a pre-filled date next to a declaration or signature needs RSFA's review (CLAUDE.md rule 4)"));
                        rule.put("reviewRequired", "admin draft: [TODAY] near a declaration");
                    }
                    unconditional.merge(name, 1, Integer::sum);
                }
                case "constant" -> {
                    if (!kind.equals("text")) {
                        problems.add(new Problem("error", "CONSTANT_TICK", name, "a constant tick can certify a declaration: "
                                + "it needs an RSFA approval record (D-016) and cannot be created here"));
                        continue;
                    }
                    s.put("constant", src.path("text").asText(""));
                    unconditional.merge(name, 1, Integer::sum);
                }
                default -> {
                    problems.add(new Problem("error", "INVALID_SOURCE", name, "source type must be column, condition, derived, today or constant"));
                    continue;
                }
            }
            out.add(rule);
        }
        unconditional.forEach((n, c) -> {
            if (c > 1) {
                problems.add(new Problem("error", "CONFLICTING_RULES", n, "more than one unconditional value for one field"));
            }
        });
        ObjectNode validation = m.putObject("validation");
        ArrayNode closed = validation.putArray("closedOptions");
        JsonNode v = mapping.path("validation");
        for (JsonNode c : v.path("closedOptions")) {
            String key = runtimeKey(c.path("columnId").asText(null), null, "(validation)", schema, problems, runtimeKeys);
            if (key != null) {
                if (!conditionKeys.contains(key)) {
                    problems.add(new Problem("warning", "CLOSED_OPTION_UNUSED", null, "a closed-option check on a column no "
                            + "condition tests would refuse every value"));
                }
                closed.addObject().put("key", key).put("multiSelect", c.path("multiSelect").asBoolean(false))
                        .put("onUnmapped", "error");
            }
        }
        ArrayNode requires = validation.putArray("requires");
        for (JsonNode r : v.path("requires")) {
            String id = r.path("id").asText("");
            String ifKey = runtimeKey(r.path("ifPresentColumnId").asText(null), null, "(validation)", schema, problems, runtimeKeys);
            String reqKey = runtimeKey(r.path("requireColumnId").asText(null), null, "(validation)", schema, problems, runtimeKeys);
            String op = r.path("requireOperator").asText("present");
            if (ifKey == null || reqKey == null || !id.matches("[A-Z0-9-]{3,40}") || !OPERATORS.contains(op)) {
                problems.add(new Problem("error", "INVALID_REQUIREMENT", null, "a rule needs an id (A-Z, 0-9, -), two columns and an operator"));
                continue;
            }
            ObjectNode req = requires.addObject();
            req.put("id", id).put("ifPresent", ifKey).put("reason", Workspace.safeText(r.path("reason").asText(""), 200));
            ObjectNode cond = req.putObject("require");
            cond.put("key", reqKey).put("operator", op);
            if (!r.path("requireValue").asText("").isEmpty()) {
                cond.put("value", r.path("requireValue").asText());
            }
        }
        return new Result(m, List.copyOf(problems), runtimeKeys);
    }

    private static String runtimeKey(String columnId, String fallbackTitle, String field, MondaySchema schema,
                                     List<Problem> problems, Map<String, String> keys) {
        if (columnId == null || columnId.isBlank()) {
            problems.add(new Problem("error", "NO_COLUMN", field, "choose a Monday column"));
            return null;
        }
        var col = schema.column(columnId);
        if (col.isEmpty()) {
            problems.add(new Problem("error", "UNKNOWN_COLUMN", field, "column " + columnId + " is not in the current Monday "
                    + "schema" + (fallbackTitle == null ? "" : " (last known title kept in the draft)")));
            return null;
        }
        if (col.get().duplicateTitle()) {
            problems.add(new Problem("warning", "DUPLICATE_TITLE", field, "another column has the same title; Make would "
                    + "merge both values"));
        }
        keys.put(columnId, col.get().title());
        return col.get().title();
    }
}
