package ondcmcp;

import com.fasterxml.jackson.databind.JsonNode;
import ondcmcp.bap.MockBapClient;
import ondcmcp.protocol.McpServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Drives the whole metro flow through the MCP layer against the mock seller, including the async waits. */
class MockFlowTest {

    @BeforeAll static void fast() { System.setProperty("ondc.mcp.fastpoll", "true"); }

    private final McpServer server = new McpServer(Main.registry(new MockBapClient(false)), Main.INSTRUCTIONS);
    private int id = 0;

    private JsonNode call(String tool, String argsJson) throws Exception {
        var r = server.handle(Json.read("{\"jsonrpc\":\"2.0\",\"id\":" + (++id) + ",\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool + "\",\"arguments\":" + argsJson + "}}")).orElseThrow();
        assertFalse(r.at("/result/isError").asBoolean(), () -> tool + " failed: " + r.at("/result/content/0/text").asText());
        return Json.read(r.at("/result/content/0/text").asText());
    }

    @Test
    void metroEndToEnd() throws Exception {
        JsonNode s = call("ondc_transit_search", "{\"startGps\":\"28.6328,77.2197\",\"endGps\":\"28.5433,77.2066\",\"vehicleCategory\":\"METRO\"}");
        String tx = s.get("transactionId").asText();

        JsonNode results = call("ondc_transit_results", "{\"transactionId\":\"" + tx + "\",\"waitSeconds\":5}");
        assertEquals("SEARCH_RESULTS", results.get("state").asText(), "polling must wait for the on_search");
        JsonNode item = results.at("/providers/0/items/0");
        String providerId = results.at("/providers/0/providerId").asText();

        call("ondc_transit_select", "{\"transactionId\":\"" + tx + "\",\"providerId\":\"" + providerId + "\",\"itemId\":\"" + item.get("itemId").asText() + "\",\"ticketCount\":2}");
        JsonNode quote = call("ondc_transit_quote", "{\"transactionId\":\"" + tx + "\",\"waitSeconds\":5}");
        assertEquals("QUOTE_RECEIVED", quote.get("state").asText());
        assertEquals(80.0, quote.get("fare").asDouble(), 0.001);

        call("ondc_transit_init", "{\"transactionId\":\"" + tx + "\",\"name\":\"Asha\",\"phone\":\"9876543210\"}");
        JsonNode pay = call("ondc_transit_pay_wallet", "{\"transactionId\":\"" + tx + "\"}");
        assertTrue(pay.get("paid").asBoolean());
        call("ondc_transit_confirm", "{\"transactionId\":\"" + tx + "\"}");

        JsonNode status = call("ondc_transit_status", "{\"transactionId\":\"" + tx + "\",\"waitSeconds\":5}");
        assertEquals("CONFIRMED", status.get("state").asText());
        assertEquals(2, status.get("qrTokens").size());
        assertTrue(status.get("orderId").asText().startsWith("MOCK-"));

        JsonNode mine = call("ondc_transit_my_tickets", "{}");
        assertEquals(1, mine.size());

        JsonNode cancel = call("ondc_transit_cancel", "{\"transactionId\":\"" + tx + "\"}");
        assertEquals("CANCELLED", cancel.get("state").asText());
        assertEquals(80.0, cancel.get("refundAmount").asDouble(), 0.001);
    }

    @Test
    void confirmWithoutPaymentIsRefused() throws Exception {
        JsonNode s = call("ondc_transit_search", "{\"startGps\":\"1,1\",\"endGps\":\"2,2\",\"vehicleCategory\":\"BUS\"}");
        String tx = s.get("transactionId").asText();
        JsonNode results = call("ondc_transit_results", "{\"transactionId\":\"" + tx + "\",\"waitSeconds\":5}");
        call("ondc_transit_select", "{\"transactionId\":\"" + tx + "\",\"providerId\":\"BUS-OP\",\"itemId\":\"" + results.at("/providers/0/items/0/itemId").asText() + "\"}");
        call("ondc_transit_quote", "{\"transactionId\":\"" + tx + "\",\"waitSeconds\":5}");
        call("ondc_transit_init", "{\"transactionId\":\"" + tx + "\"}");
        var r = server.handle(Json.read("{\"jsonrpc\":\"2.0\",\"id\":99,\"method\":\"tools/call\",\"params\":{\"name\":\"ondc_transit_confirm\",\"arguments\":{\"transactionId\":\"" + tx + "\"}}}")).orElseThrow();
        assertTrue(r.at("/result/isError").asBoolean());
        assertTrue(r.at("/result/content/0/text").asText().contains("402"));
    }

    @Test
    void intercityAndEntryPassReachInit() throws Exception {
        JsonNode s = call("ondc_intercity_search", "{\"originName\":\"Bengaluru\",\"originCityCode\":\"std:080\",\"destinationName\":\"Chennai\",\"destinationCityCode\":\"std:044\",\"date\":\"2026-11-01\"}");
        String tx = s.get("transactionId").asText();
        JsonNode trips = call("ondc_intercity_results", "{\"transactionId\":\"" + tx + "\",\"waitSeconds\":5}");
        assertEquals(2, trips.get("trips").size());
        call("ondc_intercity_select_trip", "{\"transactionId\":\"" + tx + "\",\"trip\":" + trips.at("/trips/1") + ",\"seats\":[{\"id\":\"A1\",\"label\":\"A1\",\"fare\":1299}]}");
        JsonNode draft = call("ondc_intercity_draft_quote", "{\"transactionId\":\"" + tx + "\",\"waitSeconds\":5}");
        assertEquals("DRAFT_QUOTE", draft.get("state").asText());
        call("ondc_intercity_select_seats", "{\"transactionId\":\"" + tx + "\"}");
        JsonNode q = call("ondc_intercity_quote", "{\"transactionId\":\"" + tx + "\",\"waitSeconds\":5}");
        assertEquals(1299.0, q.get("fare").asDouble(), 0.001);
        call("ondc_intercity_init", "{\"transactionId\":\"" + tx + "\",\"billingName\":\"A\",\"billingPhone\":\"9\",\"passengers\":[{\"name\":\"A\",\"age\":30,\"gender\":\"FEMALE\",\"seatId\":\"A1\"}]}");
        assertEquals("INIT_RECEIVED", call("ondc_intercity_status", "{\"transactionId\":\"" + tx + "\",\"waitSeconds\":0}").get("state").asText());

        JsonNode e = call("ondc_entrypass_search", "{\"cityCode\":\"std:011\",\"venueName\":\"Red Fort\"}");
        String etx = e.get("transactionId").asText();
        JsonNode venues = call("ondc_entrypass_results", "{\"transactionId\":\"" + etx + "\",\"waitSeconds\":5}");
        assertEquals("SEARCH_RESULTS", venues.get("state").asText());
        call("ondc_entrypass_select", "{\"transactionId\":\"" + etx + "\",\"providerId\":\"ASI-DELHI\",\"fulfillmentId\":\"SLOT-RF-AM\",\"lines\":[{\"itemId\":\"red-fort-IND-ADULT\",\"parentItemId\":\"red-fort\",\"count\":2},{\"itemId\":\"red-fort-FOR-ADULT\",\"parentItemId\":\"red-fort\",\"count\":1}]}");
        JsonNode dq = call("ondc_entrypass_draft_quote", "{\"transactionId\":\"" + etx + "\",\"waitSeconds\":5}");
        assertEquals(620.0, dq.get("fare").asDouble(), 0.001);
        call("ondc_entrypass_select_confirm_lines", "{\"transactionId\":\"" + etx + "\"}");
        assertEquals("QUOTE_RECEIVED", call("ondc_entrypass_quote", "{\"transactionId\":\"" + etx + "\",\"waitSeconds\":5}").get("state").asText());
    }
}
