package nz.co.rsfa.endforms.fill;

import java.util.List;

/**
 * Outcome of a fill. {@code pdf} is null whenever any ERROR issue exists (strict by design).
 *
 * @param filled  PDF fields that received a value or state
 * @param skipped PDF fields left untouched because the (optional) source value was empty/absent
 *                or no button condition matched
 */
public record FillResult(byte[] pdf, List<Issue> issues, List<String> filled, List<String> skipped) {

    public boolean ok() {
        return pdf != null;
    }

    public List<Issue> errors() {
        return issues.stream().filter(i -> i.severity() == Issue.Severity.ERROR).toList();
    }
}
