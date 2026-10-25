package ondcmcp;

import com.fasterxml.jackson.databind.JsonNode;
import ondcmcp.bap.MockBapClient;
import ondcmcp.protocol.McpServer;
import ondcmcp.transport.HttpTransport;
import ondcmcp.transport.StdioTransport;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class TransportTest {

    private final McpServer server = new McpServer(Main.registry(new MockBapClient(true)), Main.INSTRUCTIONS);

    @Test
    void stdioIsOneMessagePerLineAndSkipsNotifications() throws Exception {
        String in = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}\n"
                + "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n"
                + "not json\n"
                + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new StdioTransport(server).run(new ByteArrayInputStream(in.getBytes(StandardCharsets.UTF_8)), out);
        String[] lines = out.toString(StandardCharsets.UTF_8).trim().split("\n");
        assertEquals(3, lines.length);
        assertEquals(1, Json.read(lines[0]).get("id").asInt());
        assertEquals(McpServer.PARSE_ERROR, Json.read(lines[1]).at("/error/code").asInt());
        assertEquals(2, Json.read(lines[2]).get("id").asInt());
    }

    @Test
    void httpTransportRoundTripWithBearer() throws Exception {
        var http = new HttpTransport(server, "s3cret").start("127.0.0.1", 0);
        try {
            int port = http.getAddress().getPort();
            HttpClient c = HttpClient.newHttpClient();
            HttpResponse<String> unauth = c.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp")).POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(401, unauth.statusCode());
            HttpResponse<String> ok = c.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp")).header("Authorization", "Bearer s3cret").POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, ok.statusCode());
            JsonNode body = Json.read(ok.body());
            assertTrue(body.at("/result/tools").size() > 20);
            HttpResponse<String> note = c.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp")).header("Authorization", "Bearer s3cret").POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(202, note.statusCode());
            HttpResponse<String> get = c.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp")).header("Authorization", "Bearer s3cret").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(405, get.statusCode());
        } finally { http.stop(0); }
    }
}
