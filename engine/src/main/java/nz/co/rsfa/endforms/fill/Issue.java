package nz.co.rsfa.endforms.fill;

/**
 * A structured validation finding. Messages must never contain request data values,
 * only field names, keys and codes, so issues are safe to log and return.
 */
public record Issue(Severity severity, String code, String pdfField, String sourceKey, String message) {

    public enum Severity { ERROR, WARNING }

    static Issue error(String code, String pdfField, String sourceKey, String message) {
        return new Issue(Severity.ERROR, code, pdfField, sourceKey, message);
    }

    static Issue warning(String code, String pdfField, String sourceKey, String message) {
        return new Issue(Severity.WARNING, code, pdfField, sourceKey, message);
    }
}
