package nz.co.rsfa.endforms.pdf;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Structural inventory of a template's form fields. Never reports field values. */
public final class TemplateInspector {

    private TemplateInspector() {
    }

    public record FieldInfo(String name, String kind, List<String> states, int widgets, List<Integer> pages,
                            Integer maxLen, boolean comb, boolean multiline, boolean readOnly, boolean required) {
    }

    public record TemplateInfo(int pages, boolean encrypted, boolean acroForm, boolean xfa,
                               boolean needAppearances, List<FieldInfo> fields, List<String> duplicateNames) {
    }

    public static TemplateInfo inspect(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDAcroForm form = doc.getDocumentCatalog().getAcroForm();
            if (form == null) {
                return new TemplateInfo(doc.getNumberOfPages(), doc.isEncrypted(), false, false, false, List.of(), List.of());
            }
            Map<String, Integer> nameCount = new LinkedHashMap<>();
            List<FieldInfo> out = new ArrayList<>();
            for (PDField f : form.getFieldTree()) {
                if (f instanceof org.apache.pdfbox.pdmodel.interactive.form.PDNonTerminalField) {
                    continue;
                }
                nameCount.merge(f.getFullyQualifiedName(), 1, Integer::sum);
                List<Integer> pages = new ArrayList<>();
                for (PDAnnotationWidget w : f.getWidgets()) {
                    PDPage p = w.getPage();
                    pages.add(p == null ? -1 : doc.getPages().indexOf(p));
                }
                List<String> states = new ArrayList<>();
                if (f instanceof PDButton b && !(f instanceof PDPushButton)) {
                    states.addAll(new TreeSet<>(b.getOnValues()));
                } else if (f instanceof PDChoice c) {
                    states.addAll(c.getOptionsDisplayValues());
                }
                Integer maxLen = f instanceof PDTextField t && t.getMaxLen() > 0 ? t.getMaxLen() : null;
                out.add(new FieldInfo(f.getFullyQualifiedName(), kind(f), states, f.getWidgets().size(), pages, maxLen,
                        f instanceof PDTextField t2 && t2.isComb(), f instanceof PDTextField t3 && t3.isMultiline(),
                        f.isReadOnly(), f.isRequired()));
            }
            List<String> dups = nameCount.entrySet().stream().filter(e -> e.getValue() > 1).map(Map.Entry::getKey).toList();
            return new TemplateInfo(doc.getNumberOfPages(), doc.isEncrypted(), true, form.hasXFA(),
                    form.getNeedAppearances(), out, dups);
        }
    }

    public static String kind(PDField f) {
        if (f instanceof PDTextField) return "text";
        if (f instanceof PDCheckBox) return "checkbox";
        if (f instanceof PDRadioButton) return "radio";
        if (f instanceof PDComboBox) return "combo";
        if (f instanceof PDListBox) return "list";
        if (f instanceof PDSignatureField) return "signature";
        if (f instanceof PDPushButton) return "pushbutton";
        return "unknown";
    }
}
