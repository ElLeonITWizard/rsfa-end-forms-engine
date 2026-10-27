package nz.co.rsfa.endforms;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.awt.image.BufferedImage;
import java.io.IOException;

/** Rasterises a page region to check that filled values are visible, not just set in the field dictionary. */
final class Rendering {

    private static final float DPI = 144f;

    private Rendering() {
    }

    /** Page-content text inside a rectangle in PDF points (bottom-left origin), trimmed. Not widget values. */
    static String textIn(byte[] pdf, int page, float x, float y, float w, float h) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            float pageH = doc.getPage(page).getMediaBox().getHeight();
            var stripper = new org.apache.pdfbox.text.PDFTextStripperByArea();
            stripper.addRegion("r", new java.awt.geom.Rectangle2D.Float(x, pageH - y - h, w, h));
            stripper.extractRegions(doc.getPage(page));
            return stripper.getTextForRegion("r").trim();
        }
    }

    /** Names of fonts that are embedded in the document's page and AcroForm resources (subset prefix removed). */
    static java.util.Set<String> embeddedFonts(byte[] pdf) throws IOException {
        java.util.Set<String> out = new java.util.TreeSet<>();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            java.util.List<org.apache.pdfbox.pdmodel.PDResources> res = new java.util.ArrayList<>();
            for (var p : doc.getPages()) {
                if (p.getResources() != null) res.add(p.getResources());
            }
            var form = doc.getDocumentCatalog().getAcroForm();
            if (form != null && form.getDefaultResources() != null) res.add(form.getDefaultResources());
            for (var r : res) {
                for (var n : r.getFontNames()) {
                    var f = r.getFont(n);
                    if (f != null && f.isEmbedded()) out.add(f.getName().replaceFirst("^[A-Z]{6}\\+", ""));
                }
            }
        }
        return out;
    }

    /** Counts dark pixels inside a rectangle given in PDF points (bottom-left origin). */
    static int darkPixels(byte[] pdf, int page, float x, float y, float w, float h) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            BufferedImage img = new PDFRenderer(doc).renderImageWithDPI(page, DPI);
            float s = DPI / 72f;
            float pageH = doc.getPage(page).getMediaBox().getHeight();
            int x0 = Math.round(x * s), x1 = Math.round((x + w) * s);
            int y0 = Math.round((pageH - y - h) * s), y1 = Math.round((pageH - y) * s);
            int count = 0;
            for (int py = Math.max(0, y0); py < Math.min(img.getHeight(), y1); py++) {
                for (int px = Math.max(0, x0); px < Math.min(img.getWidth(), x1); px++) {
                    int rgb = img.getRGB(px, py);
                    int lum = ((rgb >> 16 & 0xff) * 299 + (rgb >> 8 & 0xff) * 587 + (rgb & 0xff) * 114) / 1000;
                    if (lum < 100) {
                        count++;
                    }
                }
            }
            return count;
        }
    }
}
