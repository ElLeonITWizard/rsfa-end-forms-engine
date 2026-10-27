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
        List<FieldRule> fields,
        List<JsonNode> unsupported) {

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

    public Manifest {
        encryption = encryption == null ? EncryptionPolicy.reject : encryption;
        sourceKeyNormalization = sourceKeyNormalization == null ? KeyNormalization.none : sourceKeyNormalization;
        fields = fields == null ? List.of() : List.copyOf(fields);
        unsupported = unsupported == null ? List.of() : List.copyOf(unsupported);
    }

    public String key() {
        return templateId + "@" + templateVersion;
    }
}
