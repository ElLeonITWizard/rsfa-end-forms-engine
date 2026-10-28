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
        return manifest("", fieldsJson, tpl);
    }

    /** @param extra additional top-level manifest properties, e.g. {@code "fontFallback":"unicode",} */
    static Manifest manifest(String extra, String fieldsJson, byte[] tpl) throws IOException {
        String m = """
                {"schemaVersion":1,"templateId":"SYNTH","templateVersion":"1","templateFile":"synth.pdf",%s
                 "templateSha256":"%s","fields":%s}""".formatted(extra, FormFiller.sha256(tpl), fieldsJson);
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
    void unmatchedConditionWritesNothing() throws IOException {
        String rules = """
                [{"pdfField":"Consent box","kind":"checkbox","source":{"when":{"key":"Consent","operator":"equals","value":"Yes"}}}]""";
        FillResult r = FILLER.fill(template, manifest(rules), data("{\"Consent\":\"No\"}"));
        assertTrue(r.ok(), () -> r.issues().toString());
        assertEquals(List.of("Consent box"), r.skipped());
        assertTrue(r.filled().isEmpty());
        assertEquals("Off", values(r.pdf()).get("Consent box"));
    }

    @Test
    void constantCheckedAndUnchecked() throws IOException {
        String rules = """
                [{"pdfField":"Consent box","kind":"checkbox","source":{"constant":true},"approval":%s},
                 {"pdfField":"Frequency","kind":"checkbox","option":"3","source":{"constant":false}}]""".formatted(APPROVAL);
        FillResult r = FILLER.fill(template, manifest(rules), data("{}"));
        assertTrue(r.ok(), () -> r.issues().toString());
        assertEquals("Yes", values(r.pdf()).get("Consent box"));
        assertEquals("Off", values(r.pdf()).get("Frequency"));
    }

    static final String APPROVAL = "{\"approvedBy\":\"Test Adviser (fictional)\",\"approvedOn\":\"2026-10-01\",\"reference\":\"TEST-001\"}";

    @Test
    void incompleteOrUnverifiableApprovalsFailTheFill() throws IOException {
        for (String bad : List.of("\"approved\":true",
                "\"approval\":{\"approvedBy\":\"\",\"approvedOn\":\"2026-10-01\",\"reference\":\"X\"}",
                "\"approval\":{\"approvedBy\":\"A\",\"approvedOn\":\"2026-12-31\",\"reference\":\"X\"}",
                "\"approval\":{\"approvedBy\":\"A\",\"approvedOn\":\"1 Oct\",\"reference\":\"X\"}")) {
            String rules = "[{\"pdfField\":\"Consent box\",\"kind\":\"checkbox\",\"source\":{\"constant\":true}," + bad + "}]";
            FillResult r = FILLER.fill(template, manifest(rules), data("{}"));
            assertNull(r.pdf(), bad);
            assertEquals(List.of("INVALID_APPROVAL"), codes(r), bad);
        }
    }

    @Test
    void templatePresetTicksAreClearedUnlessAnAuthorizedRuleSelectsThem() throws IOException {
        byte[] preset = SyntheticTemplate.buildWithPresetTick();
        FillResult none = FILLER.fill(preset, manifest("[]", preset), data("{}"));
        assertEquals("Off", values(none.pdf()).get("Consent box"));
        assertEquals(List.of("TEMPLATE_PRESET_CLEARED"), codes(none));

        String when = "[{\"pdfField\":\"Consent box\",\"kind\":\"checkbox\",\"source\":{\"when\":{\"key\":\"C\",\"operator\":\"equals\",\"value\":\"Yes\"}}}]";
        assertEquals("Yes", values(FILLER.fill(preset, manifest(when, preset), data("{\"C\":\"Yes\"}")).pdf()).get("Consent box"));
        assertEquals("Off", values(FILLER.fill(preset, manifest(when, preset), data("{\"C\":\"No\"}")).pdf()).get("Consent box"));

        String unapproved = "[{\"pdfField\":\"Consent box\",\"kind\":\"checkbox\",\"source\":{\"constant\":true}}]";
        FillResult u = FILLER.fill(preset, manifest(unapproved, preset), data("{}"));
        assertEquals("Off", values(u.pdf()).get("Consent box"), "an unapproved constant must not keep the preset tick");
        assertEquals(List.of("CONSTANT_CHECK_NOT_APPROVED", "TEMPLATE_PRESET_CLEARED"), codes(u));

        String approved = "[{\"pdfField\":\"Consent box\",\"kind\":\"checkbox\",\"source\":{\"constant\":true},\"approval\":" + APPROVAL + "}]";
        FillResult a = FILLER.fill(preset, manifest(approved, preset), data("{}"));
        assertEquals("Yes", values(a.pdf()).get("Consent box"));
        assertTrue(a.issues().isEmpty(), a.issues().toString());
    }

    // ------------------------------------------------------------------ comb fields

    @Test
    void combDigitsAreCentredAndPlaceholdersKnockedOut() throws IOException {
        String rules = "[{\"pdfField\":\"Date\",\"kind\":\"text\",\"source\":{\"key\":\"d\"}}]";
        FillResult r = FILLER.fill(template, manifest(rules), data("{\"d\":\"271026\"}"));
        assertTrue(r.ok(), () -> r.issues().toString());
        assertTrue(r.issues().isEmpty(), r.issues().toString());
        assertEquals("271026", values(r.pdf()).get("Date"), "the value itself is never altered");
        int before = Rendering.colourPixels(template, 0, 40, 680, 120, 18, SyntheticTemplate.PLACEHOLDER, 40);
        int after = Rendering.colourPixels(r.pdf(), 0, 40, 680, 120, 18, SyntheticTemplate.PLACEHOLDER, 40);
        assertTrue(before > 100, "fixture must show placeholders, got " + before);
        assertEquals(0, after, "placeholders under filled cells must be hidden");
        for (int i = 0; i < 6; i++) {
            float[] c = Rendering.inkCentre(r.pdf(), 0, 40 + i * 20, 680, 20, 18);
            assertEquals(40 + i * 20 + 10, c[0], 1.0, "digit " + i + " horizontally centred");
            assertEquals(689, c[1], 1.5, "digit " + i + " vertically centred");
        }
    }

    @Test
    void combKnockoutIsSkippedOnANonUniformBackground() throws IOException {
        String rules = "[{\"pdfField\":\"Code\",\"kind\":\"text\",\"source\":{\"key\":\"c\"}}]";
        FillResult r = FILLER.fill(template, manifest(rules), data("{\"c\":\"AB12\"}"));
        assertTrue(r.ok());
        assertEquals(List.of("COMB_BACKGROUND_NOT_UNIFORM"), codes(r));
        try (PDDocument d = Loader.loadPDF(r.pdf())) {
            assertEquals("AB12", d.getDocumentCatalog().getAcroForm().getField("Code").getValueAsString());
        }
    }

    @Test
    void constantTickIsNotAppliedUntilApproved() throws IOException {
        String rules = "[{\"pdfField\":\"Consent box\",\"kind\":\"checkbox\",\"source\":{\"constant\":true}}]";
        FillResult r = FILLER.fill(template, manifest(rules), data("{}"));
        assertTrue(r.ok());
        assertEquals(List.of("CONSTANT_CHECK_NOT_APPROVED"), codes(r));
        assertEquals("Off", values(r.pdf()).get("Consent box"), "an unreviewed declaration tick must stay Off");
    }

    @Test
    void forcedOffNeedsNoOnStateOnMultiStateCheckbox() throws IOException {
        String rules = """
                [{"pdfField":"Frequency","kind":"checkbox","source":{"constant":false}},
                 {"pdfField":"Frequency","kind":"checkbox","option":"2","source":{"when":{"key":"F","operator":"equals","value":"x"}}}]""";
        FillResult off = FILLER.fill(template, manifest(rules), data("{\"F\":\"y\"}"));
        assertTrue(off.ok(), () -> off.issues().toString());
        assertEquals("Off", values(off.pdf()).get("Frequency"));
        FillResult on = FILLER.fill(template, manifest(rules), data("{\"F\":\"x\"}"));
        assertEquals("2", values(on.pdf()).get("Frequency"), "a matching option wins over the field-level default Off");
    }

    // ------------------------------------------------------------------ line items

    static final String LINE_RULES = """
            [{"pdfField":"Full name","kind":"text","source":{"lineItem":{"array":"Subitems","position":1,"path":["name"]}}},
             {"pdfField":"Postcode","kind":"text","source":{"lineItem":{"array":"Subitems","position":2,"path":["values","Code"]}}},
             {"pdfField":"Notes","kind":"text","source":{"lineItem":{"array":"Subitems","position":3,"path":["values","Code"]}}},
             {"pdfField":"Repayment frequency","kind":"combo","source":{"lineItem":{"array":"Subitems","position":1,"path":["values","Repayment frequency"]}}}]""";

    @Test
    void lineItemsReadTheNthArrayElement() throws IOException {
        FillResult r = FILLER.fill(template, manifest(LINE_RULES), data("""
                {"Subitems":[{"name":"Facility A","values":{"Repayment frequency":"Weekly","Code":"0001"}},
                             {"name":"Facility B","values":{"Code":"0002"}}]}"""));
        assertTrue(r.ok(), () -> r.issues().toString());
        Map<String, String> v = values(r.pdf());
        assertEquals("Facility A", v.get("Full name"));
        assertEquals("0002", v.get("Postcode"));
        assertEquals("Weekly", v.get("Repayment frequency"));
        assertEquals(List.of("Notes"), r.skipped(), "row 3 does not exist: skipped quietly");
        assertTrue(r.issues().isEmpty(), r.issues().toString());
    }

    @Test
    void lineItemContractProblemsAreReported() throws IOException {
        FillResult noArray = FILLER.fill(template, manifest(LINE_RULES), data("{}"));
        assertTrue(noArray.ok());
        assertTrue(codes(noArray).contains("SOURCE_KEY_ABSENT"));
        FillResult missingColumn = FILLER.fill(template, manifest(LINE_RULES),
                data("{\"Subitems\":[{\"name\":\"A\",\"values\":{}}]}"));
        assertTrue(missingColumn.issues().stream().anyMatch(i -> i.code().equals("SOURCE_KEY_ABSENT")
                && "Subitems[1].values.Repayment frequency".equals(i.sourceKey())));
        FillResult notArray = FILLER.fill(template, manifest(LINE_RULES), data("{\"Subitems\":\"x\"}"));
        assertNull(notArray.pdf());
        assertTrue(codes(notArray).contains("UNSUPPORTED_VALUE_TYPE"));
    }

    // ------------------------------------------------------------------ overlays

    static String overlay(String id, double x0, double y0, double x1, double y1, String source) {
        return """
                {"id":"%s","page":0,"rect":[%s,%s,%s,%s],"source":%s}""".formatted(id, x0, y0, x1, y1, source);
    }

    @Test
    void overlaysDrawTextAtTheirRectInAnEmbeddedFont() throws IOException {
        String overlays = "\"overlays\":[" + overlay("a", 360, 740, 560, 758, "{\"key\":\"Applicant 1 Full Name\"}")
                + "," + overlay("b", 360, 700, 560, 718, "{\"constant\":\"Mere Tāwhiri-Sample\"}") + "],";
        FillResult r = FILLER.fill(template, manifest(overlays, "[]", template), data("{\"Applicant 1 Full Name\":\"Jane Example\"}"));
        assertTrue(r.ok(), () -> r.issues().toString());
        assertEquals(List.of("overlay:a", "overlay:b"), r.filled());
        assertEquals("Jane Example", Rendering.textIn(r.pdf(), 0, 360, 740, 200, 18));
        assertEquals("Mere Tāwhiri-Sample", Rendering.textIn(r.pdf(), 0, 360, 700, 200, 18));
        assertTrue(Rendering.darkPixels(r.pdf(), 0, 360, 740, 200, 18) > 50);
        assertTrue(Rendering.embeddedFonts(r.pdf()).contains("LiberationSans"), "overlay font must be embedded");
    }

    @Test
    void overlayFailuresAreStructured() throws IOException {
        String tooLong = "\"overlays\":[" + overlay("t", 40, 400, 70, 412, "{\"constant\":\"This value is far too long for a tiny box\"}") + "],";
        assertEquals(List.of("OVERLAY_TEXT_TOO_LONG"), codes(FILLER.fill(template, manifest(tooLong, "[]", template), data("{}"))));
        String outside = "\"overlays\":[" + overlay("o", 500, 830, 700, 850, "{\"constant\":\"x\"}") + "],";
        assertEquals(List.of("OVERLAY_OUT_OF_PAGE"), codes(FILLER.fill(template, manifest(outside, "[]", template), data("{}"))));
        String underWidget = "\"overlays\":[" + overlay("w", 40, 600, 340, 618, "{\"constant\":\"x\"}") + "],";
        FillResult r = FILLER.fill(template, manifest(underWidget, "[]", template), data("{}"));
        assertTrue(r.ok());
        assertEquals(List.of("OVERLAY_UNDER_OPAQUE_WIDGET"), codes(r));
        String required = "\"overlays\":[" + overlay("r", 40, 400, 300, 418, "{\"key\":\"k\"}").replace("}}", "},\"required\":true}") + "],";
        assertEquals(List.of("MISSING_REQUIRED_VALUE"), codes(FILLER.fill(template, manifest(required, "[]", template), data("{}"))));
    }

    @Test
    void overlaysOnEncryptedTemplatesAreRefused() throws IOException {
        byte[] enc = SyntheticTemplate.buildEncrypted();
        String extra = "\"encryption\":\"preserve\",\"overlays\":[" + overlay("e", 40, 400, 300, 418, "{\"constant\":\"x\"}") + "],";
        assertEquals(List.of("OVERLAY_ON_ENCRYPTED_TEMPLATE"), codes(FILLER.fill(enc, manifest(extra, "[]", enc), data("{}"))));
    }

    // ------------------------------------------------------------------ fonts

    @Test
    void unicodeFallbackFillsMacronsWhenTheManifestAllowsIt() throws IOException {
        String rules = "[{\"pdfField\":\"Full name\",\"kind\":\"text\",\"source\":{\"key\":\"n\"}}]";
        FillResult r = FILLER.fill(template, manifest("\"fontFallback\":\"unicode\",", rules, template),
                data("{\"n\":\"Ātaahua Ōtāhuhu\"}"));
        assertTrue(r.ok(), () -> r.issues().toString());
        assertEquals(List.of("FONT_FALLBACK"), codes(r));
        assertEquals("Ātaahua Ōtāhuhu", values(r.pdf()).get("Full name"));
        assertTrue(Rendering.darkPixels(r.pdf(), 0, 40, 740, 300, 18) > 50, "fallback appearance must render");
        assertTrue(Rendering.embeddedFonts(r.pdf()).contains("LiberationSans"));
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
        Manifest m = JSON.readValue(JSON.writeValueAsString(manifest(rules)).replace("\"sourceKeyNormalization\":\"none\"", "\"sourceKeyNormalization\":\"macroforge-v1\""), Manifest.class);
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
        assertEquals(Manifest.FontFallback.none, m.fontFallback());
        assertNotNull(m.unsupported());
        assertTrue(m.overlays().isEmpty());
    }

    // ------------------------------------------------------------------ manifest validation (D-023)

    static final String CLOSED = """
            "validation":{"closedOptions":[{"key":"Freq","multiSelect":true}],
              "requires":[{"id":"TEST-WEEKLY","ifPresent":"Postcode",
                           "require":{"key":"Freq","operator":"equals","value":"Weekly"},"reason":"test form is weekly only"},
                          {"id":"TEST-PRESENT","ifPresent":"Applicant 1 Full Name",
                           "require":{"key":"Consent","operator":"present"},"reason":"consent answer needed"}]},""";

    @Test
    void closedOptionsRefuseValuesThatNoBoxRepresents() throws IOException {
        Manifest m = manifest("\"validation\":{\"closedOptions\":[{\"key\":\"Freq\"}]},", ALL_RULES, template);
        assertTrue(FILLER.fill(template, m, data(JANE)).ok(), "Fortnightly is represented by a contains rule");
        FillResult renamed = FILLER.fill(template, m, data(JANE.replace("Fortnightly", "Every two weeks")));
        assertNull(renamed.pdf(), "a renamed Monday label must not leave the boxes silently blank");
        assertEquals(List.of("UNMAPPED_OPTION"), codes(renamed));
        assertEquals("Freq", renamed.issues().getFirst().sourceKey());
        assertFalse(renamed.issues().toString().contains("Every two weeks"), "issues never echo values");
        assertTrue(FILLER.fill(template, m, data(JANE.replace("\"Freq\":\"Fortnightly\",", ""))).ok(),
                "an absent value is handled by the field rules, not by closedOptions");

        Manifest blank = manifest("\"validation\":{\"closedOptions\":[{\"key\":\"Freq\",\"onUnmapped\":\"blank\"}]},",
                ALL_RULES, template);
        FillResult b = FILLER.fill(template, blank, data(JANE.replace("Fortnightly", "Every two weeks")));
        assertTrue(b.ok());
        assertEquals(List.of("UNMAPPED_OPTION_LEFT_BLANK"), codes(b));
        assertEquals("Off", values(b.pdf()).get("Frequency"));
    }

    @Test
    void multiSelectValuesThatLegacyEqualsRulesCannotRepresentAreRefused() throws IOException {
        Manifest m = manifest("\"validation\":{\"closedOptions\":[{\"key\":\"Freq\",\"multiSelect\":true}]},",
                ALL_RULES, template);
        FillResult r = FILLER.fill(template, m, data(JANE.replace("Fortnightly", "Weekly, Monthly")));
        assertNull(r.pdf());
        assertEquals(List.of("MULTI_SELECT_NOT_REPRESENTABLE"), codes(r));
        assertEquals("1", values(FILLER.fill(template, m, data(JANE.replace("Fortnightly", "Weekly"))).pdf()).get("Frequency"));
    }

    @Test
    void preconditionsRefuseDocumentsThatWouldMisstateAValue() throws IOException {
        Manifest m = manifest(CLOSED, ALL_RULES, template);
        FillResult r = FILLER.fill(template, m, data(JANE));
        assertNull(r.pdf(), "Postcode present but Freq is not Weekly");
        assertEquals(List.of("PRECONDITION_FAILED"), codes(r));
        assertTrue(r.issues().getFirst().message().contains("TEST-WEEKLY"));
        assertTrue(FILLER.fill(template, m, data(JANE.replace("Fortnightly", "Weekly"))).ok());
        FillResult noConsent = FILLER.fill(template, m, data(JANE.replace("Fortnightly", "Weekly").replace("\"Consent\":\"Yes\",", "")));
        assertEquals(List.of("PRECONDITION_FAILED"), codes(noConsent).stream().filter(c -> !c.equals("SOURCE_KEY_ABSENT")).toList());
        assertTrue(noConsent.issues().stream().anyMatch(i -> i.message().contains("TEST-PRESENT")));
        assertTrue(FILLER.fill(template, m, data(JANE.replace("\"Postcode\":\"0999\",", ""))).ok(),
                "no trigger value, no requirement");
    }

    @Test
    void closedOptionKeyWithoutRuleIsAnInvalidManifest() throws IOException {
        Manifest m = manifest("\"validation\":{\"closedOptions\":[{\"key\":\"Nope\"}]},", ALL_RULES, template);
        assertEquals(List.of("INVALID_RULE"), codes(FILLER.fill(template, m, data(JANE))));
    }

    @Test
    void includesTicksEverySelectedLabelOfAMultiSelectAndClosedOptionsCheckEachLabel() throws IOException {
        String rules = """
                [{"pdfField":"Frequency","kind":"checkbox","option":"1","source":{"when":{"key":"Freq","operator":"includes","value":"Weekly"}}},
                 {"pdfField":"Consent box","kind":"checkbox","source":{"when":{"key":"Freq","operator":"includes","value":"Monthly"}}}]""";
        Manifest m = manifest("\"validation\":{\"closedOptions\":[{\"key\":\"Freq\",\"multiSelect\":true}]},", rules, template);
        FillResult both = FILLER.fill(template, m, data("{\"Freq\":\"Weekly, Monthly\"}"));
        assertTrue(both.ok(), () -> both.issues().toString());
        assertEquals("1", values(both.pdf()).get("Frequency"));
        assertEquals("Yes", values(both.pdf()).get("Consent box"));
        assertEquals("Off", values(FILLER.fill(template, m, data("{\"Freq\":\"Weekly\"}")).pdf()).get("Consent box"));
        assertEquals(List.of("UNMAPPED_OPTION"), codes(FILLER.fill(template, m, data("{\"Freq\":\"Weekly Plus\"}"))),
                "includes matches whole labels only");
        assertEquals(List.of("UNMAPPED_OPTION"), codes(FILLER.fill(template, m, data("{\"Freq\":\"Weekly, Daily\"}"))),
                "every selected label must be represented");
    }

    @Test
    void conditionalRequirementsRefuseContradictoryData() throws IOException {
        Manifest m = manifest("""
                "validation":{"requires":[{"id":"T-NEVER","when":{"key":"Freq","operator":"equals","value":"Never"},
                  "require":{"key":"Postcode","operator":"absent"},"reason":"never but details given"}]},""", ALL_RULES, template);
        FillResult r = FILLER.fill(template, m, data(JANE.replace("Fortnightly", "Never")));
        assertEquals(List.of("PRECONDITION_FAILED"), codes(r));
        assertTrue(r.issues().getFirst().message().contains("T-NEVER"));
        assertTrue(FILLER.fill(template, m, data(JANE.replace("Fortnightly", "Never").replace("\"Postcode\":\"0999\",", ""))).ok());
        assertTrue(FILLER.fill(template, m, data(JANE)).ok(), "condition not met: no requirement");
        Manifest both = manifest("""
                "validation":{"requires":[{"id":"T-BAD","ifPresent":"Postcode","when":{"key":"Freq","operator":"present"},
                  "require":{"key":"Postcode","operator":"present"},"reason":"x"}]},""", ALL_RULES, template);
        assertEquals(List.of("INVALID_RULE"), codes(FILLER.fill(template, both, data(JANE))));
    }
}
