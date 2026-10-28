package nz.co.rsfa.endforms.relay;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Remembers which signed requests were already accepted, so a captured request cannot be replayed inside the
 * timestamp window. Keys are SHA-256 hex digests of the signature: no payload, value or secret is ever stored.
 * Implementations fail closed: when they cannot answer, they throw and the relay refuses the request (503).
 */
public interface ReplayStore {

    /**
     * Atomically records {@code key} until {@code expiresAtEpochSecond}.
     *
     * @return true if this is the first claim of the key (request accepted), false if it was already claimed (replay)
     * @throws IOException when the store cannot be reached or answers unexpectedly (the request is refused)
     */
    boolean claim(String key, long expiresAtEpochSecond, long nowEpochSecond) throws IOException;

    /** Short name for logs and /health: {@code memory} or {@code gcs}. */
    String kind();

    /**
     * Per-instance store. Correct only while a single relay instance runs (Cloud Run {@code --max-instances=1}): a
     * replay that reaches another instance is not detected. Fails closed when full instead of evicting fresh entries.
     */
    final class Memory implements ReplayStore {
        private final int capacity;
        private final Map<String, Long> seen = new LinkedHashMap<>();

        public Memory(int capacity) {
            this.capacity = capacity;
        }

        @Override
        public synchronized boolean claim(String key, long expiresAt, long now) throws IOException {
            seen.values().removeIf(exp -> exp < now);
            if (seen.containsKey(key)) {
                return false;
            }
            if (seen.size() >= capacity) {
                throw new IOException("replay store full");
            }
            seen.put(key, expiresAt);
            return true;
        }

        @Override
        public String kind() {
            return "memory";
        }
    }
}
