package nz.co.rsfa.endforms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import nz.co.rsfa.endforms.catalog.TemplateCatalog;
import nz.co.rsfa.endforms.fill.FillResult;
import nz.co.rsfa.endforms.fill.FormFiller;
import nz.co.rsfa.endforms.fill.Issue;
import nz.co.rsfa.endforms.mapping.FieldRule;
import nz.co.rsfa.endforms.mapping.Manifest;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDChoice;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end checks with REAL lender templates extracted from the legacy .mforge and the
 * committed, imported manifests. Templates are private (.local/, never in git), so these tests
 * are skipped when the templates (or the repo's mappings, e.g. inside a Docker build) are absent.
 */
class LegacyTemplatesTest {

    static final Path REPO = Path.of(System.getProperty("rsfa.repo.dir", ".."));
    static final Path LOCAL = Path.of(System.getProperty("rsfa.local.dir", "../.local"));
    static final Path TEMPLATES = LOCAL.resolve("generated/templates");
    static final ObjectMapper JSON = new ObjectMapper();
    static final FormFiller FILLER = new FormFiller(zone -> LocalDate.of(2026, 10, 27));

    record Case(Manifest manifest, byte[] template, JsonNode data) {
    }

    static Optional<Case> load(String manifestPath, String fixture) throws IOException {
        Path mp = REPO.resolve(manifestPath);
        Path fx = REPO.resolve(fixture);
        if (!Files.isRegularFile(mp) || !Files.isRegularFile(fx)) {
            return Optional.empty();
        }
        Manifest m = TemplateCatalog.readManifest(mp);
        Path t = TEMPLATES.resolve(m.templateFile());
        if (!Files.isRegularFile(t)) {
            return Optional.empty();
        }
        return Optional.of(new Case(m, Files.readAllBytes(t), JSON.readTree(fx.toFile()).get("data")));
    }

    static Case require(String manifestPath, String fixture) throws IOException {
        Optional<Case> c = load(manifestPath, fixture);
        assumeTrue(c.isPresent(), "template/mapping not available (run import_mforge.py extract)");
        return c.get();
    }

    static final String BOARDER = "mappings/bnz/BNZ_BOARDER_FORM@2.0_8-24.json";
    static final String HANDOVER = "mappings/bnz/BNZ_HANDOVER_FORM@8-24.json";

    @Test
    void bnzBoarderFormIsCompleteIncludingCustomerNames() throws IOException {
        Case k = require(BOARDER, "tests/fixtures/bnz-boarder.request.json");
        FillResult r = FILLER.fill(k.template(), k.manifest(), k.data());
        assertTrue(r.ok(), () -> r.issues().toString());
        assertTrue(r.issues().isEmpty(), () -> r.issues().toString());
        assertEquals(9, r.filled().size(), "7 form fields + 2 customer-name overlays");

        Map<String, String> expected = Map.of(
                "Text 2", "12 Example Street, Testville",
                "Text 3", "Auckland",
                "Text 4", "0999",
                "Text 5", "2",
                "Text 6", "250.00",
                "Date 1", "271026",
                "Date 2", "271026");
        assertEquals(expected, fieldValues(r.pdf(), expected.keySet()));
        assertVisible(r.pdf(), k.template(), expected.keySet());
        assertOverlayText(r.pdf(), k.manifest(), Map.of("Applicant 1 Full Name", "Jane Example",
                "Applicant 2 Full Name", "John Sample"));
        FillResult again = FILLER.fill(k.template(), k.manifest(), k.data());
        assertEquals(fieldValues(r.pdf(), expected.keySet()), fieldValues(again.pdf(), expected.keySet()));
        assertEquals(Rendering.textIn(r.pdf(), 0, 0, 680, 595, 40), Rendering.textIn(again.pdf(), 0, 0, 680, 595, 40),
                "same input + mapping must give the same overlay text");
        writeOutput("BNZ_BoarderForm_POC.pdf", r.pdf());
    }

    @Test
    void bnzBoarderFormWithMaoriMacrons() throws IOException {
        Case k = require(BOARDER, "tests/fixtures/bnz-boarder-macrons.request.json");
        FillResult r = FILLER.fill(k.template(), k.manifest(), k.data());
        assertTrue(r.ok(), () -> r.issues().toString());
        // Text 3's own font (embedded SerranoPro subset) lacks Ō/ā: the reviewed manifest allows the Unicode fallback
        assertEquals(List.of("FONT_FALLBACK:Text 3"),
                r.issues().stream().map(i -> i.code() + ":" + i.pdfField()).toList());
        assertEquals("Ōtāhuhu", fieldValues(r.pdf(), List.of("Text 3")).get("Text 3"));
        assertVisible(r.pdf(), k.template(), List.of("Text 3"));
        assertOverlayText(r.pdf(), k.manifest(), Map.of("Applicant 1 Full Name", "Aroha Tūhoe-Example",
                "Applicant 2 Full Name", "Mere Tāwhiri-Sample"));
        assertTrue(Rendering.embeddedFonts(r.pdf()).contains("LiberationSans"));
        writeOutput("BNZ_BoarderForm_macrons_POC.pdf", r.pdf());
    }

    @Test
    void bnzHandoverRepeatedLoanFacilitiesFromLineItems() throws IOException {
        Case k = require(HANDOVER, "tests/fixtures/bnz-handover.request.json");
        FillResult r = FILLER.fill(k.template(), k.manifest(), k.data());
        assertTrue(r.ok(), () -> r.issues().toString());
        Map<String, String> rows = new TreeMap<>(Map.ofEntries(
                Map.entry("BNZ Product dropdown 1", "Classic (fixed rate)"),
                Map.entry("Loan Amount 1", "450000"),
                Map.entry("Agreed Interest 1", "5.49"),
                Map.entry("Payment Rate Period 1", "Fortnightly"),
                Map.entry("Select the main purpose for each facility 1", "–  Buy a home or land (incl. investment)"),
                Map.entry("BNZ Product dropdown 2", "Rapid Repay (revolving credit)"),
                Map.entry("Loan Amount 2", "50000"),
                Map.entry("Agreed Interest 2", "6.10"),
                Map.entry("Payment Rate Period 2", "Monthly"),
                Map.entry("BNZ Product dropdown 3", "TotalMoney (offsetting)"),
                Map.entry("Loan Amount 3", "25000"),
                Map.entry("Agreed Interest 3", "5.95"),
                Map.entry("Payment Rate Period 3", "Weekly")));
        assertEquals(rows, fieldValues(r.pdf(), rows.keySet()));
        for (int row = 4; row <= 6; row++) {
            for (String f : List.of("BNZ Product dropdown ", "Loan Amount ", "Agreed Interest ", "Payment Rate Period ")) {
                assertTrue(r.skipped().contains(f + row), "row " + row + " must stay empty: " + f);
            }
        }
        assertVisible(r.pdf(), k.template(), List.of("Loan Amount 1", "Agreed Interest 2", "Loan Amount 3"));
        // the adviser certification tick is a legacy constant: not applied until a human approves it
        assertEquals("Off", fieldValues(r.pdf(), List.of("Check Box 31")).get("Check Box 31"));
        assertTrue(r.issues().stream().anyMatch(i -> i.code().equals("CONSTANT_CHECK_NOT_APPROVED")
                && "Check Box 31".equals(i.pdfField())));
        writeOutput("BNZ_Handover_POC.pdf", r.pdf());
    }

    @Test
    void makeDatesDoNotFitTheSixCellRepaymentDate() throws IOException {
        Case k = require(HANDOVER, "tests/fixtures/bnz-handover.request.json");
        ObjectNode data = k.data().deepCopy();
        ((ObjectNode) data.get("Subitems").get(0).get("values")).put("First repayment date", "15112026");
        FillResult r = FILLER.fill(k.template(), k.manifest(), data);
        assertNull(r.pdf(), "Make sends subitem dates as DDMMYYYY; the comb field holds 6 cells");
        assertEquals(List.of("TEXT_TOO_LONG:First Repayment Date 1"), r.errors().stream()
                .map(i -> i.code() + ":" + i.pdfField()).toList());
    }

    @Test
    void ghlTrustFormCheckboxesOnEncryptedTemplate() throws IOException {
        Case k = require("mappings/ghl/GHL_NP_TRUST_FORM@8-19.json", "tests/fixtures/ghl-np-trust.request.json");
        FillResult r = FILLER.fill(k.template(), k.manifest(), k.data());
        assertTrue(r.ok(), () -> r.issues().toString());
        assertEquals(Map.of(
                "Full trustorganisation name", "Example Family Trust",
                "Check Box94", "Yes",
                "Check Box95", "3",
                "Check Box97", "Yes",
                "Check Box99", "4",
                "Check Box101", "2",
                "Check Box103", "3",
                "Check Box105", "No"), fieldValues(r.pdf(), List.of("Full trustorganisation name", "Check Box94",
                "Check Box95", "Check Box97", "Check Box99", "Check Box101", "Check Box103", "Check Box105")));
        try (PDDocument d = Loader.loadPDF(r.pdf())) {
            assertTrue(d.isEncrypted(), "original encryption must be preserved");
        }
        assertTrue(r.skipped().contains("Check Box98"), "unmatched optional checkbox stays untouched");
        assertVisible(r.pdf(), k.template(), List.of("Full trustorganisation name", "Check Box95", "Check Box101"));
        writeOutput("GHL_NP_Trust_POC.pdf", r.pdf());
    }

    @Test
    void everyCommittedManifestResolvesAgainstItsTemplate() throws IOException {
        assumeTrue(Files.isDirectory(REPO.resolve("mappings")) && Files.isDirectory(TEMPLATES), "no mappings/templates");
        TemplateCatalog catalog = TemplateCatalog.load(REPO.resolve("mappings"), List.of(TEMPLATES));
        int checked = 0;
        for (Manifest m : catalog.manifests()) {
            Optional<byte[]> t = catalog.templateBytes(m);
            if (t.isEmpty()) {
                continue;
            }
            assertEquals(m.templateSha256(), FormFiller.sha256(t.get()), m.key());
            try (PDDocument d = Loader.loadPDF(t.get())) {
                PDAcroForm form = d.getDocumentCatalog().getAcroForm();
                for (FieldRule rule : m.fields()) {
                    assertTrue(form.getField(rule.pdfField()) != null, m.key() + " -> " + rule.pdfField());
                }
                for (Manifest.Overlay o : m.overlays()) {
                    PDRectangle box = d.getPage(o.page()).getMediaBox();
                    assertTrue(o.rect().get(2) <= box.getUpperRightX() + 1 && o.rect().get(3) <= box.getUpperRightY() + 1,
                            m.key() + " overlay " + o.id());
                }
            }
            checked++;
        }
        assumeTrue(checked > 0, "no templates extracted");
    }

    // ------------------------------------------------------------------ helpers

    static Map<String, String> fieldValues(byte[] pdf, Collection<String> names) throws IOException {
        try (PDDocument d = Loader.loadPDF(pdf)) {
            PDAcroForm f = d.getDocumentCatalog().getAcroForm();
            Map<String, String> out = new TreeMap<>();
            for (String n : names) {
                PDField field = f.getField(n);
                out.put(n, field instanceof PDChoice c ? String.join("|", c.getValue()) : field.getValueAsString());
            }
            return out;
        }
    }

    /** The text drawn inside each overlay's rect must be exactly the expected value. */
    static void assertOverlayText(byte[] pdf, Manifest m, Map<String, String> bySourceKey) throws IOException {
        int seen = 0;
        for (Manifest.Overlay o : m.overlays()) {
            String expected = bySourceKey.get(o.source().key());
            if (expected == null) {
                continue;
            }
            List<Double> r = o.rect();
            float x = r.get(0).floatValue(), y = r.get(1).floatValue();
            float w = (float) (r.get(2) - r.get(0)), h = (float) (r.get(3) - r.get(1));
            assertEquals(expected, Rendering.textIn(pdf, o.page(), x, y, w, h), "overlay " + o.id());
            assertTrue(Rendering.darkPixels(pdf, o.page(), x, y, w, h) > 50, "overlay not visible: " + o.id());
            seen++;
        }
        assertEquals(bySourceKey.size(), seen);
    }

    /** Each named field must render visibly more ink than the blank template in at least one widget. */
    static void assertVisible(byte[] filled, byte[] blank, Collection<String> names) throws IOException {
        try (PDDocument d = Loader.loadPDF(filled)) {
            PDAcroForm f = d.getDocumentCatalog().getAcroForm();
            for (String n : names) {
                boolean visible = false;
                for (PDAnnotationWidget w : f.getField(n).getWidgets()) {
                    int page = d.getPages().indexOf(w.getPage());
                    PDRectangle r = w.getRectangle();
                    int after = Rendering.darkPixels(filled, page, r.getLowerLeftX(), r.getLowerLeftY(), r.getWidth(), r.getHeight());
                    int before = Rendering.darkPixels(blank, page, r.getLowerLeftX(), r.getLowerLeftY(), r.getWidth(), r.getHeight());
                    visible |= after > before + 5;
                }
                assertTrue(visible, "no visible rendering for field " + n);
            }
        }
    }

    static void writeOutput(String name, byte[] pdf) throws IOException {
        Path out = LOCAL.resolve("output");
        Files.createDirectories(out);
        Files.write(out.resolve(name), pdf);
    }

    static List<String> codes(FillResult r) {
        return r.issues().stream().map(Issue::code).toList();
    }
}
