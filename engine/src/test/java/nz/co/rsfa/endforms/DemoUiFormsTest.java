package nz.co.rsfa.endforms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The demo UI must use the API's real request schema: every data key it sends is a key the template's manifest reads,
 * and its sample data is the synthetic fixture. Skipped when the repo's mappings or fixtures are absent (e.g. inside a Docker build).
 */
class DemoUiFormsTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final Path REPO = Path.of(System.getProperty("rsfa.repo.dir", ".."));

    @Test
    void formKeysMatchManifestAndSampleMatchesFixture() throws Exception {
        assumeTrue(Files.isDirectory(REPO.resolve("mappings"))
                && Files.isRegularFile(REPO.resolve("tests/fixtures/bnz-boarder.request.json")), "no mappings/fixtures");
        JsonNode forms;
        try (InputStream in = DemoUiFormsTest.class.getResourceAsStream("/ui/forms.json")) {
            assertNotNull(in);
            forms = JSON.readTree(in);
        }
        assertEquals(1, forms.get("templates").size(), "demo scope is BNZ Boarder only");
        for (JsonNode t : forms.get("templates")) {
            String id = t.get("templateId").asText();
            String version = t.get("templateVersion").asText();
            Path manifestPath = REPO.resolve("mappings/bnz/" + id + "@" + version + ".json");
            JsonNode manifest = JSON.readTree(manifestPath.toFile());
            assertEquals(id, manifest.get("templateId").asText());
            assertEquals(version, manifest.get("templateVersion").asText());
            Set<String> manifestKeys = new HashSet<>();
            manifest.get("fields").forEach(f -> {
                if (f.path("source").has("key")) {
                    manifestKeys.add(f.get("source").get("key").asText());
                }
            });
            manifest.path("overlays").forEach(o -> manifestKeys.add(o.path("source").path("key").asText(o.path("key").asText())));
            Set<String> uiKeys = new HashSet<>();
            t.get("sections").forEach(s -> s.get("fields").forEach(f -> uiKeys.add(f.get("key").asText())));
            assertEquals(manifestKeys, uiKeys, "UI fields must be exactly the manifest's data keys");

            JsonNode fixture = JSON.readTree(REPO.resolve("tests/fixtures/bnz-boarder.request.json").toFile());
            JsonNode sample = t.get("sample");
            assertEquals(fixture.get("today").asText(), sample.get("today").asText());
            sample.get("data").fields().forEachRemaining(e -> {
                assertTrue(uiKeys.contains(e.getKey()), e.getKey());
                assertEquals(fixture.get("data").get(e.getKey()).asText(), e.getValue().asText(), e.getKey());
            });
        }
    }
}
