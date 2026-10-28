package nz.co.rsfa.endforms.relay;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;

/**
 * Google-signed ID token for the relay's own service account, from the Cloud Run metadata server. No key file: the
 * platform signs it. Cached until five minutes before expiry. The token is never logged.
 */
public final class MetadataIdTokenSource implements RelayServer.IdTokenSource {

    private static final String METADATA =
            "http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/identity?audience=";
    private final String audience;
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY)
            .connectTimeout(Duration.ofSeconds(5)).build();
    private String cached;
    private long expiresAt;

    public MetadataIdTokenSource(String audience, Clock clock) {
        this.audience = audience;
        this.clock = clock;
    }

    @Override
    public synchronized String token() throws IOException {
        long now = clock.instant().getEpochSecond();
        if (cached != null && now < expiresAt - 300) {
            return cached;
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(METADATA + URLEncoder.encode(audience, StandardCharsets.UTF_8)))
                .timeout(Duration.ofSeconds(5)).header("Metadata-Flavor", "Google").GET().build();
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200 || res.body().isBlank()) {
                throw new IOException("metadata server returned " + res.statusCode());
            }
            cached = res.body().trim();
            expiresAt = expiry(cached, now);
            return cached;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
    }

    /** {@code exp} claim of the JWT; falls back to 30 minutes when unreadable. */
    static long expiry(String jwt, long now) {
        try {
            String[] parts = jwt.split("\\.");
            byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
            return new ObjectMapper().readTree(payload).path("exp").asLong(now + 1800);
        } catch (RuntimeException | IOException e) {
            return now + 1800;
        }
    }
}
