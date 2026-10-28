package nz.co.rsfa.endforms.admin;

import com.sun.net.httpserver.HttpExchange;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Who is calling the administration area and what they may do (D-041).
 *
 * <p>Roles (server-side; the browser only displays them):
 * <ul>
 *   <li>{@code viewer}: read the library, drafts, test results and audit trail;</li>
 *   <li>{@code author}: viewer + upload templates, create and save drafts, generate tests, request (mock) suggestions,
 *       mark ready for review or back to draft;</li>
 *   <li>{@code approver}: viewer + record or revoke the approval of a reviewed version;</li>
 *   <li>{@code admin}: everything above.</li>
 * </ul>
 * On Cloud Run the identity is the IAP-verified email ({@link IapVerifier}) and the role comes from the explicit
 * {@code ADMIN_USERS} allowlist; anyone else is refused even if IAP let them through. Locally, a single developer
 * identity ({@code ADMIN_ACTOR}) with the admin role is used on a loopback address only.
 */
public final class AdminAccess {

    public static final List<String> ROLES = List.of("viewer", "author", "approver", "admin");

    public enum Permission { READ, EDIT, APPROVE }

    public record Principal(String email, String role) {
        public boolean may(Permission p) {
            return switch (p) {
                case READ -> true;
                case EDIT -> role.equals("author") || role.equals("admin");
                case APPROVE -> role.equals("approver") || role.equals("admin");
            };
        }

        /** Stable, non-reversible short id for logs (the email itself is never logged). */
        public String logId() {
            return AdminAccess.logId(email);
        }
    }

    /**
     * Authentication outcome: a principal, or a refusal code and HTTP status. {@code email} is the verified identity of
     * a refused but signed-in user (shown only to that user, so a wrong Google account can be recognised; never logged).
     */
    public record Outcome(Principal principal, int status, String code, String email) {
        static Outcome ok(Principal p) {
            return new Outcome(p, 200, null, null);
        }

        static Outcome refused(int status, String code) {
            return new Outcome(null, status, code, null);
        }
    }

    public interface Authenticator {
        Outcome authenticate(HttpExchange ex);

        /** {@code local} or {@code iap}; shown in the UI. */
        String kind();
    }

    /** Local development: one identity with the admin role (the server binds to loopback only). */
    public static Authenticator local(String actor) {
        Principal p = new Principal(Workspace.safeText(actor == null || actor.isBlank() ? "local-admin" : actor, 80), "admin");
        return new Authenticator() {
            @Override
            public Outcome authenticate(HttpExchange ex) {
                return Outcome.ok(p);
            }

            @Override
            public String kind() {
                return "local";
            }
        };
    }

    /** Cloud Run behind IAP: a valid IAP assertion AND an entry in the allowlist. */
    public static Authenticator iap(IapVerifier verifier, Map<String, String> users) {
        Map<String, String> allow = Map.copyOf(users);
        return new Authenticator() {
            @Override
            public Outcome authenticate(HttpExchange ex) {
                IapVerifier.Identity id;
                try {
                    id = verifier.verify(ex.getRequestHeaders().getFirst(IapVerifier.HEADER));
                } catch (IapVerifier.Invalid e) {
                    return Outcome.refused(401, e.code);
                }
                String role = allow.get(id.email());
                if (role == null) {
                    return new Outcome(null, 403, "NOT_AUTHORISED_USER", id.email());
                }
                return Outcome.ok(new Principal(id.email(), role));
            }

            @Override
            public String kind() {
                return "iap";
            }
        };
    }

    /**
     * Parses {@code ADMIN_USERS}: {@code email:role} entries separated by commas, e.g.
     * {@code a@example.org:admin,b@example.org:author}. Fails closed on an unknown role, a duplicate, a malformed email
     * or a list without any admin.
     */
    public static Map<String, String> parseUsers(String spec) {
        Map<String, String> out = new LinkedHashMap<>();
        if (spec == null || spec.isBlank()) {
            throw new IllegalArgumentException("ADMIN_USERS is empty: name at least one administrator");
        }
        for (String entry : spec.split(",")) {
            String e = entry.trim();
            if (e.isEmpty()) {
                continue;
            }
            int i = e.lastIndexOf(':');
            if (i <= 0) {
                throw new IllegalArgumentException("ADMIN_USERS entries are email:role");
            }
            String email = e.substring(0, i).trim().toLowerCase(Locale.ROOT);
            String role = e.substring(i + 1).trim();
            if (!email.matches("[^@\\s:,]{1,64}@[a-z0-9.-]{1,190}\\.[a-z]{2,}")) {
                throw new IllegalArgumentException("ADMIN_USERS has a malformed email (entry " + (out.size() + 1) + ")");
            }
            if (!ROLES.contains(role)) {
                throw new IllegalArgumentException("ADMIN_USERS role must be one of " + ROLES);
            }
            if (out.put(email, role) != null) {
                throw new IllegalArgumentException("ADMIN_USERS lists an email twice");
            }
        }
        if (!out.containsValue("admin")) {
            throw new IllegalArgumentException("ADMIN_USERS needs at least one admin");
        }
        return out;
    }

    /**
     * Cross-site request guard for state-changing requests: browsers attach the IAP session cookie to any request,
     * so a write must carry the custom header {@code X-RSFA-Admin: 1} (which a cross-site form cannot set without a
     * CORS preflight this server never grants) and, when the browser sends it, {@code Sec-Fetch-Site: same-origin}.
     */
    public static boolean sameOriginWrite(HttpExchange ex) {
        String m = ex.getRequestMethod();
        if (m.equals("GET") || m.equals("HEAD")) {
            return true;
        }
        String site = ex.getRequestHeaders().getFirst("Sec-Fetch-Site");
        return "1".equals(ex.getRequestHeaders().getFirst("X-RSFA-Admin")) && (site == null || site.equals("same-origin"));
    }

    static String logId(String email) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(("rsfa-admin:" + email).getBytes(StandardCharsets.UTF_8));
            return "u-" + HexFormat.of().formatHex(d, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
