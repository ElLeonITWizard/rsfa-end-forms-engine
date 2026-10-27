package nz.co.rsfa.endforms.mapping;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * One instruction: put a value (or a checked state) into one PDF field.
 *
 * <p>Several rules may target the same button field with different {@code option}s
 * (legacy {@code Field#option} syntax); at most one of them may select at fill time.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FieldRule(
        String pdfField,
        Kind kind,
        String option,
        Source source,
        boolean required) {

    public enum Kind { text, checkbox, radio, combo, list }

    /**
     * Exactly one of {@code key}, {@code constant}, {@code generated} or {@code when} is set.
     *
     * @param key       top-level key of the request {@code data} object (a Monday column title)
     * @param constant  literal text, or boolean for a fixed checkbox state
     * @param generated currently only {@code "today"}
     * @param format    {@link java.time.format.DateTimeFormatter} pattern for generated dates
     * @param zone      IANA zone for generated dates
     * @param when      condition that selects a button option / checks a checkbox
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Source(String key, JsonNode constant, String generated, String format, String zone,
                         Condition when) {
        public Source {
            constant = constant == null || constant.isNull() || constant.isMissingNode() ? null : constant;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Condition(String key, Operator operator, String value) {
    }

    /** Legacy MacroForge operators. Matching is exact and case-sensitive (legacy semantics unverified). */
    public enum Operator { equals, contains }
}
