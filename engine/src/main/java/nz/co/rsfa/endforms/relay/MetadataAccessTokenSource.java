package nz.co.rsfa.endforms.relay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;

/**
 * OAuth access token of the relay's own service account from the Cloud Run metadata server, used only for the replay
 * bucket. No key file. Cached until five minutes before expiry; never logged.
 */
public final class MetadataAccessTokenSource implements GcsReplayStore.AccessTokenSource {

    private static final URI METADATA =
            URI.create("http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token");
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY)
            .connectTimeout(Duration.ofSeconds(5)).build();
    private String cached;
    private long expiresAt;

    public MetadataAccessTokenSource(Clock clock) {
        this.clock = clock;
    }

    @Override
    public synchronized String token() throws IOException {
        long now = clock.instant().getEpochSecond();
        if (cached != null && now < expiresAt - 300) {
            return cached;
        }
        HttpRequest req = HttpRequest.newBuilder(METADATA).timeout(Duration.ofSeconds(5))
                .header("Metadata-Flavor", "Google").GET().build();
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                throw new IOException("metadata server returned " + res.statusCode());
            }
            JsonNode n = new ObjectMapper().readTree(res.body());
            String token = n.path("access_token").asText("");
            if (token.isBlank()) {
                throw new IOException("metadata server returned no access token");
            }
            cached = token;
            expiresAt = now + n.path("expires_in").asLong(600);
            return cached;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
    }
}
