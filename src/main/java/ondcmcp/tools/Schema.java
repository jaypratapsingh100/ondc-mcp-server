package ondcmcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ondcmcp.Json;

import java.util.List;

/** JSON-Schema one-liners and tolerant argument readers. */
public final class Schema {
    private Schema() {}

    public static ObjectNode str(String d) { return Json.obj().put("type", "string").put("description", d); }
    public static ObjectNode integer(String d) { return Json.obj().put("type", "integer").put("description", d); }
    public static ObjectNode number(String d) { return Json.obj().put("type", "number").put("description", d); }
    public static ObjectNode bool(String d) { return Json.obj().put("type", "boolean").put("description", d); }
    public static ObjectNode enumOf(String d, List<String> values) { ObjectNode n = str(d); var a = n.putArray("enum"); values.forEach(a::add); return n; }
    public static ObjectNode arrayOf(String d, ObjectNode items) { return Json.obj().put("type", "array").put("description", d).set("items", items) instanceof ObjectNode o ? o : null; }

    /** {@code object(props, required...)}; props is an ObjectNode of name → schema. */
    public static ObjectNode object(ObjectNode props, String... required) {
        ObjectNode s = Json.obj().put("type", "object");
        s.set("properties", props);
        if (required.length > 0) { var r = s.putArray("required"); for (String q : required) r.add(q); }
        s.put("additionalProperties", false);
        return s;
    }

    public static ObjectNode props(Object... kv) {
        ObjectNode p = Json.obj();
        for (int i = 0; i < kv.length; i += 2) p.set(String.valueOf(kv[i]), (JsonNode) kv[i + 1]);
        return p;
    }

    public static String s(JsonNode a, String k) { JsonNode v = a == null ? null : a.get(k); if (v == null || v.isNull()) return null; String t = v.isTextual() ? v.asText() : v.toString(); return t.isBlank() ? null : t.trim(); }
    public static String s(JsonNode a, String k, String d) { String v = s(a, k); return v == null ? d : v; }
    public static String req(JsonNode a, String k) throws Tool.ToolException { String v = s(a, k); if (v == null) throw new Tool.ToolException("'" + k + "' is required"); return v; }
    public static int integer(JsonNode a, String k, int d) { JsonNode v = a == null ? null : a.get(k); if (v == null || v.isNull()) return d; return v.isNumber() ? v.asInt() : Integer.parseInt(v.asText().trim()); }
    public static boolean flag(JsonNode a, String k) { JsonNode v = a == null ? null : a.get(k); return v != null && (v.isBoolean() ? v.asBoolean() : "true".equalsIgnoreCase(v.asText())); }
}
