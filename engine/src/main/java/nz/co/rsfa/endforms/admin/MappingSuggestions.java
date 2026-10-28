package nz.co.rsfa.endforms.admin;

import java.util.List;

/**
 * Provider-independent contract for mapping suggestions ({@code contracts/mapping-suggestions.v1.schema.json}, D-039).
 *
 * <p>The request carries template STRUCTURE only (field names, kinds, options, printed labels) plus the Monday schema
 * (column IDs, titles, types, labels) and reference mappings (which column an imported MacroForge manifest uses for a
 * similarly named field). It never carries data values or the PDF itself. A response is a list of proposals for a
 * person to accept or reject one by one; nothing is applied automatically and nothing is executable.
 */
public final class MappingSuggestions {

    private MappingSuggestions() {
    }

    public static final String CONTRACT = "mapping-suggestions.v1";

    public record TemplateField(String name, String kind, List<String> options, String label, boolean protectedField,
                                boolean declarationLike, boolean alreadyMapped) {
    }

    public record SchemaColumn(String id, String title, String type, List<String> labels) {
    }

    /** A field of an imported (MacroForge-derived) manifest and the runtime key it reads. */
    public record Reference(String templateKey, String pdfField, String label, String sourceKey) {
    }

    /** @param dataPolicy always {@code "structure-only"}: no data values, no PDF bytes */
    public record Request(String contractVersion, String templateKey, String dataPolicy, List<TemplateField> fields,
                          List<SchemaColumn> columns, List<Reference> references) {
    }

    /**
     * @param sourceType  {@code column} (text value) or {@code condition} (tick when the column has {@code value})
     * @param confidence  0..1, the provider's own estimate; never a permission to apply
     */
    public record Proposal(String pdfField, String sourceType, String columnId, String columnTitle, String operator,
                           String value, String option, double confidence, List<String> evidence,
                           List<String> ambiguities, List<Alternative> alternatives) {
    }

    public record Alternative(String columnId, String columnTitle, double confidence) {
    }

    /** Fields the provider must not propose for (signatures, declarations, certifications, consents). */
    public record Refusal(String pdfField, String reason) {
    }

    /** @param isMock true when the proposals do not come from an AI model (must be shown to the user) */
    public record Provider(String id, boolean isMock, String model, String notice) {
    }

    public record Response(String contractVersion, Provider provider, String generatedAt, List<Proposal> proposals,
                           List<Refusal> refused, List<String> unmatched) {
    }

    /** A source of suggestions. Implementations: {@link MockSuggestionProvider}; a live AI provider is not created. */
    public interface SuggestionProvider {
        Response suggest(Request request);
    }
}
