package nz.co.rsfa.endforms.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Verifies the identity Identity-Aware Proxy signs into every request it forwards (header
 * {@code x-goog-iap-jwt-assertion}; D-041): ES256 signature with a current IAP key, issuer
 * {@code https://cloud.google.com/iap}, audience {@code /projects/<number>/locations/<region>/services/<service>} for a
 * Cloud Run service, and expiry. The unsigned {@code X-Goog-Authenticated-User-Email} header is never trusted.
 * Failures carry a code only; the token is never logged.
 */
public final class IapVerifier {

    public static final String HEADER = "x-goog-iap-jwt-assertion";
    public static final String ISSUER = "https://cloud.google.com/iap";
    public static final URI GOOGLE_KEYS = URI.create("https://www.gstatic.com/iap/verify/public_key-jwk");
    private static final long SKEW_SECONDS = 30;
    private static final java.util.regex.Pattern AUDIENCE =
            java.util.regex.Pattern.compile("/projects/\\d{4,20}/locations/[a-z0-9-]{3,40}/services/[a-z0-9-]{1,63}");

    public record Identity(String email, String subject) {
    }

    public static final class Invalid extends Exception {
        public final String code;

        Invalid(String code) {
            super(code);
            this.code = code;
        }
    }

    /** Public keys by key id. */
    public interface KeySource {
        Optional<PublicKey> key(String kid) throws IOException;
    }

    private final String audience;
    private final KeySource keys;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();

    public IapVerifier(String audience, KeySource keys, Clock clock) {
        if (audience == null || !AUDIENCE.matcher(audience).matches()) {
            throw new IllegalArgumentException("IAP_AUDIENCE must be /projects/<number>/locations/<region>/services/<service>");
        }
        this.audience = audience;
        this.keys = keys;
        this.clock = clock;
    }

    public Identity verify(String jwt) throws Invalid {
        if (jwt == null || jwt.isBlank()) {
            throw new Invalid("IAP_MISSING");
        }
        String[] parts = jwt.trim().split("\\.", -1);
        if (parts.length != 3 || jwt.length() > 8192) {
            throw new Invalid("IAP_MALFORMED");
        }
        JsonNode header;
        JsonNode claims;
        byte[] sig;
        try {
            header = json.readTree(Base64.getUrlDecoder().decode(parts[0]));
            claims = json.readTree(Base64.getUrlDecoder().decode(parts[1]));
            sig = Base64.getUrlDecoder().decode(parts[2]);
        } catch (IOException | IllegalArgumentException e) {
            throw new Invalid("IAP_MALFORMED");
        }
        if (header == null || claims == null || !"ES256".equals(header.path("alg").asText()) || sig.length != 64) {
            throw new Invalid("IAP_BAD_ALGORITHM");
        }
        PublicKey key;
        try {
            key = keys.key(header.path("kid").asText("")).orElseThrow(() -> new Invalid("IAP_UNKNOWN_KEY"));
        } catch (IOException e) {
            throw new Invalid("IAP_KEYS_UNAVAILABLE");
        }
        try {
            Signature v = Signature.getInstance("SHA256withECDSAinP1363Format");
            v.initVerify(key);
            v.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!v.verify(sig)) {
                throw new Invalid("IAP_BAD_SIGNATURE");
            }
        } catch (GeneralSecurityException e) {
            throw new Invalid("IAP_BAD_SIGNATURE");
        }
        if (!ISSUER.equals(claims.path("iss").asText())) {
            throw new Invalid("IAP_BAD_ISSUER");
        }
        if (!audience.equals(claims.path("aud").asText())) {
            throw new Invalid("IAP_BAD_AUDIENCE");
        }
        long now = clock.instant().getEpochSecond();
        if (!claims.path("exp").canConvertToLong() || claims.path("exp").asLong() + SKEW_SECONDS < now) {
            throw new Invalid("IAP_EXPIRED");
        }
        if (!claims.path("iat").canConvertToLong() || claims.path("iat").asLong() - SKEW_SECONDS > now) {
            throw new Invalid("IAP_NOT_YET_VALID");
        }
        String email = claims.path("email").asText("").trim().toLowerCase(Locale.ROOT);
        String sub = claims.path("sub").asText("");
        if (!email.matches("[a-z0-9._%+'-]{1,64}@[a-z0-9-]+(\\.[a-z0-9-]+)+") || email.length() > 254 || sub.isEmpty()) {
            throw new Invalid("IAP_NO_EMAIL");
        }
        return new Identity(email, sub);
    }

    /**
     * IAP's JWK set over HTTPS, cached for an hour and refetched (at most once a minute) when a token names an unknown
     * key, so key rotation needs no restart. Fails closed when the keys cannot be fetched.
     */
    public static final class HttpKeys implements KeySource {
        private final URI uri;
        private final Clock clock;
        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        private Map<String, PublicKey> cached = Map.of();
        private long fetchedAt = Long.MIN_VALUE / 2;

        public HttpKeys(URI uri, Clock clock) {
            boolean loopback = "http".equals(uri.getScheme())
                    && ("127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost()));
            if (!"https".equals(uri.getScheme()) && !loopback) {
                throw new IllegalArgumentException("IAP keys must be fetched over https");
            }
            this.uri = uri;
            this.clock = clock;
        }

        @Override
        public synchronized Optional<PublicKey> key(String kid) throws IOException {
            long now = clock.instant().getEpochSecond();
            boolean stale = now - fetchedAt > 3600;
            boolean unknown = !cached.containsKey(kid) && now - fetchedAt > 60;
            if (stale || unknown) {
                try {
                    HttpResponse<String> r = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build(),
                            HttpResponse.BodyHandlers.ofString());
                    if (r.statusCode() != 200) {
                        throw new IOException("IAP keys answered " + r.statusCode());
                    }
                    cached = parseJwks(r.body());
                    fetchedAt = now;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted");
                } catch (IOException e) {
                    if (stale) {
                        throw e; // never verify against keys older than the cache period
                    }
                }
            }
            return Optional.ofNullable(cached.get(kid));
        }
    }

    /** Parses EC P-256 keys from a JWK set ({@code {"keys":[{"kty":"EC","crv":"P-256","kid","x","y"}]}}). */
    public static Map<String, PublicKey> parseJwks(String body) throws IOException {
        Map<String, PublicKey> out = new HashMap<>();
        JsonNode n = new ObjectMapper().readTree(body);
        for (JsonNode k : n.path("keys")) {
            if (!"EC".equals(k.path("kty").asText()) || !"P-256".equals(k.path("crv").asText())) {
                continue;
            }
            try {
                out.put(k.path("kid").asText(), ecKey(Base64.getUrlDecoder().decode(k.path("x").asText()),
                        Base64.getUrlDecoder().decode(k.path("y").asText())));
            } catch (GeneralSecurityException | IllegalArgumentException e) {
                throw new IOException("invalid IAP key");
            }
        }
        if (out.isEmpty()) {
            throw new IOException("no usable IAP keys");
        }
        return out;
    }

    static PublicKey ecKey(byte[] x, byte[] y) throws GeneralSecurityException {
        AlgorithmParameters p = AlgorithmParameters.getInstance("EC");
        p.init(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec spec = p.getParameterSpec(ECParameterSpec.class);
        return KeyFactory.getInstance("EC").generatePublic(
                new ECPublicKeySpec(new ECPoint(new BigInteger(1, x), new BigInteger(1, y)), spec));
    }
}
