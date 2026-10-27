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
