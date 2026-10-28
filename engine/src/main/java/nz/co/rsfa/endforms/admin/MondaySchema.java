package nz.co.rsfa.endforms.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The Masterboard column schema (IDs, titles, types, option labels; never item values), from a read-only
 * {@code get_board_info} export. The column ID is the stable reference a mapping stores; the title is the runtime key
 * Make sends today ({@code 8731317} module 8 keys by title), so drafts resolve the current title at compile time.
 */
public final class MondaySchema {

    /** @param duplicateTitle another column has the same title (its runtime key would be ambiguous) */
    public record Column(String id, String title, String type, List<String> labels, boolean duplicateTitle) {
    }

    private final String source;
    private final Map<String, Column> byId;

    private MondaySchema(String source, Map<String, Column> byId) {
        this.source = source;
        this.byId = byId;
    }

    public static MondaySchema empty() {
        return new MondaySchema("none", Map.of());
    }

    /** Reads {@code {"board":{"columns":[{id,title,type,settings:{labels:[{label}]}}]}}} (Monday connector export). */
    public static MondaySchema load(Path file) throws IOException {
        return parse(java.nio.file.Files.readAllBytes(file), file.getFileName().toString());
    }

    /** Same format from bytes (e.g. {@code config/monday-schema.json} in the admin bucket). */
    public static MondaySchema parse(byte[] bytes, String source) throws IOException {
        JsonNode root = new ObjectMapper().readTree(bytes);
        JsonNode cols = root.path("board").path("columns");
        if (!cols.isArray()) {
            cols = root.path("columns");
        }
        if (!cols.isArray()) {
            throw new IOException("no columns array in the schema file");
        }
        Map<String, Integer> titles = new HashMap<>();
        for (JsonNode c : cols) {
            titles.merge(c.path("title").asText(), 1, Integer::sum);
        }
        Map<String, Column> out = new LinkedHashMap<>();
        for (JsonNode c : cols) {
            List<String> labels = new ArrayList<>();
            JsonNode l = c.path("settings").path("labels");
            if (l.isArray()) {
                for (JsonNode x : l) {
                    if (!x.path("is_deactivated").asBoolean(false)) {
                        labels.add(x.isTextual() ? x.asText() : x.path("label").asText(x.path("name").asText()));
                    }
                }
            } else if (l.isObject()) { // status columns: {"0":"Working on it",...}
                l.fields().forEachRemaining(en -> labels.add(en.getValue().asText()));
            }
            String title = c.path("title").asText();
            out.put(c.path("id").asText(), new Column(c.path("id").asText(), title, c.path("type").asText(),
                    List.copyOf(labels), titles.get(title) > 1));
        }
        return new MondaySchema(source, Map.copyOf(out));
    }

    public String source() {
        return source;
    }

    public List<Column> columns() {
        return byId.values().stream().sorted(java.util.Comparator.comparing(Column::title)).toList();
    }

    public Optional<Column> column(String id) {
        return Optional.ofNullable(id == null ? null : byId.get(id));
    }

    public Optional<Column> byTitle(String title) {
        return byId.values().stream().filter(c -> c.title().equals(title)).findFirst();
    }
}
