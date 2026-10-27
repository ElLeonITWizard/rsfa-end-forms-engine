package nz.co.rsfa.endforms.mapping;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * One instruction: put a value (or a checked state) into one PDF field.
 *
 * <p>Several rules may target the same button field with different {@code option}s
 * (legacy {@code Field#option} syntax); at most one of them may select at fill time.
 *
 * <p>{@code approval}: a constant "checked" rule ticks a box regardless of the data, which can amount to
 * a declaration, certification or consent. The engine applies it only with a complete, valid
 * {@link Approval}. The bare legacy flag {@code approved: true} is rejected (D-016).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FieldRule(
        String pdfField,
        Kind kind,
        String option,
        Source source,
        boolean required,
        Approval approval,
        Boolean approved) {

    public enum Kind { text, checkbox, radio, combo, list }

    /**
     * A recorded human authorization for a constant tick.
     *
     * @param approvedBy who authorized it (name or role at RSFA)
     * @param approvedOn ISO date of the authorization; must not be in the future
     * @param reference  where the decision is recorded (ticket, email, decision-log id)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Approval(String approvedBy, String approvedOn, String reference) {
    }

    /**
     * Exactly one of {@code key}, {@code constant}, {@code generated}, {@code when} or {@code lineItem} is set.
     *
     * @param key       top-level key of the request {@code data} object (a Monday column title)
     * @param constant  literal text, or boolean for a fixed checkbox state
     * @param generated currently only {@code "today"}
     * @param format    {@link java.time.format.DateTimeFormatter} pattern for generated dates
     * @param zone      IANA zone for generated dates
     * @param when      condition that selects a button option / checks a checkbox
     * @param lineItem  one value from one element of a repeating array (legacy {@code LineItems.<field>_<n>})
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Source(String key, JsonNode constant, String generated, String format, String zone,
                         Condition when, LineItem lineItem) {
        public Source {
            constant = constant == null || constant.isNull() || constant.isMissingNode() ? null : constant;
        }
    }

    /**
     * @param array    top-level data key holding the array (e.g. {@code Subitems})
     * @param position 1-based element position, as in the legacy {@code _<n>} suffix
     * @param path     keys inside the element, e.g. {@code ["values", "Loan amount"]}
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LineItem(String array, int position, java.util.List<String> path) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Condition(String key, Operator operator, String value) {
    }

    /** Legacy MacroForge operators. Matching is exact and case-sensitive (legacy semantics unverified). */
    public enum Operator { equals, contains }
}
