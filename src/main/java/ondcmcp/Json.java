package ondcmcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/** One ObjectMapper for the process plus the JSON helpers tools share. */
public final class Json {
    private Json() {}

    public static final ObjectMapper MAPPER = new ObjectMapper().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    public static ObjectNode obj() { return MAPPER.createObjectNode(); }
    public static ArrayNode arr() { return MAPPER.createArrayNode(); }

    public static String write(Object o) {
        try { return MAPPER.writeValueAsString(o); } catch (Exception e) { return String.valueOf(o); }
    }

    public static JsonNode read(String s) throws java.io.IOException { return MAPPER.readTree(s); }

    /** Model-friendly trimming of a seller catalogue: no 200+ char strings, no 25+ item arrays, no decorative keys. */
    public static JsonNode compact(JsonNode n) { return compact(n, 0); }

    private static final Set<String> DROP = Set.of("images", "image", "tags", "raw", "rawPayload", "descriptor_long", "long_desc", "media", "icon", "logo");

    private static JsonNode compact(JsonNode n, int depth) {
        if (n == null || n.isNull()) return n;
        if (depth > 12) return MAPPER.getNodeFactory().textNode("…");
        if (n.isTextual()) { String t = n.asText(); return t.length() > 200 ? MAPPER.getNodeFactory().textNode(t.substring(0, 197) + "…") : n; }
        if (n.isArray()) {
            ArrayNode a = arr();
            int i = 0;
            for (JsonNode c : n) { if (i++ >= 25) { a.add("…" + (n.size() - 25) + " more"); break; } a.add(compact(c, depth + 1)); }
            return a;
        }
        if (n.isObject()) {
            ObjectNode o = obj();
            for (Iterator<Map.Entry<String, JsonNode>> it = n.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                if (DROP.contains(e.getKey())) continue;
                o.set(e.getKey(), compact(e.getValue(), depth + 1));
            }
            return o;
        }
        return n;
    }
}
