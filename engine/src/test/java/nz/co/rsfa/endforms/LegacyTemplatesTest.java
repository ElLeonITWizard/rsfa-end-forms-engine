package nz.co.rsfa.endforms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import nz.co.rsfa.endforms.catalog.TemplateCatalog;
import nz.co.rsfa.endforms.fill.FillResult;
import nz.co.rsfa.endforms.fill.FormFiller;
import nz.co.rsfa.endforms.mapping.FieldRule;
import nz.co.rsfa.endforms.mapping.Manifest;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end checks with REAL lender templates extracted from the legacy .mforge and the
 * committed, imported manifests. Templates are private (.local/, never in git), so these tests
 * are skipped when the templates have not been extracted (see README, step 2).
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
        Manifest m = TemplateCatalog.readManifest(REPO.resolve(manifestPath));
        Path t = TEMPLATES.resolve(m.templateFile());
        if (!Files.isRegularFile(t)) {
            return Optional.empty();
        }
        JsonNode data = JSON.readTree(REPO.resolve(fixture).toFile()).get("data");
        return Optional.of(new Case(m, Files.readAllBytes(t), data));
    }

    @Test
    void bnzBoarderFormFromImportedMapping() throws IOException {
        Optional<Case> c = load("mappings/bnz/BNZ_BOARDER_FORM@2.0_8-24.json", "tests/fixtures/bnz-boarder.request.json");
        assumeTrue(c.isPresent(), "BNZ template not extracted to .local/generated/templates");
        Case k = c.get();
        FillResult r = FILLER.fill(k.template(), k.manifest(), k.data());
        assertTrue(r.ok(), () -> r.issues().toString());
        assertEquals(7, r.filled().size());

        Map<String, String> expected = Map.of(
                "Text 2", "12 Example Street, Testville",
                "Text 3", "Auckland",
                "Text 4", "0999",
                "Text 5", "2",
                "Text 6", "250.00",
                "Date 1", "271026",
                "Date 2", "271026");
        assertFieldValues(r.pdf(), expected);
        assertVisible(r.pdf(), k.template(), expected.keySet());
        assertEquals(fieldValues(r.pdf(), expected.keySet()),
                fieldValues(FILLER.fill(k.template(), k.manifest(), k.data()).pdf(), expected.keySet()),
                "same input + mapping must give the same field values");
        writeOutput("BNZ_BoarderForm_POC.pdf", r.pdf());
    }

    @Test
    void ghlTrustFormCheckboxesOnEncryptedTemplate() throws IOException {
        Optional<Case> c = load("mappings/ghl/GHL_NP_TRUST_FORM@8-19.json", "tests/fixtures/ghl-np-trust.request.json");
        assumeTrue(c.isPresent(), "GHL template not extracted to .local/generated/templates");
        Case k = c.get();
        FillResult r = FILLER.fill(k.template(), k.manifest(), k.data());
        assertTrue(r.ok(), () -> r.issues().toString());
        assertFieldValues(r.pdf(), Map.of(
                "Full trustorganisation name", "Example Family Trust",
                "Check Box94", "Yes",
                "Check Box95", "3",
                "Check Box97", "Yes",
                "Check Box99", "4",
                "Check Box101", "2",
                "Check Box103", "3",
                "Check Box105", "No"));
        try (PDDocument d = Loader.loadPDF(r.pdf())) {
            assertTrue(d.isEncrypted(), "original encryption must be preserved");
        }
        assertTrue(r.skipped().contains("Check Box98"), "unmatched optional checkbox stays untouched");
        assertVisible(r.pdf(), k.template(), List.of("Full trustorganisation name", "Check Box95", "Check Box101"));
        writeOutput("GHL_NP_Trust_POC.pdf", r.pdf());
    }

    @Test
    void everyCommittedManifestResolvesAgainstItsTemplate() throws IOException {
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
            }
            checked++;
        }
        assumeTrue(checked > 0, "no templates extracted");
    }

    static void assertFieldValues(byte[] pdf, Map<String, String> expected) throws IOException {
        assertEquals(expected, fieldValues(pdf, expected.keySet()));
    }

    static Map<String, String> fieldValues(byte[] pdf, java.util.Collection<String> names) throws IOException {
        try (PDDocument d = Loader.loadPDF(pdf)) {
            PDAcroForm f = d.getDocumentCatalog().getAcroForm();
            Map<String, String> out = new java.util.TreeMap<>();
            for (String n : names) {
                out.put(n, f.getField(n).getValueAsString());
            }
            return out;
        }
    }

    /** Each named field must render visibly more ink than the blank template in at least one widget. */
    static void assertVisible(byte[] filled, byte[] blank, java.util.Collection<String> names) throws IOException {
        try (PDDocument d = Loader.loadPDF(filled)) {
            PDAcroForm f = d.getDocumentCatalog().getAcroForm();
            for (String n : names) {
                PDField field = f.getField(n);
                boolean visible = false;
                for (PDAnnotationWidget w : field.getWidgets()) {
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
}
