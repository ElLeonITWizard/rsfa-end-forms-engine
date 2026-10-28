package nz.co.rsfa.endforms.admin;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceCharacteristicsDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceEntry;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDComboBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDRadioButton;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A FICTIONAL bank form ("Example Bank — Customer Account Questionnaire") for demos, browser tests and screenshots of
 * the administration area, so no real lender PDF is needed. It has printed labels next to each field and every field
 * kind the engine handles, plus a signature field and a declaration box that must never be filled.
 */
public final class ExampleForm {

    private ExampleForm() {
    }

    public static byte[] build() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDType1Font helv = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDType1Font zapf = new PDType1Font(Standard14Fonts.FontName.ZAPF_DINGBATS);
            PDResources dr = new PDResources();
            dr.put(COSName.getPDFName("Helv"), helv);
            dr.put(COSName.getPDFName("ZaDb"), zapf);
            PDAcroForm form = new PDAcroForm(doc);
            form.setDefaultResources(dr);
            form.setDefaultAppearance("/Helv 10 Tf 0 g");
            doc.getDocumentCatalog().setAcroForm(form);
            List<String[]> labels = new ArrayList<>();
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setNonStrokingColor(0.07f, 0.21f, 0.36f);
                cs.addRect(0, 780, 595, 62);
                cs.fill();
                text(cs, bold, 18, 40, 805, "EXAMPLE BANK", 1f);
                text(cs, helv, 11, 40, 788, "Customer Account Questionnaire  (fictional form for testing)", 1f);
                text(cs, bold, 12, 40, 750, "1. Customer details", 0f);
                text(cs, helv, 10, 40, 722, "Customer full name", 0f);
                text(cs, helv, 10, 40, 697, "Postal address", 0f);
                text(cs, helv, 10, 40, 672, "Town or city", 0f);
                text(cs, helv, 10, 360, 672, "Postcode", 0f);
                text(cs, helv, 10, 40, 647, "Customer number", 0f);
                text(cs, bold, 12, 40, 610, "2. Loan", 0f);
                text(cs, helv, 10, 40, 582, "Loan amount", 0f);
                text(cs, helv, 10, 40, 557, "Repayment frequency", 0f);
                text(cs, helv, 10, 212, 557, "Weekly", 0f);
                text(cs, helv, 10, 292, 557, "Fortnightly", 0f);
                text(cs, helv, 10, 392, 557, "Monthly", 0f);
                text(cs, helv, 10, 40, 532, "Account purpose", 0f);
                text(cs, bold, 12, 40, 495, "3. Account use", 0f);
                text(cs, helv, 10, 62, 470, "Everyday banking", 0f);
                text(cs, helv, 10, 62, 448, "Savings", 0f);
                text(cs, bold, 12, 40, 400, "4. Declaration", 0f);
                text(cs, helv, 10, 62, 375, "I declare that the information given is true and complete", 0f);
                text(cs, helv, 10, 40, 330, "Customer signature", 0f);
                text(cs, helv, 10, 330, 330, "Date signed", 0f);
            }
            List<PDField> fields = form.getFields();
            fields.add(textField(form, page, "CustName", 160, 717, 380, 18));
            fields.add(textField(form, page, "PostalAddr", 160, 692, 380, 18));
            fields.add(textField(form, page, "City", 160, 667, 180, 18));
            fields.add(textField(form, page, "PCode", 410, 667, 130, 18));
            fields.add(textField(form, page, "CustNo", 160, 642, 180, 18));
            fields.add(textField(form, page, "LoanAmt", 160, 577, 180, 18));
            PDRadioButton freq = new PDRadioButton(form);
            freq.setPartialName("RepayFreq");
            List<PDAnnotationWidget> ws = new ArrayList<>();
            String[] opts = {"Weekly", "Fortnightly", "Monthly"};
            int[] xs = {195, 275, 375};
            for (int i = 0; i < 3; i++) {
                ws.add(button(page, xs[i], 555, 12, opts[i], zapf, freq.getCOSObject()));
            }
            freq.setWidgets(ws);
            ws.forEach(w -> addAnnot(page, w));
            freq.getCOSObject().setName(COSName.V, "Off");
            fields.add(freq);
            PDComboBox purpose = new PDComboBox(form);
            purpose.setPartialName("Purpose");
            purpose.setOptions(List.of("Home loan", "Refinance", "Top-up"));
            widget(page, purpose, 160, 527, 180, 18);
            fields.add(purpose);
            fields.add(checkbox(form, page, "UseEveryday", 42, 467, zapf));
            fields.add(checkbox(form, page, "UseSavings", 42, 445, zapf));
            fields.add(checkbox(form, page, "DeclTick", 42, 372, zapf));
            PDSignatureField sig = new PDSignatureField(form);
            sig.setPartialName("CustSignature");
            widget(page, sig, 40, 290, 250, 32);
            fields.add(sig);
            fields.add(textField(form, page, "DateSigned", 400, 325, 140, 18));
            doc.setDocumentId(20261028L); // fixed trailer /ID: identical bytes on every build (reproducible bundles, tests)
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static void text(PDPageContentStream cs, PDType1Font f, float size, float x, float y, String s, float white)
            throws IOException {
        cs.beginText();
        cs.setNonStrokingColor(white, white, white);
        cs.setFont(f, size);
        cs.newLineAtOffset(x, y);
        cs.showText(s);
        cs.endText();
    }

    private static PDTextField textField(PDAcroForm form, PDPage page, String name, float x, float y, float w, float h) {
        PDTextField t = new PDTextField(form);
        t.setPartialName(name);
        t.setDefaultAppearance("/Helv 10 Tf 0 g");
        widget(page, t, x, y, w, h);
        return t;
    }

    private static void widget(PDPage page, PDField f, float x, float y, float w, float h) {
        PDAnnotationWidget widget = f.getWidgets().get(0);
        widget.setRectangle(new PDRectangle(x, y, w, h));
        widget.setPage(page);
        PDAppearanceCharacteristicsDictionary mk = new PDAppearanceCharacteristicsDictionary(new COSDictionary());
        mk.setBorderColour(new PDColor(new float[] {0.6f, 0.65f, 0.75f}, PDDeviceRGB.INSTANCE));
        widget.setAppearanceCharacteristics(mk);
        addAnnot(page, widget);
    }

    private static void addAnnot(PDPage page, PDAnnotationWidget w) {
        try {
            page.getAnnotations().add(w);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static PDCheckBox checkbox(PDAcroForm form, PDPage page, String name, float x, float y, PDType1Font zapf)
            throws IOException {
        PDCheckBox c = new PDCheckBox(form);
        c.setPartialName(name);
        PDAnnotationWidget w = button(page, x, y, 12, "Yes", zapf, null);
        c.setWidgets(List.of(w));
        addAnnot(page, w);
        c.getCOSObject().setName(COSName.V, "Off");
        return c;
    }

    private static PDAnnotationWidget button(PDPage page, float x, float y, float size, String on, PDType1Font zapf,
                                             COSDictionary parent) throws IOException {
        PDAnnotationWidget w = new PDAnnotationWidget();
        w.setRectangle(new PDRectangle(x, y, size, size));
        w.setPage(page);
        if (parent != null) {
            w.getCOSObject().setItem(COSName.PARENT, parent);
        }
        PDAppearanceCharacteristicsDictionary mk = new PDAppearanceCharacteristicsDictionary(new COSDictionary());
        mk.setBorderColour(new PDColor(new float[] {0.4f, 0.45f, 0.55f}, PDDeviceRGB.INSTANCE));
        mk.setNormalCaption("4");
        w.setAppearanceCharacteristics(mk);
        PDAppearanceDictionary ap = new PDAppearanceDictionary();
        COSDictionary n = new COSDictionary();
        n.setItem(COSName.getPDFName(on), stream(page, size, true, zapf));
        n.setItem(COSName.Off, stream(page, size, false, zapf));
        ap.setNormalAppearance(new PDAppearanceEntry(n));
        w.setAppearance(ap);
        w.setAppearanceState("Off");
        return w;
    }

    private static PDAppearanceStream stream(PDPage page, float size, boolean on, PDType1Font zapf) throws IOException {
        PDAppearanceStream s = new PDAppearanceStream(new org.apache.pdfbox.cos.COSStream());
        s.setBBox(new PDRectangle(size, size));
        PDResources r = new PDResources();
        r.put(COSName.getPDFName("ZaDb"), zapf);
        s.setResources(r);
        try (var out = s.getCOSObject().createOutputStream()) {
            String body = "0.4 0.45 0.55 RG 1 w 0.5 0.5 " + (size - 1) + " " + (size - 1) + " re S\n"
                    + (on ? "BT /ZaDb " + (size - 2) + " Tf 0 g 1.5 2 Td (4) Tj ET\n" : "");
            out.write(body.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        }
        return s;
    }
}
