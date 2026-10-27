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

    /** Counts pixels within {@code tol} (per channel, 0-255) of an RGB colour (0-1 floats) inside a PDF-point rect. */
    static int colourPixels(byte[] pdf, int page, float x, float y, float w, float h, float[] rgb, int tol) throws IOException {
        int[] t = {Math.round(rgb[0] * 255), Math.round(rgb[1] * 255), Math.round(rgb[2] * 255)};
        int[] count = {0};
        scan(pdf, page, x, y, w, h, (c, px, py) -> {
            if (Math.abs(c[0] - t[0]) <= tol && Math.abs(c[1] - t[1]) <= tol && Math.abs(c[2] - t[2]) <= tol) count[0]++;
        });
        return count[0];
    }

    /** Centre (PDF points, bottom-left origin) of the dark ink inside a rect. */
    static float[] inkCentre(byte[] pdf, int page, float x, float y, float w, float h) throws IOException {
        double[] acc = new double[3];
        float s = DPI / 72f;
        float[] pageH = new float[1];
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            pageH[0] = doc.getPage(page).getMediaBox().getHeight();
        }
        scan(pdf, page, x, y, w, h, (c, px, py) -> {
            if ((c[0] * 299 + c[1] * 587 + c[2] * 114) / 1000 < 100) {
                acc[0] += px; acc[1] += py; acc[2]++;
            }
        });
        if (acc[2] == 0) return new float[] {Float.NaN, Float.NaN};
        return new float[] {(float) (acc[0] / acc[2] / s), pageH[0] - (float) (acc[1] / acc[2] / s)};
    }

    /** Most frequent colour inside a rect that is neither near-white nor dark (placeholder text, light fills). */
    static float[] dominantLightColour(byte[] pdf, int page, float x, float y, float w, float h) throws IOException {
        java.util.Map<Integer, Integer> freq = new java.util.HashMap<>();
        scan(pdf, page, x, y, w, h, (c, px, py) -> {
            int lum = (c[0] * 299 + c[1] * 587 + c[2] * 114) / 1000;
            boolean nearWhite = c[0] > 245 && c[1] > 245 && c[2] > 245;
            if (!nearWhite && lum > 120) freq.merge((c[0] / 8) << 10 | (c[1] / 8) << 5 | (c[2] / 8), 1, Integer::sum);
        });
        int key = freq.entrySet().stream().max(java.util.Map.Entry.comparingByValue()).orElseThrow().getKey();
        return new float[] {((key >> 10 & 31) * 8 + 4) / 255f, ((key >> 5 & 31) * 8 + 4) / 255f, ((key & 31) * 8 + 4) / 255f};
    }

    interface PixelVisitor {
        void visit(int[] rgb, int px, int py);
    }

    /** Visits pixels strictly inside the rect (0.75pt inset so cell borders are excluded). */
    private static void scan(byte[] pdf, int page, float x, float y, float w, float h, PixelVisitor v) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            BufferedImage img = new PDFRenderer(doc).renderImageWithDPI(page, DPI);
            float s = DPI / 72f, pageH = doc.getPage(page).getMediaBox().getHeight(), in = 0.75f;
            int x0 = Math.round((x + in) * s), x1 = Math.round((x + w - in) * s);
            int y0 = Math.round((pageH - y - h + in) * s), y1 = Math.round((pageH - y - in) * s);
            for (int py = Math.max(0, y0); py < Math.min(img.getHeight(), y1); py++) {
                for (int px = Math.max(0, x0); px < Math.min(img.getWidth(), x1); px++) {
                    int rgb = img.getRGB(px, py);
                    v.visit(new int[] {rgb >> 16 & 0xff, rgb >> 8 & 0xff, rgb & 0xff}, px, py);
                }
            }
        }
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
