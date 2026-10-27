package nz.co.rsfa.endforms.fill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import nz.co.rsfa.endforms.mapping.FieldRule;
import nz.co.rsfa.endforms.mapping.FieldRule.Kind;
import nz.co.rsfa.endforms.mapping.Manifest;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDButton;
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDChoice;
import org.apache.pdfbox.pdmodel.interactive.form.PDComboBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDListBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDPushButton;
import org.apache.pdfbox.pdmodel.interactive.form.PDRadioButton;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Fills one AcroForm template from a flat JSON {@code data} object using a {@link Manifest}.
 *
 * <p>Deterministic and strict: all rules are validated first, and if any ERROR is found no PDF
 * is produced. The input template bytes are never modified. Output stays editable (no flattening).
 * Signature and push-button fields are never written.
 */
public final class FormFiller {

    private final Function<ZoneId, LocalDate> today;

    /** @param today supplies "today" per zone; injectable so tests and reprocessing are reproducible */
    public FormFiller(Function<ZoneId, LocalDate> today) {
        this.today = today;
    }

    public FormFiller() {
        this(LocalDate::now);
    }

    public FillResult fill(byte[] template, Manifest manifest, JsonNode data) {
        Run run = new Run();
        if (!sha256(template).equalsIgnoreCase(String.valueOf(manifest.templateSha256()))) {
            run.error("TEMPLATE_HASH_MISMATCH", null, null,
                    "template bytes do not match manifest templateSha256 for " + manifest.key());
            return run.result(null);
        }
        if (data == null || !data.isObject()) {
            run.error("INVALID_DATA", null, null, "data must be a JSON object");
            return run.result(null);
        }
        if (manifest.sourceKeyNormalization() == Manifest.KeyNormalization.macroforgeV1) {
            data = macroforgeKeys((ObjectNode) data);
        }
        for (JsonNode u : manifest.unsupported()) {
            run.warning("UNSUPPORTED_LEGACY_RULE", text(u, "pdfField"), text(u, "sourceKey"),
                    "legacy rule not migrated: " + text(u, "legacyType"));
        }

        try (PDDocument doc = Loader.loadPDF(template)) {
            boolean incremental = false;
            if (doc.isEncrypted()) {
                if (manifest.encryption() != Manifest.EncryptionPolicy.preserve) {
                    run.error("ENCRYPTED_TEMPLATE", null, null,
                            "template is encrypted; set manifest encryption=preserve after review");
                    return run.result(null);
                }
                if (!doc.getCurrentAccessPermission().canFillInForm()) {
                    run.error("FILL_NOT_PERMITTED", null, null, "template permissions forbid form filling");
                    return run.result(null);
                }
                incremental = true; // keeps original bytes and encryption; owner password is unknown
            }
            PDAcroForm form = doc.getDocumentCatalog().getAcroForm();
            if (form == null || form.getFields().isEmpty()) {
                run.error("NO_ACROFORM", null, null, "template has no interactive form fields");
                return run.result(null);
            }
            if (form.hasXFA()) {
                run.error("XFA_PRESENT", null, null, "XFA forms are not supported");
                return run.result(null);
            }

            Map<String, List<FieldRule>> byField = new LinkedHashMap<>();
            for (FieldRule r : manifest.fields()) {
                byField.computeIfAbsent(r.pdfField(), k -> new ArrayList<>()).add(r);
            }
            List<Runnable> writes = new ArrayList<>();
            for (var e : byField.entrySet()) {
                plan(form.getField(e.getKey()), e.getKey(), e.getValue(), data, run, writes);
            }
            if (run.hasErrors()) {
                return run.result(null);
            }
            for (Runnable w : writes) {
                w.run();
            }
            if (run.hasErrors()) {
                return run.result(null);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(template.length + 64 * 1024);
            if (incremental) {
                doc.saveIncremental(out);
            } else {
                doc.save(out);
            }
            return run.result(out.toByteArray());
        } catch (IOException e) {
            run.error("PDF_IO_ERROR", null, null, "could not read or write PDF: " + e.getClass().getSimpleName());
            return run.result(null);
        }
    }

    // ------------------------------------------------------------------ planning (validation only)

    private void plan(PDField field, String name, List<FieldRule> rules, JsonNode data, Run run,
                      List<Runnable> writes) {
        if (field == null) {
            run.error("UNRESOLVED_PDF_FIELD", name, null, "field not found in template");
            return;
        }
        if (field instanceof PDSignatureField || field instanceof PDPushButton) {
            run.error("FORBIDDEN_TARGET", name, null, "signature/push-button fields are never filled");
            return;
        }
        Kind kind = rules.getFirst().kind();
        if (rules.stream().anyMatch(r -> r.kind() != kind)) {
            run.error("CONFLICTING_RULES", name, null, "rules for one field disagree on kind");
            return;
        }
        if (!kindMatches(kind, field)) {
            run.error("KIND_MISMATCH", name, null,
                    "manifest kind " + kind + " but PDF field is " + field.getClass().getSimpleName());
            return;
        }
        if (field.isReadOnly()) {
            run.warning("READ_ONLY_FIELD", name, null, "field is read-only in the template");
        }
        switch (kind) {
            case text -> planText((PDTextField) field, rules, data, run, writes);
            case combo, list -> planChoice((PDChoice) field, rules, data, run, writes);
            case checkbox, radio -> planButton((PDButton) field, rules, data, run, writes);
        }
    }

    private static boolean kindMatches(Kind kind, PDField f) {
        return switch (kind) {
            case text -> f instanceof PDTextField;
            case checkbox -> f instanceof PDCheckBox;
            case radio -> f instanceof PDRadioButton;
            case combo -> f instanceof PDComboBox;
            case list -> f instanceof PDListBox;
        };
    }

    private void planText(PDTextField field, List<FieldRule> rules, JsonNode data, Run run, List<Runnable> writes) {
        String name = field.getFullyQualifiedName();
        if (rules.size() != 1) {
            run.error("DUPLICATE_RULE", name, null, "text fields accept exactly one rule");
            return;
        }
        FieldRule rule = rules.getFirst();
        String value = scalar(rule, data, run);
        if (value == null) {
            return;
        }
        if (field.getMaxLen() > 0 && value.codePointCount(0, value.length()) > field.getMaxLen()) {
            run.error("TEXT_TOO_LONG", name, rule.source().key(),
                    "value exceeds field MaxLen " + field.getMaxLen() + " (no silent truncation)");
            return;
        }
        if (!field.isMultiline() && (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)) {
            run.error("MULTILINE_NOT_ALLOWED", name, rule.source().key(), "line break in single-line field");
            return;
        }
        writes.add(() -> {
            try {
                field.setValue(value);
                run.filled.add(name);
            } catch (IllegalArgumentException e) {
                // PDFBox throws when the field's font cannot encode a character (e.g. macrons)
                run.error("UNENCODABLE_TEXT", name, rule.source().key(),
                        "field font cannot encode at least one character of the value");
            } catch (IOException e) {
                run.error("APPEARANCE_FAILED", name, rule.source().key(), "could not build field appearance");
            }
        });
    }

    private void planChoice(PDChoice field, List<FieldRule> rules, JsonNode data, Run run, List<Runnable> writes) {
        String name = field.getFullyQualifiedName();
        if (rules.size() != 1) {
            run.error("DUPLICATE_RULE", name, null, "choice fields accept exactly one rule");
            return;
        }
        FieldRule rule = rules.getFirst();
        String value = scalar(rule, data, run);
        if (value == null) {
            return;
        }
        boolean known = field.getOptionsExportValues().contains(value) || field.getOptionsDisplayValues().contains(value);
        if (!known) {
            run.error("INVALID_OPTION", name, rule.source().key(), "value is not one of the field's options");
            return;
        }
        writes.add(() -> {
            try {
                field.setValue(value);
                run.filled.add(name);
            } catch (IllegalArgumentException | IOException e) {
                run.error("APPEARANCE_FAILED", name, rule.source().key(), "could not set choice value");
            }
        });
    }

    /**
     * Buttons: each rule either selects an on-state (option, or the single on-state) or forces Off.
     * More than one distinct selection is a conflict. Nothing selected leaves the field untouched.
     */
    private void planButton(PDButton field, List<FieldRule> rules, JsonNode data, Run run, List<Runnable> writes) {
        String name = field.getFullyQualifiedName();
        Set<String> onValues = field.getOnValues();
        Set<String> selected = new LinkedHashSet<>();
        boolean forceOff = false;
        for (FieldRule r : rules) {
            FieldRule.Source s = r.source();
            String state = r.option();
            if (state == null && field instanceof PDCheckBox && onValues.size() == 1) {
                state = onValues.iterator().next();
            }
            Boolean on;
            if (s.when() != null) {
                on = matches(s.when(), data, name, run);
            } else if (s.constant() != null && s.constant().isBoolean()) {
                on = s.constant().booleanValue();
            } else if (s.key() != null && field instanceof PDRadioButton && state == null) {
                String v = scalar(r, data, run);       // radio fed directly with an export value
                state = v;
                on = v == null ? null : Boolean.TRUE;
            } else {
                run.error("UNSUPPORTED_SOURCE", name, s.key(), "button rule needs when, boolean constant or key");
                return;
            }
            if (on == null || (!on && s.when() != null)) {
                continue;  // error recorded / absent radio value / condition not met: leave untouched
            }
            if (state == null) {
                run.error("AMBIGUOUS_ON_STATE", name, null, "checkbox has several on-states; rule needs option");
                return;
            }
            if (!onValues.contains(state)) {
                run.error("INVALID_OPTION", name, s.key(), "requested state is not an on-state of the field");
                return;
            }
            if (on) {
                selected.add(state);
            } else {
                forceOff = true;
            }
        }
        if (selected.size() > 1) {
            run.error("CONFLICTING_SELECTION", name, null, selected.size() + " options selected for one field");
            return;
        }
        if (selected.isEmpty() && !forceOff) {
            if (rules.stream().anyMatch(FieldRule::required)) {
                run.error("MISSING_REQUIRED_VALUE", name, null, "no option selected for required field");
            } else {
                run.skipped.add(name);
            }
            return;
        }
        String target = selected.isEmpty() ? "Off" : selected.iterator().next();
        writes.add(() -> {
            try {
                if (target.equals("Off") && field instanceof PDCheckBox cb) {
                    cb.unCheck();
                } else {
                    field.setValue(target);
                }
                run.filled.add(name);
            } catch (IllegalArgumentException | IOException e) {
                run.error("APPEARANCE_FAILED", name, null, "could not set button state");
            }
        });
    }

    // ------------------------------------------------------------------ values

    /** Resolve a text-like value. Returns null when nothing should be written (issue recorded if needed). */
    private String scalar(FieldRule rule, JsonNode data, Run run) {
        FieldRule.Source s = rule.source();
        String name = rule.pdfField();
        if (s == null) {
            run.error("INVALID_RULE", name, null, "rule has no source");
            return null;
        }
        if (s.generated() != null) {
            if (!"today".equals(s.generated())) {
                run.error("UNSUPPORTED_SOURCE", name, null, "unknown generated source " + s.generated());
                return null;
            }
            try {
                ZoneId zone = ZoneId.of(s.zone() == null ? "Pacific/Auckland" : s.zone());
                return today.apply(zone).format(DateTimeFormatter.ofPattern(s.format() == null ? "dd/MM/yyyy" : s.format()));
            } catch (DateTimeException | IllegalArgumentException e) {
                run.error("INVALID_RULE", name, null, "invalid date format or zone");
                return null;
            }
        }
        JsonNode v;
        if (s.constant() != null) {
            v = s.constant();
        } else if (s.key() != null) {
            if (!data.has(s.key())) {
                return missing(rule, run, true);
            }
            v = data.get(s.key());
        } else {
            run.error("UNSUPPORTED_SOURCE", name, null, "rule needs key, constant or generated");
            return null;
        }
        if (v == null || v.isNull() || (v.isTextual() && v.asText().isBlank())) {
            return missing(rule, run, false);
        }
        if (v.isTextual()) {
            return v.asText();
        }
        if (v.isNumber()) {
            return v.isIntegralNumber() ? v.bigIntegerValue().toString() : new BigDecimal(v.asText()).toPlainString();
        }
        if (v.isArray()) {
            run.error("AMBIGUOUS_SOURCE_VALUE", name, s.key(),
                    "source is an array (duplicate Monday column titles?); mapping must choose one");
        } else {
            run.error("UNSUPPORTED_VALUE_TYPE", name, s.key(), "source value type " + v.getNodeType() + " not allowed here");
        }
        return null;
    }

    private String missing(FieldRule rule, Run run, boolean absent) {
        if (rule.required()) {
            run.error("MISSING_REQUIRED_VALUE", rule.pdfField(), rule.source().key(), "required value is missing");
        } else {
            if (absent) {
                run.warning("SOURCE_KEY_ABSENT", rule.pdfField(), rule.source().key(),
                        "key not present in data (payload contract drift?)");
            }
            run.skipped.add(rule.pdfField());
        }
        return null;
    }

    private Boolean matches(FieldRule.Condition c, JsonNode data, String field, Run run) {
        if (c.key() == null || c.operator() == null || c.value() == null) {
            run.error("INVALID_RULE", field, c.key(), "condition needs key, operator and value");
            return null;
        }
        JsonNode v = data.get(c.key());
        if (v == null) {
            run.warning("SOURCE_KEY_ABSENT", field, c.key(), "condition key not present in data");
            return false;
        }
        if (v.isNull()) {
            return false;
        }
        if (v.isArray() || v.isObject()) {
            run.error("AMBIGUOUS_SOURCE_VALUE", field, c.key(), "condition source is not a scalar");
            return null;
        }
        String s = v.asText();
        return switch (c.operator()) {
            case equals -> s.equals(c.value());
            case contains -> s.contains(c.value());
        };
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Applies legacy key normalisation. Keys that collide after normalisation are merged into an
     * array, so any rule reading them fails with AMBIGUOUS_SOURCE_VALUE instead of picking one.
     */
    static ObjectNode macroforgeKeys(ObjectNode data) {
        ObjectNode out = data.objectNode();
        Map<String, ArrayNode> collided = new LinkedHashMap<>();
        for (var e : data.properties()) {
            String k = e.getKey().replace('/', '_').replace('.', '_');
            JsonNode prev = out.get(k);
            if (prev == null) {
                out.set(k, e.getValue());
            } else {
                ArrayNode merged = collided.computeIfAbsent(k, x -> data.arrayNode().add(prev));
                merged.add(e.getValue());
                out.set(k, merged);
            }
        }
        return out;
    }

    private static String text(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v == null || v.isNull() ? null : v.asText();
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class Run {
        final List<Issue> issues = new ArrayList<>();
        final List<String> filled = new ArrayList<>();
        final List<String> skipped = new ArrayList<>();

        void error(String code, String f, String k, String m) {
            issues.add(Issue.error(code, f, k, m));
        }

        void warning(String code, String f, String k, String m) {
            issues.add(Issue.warning(code, f, k, m));
        }

        boolean hasErrors() {
            return issues.stream().anyMatch(i -> i.severity() == Issue.Severity.ERROR);
        }

        FillResult result(byte[] pdf) {
            boolean ok = pdf != null && !hasErrors();
            return new FillResult(ok ? pdf : null, List.copyOf(issues), ok ? List.copyOf(filled) : List.of(),
                    List.copyOf(skipped));
        }
    }
}
