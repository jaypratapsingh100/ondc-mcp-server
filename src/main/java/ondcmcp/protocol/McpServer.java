package ondcmcp.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ondcmcp.Json;
import ondcmcp.tools.Tool;
import ondcmcp.tools.ToolRegistry;

import java.util.List;
import java.util.Optional;

/**
 * JSON-RPC 2.0 dispatch for a tools-only MCP server: initialize, notifications/initialized,
 * ping, tools/list, tools/call. Transport-agnostic — stdio and Streamable HTTP both feed
 * messages through {@link #handle(JsonNode)}.
 */
public final class McpServer {

    public static final String NAME = "ondc-mcp-server";
    public static final String VERSION = "0.1.0";
    public static final List<String> PROTOCOLS = List.of("2025-06-18", "2025-03-26", "2024-11-05");
    public static final int PARSE_ERROR = -32700, INVALID_REQUEST = -32600, METHOD_NOT_FOUND = -32601, INVALID_PARAMS = -32602, INTERNAL_ERROR = -32603;

    private final ToolRegistry tools;
    private final String instructions;

    public McpServer(ToolRegistry tools, String instructions) { this.tools = tools; this.instructions = instructions; }

    /** Empty for notifications; otherwise a response (result or error). Never throws. */
    public Optional<ObjectNode> handle(JsonNode msg) {
        if (msg == null || !msg.isObject()) return Optional.of(error(null, INVALID_REQUEST, "Expected a JSON-RPC request object"));
        JsonNode id = msg.get("id");
        boolean notification = id == null || id.isNull();
        String method = msg.path("method").asText(null);
        if (!"2.0".equals(msg.path("jsonrpc").asText())) return notification ? Optional.empty() : Optional.of(error(id, INVALID_REQUEST, "jsonrpc must be \"2.0\""));
        if (method == null) return notification ? Optional.empty() : Optional.of(error(id, INVALID_REQUEST, "method is required"));
        if (notification) return Optional.empty();
        JsonNode params = msg.path("params");
        try {
            return Optional.of(switch (method) {
                case "initialize" -> result(id, initialize(params));
                case "ping" -> result(id, Json.obj());
                case "tools/list" -> result(id, toolsList());
                case "tools/call" -> result(id, toolsCall(params));
                case "resources/list" -> result(id, Json.obj().set("resources", Json.arr()));
                case "prompts/list" -> result(id, Json.obj().set("prompts", Json.arr()));
                default -> error(id, METHOD_NOT_FOUND, "Method not found: " + method);
            });
        } catch (IllegalArgumentException e) {
            return Optional.of(error(id, INVALID_PARAMS, e.getMessage()));
        } catch (Exception e) {
            return Optional.of(error(id, INTERNAL_ERROR, "Internal error: " + e.getMessage()));
        }
    }

    private ObjectNode initialize(JsonNode params) {
        String asked = params.path("protocolVersion").asText(PROTOCOLS.get(0));
        ObjectNode r = Json.obj();
        r.put("protocolVersion", PROTOCOLS.contains(asked) ? asked : PROTOCOLS.get(0));
        r.putObject("capabilities").putObject("tools").put("listChanged", false);
        r.putObject("serverInfo").put("name", NAME).put("version", VERSION);
        r.put("instructions", instructions);
        return r;
    }

    private ObjectNode toolsList() {
        ObjectNode r = Json.obj();
        ArrayNode arr = r.putArray("tools");
        for (Tool t : tools.all()) {
            ObjectNode n = arr.addObject();
            n.put("name", t.name());
            n.put("description", t.description());
            n.set("inputSchema", t.inputSchema());
            ObjectNode ann = n.putObject("annotations");
            ann.put("readOnlyHint", !t.write()); ann.put("destructiveHint", t.write()); ann.put("idempotentHint", !t.write()); ann.put("openWorldHint", true);
        }
        return r;
    }

    private ObjectNode toolsCall(JsonNode params) {
        String name = params.path("name").asText(null);
        if (name == null || name.isBlank()) throw new IllegalArgumentException("params.name is required");
        Tool tool = tools.find(name).orElseThrow(() -> new IllegalArgumentException("Unknown tool: " + name));
        JsonNode args = params.path("arguments");
        ObjectNode r = Json.obj();
        ArrayNode content = r.putArray("content");
        try {
            JsonNode out = tool.call(args.isObject() ? args : Json.obj());
            String text = out == null ? "null" : out.isTextual() ? out.asText() : Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(out);
            if (text.length() > 60_000) text = text.substring(0, 60_000) + "\n…[truncated — ask for a compact view or a narrower query]";
            content.addObject().put("type", "text").put("text", text);
            r.put("isError", false);
        } catch (Tool.ToolException | IllegalArgumentException e) {
            content.addObject().put("type", "text").put("text", "Error: " + e.getMessage());
            r.put("isError", true);
        } catch (Exception e) {
            content.addObject().put("type", "text").put("text", "Tool failed: " + e);
            r.put("isError", true);
        }
        return r;
    }

    private static ObjectNode result(JsonNode id, JsonNode result) {
        ObjectNode r = Json.obj(); r.put("jsonrpc", "2.0"); r.set("id", id); r.set("result", result); return r;
    }

    public static ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode r = Json.obj(); r.put("jsonrpc", "2.0"); r.set("id", id == null ? Json.MAPPER.nullNode() : id);
        r.putObject("error").put("code", code).put("message", message == null ? "error" : message);
        return r;
    }
}
