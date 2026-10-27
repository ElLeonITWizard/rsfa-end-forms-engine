package nz.co.rsfa.endforms.catalog;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import nz.co.rsfa.endforms.mapping.Manifest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Resolves {@code templateId@templateVersion} to a manifest (from the mappings directory, in git)
 * and its template binary (from private template directories, never in git).
 *
 * <p>Template directories are local paths today; on Cloud Run they would be a read-only
 * Cloud Storage volume mount, so no storage SDK is needed in the engine (see docs/decisions.md).
 */
public final class TemplateCatalog {

    public static final long MAX_TEMPLATE_BYTES = 25L * 1024 * 1024;
    private static final Pattern SAFE_FILE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,200}\\.pdf");

    private final Map<String, Manifest> manifests;
    private final List<Path> templateDirs;

    private TemplateCatalog(Map<String, Manifest> manifests, List<Path> templateDirs) {
        this.manifests = manifests;
        this.templateDirs = templateDirs;
    }

    public static ObjectMapper json() {
        return new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public static Manifest readManifest(Path file) throws IOException {
        Manifest m = json().readValue(file.toFile(), Manifest.class);
        if (m.schemaVersion() != Manifest.SUPPORTED_SCHEMA_VERSION) {
            throw new IOException("unsupported manifest schemaVersion " + m.schemaVersion() + " in " + file.getFileName());
        }
        if (m.templateId() == null || m.templateVersion() == null || m.templateSha256() == null || m.templateFile() == null) {
            throw new IOException("manifest missing templateId/templateVersion/templateFile/templateSha256: " + file.getFileName());
        }
        if (!SAFE_FILE.matcher(m.templateFile()).matches()) {
            throw new IOException("unsafe templateFile name in " + file.getFileName());
        }
        return m;
    }

    public static TemplateCatalog load(Path mappingsDir, List<Path> templateDirs) throws IOException {
        Map<String, Manifest> found = new TreeMap<>();
        try (Stream<Path> files = Files.walk(mappingsDir)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".json")).sorted().toList()) {
                Manifest m = readManifest(p);
                if (found.putIfAbsent(m.key(), m) != null) {
                    throw new IOException("duplicate manifest for " + m.key());
                }
            }
        }
        List<Path> dirs = templateDirs.stream().map(d -> d.toAbsolutePath().normalize()).toList();
        return new TemplateCatalog(Map.copyOf(found), dirs);
    }

    public Optional<Manifest> manifest(String templateId, String templateVersion) {
        return Optional.ofNullable(manifests.get(templateId + "@" + templateVersion));
    }

    public Collection<Manifest> manifests() {
        return manifests.values();
    }

    /** Reads the template binary for a manifest; the SHA-256 check happens in the filler. */
    public Optional<byte[]> templateBytes(Manifest m) throws IOException {
        if (!SAFE_FILE.matcher(m.templateFile()).matches()) {
            return Optional.empty();
        }
        for (Path dir : templateDirs) {
            Path p = dir.resolve(m.templateFile()).normalize();
            if (!p.startsWith(dir) || !Files.isRegularFile(p)) {
                continue;
            }
            if (Files.size(p) > MAX_TEMPLATE_BYTES) {
                throw new IOException("template exceeds size limit: " + m.templateFile());
            }
            return Optional.of(Files.readAllBytes(p));
        }
        return Optional.empty();
    }
}
