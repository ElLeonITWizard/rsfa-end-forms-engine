package nz.co.rsfa.endforms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import nz.co.rsfa.endforms.fill.FillResult;
import nz.co.rsfa.endforms.fill.FormFiller;
import nz.co.rsfa.endforms.fill.Issue;
import nz.co.rsfa.endforms.mapping.Manifest;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDChoice;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormFillerTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final FormFiller FILLER = new FormFiller(zone -> LocalDate.of(2026, 10, 27));
    static byte[] template;

    @BeforeAll
    static void buildTemplate() throws IOException {
        template = SyntheticTemplate.build();
    }

    static Manifest manifest(String fieldsJson) throws IOException {
        return manifest(fieldsJson, template);
    }

    static Manifest manifest(String fieldsJson, byte[] tpl) throws IOException {
        String m = """
                {"schemaVersion":1,"templateId":"SYNTH","templateVersion":"1","templateFile":"synth.pdf",
                 "templateSha256":"%s","fields":%s}""".formatted(FormFiller.sha256(tpl), fieldsJson);
        return JSON.readValue(m, Manifest.class);
    }

    static JsonNode data(String json) throws IOException {
        return JSON.readTree(json);
    }

    static Map<String, String> values(byte[] pdf) throws IOException {
        try (PDDocument d = Loader.loadPDF(pdf)) {
            PDAcroForm f = d.getDocumentCatalog().getAcroForm();
            return Map.of(
                    "Full name", f.getField("Full name").getValueAsString(),
                    "Postcode", f.getField("Postcode").getValueAsString(),
                    "Date", f.getField("Date").getValueAsString(),
                    "Consent box", f.getField("Consent box").getValueAsString(),
                    "Frequency", f.getField("Frequency").getValueAsString(),
                    "Residency", f.getField("Residency").getValueAsString(),
                    "Repayment frequency", String.join("|", ((PDChoice) f.getField("Repayment frequency")).getValue()));
        }
    }

    static final String ALL_RULES = """
            [{"pdfField":"Full name","kind":"text","source":{"key":"Applicant 1 Full Name"},"required":true},
             {"pdfField":"Postcode","kind":"text","source":{"key":"Postcode"}},
             {"pdfField":"Date","kind":"text","source":{"generated":"today","format":"ddMMyy"}},
             {"pdfField":"Consent box","kind":"checkbox","source":{"when":{"key":"Consent","operator":"equals","value":"Yes"}}},
             {"pdfField":"Frequency","kind":"checkbox","option":"1","source":{"when":{"key":"Freq","operator":"equals","value":"Weekly"}}},
             {"pdfField":"Frequency","kind":"checkbox","option":"2","source":{"when":{"key":"Freq","operator":"contains","value":"Fortnight"}}},
             {"pdfField":"Frequency","kind":"checkbox","option":"3","source":{"when":{"key":"Freq","operator":"equals","value":"Monthly"}}},
             {"pdfField":"Residency","kind":"radio","option":"NZ","source":{"when":{"key":"Tax resident","operator":"equals","value":"NZ only"}}},
             {"pdfField":"Repayment frequency","kind":"combo","source":{"key":"Repayment"}}]""";

    static final String JANE = """
            {"Applicant 1 Full Name":"Jane Example","Postcode":"0999","Consent":"Yes","Freq":"Fortnightly",
             "Tax resident":"NZ only","Repayment":"Monthly"}""";

    @Test
    void fillsEverySupportedKindAndKeepsTemplateUntouched() throws IOException {
        byte[] before = template.clone();
        FillResult r = FILLER.fill(template, manifest(ALL_RULES), data(JANE));
        assertTrue(r.ok(), () -> r.issues().toString());
        assertArrayEquals(before, template, "input template must never be modified");
        Map<String, String> v = values(r.pdf());
        assertEquals("Jane Example", v.get("Full name"));
        assertEquals("0999", v.get("Postcode"));
        assertEquals("271026", v.get("Date"));
        assertEquals("Yes", v.get("Consent box"));
        assertEquals("2", v.get("Frequency"));
        assertEquals("NZ", v.get("Residency"));
        assertEquals("Monthly", v.get("Repayment frequency"));
        try (PDDocument d = Loader.loadPDF(r.pdf())) {
            PDCheckBox freq = (PDCheckBox) d.getDocumentCatalog().getAcroForm().getField("Frequency");
            List<String> states = freq.getWidgets().stream().map(w -> w.getAppearanceState().getName()).toList();
            assertEquals(List.of("Off", "2", "Off"), states, "only the selected widget is on");
            assertFalse(d.getDocumentCatalog().getAcroForm().getFields().isEmpty(), "output stays editable");
        }
    }

    @Test
    void repeatedRunsProduceEquivalentFieldValues() throws IOException {
        Manifest m = manifest(ALL_RULES);
        FillResult a = FILLER.fill(template, m, data(JANE));
        FillResult b = FILLER.fill(template, m, data(JANE));
        assertEquals(values(a.pdf()), values(b.pdf()));
        assertEquals(a.filled(), b.filled());
    }

    @Test
    void missingRequiredValueFailsWithoutPdf() throws IOException {
        FillResult r = FILLER.fill(template, manifest(ALL_RULES), data("{\"Applicant 1 Full Name\":\"  \"}"));
        assertNull(r.pdf());
        assertTrue(codes(r).contains("MISSING_REQUIRED_VALUE"));
    }

    @Test
    void absentOptionalKeyIsSkippedWithContractWarning() throws IOException {
        FillResult r = FILLER.fill(template, manifest(ALL_RULES), data("{\"Applicant 1 Full Name\":\"Jane Example\"}"));
        assertTrue(r.ok());
        assertTrue(r.skipped().contains("Postcode"));
        assertTrue(codes(r).contains("SOURCE_KEY_ABSENT"));
    }

    @Test
    void structuralErrorsAreReported() throws IOException {
        String rules = """
                [{"pdfField":"Does not exist","kind":"text","source":{"key":"a"}},
                 {"pdfField":"Consent box","kind":"text","source":{"key":"a"}},
                 {"pdfField":"Customer signature","kind":"text","source":{"key":"a"}},
                 {"pdfField":"Postcode","kind":"text","source":{"key":"long"}},
                 {"pdfField":"Repayment frequency","kind":"combo","source":{"key":"bad"}},
                 {"pdfField":"Full name","kind":"text","source":{"key":"dup"}}]""";
        FillResult r = FILLER.fill(template, manifest(rules),
                data("{\"a\":\"x\",\"long\":\"12345\",\"bad\":\"Daily\",\"dup\":[\"one\",\"two\"]}"));
        assertNull(r.pdf());
        assertEquals(List.of("UNRESOLVED_PDF_FIELD", "KIND_MISMATCH", "FORBIDDEN_TARGET", "TEXT_TOO_LONG",
                "INVALID_OPTION", "AMBIGUOUS_SOURCE_VALUE"), codes(r));
    }

    @Test
    void conflictingButtonSelectionIsAnError() throws IOException {
        String rules = """
                [{"pdfField":"Frequency","kind":"checkbox","option":"1","source":{"when":{"key":"Freq","operator":"contains","value":"ly"}}},
                 {"pdfField":"Frequency","kind":"checkbox","option":"3","source":{"when":{"key":"Freq","operator":"contains","value":"Month"}}}]""";
        FillResult r = FILLER.fill(template, manifest(rules), data("{\"Freq\":\"Monthly\"}"));
        assertEquals(List.of("CONFLICTING_SELECTION"), codes(r));
    }

    @Test
    void unmatchedConditionLeavesExistingStateUntouched() throws IOException {
        byte[] prechecked = FILLER.fill(template, manifest(
                "[{\"pdfField\":\"Consent box\",\"kind\":\"checkbox\",\"source\":{\"constant\":true}}]"),
                data("{}")).pdf();
        String rules = """
                [{"pdfField":"Consent box","kind":"checkbox","source":{"when":{"key":"Consent","operator":"equals","value":"Yes"}}}]""";
        FillResult r = FILLER.fill(prechecked, manifest(rules, prechecked), data("{\"Consent\":\"No\"}"));
        assertTrue(r.ok(), () -> r.issues().toString());
        assertEquals(List.of("Consent box"), r.skipped());
        assertEquals("Yes", values(r.pdf()).get("Consent box"), "a non-matching condition must not force Off");
    }

    @Test
    void constantCheckedAndUnchecked() throws IOException {
        String rules = """
                [{"pdfField":"Consent box","kind":"checkbox","source":{"constant":true}},
                 {"pdfField":"Frequency","kind":"checkbox","option":"3","source":{"constant":false}}]""";
        FillResult r = FILLER.fill(template, manifest(rules), data("{}"));
        assertTrue(r.ok(), () -> r.issues().toString());
        assertEquals("Yes", values(r.pdf()).get("Consent box"));
        assertEquals("Off", values(r.pdf()).get("Frequency"));
    }

    @Test
    void unencodableCharactersFailInsteadOfBeingDropped() throws IOException {
        String rules = "[{\"pdfField\":\"Full name\",\"kind\":\"text\",\"source\":{\"key\":\"n\"}}]";
        FillResult r = FILLER.fill(template, manifest(rules), data("{\"n\":\"Ātaahua Example\"}"));
        assertNull(r.pdf());
        assertEquals(List.of("UNENCODABLE_TEXT"), codes(r));
    }

    @Test
    void templateHashMismatchIsRejected() throws IOException {
        byte[] other = Arrays.copyOf(template, template.length + 1);
        FillResult r = FILLER.fill(other, manifest(ALL_RULES), data(JANE));
        assertEquals(List.of("TEMPLATE_HASH_MISMATCH"), codes(r));
    }

    @Test
    void issuesNeverEchoDataValues() throws IOException {
        String secret = "Zq-SECRET-VALUE-91";
        String rules = """
                [{"pdfField":"Postcode","kind":"text","source":{"key":"p"}},
                 {"pdfField":"Repayment frequency","kind":"combo","source":{"key":"r"}},
                 {"pdfField":"Residency","kind":"radio","source":{"key":"r"}}]""";
        FillResult r = FILLER.fill(template, manifest(rules), data("{\"p\":\"" + secret + "\",\"r\":\"" + secret + "\"}"));
        assertFalse(r.issues().isEmpty());
        assertFalse(r.issues().toString().contains(secret), r.issues().toString());
    }

    @Test
    void textAppearanceIsActuallyRendered() throws IOException {
        FillResult r = FILLER.fill(template, manifest(ALL_RULES), data(JANE));
        // widget "Full name" at x=40..340, y=740..758 (PDF points, bottom-left origin)
        int dark = Rendering.darkPixels(r.pdf(), 0, 40, 740, 300, 18);
        int blank = Rendering.darkPixels(template, 0, 40, 740, 300, 18);
        assertTrue(dark > blank + 50, "expected visible glyphs in the field, dark=" + dark + " blank=" + blank);
    }

    static List<String> codes(FillResult r) {
        return r.issues().stream().map(Issue::code).toList();
    }

    @Test
    void macroforgeKeyNormalisationMatchesLegacyKeys() throws IOException {
        String rules = "[{\"pdfField\":\"Full name\",\"kind\":\"text\",\"source\":{\"key\":\"Applicant 1 Residential City_Town\"}}]";
        Manifest m = JSON.readValue(JSON.writeValueAsString(manifest(rules)).replace("\"none\"", "\"macroforge-v1\""), Manifest.class);
        assertEquals(Manifest.KeyNormalization.macroforgeV1, m.sourceKeyNormalization());
        FillResult r = FILLER.fill(template, m, data("{\"Applicant 1 Residential City/Town\":\"Auckland\"}"));
        assertTrue(r.ok(), () -> r.issues().toString());
        assertEquals("Auckland", values(r.pdf()).get("Full name"));

        FillResult clash = FILLER.fill(template, m,
                data("{\"Applicant 1 Residential City/Town\":\"A\",\"Applicant 1 Residential City.Town\":\"B\"}"));
        assertEquals(List.of("AMBIGUOUS_SOURCE_VALUE"), codes(clash), "keys colliding after normalisation must not pick one");
    }

    @Test
    void manifestDefaultsAreSafe() throws IOException {
        Manifest m = manifest("[]");
        assertEquals(Manifest.EncryptionPolicy.reject, m.encryption());
        assertNotNull(m.unsupported());
    }
}
