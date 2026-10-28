package nz.co.rsfa.endforms.mapping;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * A version-controlled mapping for exactly one template binary (identified by SHA-256).
 * Produced by {@code tools/import_mforge.py}; see docs/02-poc-guide-and-tests.md for the schema.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Manifest(
        int schemaVersion,
        String templateId,
        String templateVersion,
        String templateFile,
        String templateSha256,
        EncryptionPolicy encryption,
        KeyNormalization sourceKeyNormalization,
        FontFallback fontFallback,
        List<FieldRule> fields,
        List<Overlay> overlays,
        List<JsonNode> unsupported,
        Validation validation) {

    public static final int SUPPORTED_SCHEMA_VERSION = 1;

    /** What to do when the template is encrypted (typically bank owner-password restrictions). */
    public enum EncryptionPolicy {
        /** Refuse to fill (default): the bank's document restrictions are not silently altered. */
        reject,
        /** Fill and re-save with the template's original encryption and permissions. */
        preserve
    }

    /** How incoming data keys are normalised before lookup. */
    public enum KeyNormalization {
        /** Exact key match. */
        none,
        /**
         * Legacy MacroForge behaviour: '/' and '.' in keys become '_' (evidence: the .mforge sample
         * payload has 0 of 476 keys containing either character; the Make contract has 30).
         */
        @com.fasterxml.jackson.annotation.JsonProperty("macroforge-v1")
        macroforgeV1
    }

    /** What to do when a form field's own font cannot encode a value (e.g. Māori macrons). */
    public enum FontFallback {
        /** Fail with UNENCODABLE_TEXT. */
        none,
        /** Re-point that field's appearance at the engine's embedded Unicode font (Liberation Sans). */
        unicode
    }

    /**
     * Text drawn at a fixed position (legacy MacroForge "custom field").
     *
     * @param rect     [llx, lly, urx, ury] in PDF points, bottom-left origin (already converted by the importer)
     * @param fontSize maximum font size in points; the engine shrinks to fit, never truncates (default 10)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Overlay(String id, int page, List<Double> rect, FieldRule.Source source, boolean required,
                          Double fontSize) {
    }

    /**
     * Data checks applied before any field is planned (reviewed per template; see docs/06). No data values are
     * ever reported, only keys, rule ids and codes.
     *
     * @param closedOptions keys whose value must select one of this manifest's {@code when} rules
     * @param requires      business preconditions, e.g. "a board amount is only valid for this form if weekly"
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Validation(List<ClosedOption> closedOptions, List<Requirement> requires) {
        public Validation {
            closedOptions = closedOptions == null ? List.of() : List.copyOf(closedOptions);
            requires = requires == null ? List.of() : List.copyOf(requires);
        }
    }

    /**
     * A choice column whose every value must be represented on the form.
     *
     * @param key         data key (Monday column title) used by the manifest's {@code when} rules
     * @param multiSelect the Monday column allows several labels (its text is joined with ", "); the legacy
     *                    {@code equals} rules can only represent one, so more than one is refused
     * @param onUnmapped  {@code error} (default): refuse the document; {@code blank}: leave the boxes empty with a warning
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ClosedOption(String key, boolean multiSelect, String onUnmapped) {
    }

    /**
     * When {@code ifPresent} has a value (or the {@code when} condition holds), {@code require} must hold, otherwise the
     * document is refused. Exactly one of {@code ifPresent} / {@code when} is set.
     *
     * @param id     stable identifier reported in issues
     * @param reason why, in business terms (reported; must not contain data)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Requirement(String id, String ifPresent, FieldRule.Condition when, FieldRule.Condition require,
                              String reason) {
    }

    public Manifest {
        validation = validation == null ? new Validation(null, null) : validation;
        encryption = encryption == null ? EncryptionPolicy.reject : encryption;
        sourceKeyNormalization = sourceKeyNormalization == null ? KeyNormalization.none : sourceKeyNormalization;
        fontFallback = fontFallback == null ? FontFallback.none : fontFallback;
        fields = fields == null ? List.of() : List.copyOf(fields);
        overlays = overlays == null ? List.of() : List.copyOf(overlays);
        unsupported = unsupported == null ? List.of() : List.copyOf(unsupported);
    }

    public String key() {
        return templateId + "@" + templateVersion;
    }
}
