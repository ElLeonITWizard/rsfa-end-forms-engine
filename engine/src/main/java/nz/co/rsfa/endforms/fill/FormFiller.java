package nz.co.rsfa.endforms.fill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import nz.co.rsfa.endforms.mapping.FieldRule;
import nz.co.rsfa.endforms.mapping.FieldRule.Kind;
import nz.co.rsfa.endforms.mapping.Manifest;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceCharacteristicsDictionary;
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
import java.io.InputStream;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fills one AcroForm template from a flat JSON {@code data} object using a {@link Manifest}.
 *
 * <p>Deterministic and strict: all rules are validated first, and if any ERROR is found no PDF
 * is produced. The input template bytes are never modified. Output stays editable (no flattening).
 * Signature and push-button fields are never written.
 */
public final class FormFiller {

    /** Unicode TTF shipped inside PDFBox (SIL OFL); used for overlays and the opt-in field font fallback. */
    static final String UNICODE_FONT = "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf";
    static final String FALLBACK_FONT_NAME = "RsfaUnicode";
    private static final Pattern DA_FONT = Pattern.compile("/(\\S+)\\s+([\\d.]+)\\s+Tf");
    private static final float OVERLAY_DEFAULT_SIZE = 10f;
    private static final float OVERLAY_MIN_SIZE = 6f;
    private static final float OVERLAY_PADDING = 2f;

    private final Function<ZoneId, LocalDate> today;

    /** @param today supplies "today" per zone; injectable so tests and reprocessing are reproducible */
    public FormFiller(Function<ZoneId, LocalDate> today) {
        this.today = today;
    }

    public FormFiller() {
        this(LocalDate::now);
    }

    public FillResult fill(byte[] template, Manifest manifest, JsonNode data) {
        Run run = new Run(manifest);
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
        validate(manifest, data, run);

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
                if (!manifest.overlays().isEmpty()) {
                    run.error("OVERLAY_ON_ENCRYPTED_TEMPLATE", null, null,
                            "overlays on encrypted templates are not verified yet");
                    return run.result(null);
                }
            }
            run.doc = doc;
            run.combs = new CombAppearance(template);
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
            for (Manifest.Overlay o : manifest.overlays()) {
                planOverlay(doc, o, data, run, writes);
            }
            clearTemplatePresets(form, run, writes);
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
        } finally {
            if (run.combs != null) {
                try {
                    run.combs.close();
                } catch (IOException ignored) {
                    // the sampling copy is read-only; nothing to recover
                }
            }
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
        String value = scalar(name, rule.source(), rule.required(), data, run);
        if (value == null) {
            return;
        }
        if (field.getMaxLen() > 0 && value.codePointCount(0, value.length()) > field.getMaxLen()) {
            run.error("TEXT_TOO_LONG", name, keyOf(rule.source()),
                    "value exceeds field MaxLen " + field.getMaxLen() + " (no silent truncation)");
            return;
        }
        if (!field.isMultiline() && (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)) {
            run.error("MULTILINE_NOT_ALLOWED", name, keyOf(rule.source()), "line break in single-line field");
            return;
        }
        boolean fallback = !encodableInFieldFont(field, value);
        if (fallback && run.manifest.fontFallback() != Manifest.FontFallback.unicode) {
            run.error("UNENCODABLE_TEXT", name, keyOf(rule.source()),
                    "field font cannot encode at least one character of the value");
            return;
        }
        if (fallback) {
            run.fullUnicodeFont = true; // AcroForm appearances need a fully embedded font
        }
        writes.add(() -> {
            try {
                if (fallback) {
                    useUnicodeFont(field, run);
                    run.warning("FONT_FALLBACK", name, keyOf(rule.source()),
                            "value needed the embedded Unicode font instead of the field's own font");
                }
                field.setValue(value);
                String combWarning = run.combs.apply(run.doc, field, value);
                if (combWarning != null) {
                    run.warning(combWarning, name, null, "comb cells drawn without placeholder knockout");
                }
                run.filled.add(name);
            } catch (IllegalArgumentException e) {
                // PDFBox throws when the field's font cannot encode a character (e.g. macrons)
                run.error("UNENCODABLE_TEXT", name, keyOf(rule.source()),
                        "field font cannot encode at least one character of the value");
            } catch (IOException e) {
                run.error("APPEARANCE_FAILED", name, keyOf(rule.source()), "could not build field appearance");
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
        String value = scalar(name, rule.source(), rule.required(), data, run);
        if (value == null) {
            return;
        }
        boolean known = field.getOptionsExportValues().contains(value) || field.getOptionsDisplayValues().contains(value);
        if (!known) {
            run.error("INVALID_OPTION", name, keyOf(rule.source()), "value is not one of the field's options");
            return;
        }
        writes.add(() -> {
            try {
                field.setValue(value);
                run.filled.add(name);
            } catch (IllegalArgumentException | IOException e) {
                run.error("APPEARANCE_FAILED", name, keyOf(rule.source()), "could not set choice value");
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
                if (on) {
                    String invalid = approvalProblem(r);
                    if (invalid != null) {
                        if (r.approval() == null && !Boolean.TRUE.equals(r.approved())) {
                            run.warning("CONSTANT_CHECK_NOT_APPROVED", name, null,
                                    "constant tick not applied: needs a recorded approval (approvedBy, approvedOn, reference)");
                            continue;
                        }
                        run.error("INVALID_APPROVAL", name, null, invalid);
                        return;
                    }
                }
            } else if (s.key() != null && field instanceof PDRadioButton && state == null) {
                String v = scalar(name, s, r.required(), data, run); // radio fed directly with an export value
                state = v;
                on = v == null ? null : Boolean.TRUE;
            } else {
                run.error("UNSUPPORTED_SOURCE", name, s.key(), "button rule needs when, boolean constant or key");
                return;
            }
            if (on == null || (!on && s.when() != null)) {
                continue;  // error recorded / absent radio value / condition not met: leave untouched
            }
            if (!on) {
                forceOff = true; // constant false: all widgets Off, no on-state needed
                continue;
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
        run.decidedButtons.add(name); // selected by an authorized rule, or explicitly forced Off
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

    /** Null when the rule carries a complete, valid approval; otherwise the reason (no data values). */
    private String approvalProblem(FieldRule r) {
        FieldRule.Approval a = r.approval();
        if (a == null) {
            return Boolean.TRUE.equals(r.approved())
                    ? "'approved: true' alone is not an authorization; record approvedBy, approvedOn and reference"
                    : "no approval recorded";
        }
        if (a.approvedBy() == null || a.approvedBy().isBlank() || a.reference() == null || a.reference().isBlank()) {
            return "approval must name approvedBy and reference";
        }
        try {
            LocalDate on = LocalDate.parse(String.valueOf(a.approvedOn()));
            if (on.isAfter(today.apply(ZoneId.of("Pacific/Auckland")))) {
                return "approvedOn is in the future";
            }
        } catch (DateTimeException e) {
            return "approvedOn must be an ISO date (YYYY-MM-DD)";
        }
        return null;
    }

    /**
     * Buttons that are already on in the blank template (a bank's pre-ticked declaration, for example) are reset to
     * Off unless a rule of this fill selected them. Template defaults never certify anything on their own.
     */
    private void clearTemplatePresets(PDAcroForm form, Run run, List<Runnable> writes) {
        for (PDField f : form.getFieldTree()) {
            if (!(f instanceof PDCheckBox || f instanceof PDRadioButton) || run.decidedButtons.contains(f.getFullyQualifiedName())) {
                continue;
            }
            String v = f.getValueAsString();
            if (v == null || v.isEmpty() || "Off".equals(v)) {
                continue;
            }
            String name = f.getFullyQualifiedName();
            run.skipped.remove(name);
            writes.add(() -> {
                try {
                    if (f instanceof PDCheckBox cb) {
                        cb.unCheck();
                    } else {
                        ((PDRadioButton) f).setValue("Off");
                    }
                    run.warning("TEMPLATE_PRESET_CLEARED", name, null,
                            "button was on in the blank template and no authorized rule selected it; set to Off");
                } catch (IOException | IllegalArgumentException e) {
                    run.error("APPEARANCE_FAILED", name, null, "could not clear template preset");
                }
            });
        }
    }

    // ------------------------------------------------------------------ values

    /**
     * Resolve a text-like value for a field or overlay ({@code label}). Returns null when nothing should
     * be written; an issue is recorded where needed. Issues name keys, never values.
     */
    private String scalar(String label, FieldRule.Source s, boolean required, JsonNode data, Run run) {
        if (s == null) {
            run.error("INVALID_RULE", label, null, "rule has no source");
            return null;
        }
        if (s.generated() != null) {
            if (!"today".equals(s.generated())) {
                run.error("UNSUPPORTED_SOURCE", label, null, "unknown generated source " + s.generated());
                return null;
            }
            try {
                ZoneId zone = ZoneId.of(s.zone() == null ? "Pacific/Auckland" : s.zone());
                return today.apply(zone).format(DateTimeFormatter.ofPattern(s.format() == null ? "dd/MM/yyyy" : s.format()));
            } catch (DateTimeException | IllegalArgumentException e) {
                run.error("INVALID_RULE", label, null, "invalid date format or zone");
                return null;
            }
        }
        String key = keyOf(s);
        JsonNode v;
        if (s.constant() != null) {
            v = s.constant();
        } else if (s.key() != null) {
            if (!data.has(s.key())) {
                return missing(label, key, required, run, true);
            }
            v = data.get(s.key());
        } else if (s.lineItem() != null) {
            FieldRule.LineItem li = s.lineItem();
            JsonNode arr = li.array() == null ? null : data.get(li.array());
            if (arr == null) {
                return missing(label, key, required, run, true);
            }
            if (!arr.isArray() || li.position() < 1 || li.path() == null || li.path().isEmpty()) {
                run.error(arr.isArray() ? "INVALID_RULE" : "UNSUPPORTED_VALUE_TYPE", label, key,
                        "line item needs an array source, position >= 1 and a path");
                return null;
            }
            if (li.position() > arr.size()) {
                return missing(label, key, required, run, false); // fewer rows than the form offers
            }
            v = arr.get(li.position() - 1);
            for (String p : li.path()) {
                v = v != null && v.isObject() ? v.get(p) : null;
            }
            if (v == null) {
                return missing(label, key, required, run, true);
            }
        } else {
            run.error("UNSUPPORTED_SOURCE", label, null, "rule needs key, constant, generated or lineItem");
            return null;
        }
        if (v.isNull() || (v.isTextual() && v.asText().isBlank())) {
            return missing(label, key, required, run, false);
        }
        if (v.isTextual()) {
            return v.asText();
        }
        if (v.isNumber()) {
            return v.isIntegralNumber() ? v.bigIntegerValue().toString() : new BigDecimal(v.asText()).toPlainString();
        }
        if (v.isArray()) {
            run.error("AMBIGUOUS_SOURCE_VALUE", label, key,
                    "source is an array (duplicate Monday column titles?); mapping must choose one");
        } else {
            run.error("UNSUPPORTED_VALUE_TYPE", label, key, "source value type " + v.getNodeType() + " not allowed here");
        }
        return null;
    }

    /** A printable reference to where a value comes from (key names only). */
    static String keyOf(FieldRule.Source s) {
        if (s == null) {
            return null;
        }
        if (s.lineItem() != null) {
            FieldRule.LineItem li = s.lineItem();
            return li.array() + "[" + li.position() + "]." + String.join(".", li.path() == null ? List.of() : li.path());
        }
        return s.key();
    }

    private String missing(String label, String key, boolean required, Run run, boolean absent) {
        if (required) {
            run.error("MISSING_REQUIRED_VALUE", label, key, "required value is missing");
        } else {
            if (absent) {
                run.warning("SOURCE_KEY_ABSENT", label, key, "key not present in data (payload contract drift?)");
            }
            run.skipped.add(label);
        }
        return null;
    }

    // ------------------------------------------------------------------ fonts

    private static boolean encodableInFieldFont(PDTextField field, String value) {
        Matcher m = DA_FONT.matcher(String.valueOf(field.getDefaultAppearance()));
        PDResources dr = field.getAcroForm().getDefaultResources();
        if (!m.find() || dr == null) {
            return true; // cannot tell; setValue reports UNENCODABLE_TEXT if it fails
        }
        try {
            PDFont font = dr.getFont(COSName.getPDFName(m.group(1)));
            if (font == null) {
                return true;
            }
            font.encode(value);
            return true;
        } catch (IllegalArgumentException | IOException e) {
            return false;
        }
    }

    /** Point the field (and any widget-level DA) at the embedded Unicode font, keeping the size. */
    private static void useUnicodeFont(PDTextField field, Run run) throws IOException {
        PDAcroForm form = field.getAcroForm();
        PDResources dr = form.getDefaultResources();
        if (dr == null) {
            dr = new PDResources();
            form.setDefaultResources(dr);
        }
        COSName fname = COSName.getPDFName(FALLBACK_FONT_NAME);
        if (dr.getFont(fname) == null) {
            dr.put(fname, run.unicodeFont());
        }
        Matcher m = DA_FONT.matcher(String.valueOf(field.getDefaultAppearance()));
        String size = m.find() ? m.group(2) : "0";
        String da = m.reset().find() ? m.replaceFirst("/" + FALLBACK_FONT_NAME + " " + size + " Tf")
                : "/" + FALLBACK_FONT_NAME + " 0 Tf 0 g";
        field.setDefaultAppearance(da);
        for (PDAnnotationWidget w : field.getWidgets()) {
            if (w.getCOSObject() != field.getCOSObject()) { // merged field/widget shares one dictionary
                w.getCOSObject().removeItem(COSName.DA);
            }
        }
    }

    // ------------------------------------------------------------------ overlays

    /**
     * Legacy MacroForge "custom fields": single-line text drawn into the page content at a fixed rect.
     * Font: embedded Liberation Sans. Size: the manifest maximum (default 10pt), capped at 70% of the
     * rect height, shrunk to fit the width; below 6pt it is an error (never truncated).
     */
    private void planOverlay(PDDocument doc, Manifest.Overlay o, JsonNode data, Run run, List<Runnable> writes) {
        String label = "overlay:" + o.id();
        if (o.page() < 0 || o.page() >= doc.getNumberOfPages() || o.rect() == null || o.rect().size() != 4) {
            run.error("INVALID_RULE", label, null, "overlay page or rect invalid");
            return;
        }
        PDPage page = doc.getPage(o.page());
        PDRectangle box = page.getMediaBox();
        float x0 = o.rect().get(0).floatValue(), y0 = o.rect().get(1).floatValue();
        float x1 = o.rect().get(2).floatValue(), y1 = o.rect().get(3).floatValue();
        if (page.getRotation() % 360 != 0) {
            run.error("OVERLAY_ROTATED_PAGE", label, null, "overlay on a rotated page is not supported");
            return;
        }
        if (x1 <= x0 || y1 <= y0 || x0 < box.getLowerLeftX() - 1 || y0 < box.getLowerLeftY() - 1
                || x1 > box.getUpperRightX() + 1 || y1 > box.getUpperRightY() + 1) {
            run.error("OVERLAY_OUT_OF_PAGE", label, null, "overlay rect is empty or outside the page");
            return;
        }
        String value = scalar(label, o.source(), o.required(), data, run);
        if (value == null) {
            return;
        }
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            run.error("MULTILINE_NOT_ALLOWED", label, keyOf(o.source()), "overlays are single-line");
            return;
        }
        try {
            for (PDAnnotation a : page.getAnnotations()) {
                if (a instanceof PDAnnotationWidget w && intersects(w.getRectangle(), x0, y0, x1, y1)) {
                    PDAppearanceCharacteristicsDictionary mk = w.getAppearanceCharacteristics();
                    if (mk != null && mk.getBackground() != null) {
                        run.warning("OVERLAY_UNDER_OPAQUE_WIDGET", label, null,
                                "overlay intersects a widget with a background colour; it may be hidden");
                    }
                }
            }
        } catch (IOException e) {
            run.warning("OVERLAY_ANNOTATIONS_UNREADABLE", label, null, "could not check widgets under the overlay");
        }
        float maxSize = o.fontSize() == null ? OVERLAY_DEFAULT_SIZE : o.fontSize().floatValue();
        writes.add(() -> {
            try {
                PDType0Font font = run.unicodeFont();
                try {
                    font.encode(value);
                } catch (IllegalArgumentException e) {
                    run.error("UNENCODABLE_TEXT", label, keyOf(o.source()), "overlay font cannot encode the value");
                    return;
                }
                float h = y1 - y0, avail = (x1 - x0) - 2 * OVERLAY_PADDING;
                float size = Math.min(maxSize, h * 0.7f);
                float width = font.getStringWidth(value) / 1000f * size;
                if (width > avail) {
                    size = size * avail / width;
                }
                if (size < OVERLAY_MIN_SIZE) {
                    run.error("OVERLAY_TEXT_TOO_LONG", label, keyOf(o.source()),
                            "value does not fit the overlay at the minimum font size (no truncation)");
                    return;
                }
                float capHeight = font.getFontDescriptor() != null && font.getFontDescriptor().getCapHeight() > 0
                        ? font.getFontDescriptor().getCapHeight() / 1000f : 0.7f;
                float baseline = y0 + (h - size * capHeight) / 2f;
                try (PDPageContentStream cs = new PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND,
                        true, true)) {
                    cs.beginText();
                    cs.setFont(font, size);
                    cs.setNonStrokingColor(0f);
                    cs.newLineAtOffset(x0 + OVERLAY_PADDING, baseline);
                    cs.showText(value);
                    cs.endText();
                }
                run.filled.add(label);
            } catch (IOException e) {
                run.error("APPEARANCE_FAILED", label, keyOf(o.source()), "could not draw overlay");
            }
        });
    }

    private static boolean intersects(PDRectangle r, float x0, float y0, float x1, float y1) {
        return r != null && r.getLowerLeftX() < x1 && r.getUpperRightX() > x0
                && r.getLowerLeftY() < y1 && r.getUpperRightY() > y0;
    }

    private Boolean matches(FieldRule.Condition c, JsonNode data, String field, Run run) {
        if (c.key() == null || c.operator() == null || (c.value() == null && c.operator() != FieldRule.Operator.present)) {
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
            case present -> !s.isBlank();
        };
    }

    // ------------------------------------------------------------------ manifest validation (D-023)

    /** Separator Monday uses in the {@code .text} of a multi-label dropdown. */
    static final String MULTI_SELECT_SEPARATOR = ", ";

    /**
     * Applies the manifest's reviewed data checks: closed options (every incoming choice value must be represented by
     * a rule, so a renamed Monday label or a multi-selection never leaves boxes silently blank) and business
     * preconditions. Issues carry keys, rule ids and codes only.
     */
    private void validate(Manifest manifest, JsonNode data, Run run) {
        for (Manifest.ClosedOption co : manifest.validation().closedOptions()) {
            List<FieldRule.Condition> conds = manifest.fields().stream()
                    .map(r -> r.source() == null ? null : r.source().when())
                    .filter(c -> c != null && co.key() != null && co.key().equals(c.key())).toList();
            if (conds.isEmpty()) {
                run.error("INVALID_RULE", null, co.key(), "closedOptions key has no when-rule in this manifest");
                continue;
            }
            JsonNode v = data.get(co.key());
            if (v == null || v.isNull() || (v.isTextual() && v.asText().isBlank())) {
                continue; // absence is handled by the field rules (SOURCE_KEY_ABSENT / not selected)
            }
            if (!v.isValueNode() || v.isBoolean()) {
                run.error("AMBIGUOUS_SOURCE_VALUE", null, co.key(), "closed-option source must be a text value");
                continue;
            }
            String value = v.asText();
            if (co.multiSelect() && value.contains(MULTI_SELECT_SEPARATOR)) {
                run.error("MULTI_SELECT_NOT_REPRESENTABLE", null, co.key(),
                        "several labels selected; this form's legacy rules can represent only one");
                continue;
            }
            boolean represented = conds.stream().anyMatch(c -> switch (c.operator()) {
                case equals -> value.equals(c.value());
                case contains -> c.value() != null && value.contains(c.value());
                case present -> false;
            });
            if (!represented) {
                if ("blank".equals(co.onUnmapped())) {
                    run.warning("UNMAPPED_OPTION_LEFT_BLANK", null, co.key(),
                            "value matches no box on this form; boxes left blank by reviewed policy");
                } else {
                    run.error("UNMAPPED_OPTION", null, co.key(),
                            "value matches no box on this form (renamed Monday label or missing rule); not generated");
                }
            }
        }
        for (Manifest.Requirement req : manifest.validation().requires()) {
            if (req.ifPresent() == null || req.require() == null) {
                run.error("INVALID_RULE", null, req.ifPresent(), "requirement " + req.id() + " needs ifPresent and require");
                continue;
            }
            JsonNode trigger = data.get(req.ifPresent());
            if (trigger == null || trigger.isNull() || (trigger.isTextual() && trigger.asText().isBlank())) {
                continue;
            }
            Boolean ok = matches(req.require(), data, null, run);
            if (ok != null && !ok) {
                run.error("PRECONDITION_FAILED", null, req.require().key(),
                        "requirement " + req.id() + " not met: " + req.reason());
            }
        }
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
        final Manifest manifest;
        PDDocument doc;
        CombAppearance combs;
        boolean fullUnicodeFont;
        private PDType0Font unicodeFont;
        final List<Issue> issues = new ArrayList<>();
        final List<String> filled = new ArrayList<>();
        final List<String> skipped = new ArrayList<>();
        final Set<String> decidedButtons = new java.util.HashSet<>();

        void error(String code, String f, String k, String m) {
            issues.add(Issue.error(code, f, k, m));
        }

        void warning(String code, String f, String k, String m) {
            issues.add(Issue.warning(code, f, k, m));
        }

        Run(Manifest manifest) {
            this.manifest = manifest;
        }

        /** Loaded once per document: subset for overlays only, full embedding if a form field uses it. */
        PDType0Font unicodeFont() throws IOException {
            if (unicodeFont == null) {
                try (InputStream in = FormFiller.class.getResourceAsStream(UNICODE_FONT)) {
                    if (in == null) {
                        throw new IOException("bundled Unicode font missing");
                    }
                    unicodeFont = PDType0Font.load(doc, in, !fullUnicodeFont);
                }
            }
            return unicodeFont;
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
