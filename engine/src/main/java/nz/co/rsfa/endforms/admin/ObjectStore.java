package nz.co.rsfa.endforms.admin;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Write-once object storage for the administration area (D-041). Objects are only ever created, never overwritten or
 * deleted by the application, so the only concurrency primitive needed is an atomic create-if-absent: Cloud Storage's
 * {@code ifGenerationMatch=0} on Cloud Run ({@link GcsObjectStore}), a hard link in local development
 * ({@link Local}). This also keeps the service account at create + read permissions (no overwrite, no delete).
 */
public interface ObjectStore {

    /** Object names: lower-case path segments of letters, digits, {@code . _ -}. No traversal, no user text. */
    Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9._-]{0,80}(/[a-z0-9][a-z0-9._-]{0,80}){0,4}");

    Optional<byte[]> get(String name) throws IOException;

    /** @return false when the object already exists (nothing written) */
    boolean createIfAbsent(String name, byte[] bytes, String contentType) throws IOException;

    /** Names under the prefix, sorted. */
    List<String> list(String prefix) throws IOException;

    /** {@code local} or {@code gcs}; shown in the UI. */
    String kind();

    static String check(String name) {
        if (name == null || !NAME.matcher(name).matches() || name.contains("..")) {
            throw new IllegalArgumentException("invalid object name");
        }
        return name;
    }

    /** Local development: a directory (by default under the git-ignored {@code .local/}). */
    final class Local implements ObjectStore {
        private final Path root;

        public Local(Path root) throws IOException {
            this.root = root.toAbsolutePath().normalize();
            Files.createDirectories(this.root);
        }

        @Override
        public Optional<byte[]> get(String name) throws IOException {
            Path p = root.resolve(check(name));
            return Files.isRegularFile(p) ? Optional.of(Files.readAllBytes(p)) : Optional.empty();
        }

        @Override
        public boolean createIfAbsent(String name, byte[] bytes, String contentType) throws IOException {
            Path target = root.resolve(check(name));
            Files.createDirectories(target.getParent());
            Path tmp = Files.createTempFile(target.getParent(), ".tmp-", ".part");
            try {
                Files.write(tmp, bytes);
                Files.createLink(target, tmp); // link(2) is atomic and fails if the target exists
                return true;
            } catch (FileAlreadyExistsException e) {
                return false;
            } finally {
                Files.deleteIfExists(tmp);
            }
        }

        @Override
        public List<String> list(String prefix) throws IOException {
            List<String> out = new ArrayList<>();
            if (!Files.isDirectory(root)) {
                return out;
            }
            try (Stream<Path> s = Files.walk(root)) {
                s.filter(Files::isRegularFile).map(p -> root.relativize(p).toString().replace('\\', '/'))
                        .filter(n -> n.startsWith(prefix) && !n.substring(n.lastIndexOf('/') + 1).startsWith(".tmp-"))
                        .sorted().forEach(out::add);
            }
            return out;
        }

        @Override
        public String kind() {
            return "local";
        }
    }
}
