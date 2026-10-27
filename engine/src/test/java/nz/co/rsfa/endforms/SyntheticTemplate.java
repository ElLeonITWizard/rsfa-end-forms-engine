package nz.co.rsfa.endforms;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceEntry;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDComboBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDRadioButton;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.apache.pdfbox.cos.COSDictionary;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds a fictional AcroForm template (no bank content) exercising each widget kind the engine
 * supports, including a legacy-style multi-widget checkbox whose widgets have different on-states.
 */
final class SyntheticTemplate {

    private SyntheticTemplate() {
    }

    static byte[] build() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDType1Font helv = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDResources dr = new PDResources();
            dr.put(COSName.getPDFName("Helv"), helv);
            PDAcroForm form = new PDAcroForm(doc);
            form.setDefaultResources(dr);
            form.setDefaultAppearance("/Helv 10 Tf 0 g");
            doc.getDocumentCatalog().setAcroForm(form);

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(helv, 12);
                cs.newLineAtOffset(40, 800);
                cs.showText("SYNTHETIC TEST FORM - not a lender document");
                cs.endText();
            }

            text(form, page, "Full name", 40, 740, 300, 0, false);
            text(form, page, "Postcode", 40, 710, 80, 4, false);
            text(form, page, "Date", 40, 680, 120, 6, true);
            text(form, page, "Notes", 40, 600, 300, 0, false).setMultiline(true);

            checkbox(doc, form, page, "Consent box", List.of("Yes"), 40, 560);
            checkbox(doc, form, page, "Frequency", List.of("1", "2", "3"), 40, 530);

            PDRadioButton radio = new PDRadioButton(form);
            radio.setPartialName("Residency");
            radio.setWidgets(widgets(doc, page, radio.getCOSObject(), List.of("NZ", "Other"), 40, 500));
            form.getFields().add(radio);

            PDComboBox combo = new PDComboBox(form);
            combo.setPartialName("Repayment frequency");
            combo.setDefaultAppearance("/Helv 10 Tf 0 g");
            combo.setOptions(List.of("Weekly", "Fortnightly", "Monthly"));
            PDAnnotationWidget cw = combo.getWidgets().getFirst();
            cw.setRectangle(new PDRectangle(40, 460, 150, 18));
            cw.setPage(page);
            page.getAnnotations().add(cw);
            form.getFields().add(combo);

            PDSignatureField sig = new PDSignatureField(form);
            sig.setPartialName("Customer signature");
            PDAnnotationWidget sw = sig.getWidgets().getFirst();
            sw.setRectangle(new PDRectangle(40, 380, 200, 40));
            sw.setPage(page);
            page.getAnnotations().add(sw);
            form.getFields().add(sig);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static PDTextField text(PDAcroForm form, PDPage page, String name, float x, float y, float w,
                                    int maxLen, boolean comb) throws IOException {
        PDTextField f = new PDTextField(form);
        f.setPartialName(name);
        f.setDefaultAppearance("/Helv 10 Tf 0 g");
        if (maxLen > 0) {
            f.setMaxLen(maxLen);
        }
        f.setComb(comb);
        PDAnnotationWidget wdg = f.getWidgets().getFirst();
        wdg.setRectangle(new PDRectangle(x, y, w, 18));
        wdg.setPage(page);
        page.getAnnotations().add(wdg);
        form.getFields().add(f);
        return f;
    }

    private static void checkbox(PDDocument doc, PDAcroForm form, PDPage page, String name, List<String> states, float x, float y)
            throws IOException {
        PDCheckBox cb = new PDCheckBox(form);
        cb.setPartialName(name);
        cb.setWidgets(widgets(doc, page, cb.getCOSObject(), states, x, y));
        form.getFields().add(cb);
    }

    /** One widget per on-state, each with an on and Off appearance, like real lender forms. */
    private static List<PDAnnotationWidget> widgets(PDDocument doc, PDPage page, COSDictionary parent, List<String> states,
                                                    float x, float y) throws IOException {
        List<PDAnnotationWidget> out = new ArrayList<>();
        for (int i = 0; i < states.size(); i++) {
            PDAnnotationWidget w = new PDAnnotationWidget();
            w.getCOSObject().setItem(COSName.PARENT, parent);
            w.setRectangle(new PDRectangle(x + i * 30, y, 14, 14));
            w.setPage(page);
            PDAppearanceDictionary ap = new PDAppearanceDictionary();
            COSDictionary normal = new COSDictionary();
            normal.setItem(COSName.getPDFName(states.get(i)), box(doc, true));
            normal.setItem(COSName.Off, box(doc, false));
            ap.setNormalAppearance(new PDAppearanceEntry(normal));
            w.setAppearance(ap);
            w.setAppearanceState("Off");
            page.getAnnotations().add(w);
            out.add(w);
        }
        return out;
    }

    private static PDAppearanceStream box(PDDocument doc, boolean filled) throws IOException {
        PDAppearanceStream s = new PDAppearanceStream(doc);
        s.setBBox(new PDRectangle(14, 14));
        try (var cs = new PDPageContentStream(doc, s)) {
            cs.addRect(1, 1, 12, 12);
            if (filled) {
                cs.fill();
            } else {
                cs.stroke();
            }
        }
        return s;
    }
}
