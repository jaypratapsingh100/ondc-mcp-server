package ondcmcp.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ondcmcp.Json;
import ondcmcp.protocol.McpServer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * MCP stdio transport: one JSON-RPC message per line on stdin, one per line on stdout.
 * Logging goes to stderr only — stdout is the protocol channel.
 */
public final class StdioTransport {

    private final McpServer server;

    public StdioTransport(McpServer server) { this.server = server; }

    public void run(InputStream in, OutputStream out) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), true);
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) continue;
            JsonNode msg;
            try { msg = Json.read(line); }
            catch (Exception e) { writer.println(Json.write(McpServer.error(null, McpServer.PARSE_ERROR, "Parse error: " + e.getMessage()))); continue; }
            if (msg.isArray()) { for (JsonNode m : msg) server.handle(m).ifPresent(r -> writer.println(Json.write(r))); continue; }
            Optional<ObjectNode> r = server.handle(msg);
            r.ifPresent(resp -> writer.println(Json.write(resp)));
        }
    }
}
