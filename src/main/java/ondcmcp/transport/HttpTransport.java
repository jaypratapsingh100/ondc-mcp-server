package ondcmcp.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import ondcmcp.Json;
import ondcmcp.protocol.McpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.concurrent.Executors;

/**
 * MCP Streamable HTTP transport, stateless: POST /mcp with one JSON-RPC message, JSON back;
 * notifications get 202; GET is 405 (no server-initiated stream); DELETE is 204.
 * Optional bearer token via {@code ONDC_MCP_BEARER}: when set, every request must carry it.
 */
public final class HttpTransport {

    private final McpServer server;
    private final String bearer;

    public HttpTransport(McpServer server, String bearer) { this.server = server; this.bearer = bearer == null || bearer.isBlank() ? null : bearer.trim(); }

    public HttpServer start(String host, int port) throws IOException {
        HttpServer http = HttpServer.create(new InetSocketAddress(host, port), 0);
        http.createContext("/mcp", this::handle);
        http.createContext("/healthz", ex -> respond(ex, 200, "{\"status\":\"ok\",\"server\":\"" + McpServer.NAME + "\"}"));
        http.setExecutor(Executors.newFixedThreadPool(4));
        http.start();
        return http;
    }

    /**
     * DNS-rebinding guard (MCP transport guidance): a browser page on another site can reach a
     * server bound to localhost, but it always sends an Origin header. Allow requests with no
     * Origin (CLI/desktop clients) or a localhost Origin; refuse everything else.
     */
    static boolean originAllowed(String origin) {
        if (origin == null || origin.isBlank()) return true;
        String o = origin.trim().toLowerCase(java.util.Locale.ROOT);
        return o.matches("https?://(localhost|127\\.0\\.0\\.1|\\[::1\\])(:\\d+)?");
    }

    void handle(HttpExchange ex) throws IOException {
        try {
            if (!originAllowed(ex.getRequestHeaders().getFirst("Origin"))) { respond(ex, 403, "{\"error\":\"origin not allowed\"}"); return; }
            if (bearer != null) {
                String auth = ex.getRequestHeaders().getFirst("Authorization");
                boolean ok = auth != null && auth.startsWith("Bearer ") && MessageDigest.isEqual(auth.substring(7).trim().getBytes(StandardCharsets.UTF_8), bearer.getBytes(StandardCharsets.UTF_8));
                if (!ok) { respond(ex, 401, "{\"error\":\"unauthorized\"}"); return; }
            }
            switch (ex.getRequestMethod()) {
                case "POST" -> {
                    JsonNode body;
                    try (InputStream in = ex.getRequestBody()) { body = Json.read(new String(in.readAllBytes(), StandardCharsets.UTF_8)); }
                    catch (Exception e) { respond(ex, 400, Json.write(McpServer.error(null, McpServer.PARSE_ERROR, "Parse error: " + e.getMessage()))); return; }
                    if (body.isArray()) {
                        ArrayNode out = Json.arr();
                        for (JsonNode m : body) server.handle(m).ifPresent(out::add);
                        if (out.isEmpty()) respond(ex, 202, ""); else respond(ex, 200, Json.write(out));
                        return;
                    }
                    Optional<ObjectNode> r = server.handle(body);
                    if (r.isEmpty()) respond(ex, 202, ""); else respond(ex, 200, Json.write(r.get()));
                }
                case "GET" -> respond(ex, 405, "{\"error\":\"stateless server: POST JSON-RPC to /mcp\"}");
                case "DELETE" -> respond(ex, 204, "");
                default -> respond(ex, 405, "{\"error\":\"method not allowed\"}");
            }
        } catch (Exception e) {
            respond(ex, 500, Json.write(McpServer.error(null, McpServer.INTERNAL_ERROR, e.toString())));
        }
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        if (bytes.length == 0) { ex.sendResponseHeaders(status, -1); ex.close(); return; }
        ex.sendResponseHeaders(status, bytes.length);
        try (var out = ex.getResponseBody()) { out.write(bytes); }
    }
}
