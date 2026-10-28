package nz.co.rsfa.endforms.admin;

import nz.co.rsfa.endforms.fill.FormFiller;
import nz.co.rsfa.endforms.pdf.TemplateInspector;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDButton;
import org.apache.pdfbox.pdmodel.interactive.form.PDChoice;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDNonTerminalField;
import org.apache.pdfbox.pdmodel.interactive.form.PDPushButton;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.PDFTextStripperByArea;

import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * Upload validation and field inspection for the administration area (D-038). Accepts only a BLANK fillable bank
 * template: anything with a customer value or a signature is rejected, so a client document can never enter the
 * template library by mistake. Field values are never returned; findings name fields and codes only.
 *
 * <p>Labels: the printed text immediately left of, or above, each widget (template text, not data). It is context for
 * the person mapping the field and for mapping suggestions.
 */
public final class TemplateAnalysis {

    public static final long MAX_UPLOAD_BYTES = 15L * 1024 * 1024;

    private TemplateAnalysis() {
    }

    /** @param severity {@code error} (upload refused), {@code warning} or {@code info} */
    public record Finding(String severity, String code, String field, String message) {
    }

    /** One widget, in PDF points with a top-left origin ({@code x}, {@code y} = top-left corner), for overlays. */
    public record Widget(int page, double x, double y, double width, double height, String onState) {
    }

    /**
     * @param protectedField signature or push button: never mappable
     * @param label          printed text near the first widget (may be empty)
     */
    public record Field(String name, String kind, List<String> options, List<Widget> widgets, Integer maxLen,
                        boolean comb, boolean multiline, boolean readOnly, boolean required, boolean protectedField,
                        String label) {
    }

    public record Page(int index, double width, double height, int rotation) {
    }

    /**
     * @param accepted   true when no {@code error} finding exists
     * @param textChars  characters of extractable page text (0 with images: a scanned or flattened document)
     */
    public record Report(boolean accepted, String sha256, long bytes, int pages, boolean encrypted, boolean acroForm,
                         boolean xfa, int textChars, int images, List<Page> pageSizes, List<Field> fields,
                         List<Finding> findings) {
    }

    public static Report analyse(byte[] pdf) {
        List<Finding> findings = new ArrayList<>();
        String sha = FormFiller.sha256(pdf);
        if (pdf.length > MAX_UPLOAD_BYTES) {
            findings.add(new Finding("error", "TOO_LARGE", null, "file exceeds " + MAX_UPLOAD_BYTES / (1024 * 1024) + " MiB"));
            return rejected(sha, pdf.length, findings);
        }
        String head = new String(pdf, 0, Math.min(pdf.length, 1024), StandardCharsets.ISO_8859_1);
        if (!head.contains("%PDF-")) {
            findings.add(new Finding("error", "NOT_A_PDF", null, "the file is not a PDF"));
            return rejected(sha, pdf.length, findings);
        }
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return inspect(doc, sha, pdf.length, findings);
        } catch (InvalidPasswordException e) {
            findings.add(new Finding("error", "PASSWORD_PROTECTED", null,
                    "the PDF needs a password to open; upload the bank's unprotected blank form"));
        } catch (IOException | RuntimeException e) {
            findings.add(new Finding("error", "CORRUPT_PDF", null, "the PDF could not be parsed (" + e.getClass().getSimpleName() + ")"));
        }
        return rejected(sha, pdf.length, findings);
    }

    private static Report rejected(String sha, long bytes, List<Finding> findings) {
        return new Report(false, sha, bytes, 0, false, false, false, 0, 0, List.of(), List.of(), List.copyOf(findings));
    }

    private static Report inspect(PDDocument doc, String sha, long bytes, List<Finding> findings) throws IOException {
        List<Page> pages = new ArrayList<>();
        int images = 0;
        for (int i = 0; i < doc.getNumberOfPages(); i++) {
            PDPage p = doc.getPage(i);
            PDRectangle box = p.getCropBox();
            pages.add(new Page(i, box.getWidth(), box.getHeight(), p.getRotation()));
            if (p.getResources() != null) {
                for (var n : p.getResources().getXObjectNames()) {
                    if (p.getResources().isImageXObject(n)) {
                        images++;
                    }
                }
            }
        }
        PDFTextStripper stripper = new PDFTextStripper();
        int textChars = stripper.getText(doc).replaceAll("\\s+", "").length();
        if (doc.isEncrypted()) {
            findings.add(new Finding("warning", "OWNER_ENCRYPTED", null, "the bank's permissions are kept: the mapping "
                    + "is created with encryption 'preserve'"));
        }
        PDAcroForm form = doc.getDocumentCatalog().getAcroForm();
        boolean xfa = form != null && form.hasXFA();
        List<Field> fields = new ArrayList<>();
        if (form != null) {
            Map<String, Integer> names = new LinkedHashMap<>();
            for (PDField f : form.getFieldTree()) {
                if (f instanceof PDNonTerminalField) {
                    continue;
                }
                names.merge(f.getFullyQualifiedName(), 1, Integer::sum);
                fields.add(field(doc, f, findings));
            }
            names.forEach((n, c) -> {
                if (c > 1) {
                    findings.add(new Finding("warning", "DUPLICATE_FIELD_NAME", n, "several fields share this name"));
                }
            });
            labels(doc, fields);
        }
        if (fields.isEmpty()) {
            boolean scanned = textChars < 20 && images > 0;
            findings.add(new Finding("error", scanned ? "SCANNED_OR_FLATTENED" : "NO_FORM_FIELDS", null, scanned
                    ? "no form fields and no extractable text: a scanned or flattened document cannot be mapped with AcroForm"
                    : "no fillable (AcroForm) fields: the form would need position-based overlays, which the administration "
                    + "area does not create"));
        }
        if (xfa) {
            findings.add(new Finding(fields.isEmpty() ? "error" : "warning", "XFA", null, fields.isEmpty()
                    ? "dynamic XFA form without AcroForm fields: not supported"
                    : "hybrid XFA form: only its AcroForm fields are used"));
        }
        boolean accepted = findings.stream().noneMatch(x -> x.severity().equals("error"));
        return new Report(accepted, sha, bytes, doc.getNumberOfPages(), doc.isEncrypted(), form != null, xfa, textChars,
                images, pages, fields, List.copyOf(findings));
    }

    private static Field field(PDDocument doc, PDField f, List<Finding> findings) {
        String name = f.getFullyQualifiedName();
        String kind = TemplateInspector.kind(f);
        boolean protectedField = f instanceof PDSignatureField || f instanceof PDPushButton;
        if (f instanceof PDSignatureField s && s.getCOSObject().getDictionaryObject(org.apache.pdfbox.cos.COSName.V) != null) {
            findings.add(new Finding("error", "SIGNED", name, "a signature is present: upload the blank form"));
        } else if (f instanceof PDTextField t && t.getValue() != null && !t.getValue().isBlank()) {
            findings.add(new Finding("error", "FIELD_HAS_VALUE", name, "the field already contains text: upload the blank form"));
        } else if (f instanceof PDChoice c && !c.getValue().isEmpty() && c.getValue().stream().anyMatch(v -> !v.isBlank())) {
            findings.add(new Finding("error", "FIELD_HAS_VALUE", name, "an option is already selected: upload the blank form"));
        } else if (f instanceof PDButton b && !(f instanceof PDPushButton) && !"Off".equals(b.getValue()) && !b.getValue().isEmpty()) {
            findings.add(new Finding("warning", "PRESET_TICK", name,
                    "ticked in the template; the engine clears preset ticks unless a reviewed rule sets them"));
        }
        List<String> options = new ArrayList<>();
        if (f instanceof PDButton b && !(f instanceof PDPushButton)) {
            options.addAll(new TreeSet<>(b.getOnValues()));
        } else if (f instanceof PDChoice c) {
            options.addAll(c.getOptionsDisplayValues());
        }
        List<Widget> widgets = new ArrayList<>();
        for (PDAnnotationWidget w : f.getWidgets()) {
            PDPage page = w.getPage();
            int idx = page == null ? -1 : doc.getPages().indexOf(page);
            if (idx < 0) {
                idx = pageOf(doc, w);
            }
            PDRectangle r = w.getRectangle();
            if (idx < 0 || r == null) {
                continue;
            }
            PDRectangle crop = doc.getPage(idx).getCropBox();
            String on = null;
            if (w.getAppearance() != null && w.getAppearance().getNormalAppearance() != null
                    && w.getAppearance().getNormalAppearance().isSubDictionary()) {
                on = w.getAppearance().getNormalAppearance().getSubDictionary().keySet().stream()
                        .map(org.apache.pdfbox.cos.COSName::getName).filter(n -> !n.equals("Off")).findFirst().orElse(null);
            }
            widgets.add(new Widget(idx, r.getLowerLeftX() - crop.getLowerLeftX(),
                    crop.getUpperRightY() - r.getUpperRightY(), r.getWidth(), r.getHeight(), on));
        }
        Integer maxLen = f instanceof PDTextField t && t.getMaxLen() > 0 ? t.getMaxLen() : null;
        return new Field(name, kind, options, widgets, maxLen, f instanceof PDTextField t2 && t2.isComb(),
                f instanceof PDTextField t3 && t3.isMultiline(), f.isReadOnly(), f.isRequired(), protectedField, "");
    }

    /** Widgets whose /P entry is missing: find the page whose annotations contain the widget. */
    private static int pageOf(PDDocument doc, PDAnnotationWidget w) {
        for (int i = 0; i < doc.getNumberOfPages(); i++) {
            try {
                if (doc.getPage(i).getAnnotations().stream().anyMatch(a -> a.getCOSObject() == w.getCOSObject())) {
                    return i;
                }
            } catch (IOException e) {
                return -1;
            }
        }
        return -1;
    }

    /** Printed text left of, or else above, each field's first widget. */
    private static void labels(PDDocument doc, List<Field> fields) throws IOException {
        Map<Integer, List<Integer>> byPage = new LinkedHashMap<>();
        for (int i = 0; i < fields.size(); i++) {
            if (!fields.get(i).widgets().isEmpty()) {
                byPage.computeIfAbsent(fields.get(i).widgets().get(0).page(), k -> new ArrayList<>()).add(i);
            }
        }
        for (var e : byPage.entrySet()) {
            PDFTextStripperByArea area = new PDFTextStripperByArea();
            area.setSortByPosition(true);
            area.setSuppressDuplicateOverlappingText(false); // regions overlap; each must see its own characters
            PDRectangle crop = doc.getPage(e.getKey()).getCropBox();
            for (int i : e.getValue()) {
                Widget w = fields.get(i).widgets().get(0);
                double h = Math.max(w.height(), 8);
                double leftX = Math.max(0, w.x() - 230);
                area.addRegion("L" + i, new Rectangle2D.Double(leftX, w.y() - 1, w.x() - leftX - 1, h + 2));
                area.addRegion("R" + i, new Rectangle2D.Double(w.x() + w.width() + 1, w.y() - 1,
                        Math.max(1, Math.min(220, crop.getWidth() - w.x() - w.width() - 1)), h + 2));
                area.addRegion("A" + i, new Rectangle2D.Double(w.x() - 4, Math.max(0, w.y() - 16), Math.min(w.width() + 160,
                        crop.getWidth() - w.x() + 4), 15));
            }
            area.extractRegions(doc.getPage(e.getKey()));
            for (int i : e.getValue()) {
                String left = clean(area.getTextForRegion("L" + i));
                String right = clean(area.getTextForRegion("R" + i));
                String above = clean(area.getTextForRegion("A" + i));
                Field f = fields.get(i);
                // check boxes and radio buttons are usually labelled on the right; text fields on the left or above
                boolean button = f.kind().equals("checkbox"); // a radio group is labelled on the left of its first option
                String label = button ? first(right, left, above) : first(left, above);
                fields.set(i, new Field(f.name(), f.kind(), f.options(), f.widgets(), f.maxLen(), f.comb(), f.multiline(),
                        f.readOnly(), f.required(), f.protectedField(), label));
            }
        }
    }

    private static String first(String... candidates) {
        for (String c : candidates) {
            if (!c.isEmpty()) {
                return c;
            }
        }
        return "";
    }

    private static String clean(String s) {
        String t = s == null ? "" : s.replaceAll("\\s+", " ").trim();
        int nl = t.length();
        return nl > 120 ? t.substring(nl - 120) : t;
    }

    /** Lower-cased label and name, for heuristics. */
    static String context(Field f) {
        return (f.label() + " " + f.name()).toLowerCase(Locale.ROOT);
    }

    /** True for a field whose label reads like a declaration, certification, consent or signature line. */
    public static boolean declarationLike(Field f) {
        String c = context(f);
        return f.protectedField() || c.matches(".*\\b(declar\\w*|certif\\w*|consent\\w*|acknowledg\\w*|sign(ed|ature)?|authoris\\w*|authoriz\\w*|confirm\\w*|agree\\w*|witness\\w*)\\b.*");
    }
}
