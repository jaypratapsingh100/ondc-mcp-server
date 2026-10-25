package ondcmcp;

import com.fasterxml.jackson.databind.node.ObjectNode;
import ondcmcp.bap.MockBapClient;
import ondcmcp.protocol.McpServer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class McpServerTest {

    private final McpServer server = new McpServer(Main.registry(new MockBapClient(true)), Main.INSTRUCTIONS);

    @Test
    void initializeAndList() throws Exception {
        ObjectNode init = server.handle(Json.read("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\"}}")).orElseThrow();
        assertEquals("2025-06-18", init.at("/result/protocolVersion").asText());
        assertEquals(McpServer.NAME, init.at("/result/serverInfo/name").asText());
        assertTrue(server.handle(Json.read("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")).isEmpty());
        ObjectNode list = server.handle(Json.read("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}")).orElseThrow();
        assertTrue(list.at("/result/tools").size() >= 25, "expected the full tool set, got " + list.at("/result/tools").size());
        boolean sawConfirm = false;
        for (var t : list.at("/result/tools")) if (t.get("name").asText().equals("ondc_transit_confirm")) { sawConfirm = true; assertTrue(t.at("/annotations/destructiveHint").asBoolean()); }
        assertTrue(sawConfirm);
    }

    @Test
    void toolErrorsAreResultsAndProtocolErrorsAreErrors() throws Exception {
        ObjectNode r = server.handle(Json.read("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"ondc_transit_results\",\"arguments\":{\"transactionId\":\"nope\",\"waitSeconds\":0}}}")).orElseThrow();
        assertTrue(r.at("/result/isError").asBoolean());
        assertTrue(r.at("/result/content/0/text").asText().contains("unknown transaction"));
        ObjectNode e = server.handle(Json.read("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"nope\"}}")).orElseThrow();
        assertEquals(McpServer.INVALID_PARAMS, e.at("/error/code").asInt());
        ObjectNode m = server.handle(Json.read("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"x/y\"}")).orElseThrow();
        assertEquals(McpServer.METHOD_NOT_FOUND, m.at("/error/code").asInt());
    }

    @Test
    void missingRequiredArgumentIsAToolError() throws Exception {
        ObjectNode r = server.handle(Json.read("{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\",\"params\":{\"name\":\"ondc_transit_search\",\"arguments\":{\"startGps\":\"1,1\"}}}")).orElseThrow();
        assertTrue(r.at("/result/isError").asBoolean());
        assertTrue(r.at("/result/content/0/text").asText().contains("'endGps' is required"));
    }
}
