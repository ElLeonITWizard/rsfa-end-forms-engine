package nz.co.rsfa.endforms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The demo UI must use the API's real request schema: for every offered template, the data keys it sends are exactly
 * the keys the template's manifest reads (rules, overlays and validation; compared after the manifest's key
 * normalisation), and its sample data is one of the synthetic fixtures. Skipped when the repo's mappings or fixtures
 * are absent (e.g. inside a Docker build).
 */
class DemoUiFormsTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final Path REPO = Path.of(System.getProperty("rsfa.repo.dir", ".."));

    static String norm(String k) {
        return k.replace('/', '_').replace('.', '_');
    }

    static Set<String> manifestKeys(JsonNode m) {
        Set<String> keys = new HashSet<>();
        m.get("fields").forEach(f -> {
            JsonNode s = f.path("source");
            if (s.has("key")) {
                keys.add(s.get("key").asText());
            }
            if (s.has("when")) {
                keys.add(s.get("when").get("key").asText());
            }
        });
        m.path("overlays").forEach(o -> keys.add(o.path("source").path("key").asText()));
        m.path("validation").path("requires").forEach(r -> {
            if (r.has("ifPresent")) {
                keys.add(r.get("ifPresent").asText());
            }
            if (r.has("when")) {
                keys.add(r.get("when").get("key").asText());
            }
            keys.add(r.get("require").get("key").asText());
        });
        return keys;
    }

    @Test
    void everyOfferedTemplateMatchesItsManifestAndASyntheticFixture() throws IOException {
        assumeTrue(Files.isDirectory(REPO.resolve("mappings")) && Files.isDirectory(REPO.resolve("tests/fixtures")),
                "no mappings/fixtures");
        JsonNode forms;
        try (InputStream in = DemoUiFormsTest.class.getResourceAsStream("/ui/forms.json")) {
            assertNotNull(in);
            forms = JSON.readTree(in);
        }
        List<JsonNode> manifests = new ArrayList<>();
        List<JsonNode> fixtures = new ArrayList<>();
        try (Stream<Path> s = Files.walk(REPO.resolve("mappings"))) {
            for (Path p : s.filter(p -> p.toString().endsWith(".json")).toList()) {
                manifests.add(JSON.readTree(p.toFile()));
            }
        }
        try (Stream<Path> s = Files.list(REPO.resolve("tests/fixtures"))) {
            for (Path p : s.filter(p -> p.toString().endsWith(".request.json")).toList()) {
                fixtures.add(JSON.readTree(p.toFile()));
            }
        }
        assertEquals("BNZ_BOARDER_FORM", forms.get("templates").get(0).get("templateId").asText(),
                "the tested, deployed form stays the default");
        for (JsonNode t : forms.get("templates")) {
            String id = t.get("templateId").asText();
            String version = t.get("templateVersion").asText();
            JsonNode manifest = manifests.stream().filter(m -> m.get("templateId").asText().equals(id)
                    && m.get("templateVersion").asText().equals(version)).findFirst().orElse(null);
            assertNotNull(manifest, "no manifest for " + id + "@" + version);
            Set<String> uiKeys = new HashSet<>();
            t.get("sections").forEach(s -> s.get("fields").forEach(f -> {
                uiKeys.add(norm(f.get("key").asText()));
                if ("select".equals(f.path("type").asText())) {
                    assertTrue(f.get("options").size() > 0, id + ": select without options " + f.get("key"));
                }
            }));
            Set<String> expected = new HashSet<>();
            manifestKeys(manifest).forEach(k -> expected.add(norm(k)));
            assertEquals(expected, uiKeys, id + ": UI fields must be exactly the manifest's data keys");
            JsonNode sample = t.get("sample");
            boolean fixtureMatch = fixtures.stream().anyMatch(fx -> fx.get("templateId").asText().equals(id)
                    && fx.path("today").asText().equals(sample.get("today").asText())
                    && sample.get("data").properties().stream()
                            .allMatch(e -> e.getValue().equals(fx.get("data").get(e.getKey()))));
            assertTrue(fixtureMatch, id + ": sample data must be one of the synthetic fixtures");
            assertFalse(t.path("status").asText().isEmpty(), id + ": status (tested | candidate) required");
        }
    }
}
