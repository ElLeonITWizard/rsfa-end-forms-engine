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
        // legacy constant ticks are not applied without a recorded approval
        assertEquals("Off", fieldValues(r.pdf(), List.of("Check Box 31")).get("Check Box 31"));
        assertTrue(r.issues().stream().anyMatch(i -> i.code().equals("CONSTANT_CHECK_NOT_APPROVED")
                && "Check Box 31".equals(i.pdfField())));
        // the adviser certification ("By ticking here, I confirm I have recommended ...") is pre-ticked in BNZ's
        // blank template; with no authorized rule the engine must clear it
        assertEquals("Yes", fieldValues(k.template(), List.of("Confirmation")).get("Confirmation"));
        assertEquals("Off", fieldValues(r.pdf(), List.of("Confirmation")).get("Confirmation"));
        assertTrue(r.issues().stream().anyMatch(i -> i.code().equals("TEMPLATE_PRESET_CLEARED")
                && "Confirmation".equals(i.pdfField())));
        // Date 3 (adviser date, comb DD|MM|YY): value intact, digits centred, placeholder letters hidden
        assertEquals("271026", fieldValues(r.pdf(), List.of("Date 3")).get("Date 3"));
        assertCombPlaceholdersHidden(k.template(), r.pdf(), "Date 3");
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

    // ------------------------------------------------------------------ iteration 6: additional forms (candidates)

    static final String ASB_BOARDER = "mappings/asb/ASB_BOARDER_DECLARATION@12-22.json";
    static final String ANZ_BOARDER = "mappings/anz/ANZ_BOARDER_INCOME_DECLARATION@02-24.json";
    static final String GHL_INDIVIDUAL = "mappings/ghl/GHL_NP_INDIVIDUAL_FORM@8-19.json";
    static final String GHL_TRUST = "mappings/ghl/GHL_NP_TRUST_FORM@8-19.json";

    @Test
    void asbBoarderDeclarationOnEncryptedTemplate() throws IOException {
        Case k = require(ASB_BOARDER, "tests/fixtures/asb-boarder.request.json");
        FillResult r = FILLER.fill(k.template(), k.manifest(), k.data());
        assertTrue(r.ok(), () -> r.issues().toString());
        assertTrue(r.issues().isEmpty(), () -> r.issues().toString());
        Map<String, String> expected = new TreeMap<>(Map.ofEntries(
                Map.entry("Borrowers or Guarantors names", "Jane Example"),
                Map.entry("Borrowers or Guarantors names 2", "John Sample"),
                Map.entry("1", "12 Example Street"), Map.entry("2", "Testville Region"), Map.entry("3", "New Zealand"),
                Map.entry("postcode", "0999"),
                Map.entry("Number of adults living in the property", "2"),
                Map.entry("Number of dependents living in the property", "1"),
                Map.entry("Number of bedrooms", "4"),
                Map.entry("Boarder Name if known", "Alex Boarder"),
                Map.entry("Board amount excluding utilities and food", "250"),
                Map.entry("frequency 1", "weekly"),
                Map.entry("Boarder Name if known_2", "Sam Lodger"),
                Map.entry("Board amount excluding utilities and food_2", "520"),
                Map.entry("frequency 2", "fortnight"),
                Map.entry("Full Name", "Jane Example"), Map.entry("Full Name_2", "John Sample"),
                Map.entry("date 1", "27102026"), Map.entry("date 2", "27102026")));
        assertEquals(expected, fieldValues(r.pdf(), expected.keySet()));
        assertVisible(r.pdf(), k.template(), List.of("1", "Boarder Name if known", "frequency 1", "frequency 2", "date 1"));
        try (PDDocument d = Loader.loadPDF(r.pdf())) {
            assertTrue(d.isEncrypted(), "ASB's encryption must be preserved");
        }
        assertEquals(expected, fieldValues(FILLER.fill(k.template(), k.manifest(), k.data()).pdf(), expected.keySet()));

        ObjectNode noFreq = k.data().deepCopy();
        noFreq.remove("Boarders - Board amount 2 frequency");
        FillResult missing = FILLER.fill(k.template(), k.manifest(), noFreq);
        assertNull(missing.pdf(), "an amount without its frequency is incomplete");
        assertTrue(missing.errors().stream().anyMatch(i -> i.code().equals("PRECONDITION_FAILED")
                && i.message().contains("ASB-BOARDER-FREQ-2")), () -> missing.issues().toString());
        writeOutput("ASB_Boarder_Declaration_POC.pdf", r.pdf());
    }

    @Test
    void anzBoarderDeclarationWithOverlaysAndWeeklyGuard() throws IOException {
        Case k = require(ANZ_BOARDER, "tests/fixtures/anz-boarder.request.json");
        FillResult r = FILLER.fill(k.template(), k.manifest(), k.data());
        assertTrue(r.ok(), () -> r.issues().toString());
        assertTrue(r.issues().isEmpty(), () -> r.issues().toString());
        Map<String, String> expected = new TreeMap<>(Map.of(
                "Property address", "12 Example Street, Testville", "Town City", "Auckland", "Postcode", "0999",
                "Cust Number", "100200300", "B1 Name", "Alex Boarder", "B1 Weekly Board", "250",
                "B2 Name", "Sam Lodger", "B2 Weekly Board", "230"));
        assertEquals(expected, fieldValues(r.pdf(), expected.keySet()));
        assertVisible(r.pdf(), k.template(), expected.keySet());
        assertOverlayText(r.pdf(), k.manifest(), Map.of("Applicant 1 Full Name", "Jane Example",
                "Applicant 2 Full Name", "John Sample"));
        assertEquals(Map.of("B1 Inclusive", "Off", "B2 Inclusive", "Off"),
                fieldValues(r.pdf(), List.of("B1 Inclusive", "B2 Inclusive")), "not mapped: left for the customer");
        byte[] again = FILLER.fill(k.template(), k.manifest(), k.data()).pdf();
        assertTrue(java.util.Arrays.equals(r.pdf(), again), "unencrypted template: identical bytes for identical input");

        ObjectNode fortnightly = k.data().deepCopy();
        fortnightly.put("Boarders - Board amount 2 frequency", "Fortnightly");
        FillResult refused = FILLER.fill(k.template(), k.manifest(), fortnightly);
        assertNull(refused.pdf(), "a fortnightly amount must not be printed in the 'Weekly board amount' box");
        assertEquals(List.of("PRECONDITION_FAILED"), refused.errors().stream().map(Issue::code).toList());
        assertTrue(refused.errors().getFirst().message().contains("ANZ-BOARDER-WEEKLY-2"));
        writeOutput("ANZ_Boarder_Income_Declaration_POC.pdf", r.pdf());
    }

    @Test
    void ghlIndividualQuestionnaireWithClosedOptions() throws IOException {
        Case k = require(GHL_INDIVIDUAL, "tests/fixtures/ghl-np-individual.request.json");
        FillResult r = FILLER.fill(k.template(), k.manifest(), k.data());
        assertTrue(r.ok(), () -> r.issues().toString());
        assertTrue(r.issues().isEmpty(), () -> r.issues().toString());
        Map<String, String> expected = new TreeMap<>(Map.of(
                "Please specify your full name", "Jane Example",
                "What is your primary occupation", "Fictional Test Analyst",
                "Check Box84", "Yes", "Check Box85", "2", "Check Box86", "2", "Check Box87", "2",
                "Check Box88", "4", "Check Box89", "1"));
        assertEquals(expected, fieldValues(r.pdf(), expected.keySet()));
        assertVisible(r.pdf(), k.template(), List.of("Check Box85", "Check Box88", "Text81"));

        // live Monday label drift: '$150,001-$250,000' exists on the board and on the PDF, but the legacy rule tests
        // '$150,000-$250,000'; MacroForge would leave 3a blank, the engine refuses
        ObjectNode drift = k.data().deepCopy();
        drift.put("Total credits or deposits (next 12 months) (ASB)", "$150,001-$250,000");
        FillResult refused = FILLER.fill(k.template(), k.manifest(), drift);
        assertNull(refused.pdf());
        assertEquals(List.of("UNMAPPED_OPTION:Total credits or deposits (next 12 months) (ASB)"),
                refused.errors().stream().map(i -> i.code() + ":" + i.sourceKey()).toList());

        ObjectNode both = k.data().deepCopy();
        both.put("What do you intend to use your accounts for? (GHL Individual)", "Everyday personal banking, To borrow money");
        assertEquals(List.of("MULTI_SELECT_NOT_REPRESENTABLE"),
                FILLER.fill(k.template(), k.manifest(), both).errors().stream().map(Issue::code).toList());
        writeOutput("GHL_NP_Individual_POC.pdf", r.pdf());
    }

    @Test
    void ghlTrustQuestionnaireWithLiveMondayLabels() throws IOException {
        Case k = require(GHL_TRUST, "tests/fixtures/ghl-np-trust-live-labels.request.json");
        FillResult r = FILLER.fill(k.template(), k.manifest(), k.data());
        assertTrue(r.ok(), () -> r.issues().toString());
        Map<String, String> expected = new TreeMap<>(Map.of(
                "Full trustorganisation name", "Example Family Trust",
                "Check Box94", "No", "undefined", "Fictional: refinance from another lender",
                "Check Box95", "1", "Check Box96", "1", "Check Box99", "3",
                "Check Box101", "1", "Check Box102", "3", "Check Box103", "4", "Check Box104", "1"));
        assertEquals(expected, fieldValues(r.pdf(), expected.keySet()));
        assertEquals("Yes", fieldValues(r.pdf(), List.of("Check Box105")).get("Check Box105"));
        ObjectNode drift = k.data().deepCopy();
        drift.put("Int Transfers OUT - Average Value per Payment (GHL Trust)", "$1,000-$10,000");
        assertEquals(List.of("UNMAPPED_OPTION:Int Transfers OUT - Average Value per Payment (GHL Trust)"),
                FILLER.fill(k.template(), k.manifest(), drift).errors().stream()
                        .map(i -> i.code() + ":" + i.sourceKey()).toList());
        writeOutput("GHL_NP_Trust_live_labels_POC.pdf", r.pdf());
    }

    @Test
    void curatedManifestsNeverTickWithoutAnAuthorizedRule() throws IOException {
        for (String[] c : List.of(new String[] {ASB_BOARDER, "tests/fixtures/asb-boarder.request.json"},
                new String[] {ANZ_BOARDER, "tests/fixtures/anz-boarder.request.json"},
                new String[] {GHL_INDIVIDUAL, "tests/fixtures/ghl-np-individual.request.json"},
                new String[] {GHL_TRUST, "tests/fixtures/ghl-np-trust-live-labels.request.json"})) {
            Case k = require(c[0], c[1]);
            FillResult r = FILLER.fill(k.template(), k.manifest(), k.data());
            assertTrue(r.ok(), c[0]);
            java.util.Set<String> ruled = new java.util.HashSet<>();
            k.manifest().fields().forEach(f -> ruled.add(f.pdfField()));
            try (PDDocument d = Loader.loadPDF(r.pdf())) {
                for (PDField f : d.getDocumentCatalog().getAcroForm().getFieldTree()) {
                    if (f instanceof org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField) {
                        assertNull(f.getCOSObject().getDictionaryObject(org.apache.pdfbox.cos.COSName.V), "signature " + f.getFullyQualifiedName());
                    }
                    if (f instanceof org.apache.pdfbox.pdmodel.interactive.form.PDButton && !ruled.contains(f.getFullyQualifiedName())) {
                        String v = f.getValueAsString();
                        assertTrue(v.isEmpty() || v.equals("Off"), c[0] + ": unruled button on: " + f.getFullyQualifiedName());
                    }
                }
            }
        }
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

    /** The template's own placeholder colour (most common light, non-white colour in the cells) must vanish. */
    static void assertCombPlaceholdersHidden(byte[] blank, byte[] filled, String field) throws IOException {
        PDRectangle r;
        int page;
        try (PDDocument d = Loader.loadPDF(filled)) {
            PDAnnotationWidget w = d.getDocumentCatalog().getAcroForm().getField(field).getWidgets().getFirst();
            r = w.getRectangle();
            page = d.getPages().indexOf(w.getPage());
        }
        float[] colour = Rendering.dominantLightColour(blank, page, r.getLowerLeftX(), r.getLowerLeftY(), r.getWidth(), r.getHeight());
        int cells;
        try (PDDocument d = Loader.loadPDF(filled)) {
            cells = ((org.apache.pdfbox.pdmodel.interactive.form.PDTextField) d.getDocumentCatalog().getAcroForm().getField(field)).getMaxLen();
        }
        float cw = r.getWidth() / cells;
        int before = 0, after = 0;
        for (int i = 0; i < cells; i++) { // cell interiors only, 1.5pt clear of borders and divider lines
            float x = r.getLowerLeftX() + i * cw + 1.5f, y = r.getLowerLeftY() + 1.5f, w = cw - 3f, h = r.getHeight() - 3f;
            before += Rendering.colourPixels(blank, page, x, y, w, h, colour, 6);
            after += Rendering.colourPixels(filled, page, x, y, w, h, colour, 6); // ±6: excludes neutral digit anti-aliasing
        }
        assertTrue(before > 50, field + ": expected visible placeholders in the blank template, got " + before);
        assertTrue(after < before / 20, field + ": placeholders still visible, before=" + before + " after=" + after);
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
