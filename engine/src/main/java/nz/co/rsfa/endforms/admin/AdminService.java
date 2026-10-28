package nz.co.rsfa.endforms.admin;

import nz.co.rsfa.endforms.catalog.TemplateCatalog;
import nz.co.rsfa.endforms.relay.MetadataAccessTokenSource;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;

/**
 * Configuration of the private administration service ({@code ENGINE_ROLE=admin}, D-041), from environment variables.
 * Fails closed on anything missing or unsafe.
 *
 * <table>
 *   <tr><td>ADMIN_USERS</td><td>required: {@code email:role,...} (viewer | author | approver | admin)</td></tr>
 *   <tr><td>IAP_AUDIENCE</td><td>required: {@code /projects/<number>/locations/<region>/services/<service>}</td></tr>
 *   <tr><td>ADMIN_BUCKET</td><td>required: the private admin bucket (templates, drafts, audit, results, config)</td></tr>
 *   <tr><td>ADMIN_SEPARATE_APPROVER</td><td>optional {@code true}: approver ≠ the person who saved the version</td></tr>
 *   <tr><td>ADMIN_SUGGESTIONS</td><td>optional, only {@code mock} (no AI provider exists)</td></tr>
 *   <tr><td>ENGINE_MAPPINGS_DIR, ADMIN_REPO_ROOT</td><td>repository manifests and status files baked into the image</td></tr>
 * </table>
 *
 * Local emulation (production-like tests, never on Cloud Run): {@code ADMIN_EMULATION=1} with {@code IAP_KEYS_URL},
 * {@code ADMIN_GCS_ENDPOINT} (loopback) and {@code ADMIN_GCS_TOKEN}. On Cloud Run ({@code K_SERVICE} set) these
 * overrides are refused, the service name must contain "admin", and the engine's secrets must not be present.
 */
public final class AdminService {

    private AdminService() {
    }

    static final List<String> EMULATION_ONLY = List.of("ADMIN_EMULATION", "IAP_KEYS_URL", "ADMIN_GCS_ENDPOINT", "ADMIN_GCS_TOKEN");
    static final List<String> ENGINE_SECRETS = List.of("ENGINE_API_KEY", "ENGINE_HMAC_SECRET", "RELAY_HMAC_SECRETS", "ENGINE_ADMIN");

    public static AdminApi build(Map<String, String> env, Clock clock) throws IOException {
        String service = env.get("K_SERVICE");
        boolean cloud = service != null;
        if (cloud) {
            for (String k : EMULATION_ONLY) {
                if (env.containsKey(k)) {
                    throw new IllegalStateException(k + " is for local emulation only and is refused on Cloud Run");
                }
            }
            if (!service.contains("admin")) {
                throw new IllegalStateException("ENGINE_ROLE=admin must run on its own Cloud Run service (name containing "
                        + "'admin'), never on the engine or relay service");
            }
        } else if (!"1".equals(env.get("ADMIN_EMULATION"))) {
            throw new IllegalStateException("ENGINE_ROLE=admin runs on Cloud Run; locally set ADMIN_EMULATION=1 (tests) "
                    + "or use ENGINE_ADMIN=local");
        }
        for (String k : ENGINE_SECRETS) {
            if (env.containsKey(k)) {
                throw new IllegalStateException(k + " belongs to the engine or relay and must not be given to the admin service");
            }
        }
        String suggestions = env.getOrDefault("ADMIN_SUGGESTIONS", "mock");
        if (!suggestions.equals("mock")) {
            throw new IllegalStateException("ADMIN_SUGGESTIONS accepts only 'mock': no AI provider is approved");
        }
        String separate = env.getOrDefault("ADMIN_SEPARATE_APPROVER", "false");
        if (!separate.equals("true") && !separate.equals("false")) {
            throw new IllegalStateException("ADMIN_SEPARATE_APPROVER must be true or false");
        }
        Map<String, String> users = AdminAccess.parseUsers(env.get("ADMIN_USERS"));
        URI keys = cloud ? IapVerifier.GOOGLE_KEYS : URI.create(required(env, "IAP_KEYS_URL"));
        IapVerifier verifier = new IapVerifier(required(env, "IAP_AUDIENCE"), new IapVerifier.HttpKeys(keys, clock), clock);
        ObjectStore store = cloud
                ? new GcsObjectStore(URI.create("https://storage.googleapis.com"), required(env, "ADMIN_BUCKET"),
                        new MetadataAccessTokenSource(clock))
                : new GcsObjectStore(URI.create(required(env, "ADMIN_GCS_ENDPOINT")), required(env, "ADMIN_BUCKET"),
                        () -> required(env, "ADMIN_GCS_TOKEN"));
        Workspace workspace = new Workspace(store, clock);
        byte[] schemaBytes = workspace.config("monday-schema.json").orElseThrow(() -> new IllegalStateException(
                "config/monday-schema.json is missing from the admin bucket (the deployment uploads it)"));
        MondaySchema schema = MondaySchema.parse(schemaBytes, "admin bucket: config/monday-schema.json");
        TemplateCatalog catalog = TemplateCatalog.load(Path.of(env.getOrDefault("ENGINE_MAPPINGS_DIR", "mappings")), List.of());
        Path repoRoot = Path.of(env.getOrDefault("ADMIN_REPO_ROOT", ".")).toAbsolutePath().normalize();
        AdminApi.Policy policy = new AdminApi.Policy(cloud ? "Private test environment" : "Local emulation of the private "
                + "environment", true, Boolean.parseBoolean(separate));
        System.err.printf("admin service: auth=iap users=%d storage=%s schemaColumns=%d manifests=%d syntheticOnly=true "
                + "separateApprover=%s suggestions=mock publication=disabled%n", users.size(), store.kind(),
                schema.columns().size(), catalog.manifests().size(), separate);
        return new AdminApi(workspace, catalog, schema, repoRoot, new MockSuggestionProvider(clock), clock,
                AdminAccess.iap(verifier, users), policy);
    }

    private static String required(Map<String, String> env, String key) {
        String v = env.get(key);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException(key + " is required for ENGINE_ROLE=admin");
        }
        return v.trim();
    }
}
