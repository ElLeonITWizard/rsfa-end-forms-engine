package nz.co.rsfa.endforms.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import nz.co.rsfa.endforms.relay.GcsReplayStore;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Cloud Storage JSON API client for the administration bucket (D-041). Creates use {@code ifGenerationMatch=0}, so a
 * second writer of the same name gets 412 and nothing is overwritten; the service account needs only
 * {@code roles/storage.objectCreator} and {@code roles/storage.objectViewer} on this one bucket. Errors carry the HTTP
 * status only (never object contents).
 */
public final class GcsObjectStore implements ObjectStore {

    private static final Pattern BUCKET = Pattern.compile("[a-z0-9][a-z0-9._-]{1,61}[a-z0-9]");
    private final URI base;
    private final String bucket;
    private final GcsReplayStore.AccessTokenSource tokens;
    private final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY)
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json = new ObjectMapper();

    /** @param base {@code https://storage.googleapis.com}; a loopback http URI only in tests and local emulation */
    public GcsObjectStore(URI base, String bucket, GcsReplayStore.AccessTokenSource tokens) {
        boolean loopback = "http".equals(base.getScheme())
                && ("127.0.0.1".equals(base.getHost()) || "localhost".equals(base.getHost()));
        if (!"https".equals(base.getScheme()) && !loopback) {
            throw new IllegalArgumentException("the admin store must use https");
        }
        if (bucket == null || !BUCKET.matcher(bucket).matches()) {
            throw new IllegalArgumentException("ADMIN_BUCKET is not a valid bucket name");
        }
        this.base = base;
        this.bucket = bucket;
        this.tokens = tokens;
    }

    @Override
    public Optional<byte[]> get(String name) throws IOException {
        HttpResponse<byte[]> r = send(HttpRequest.newBuilder(uri("/storage/v1/b/" + bucket + "/o/" + enc(ObjectStore.check(name))
                + "?alt=media")).GET(), HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() == 404) {
            return Optional.empty();
        }
        if (r.statusCode() != 200) {
            throw new IOException("storage read answered " + r.statusCode());
        }
        return Optional.of(r.body());
    }

    @Override
    public boolean createIfAbsent(String name, byte[] bytes, String contentType) throws IOException {
        HttpResponse<Void> r = send(HttpRequest.newBuilder(uri("/upload/storage/v1/b/" + bucket + "/o?uploadType=media"
                        + "&ifGenerationMatch=0&name=" + enc(ObjectStore.check(name))))
                .header("Content-Type", contentType).POST(HttpRequest.BodyPublishers.ofByteArray(bytes)),
                HttpResponse.BodyHandlers.discarding());
        if (r.statusCode() == 200) {
            return true;
        }
        if (r.statusCode() == 412) {
            return false;
        }
        throw new IOException("storage write answered " + r.statusCode());
    }

    @Override
    public List<String> list(String prefix) throws IOException {
        List<String> out = new ArrayList<>();
        String page = null;
        do {
            String q = "/storage/v1/b/" + bucket + "/o?prefix=" + enc(prefix) + "&fields=" + enc("items(name),nextPageToken")
                    + (page == null ? "" : "&pageToken=" + enc(page));
            HttpResponse<String> r = send(HttpRequest.newBuilder(uri(q)).GET(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                throw new IOException("storage list answered " + r.statusCode());
            }
            JsonNode n = json.readTree(r.body());
            n.path("items").forEach(i -> out.add(i.path("name").asText()));
            page = n.path("nextPageToken").asText(null);
        } while (page != null && !page.isEmpty());
        out.sort(null);
        return out;
    }

    @Override
    public String kind() {
        return "gcs";
    }

    private <T> HttpResponse<T> send(HttpRequest.Builder b, HttpResponse.BodyHandler<T> handler) throws IOException {
        try {
            return http.send(b.timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + tokens.token()).build(), handler);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
    }

    private URI uri(String pathAndQuery) {
        return URI.create(base.toString().replaceAll("/$", "") + pathAndQuery);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
