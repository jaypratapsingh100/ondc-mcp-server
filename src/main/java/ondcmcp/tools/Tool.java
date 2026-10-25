package ondcmcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** One MCP tool. Implementations are small lambdas registered by the domain tool sets. */
public interface Tool {
    String name();
    String description();
    ObjectNode inputSchema();
    /** True for tools that create or change an order on the network (confirm, cancel). */
    default boolean write() { return false; }
    /** Return any JSON node; the server wraps it as text content. Throw ToolException for a tool-level error. */
    JsonNode call(JsonNode args) throws Exception;

    class ToolException extends Exception { public ToolException(String m) { super(m); } }
}
