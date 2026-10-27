package nz.co.rsfa.endforms.fill;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Appearance for comb (one character per cell) text fields, e.g. the DD|MM|YY date boxes on BNZ forms.
 *
 * <p>Lender templates print faint placeholder letters (D, M, Y) inside each cell in the page content. A plain
 * appearance draws the digits on top of them, which reads as misaligned or struck through. This class:
 * <ul>
 *   <li>centres each character in its cell, horizontally (by glyph width) and vertically (by cap height),
 *       using the field's own font, size and colour from its DA;</li>
 *   <li>knocks out the placeholder only in cells that receive a character, with the cell's background colour
 *       <b>measured from the blank template's own rendering</b>. If that background is not uniform, the
 *       knockout is skipped with a warning (the colour is never assumed).</li>
 * </ul>
 * The field value is untouched: nothing is truncated or reformatted.
 */
final class CombAppearance {

    private static final Pattern DA_FONT = Pattern.compile("/(\\S+)\\s+([\\d.]+)\\s+Tf");
    private static final float SAMPLE_DPI = 144f;
    private static final float INSET = 1.0f;          // keeps cell borders and divider ticks visible
    private static final int COLOUR_TOLERANCE = 6;    // per channel, 0-255

    private final byte[] blankTemplate;
    private PDDocument blank;
    private final Map<Integer, BufferedImage> renders = new HashMap<>();

    CombAppearance(byte[] blankTemplate) {
        this.blankTemplate = blankTemplate;
    }

    /** Rewrites the normal appearance of every widget of a filled comb field. Returns a warning code or null. */
    String apply(PDDocument doc, PDTextField field, String value) throws IOException {
        int n = field.getMaxLen();
        if (!field.isComb() || n <= 0 || value.isEmpty()) {
            return null;
        }
        String warning = null;
        for (PDAnnotationWidget w : field.getWidgets()) {
            PDAppearanceDictionary apd = w.getAppearance();
            PDAppearanceStream ap = apd == null || apd.getNormalAppearance() == null ? null
                    : apd.getNormalAppearance().getAppearanceStream();
            if (ap == null) {
                warning = "COMB_APPEARANCE_SKIPPED";
                continue;
            }
            String da = w.getCOSObject().getString(COSName.DA, field.getDefaultAppearance());
            Matcher m = DA_FONT.matcher(String.valueOf(da));
            if (!m.find()) {
                warning = "COMB_APPEARANCE_SKIPPED";
                continue;
            }
            COSName fontName = COSName.getPDFName(m.group(1));
            PDResources res = ap.getResources();
            PDFont font = res == null ? null : res.getFont(fontName);
            if (font == null && field.getAcroForm().getDefaultResources() != null) {
                font = field.getAcroForm().getDefaultResources().getFont(fontName);
                if (font != null) {
                    if (res == null) {
                        res = new PDResources();
                        ap.setResources(res);
                    }
                    res.put(fontName, font);
                }
            }
            if (font == null) {
                warning = "COMB_APPEARANCE_SKIPPED";
                continue;
            }
            PDRectangle bbox = ap.getBBox() != null ? ap.getBBox() : new PDRectangle(w.getRectangle().getWidth(),
                    w.getRectangle().getHeight());
            float width = bbox.getWidth(), height = bbox.getHeight();
            float size = Float.parseFloat(m.group(2));
            if (size <= 0) {
                size = Math.min(height * 0.7f, 12f);
            }
            String colourOps = da.substring(0, m.start()) + da.substring(m.end());
            float cell = width / n;
            int count = value.codePointCount(0, value.length());
            int offset = switch (field.getQ()) {           // quadding: 0 left, 1 centre, 2 right
                case 1 -> (n - count) / 2;
                case 2 -> n - count;
                default -> 0;
            };
            float capHeight = font.getFontDescriptor() != null && font.getFontDescriptor().getCapHeight() > 0
                    ? font.getFontDescriptor().getCapHeight() / 1000f : 0.7f;
            float baseline = (height - capHeight * size) / 2f;

            int[] bg = cellBackground(doc, w, n, offset, count);
            if (bg == null) {
                warning = "COMB_BACKGROUND_NOT_UNIFORM";
            }
            StringBuilder cs = new StringBuilder("/Tx BMC\nq\n");
            if (bg != null) {
                cs.append(String.format(Locale.ROOT, "%.4f %.4f %.4f rg\n", bg[0] / 255f, bg[1] / 255f, bg[2] / 255f));
                for (int i = 0; i < count; i++) {
                    float x = (offset + i) * cell + INSET;
                    cs.append(String.format(Locale.ROOT, "%.3f %.3f %.3f %.3f re\n", x, INSET, cell - 2 * INSET,
                            height - 2 * INSET));
                }
                cs.append("f\n");
            }
            cs.append("BT\n/").append(fontName.getName()).append(' ')
                    .append(String.format(Locale.ROOT, "%.3f", size)).append(" Tf\n")
                    .append(colourOps.trim()).append('\n');
            int i = 0;
            for (int cp : value.codePoints().toArray()) {
                String ch = new String(Character.toChars(cp));
                float gw = font.getStringWidth(ch) / 1000f * size;
                float x = (offset + i) * cell + (cell - gw) / 2f;
                cs.append(String.format(Locale.ROOT, "1 0 0 1 %.3f %.3f Tm <", x, baseline))
                        .append(HexFormat.of().formatHex(font.encode(ch))).append("> Tj\n");
                i++;
            }
            cs.append("ET\nQ\nEMC\n");
            try (OutputStream os = ap.getCOSObject().createOutputStream()) {
                os.write(cs.toString().getBytes(StandardCharsets.ISO_8859_1));
            }
        }
        return warning;
    }

    /**
     * The colour of the cell interiors in the blank template, sampled near the top of each cell that will receive a
     * character (placeholders sit in the middle). Null if the samples disagree.
     */
    private int[] cellBackground(PDDocument doc, PDAnnotationWidget w, int n, int offset, int count) throws IOException {
        int page = doc.getPages().indexOf(w.getPage());
        if (page < 0) {
            return null;
        }
        BufferedImage img = render(page);
        PDRectangle r = w.getRectangle();
        float pageH = blank.getPage(page).getMediaBox().getHeight();
        float pageX0 = blank.getPage(page).getMediaBox().getLowerLeftX();
        float s = SAMPLE_DPI / 72f;
        float cell = r.getWidth() / n;
        int[] first = null;
        for (int i = 0; i < count; i++) {
            float cx = r.getLowerLeftX() + (offset + i) * cell;
            for (float fx : new float[] {0.3f, 0.7f}) {
                for (float fromTop : new float[] {2.0f, 2.8f}) {
                    int px = Math.round((cx + cell * fx - pageX0) * s);
                    int py = Math.round((pageH - (r.getUpperRightY() - fromTop - blank.getPage(page).getMediaBox().getLowerLeftY())) * s);
                    if (px < 0 || py < 0 || px >= img.getWidth() || py >= img.getHeight()) {
                        return null;
                    }
                    int rgb = img.getRGB(px, py);
                    int[] c = {rgb >> 16 & 0xff, rgb >> 8 & 0xff, rgb & 0xff};
                    if (first == null) {
                        first = c;
                    } else if (Math.abs(first[0] - c[0]) > COLOUR_TOLERANCE || Math.abs(first[1] - c[1]) > COLOUR_TOLERANCE
                            || Math.abs(first[2] - c[2]) > COLOUR_TOLERANCE) {
                        return null;
                    }
                }
            }
        }
        return first;
    }

    private BufferedImage render(int page) throws IOException {
        if (blank == null) {
            blank = Loader.loadPDF(blankTemplate);
        }
        BufferedImage img = renders.get(page);
        if (img == null) {
            img = new PDFRenderer(blank).renderImageWithDPI(page, SAMPLE_DPI);
            renders.put(page, img);
        }
        return img;
    }

    void close() throws IOException {
        if (blank != null) {
            blank.close();
        }
    }
}
