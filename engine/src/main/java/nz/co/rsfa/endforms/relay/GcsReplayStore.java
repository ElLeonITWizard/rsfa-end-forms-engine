package nz.co.rsfa.endforms.relay;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Durable replay store shared by every relay instance: one Cloud Storage object per accepted signature, created with
 * the precondition {@code ifGenerationMatch=0}. Cloud Storage makes that create atomic, so exactly one instance wins
 * (200) and every other claim of the same key gets 412. A bucket lifecycle rule deletes the objects after a day.
 * The object holds only the expiry second; its name is the signature's SHA-256. No new Google API is needed (Cloud
 * Storage is already enabled for Cloud Build). Any other answer, or no answer, fails closed.
 */
public final class GcsReplayStore implements ReplayStore {

    /** OAuth access token (not an ID token) with a storage scope, e.g. from the metadata server. */
    public interface AccessTokenSource {
        String token() throws IOException;
    }

    private static final Pattern BUCKET = Pattern.compile("[a-z0-9][a-z0-9._-]{1,61}[a-z0-9]");
    private static final Pattern KEY = Pattern.compile("[0-9a-f]{64}");
    private final URI base;
    private final String bucket;
    private final AccessTokenSource tokens;
    private final HttpClient http;

    /** @param base {@code https://storage.googleapis.com}; a loopback http URI only in tests */
    public GcsReplayStore(URI base, String bucket, AccessTokenSource tokens) {
        boolean loopback = "http".equals(base.getScheme())
                && ("127.0.0.1".equals(base.getHost()) || "localhost".equals(base.getHost()));
        if (!"https".equals(base.getScheme()) && !loopback) {
            throw new IllegalArgumentException("replay store must use https");
        }
        if (bucket == null || !BUCKET.matcher(bucket).matches()) {
            throw new IllegalArgumentException("RELAY_REPLAY_BUCKET is not a valid bucket name");
        }
        this.base = base;
        this.bucket = bucket;
        this.tokens = tokens;
        this.http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override
    public boolean claim(String key, long expiresAt, long now) throws IOException {
        if (!KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("replay key must be a SHA-256 hex digest");
        }
        URI uri = base.resolve("/upload/storage/v1/b/" + bucket + "/o?uploadType=media&ifGenerationMatch=0&name=replay%2F"
                + key);
        HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + tokens.token()).header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(Long.toString(expiresAt), StandardCharsets.US_ASCII)).build();
        try {
            int status = http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status == 200) {
                return true;
            }
            if (status == 412) {
                return false;
            }
            throw new IOException("replay store answered " + status);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
    }

    @Override
    public String kind() {
        return "gcs";
    }
}
