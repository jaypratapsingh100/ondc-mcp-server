package ondcmcp.bap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ondcmcp.Json;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An in-memory BAP + seller so anyone can run the server without an ONDC subscription.
 * It reproduces the asynchronous shape of the real thing: a search returns only a
 * transaction id; results appear on the second read; select produces a quote on the next
 * read; confirm yields QR tokens on the status read after that. Fares and routes are
 * invented but plausible (a city metro, a city bus, an intercity bus operator, a monument).
 */
public final class MockBapClient implements BapClient {

    private static final class Tx {
        String domain, state = "SEARCH_SENT", vehicleCategory, cityCode, startGps, endGps, providerId, itemId, orderId;
        int ticketCount = 1, reads = 0; double fare; String name, phone, email; boolean paid; List<String> qr = new ArrayList<>();
        ObjectNode extra = Json.obj();
    }

    private final Map<String, Tx> txs = new ConcurrentHashMap<>();
    private final boolean eagerCallbacks;

    public MockBapClient() { this(false); }
    /** With {@code eagerCallbacks} results/quotes arrive on the first read (handy in tests). */
    public MockBapClient(boolean eagerCallbacks) { this.eagerCallbacks = eagerCallbacks; }

    @Override public String mode() { return "mock"; }

    private Tx tx(String id) throws Exception { Tx t = txs.get(id); if (t == null) throw new BapException("BAP returned HTTP 404: unknown transaction " + id, Json.obj()); return t; }
    private boolean arrived(Tx t) { t.reads++; return eagerCallbacks || t.reads > 1; }
    private static String id() { return UUID.randomUUID().toString(); }

    // ── transit ────────────────────────────────────────────────────────────
    @Override public JsonNode transitSearch(String startGps, String endGps, String vehicleCategory, String cityCode, String s, String e) {
        Tx t = new Tx(); t.domain = "transit"; t.vehicleCategory = vehicleCategory == null ? "METRO" : vehicleCategory; t.cityCode = cityCode == null ? "std:011" : cityCode; t.startGps = startGps; t.endGps = endGps;
        String id = id(); txs.put(id, t);
        return Json.obj().put("transactionId", id).put("message", "Transit search sent. Poll /results for available routes.");
    }

    @Override public JsonNode transitResults(String txId) throws Exception {
        Tx t = tx(txId);
        ObjectNode r = Json.obj().put("transactionId", txId).put("vehicleCategory", t.vehicleCategory);
        if ("SEARCH_SENT".equals(t.state)) { if (!arrived(t)) { r.put("state", "SEARCH_SENT"); r.putArray("providers"); return r; } t.state = "SEARCH_RESULTS"; }
        r.put("state", t.state);
        ArrayNode providers = r.putArray("providers");
        boolean metro = t.vehicleCategory.toUpperCase().contains("METRO");
        ObjectNode p = providers.addObject();
        p.put("bppId", metro ? "mock-metro.ondc.example" : "mock-citybus.ondc.example").put("bppUri", "https://mock.example/bpp").put("providerId", metro ? "METRO-OP" : "BUS-OP").put("name", metro ? "City Metro (mock)" : "City Bus (mock)");
        ArrayNode items = p.putArray("items");
        if (metro) {
            items.addObject().put("itemId", "SJT").put("name", "Single Journey Ticket").put("fare", 40).put("currency", "INR").put("startStop", "Central").put("endStop", "South Park").put("line", "Yellow Line");
            items.addObject().put("itemId", "RJT").put("name", "Return Journey Ticket").put("fare", 80).put("currency", "INR").put("startStop", "Central").put("endStop", "South Park").put("line", "Yellow Line");
        } else {
            items.addObject().put("itemId", "BUS-534-ADULT").put("name", "534 · Adult").put("fare", 25).put("currency", "INR").put("routeCode", "534").put("startStop", "North Depot").put("endStop", "South Depot");
            items.addObject().put("itemId", "BUS-534-AC").put("name", "534 AC · Adult").put("fare", 50).put("currency", "INR").put("routeCode", "534").put("startStop", "North Depot").put("endStop", "South Depot");
        }
        return r;
    }

    @Override public JsonNode transitSelect(String txId, String bppId, String bppUri, String providerId, String itemId, int ticketCount, String s, String e) throws Exception {
        Tx t = tx(txId);
        if (!"SEARCH_RESULTS".equals(t.state)) throw new BapException("BAP returned HTTP 400: run /search and read /results first (state " + t.state + ")", Json.obj());
        t.providerId = providerId; t.itemId = itemId; t.ticketCount = Math.max(1, ticketCount); t.state = "SELECT_SENT"; t.reads = 0;
        t.fare = (itemId != null && itemId.contains("RJT") ? 80 : itemId != null && itemId.contains("AC") ? 50 : itemId != null && itemId.startsWith("BUS-OP") ? 25 : 40) * t.ticketCount;
        return Json.obj().put("message", "Select sent. Poll /quote for fare.");
    }

    @Override public JsonNode transitQuote(String txId) throws Exception {
        Tx t = tx(txId);
        ObjectNode r = Json.obj().put("transactionId", txId);
        if ("SELECT_SENT".equals(t.state)) { if (!arrived(t)) { r.put("state", "SELECT_SENT"); return r; } t.state = "QUOTE_RECEIVED"; }
        r.put("state", t.state).put("fare", t.fare).put("currency", "INR").put("ticketCount", t.ticketCount).put("itemId", t.itemId);
        ArrayNode br = r.putArray("breakup");
        br.addObject().put("title", "BASE_FARE").put("amount", t.fare);
        br.addObject().put("title", "TAX").put("amount", 0);
        return r;
    }

    @Override public JsonNode transitInit(String txId, String name, String phone, String email) throws Exception {
        Tx t = tx(txId);
        if (!"QUOTE_RECEIVED".equals(t.state) && !"INIT_RECEIVED".equals(t.state)) throw new BapException("BAP returned HTTP 400: read /quote before /init (state " + t.state + ")", Json.obj());
        t.name = name; t.phone = phone; t.email = email; t.state = "INIT_RECEIVED";
        return Json.obj().put("message", "Init sent. Pay, then /confirm.");
    }

    @Override public JsonNode transitPayWallet(String txId) throws Exception {
        Tx t = tx(txId);
        if (!"INIT_RECEIVED".equals(t.state)) throw new BapException("BAP returned HTTP 409: Not ready for payment (state " + t.state + ")", Json.obj());
        t.paid = true;
        return Json.obj().put("paid", true).put("amount", t.fare).put("message", "Paid from wallet (mock).");
    }

    @Override public JsonNode transitConfirm(String txId, String paymentTransactionId) throws Exception {
        Tx t = tx(txId);
        if (!"INIT_RECEIVED".equals(t.state)) throw new BapException("BAP returned HTTP 400: /init first (state " + t.state + ")", Json.obj());
        if (!t.paid && paymentTransactionId == null) throw new BapException("BAP returned HTTP 402: payment required — pay from wallet or pass paymentTransactionId", Json.obj());
        t.state = "CONFIRM_SENT"; t.reads = 0; t.orderId = "MOCK-" + txId.substring(0, 8).toUpperCase();
        return Json.obj().put("message", "Confirm sent. Poll /status for QR tickets.");
    }

    @Override public JsonNode transitStatus(String txId) throws Exception {
        Tx t = tx(txId);
        ObjectNode r = Json.obj().put("transactionId", txId);
        if ("CONFIRM_SENT".equals(t.state)) {
            if (arrived(t)) {
                t.state = "CONFIRMED";
                for (int i = 0; i < t.ticketCount; i++) t.qr.add("MOCKQR-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase());
            }
        }
        r.put("state", t.state).put("orderId", t.orderId).put("orderState", "CONFIRMED".equals(t.state) ? "ACTIVE" : "CANCELLED".equals(t.state) ? "CANCELLED" : null);
        ArrayNode qr = r.putArray("qrTokens"); t.qr.forEach(qr::add);
        ArrayNode st = r.putArray("ticketStatuses"); for (int i = 0; i < t.qr.size(); i++) st.add("CANCELLED".equals(t.state) ? "CANCELLED" : "UNCLAIMED");
        r.put("ticketValidTo", LocalDateTime.now().plusHours(2).withNano(0).toString());
        r.put("fare", t.fare).put("itemId", t.itemId).put("ticketCount", t.ticketCount);
        return r;
    }

    @Override public JsonNode transitCancel(String txId, String reasonCode) throws Exception {
        Tx t = tx(txId);
        if (!"CONFIRMED".equals(t.state)) throw new BapException("BAP returned HTTP 400: only a CONFIRMED booking can be cancelled (state " + t.state + ")", Json.obj());
        t.state = "CANCELLED";
        return Json.obj().put("transactionId", txId).put("state", "CANCELLED").put("refundAmount", t.fare).put("cancellationFee", 0).put("message", "Cancel sent (mock): full refund.");
    }

    @Override public JsonNode transitMyTickets() {
        ArrayNode a = Json.arr();
        txs.forEach((id, t) -> { if ("transit".equals(t.domain) && !t.qr.isEmpty()) a.addObject().put("transactionId", id).put("state", t.state).put("orderId", t.orderId).put("fare", t.fare).put("tickets", t.qr.size()); });
        return a;
    }

    // ── intercity ──────────────────────────────────────────────────────────
    @Override public JsonNode intercityCities() {
        ObjectNode r = Json.obj(); ArrayNode c = r.putArray("cities");
        c.addObject().put("name", "Bengaluru").put("code", "std:080"); c.addObject().put("name", "Chennai").put("code", "std:044"); c.addObject().put("name", "Hyderabad").put("code", "std:040");
        return r;
    }
    @Override public JsonNode intercitySearch(JsonNode body) {
        Tx t = new Tx(); t.domain = "intercity"; t.extra = body.deepCopy(); String id = id(); txs.put(id, t);
        return Json.obj().put("transactionId", id).put("message", "Intercity search sent. Poll /results.");
    }
    @Override public JsonNode intercityResults(String txId) throws Exception {
        Tx t = tx(txId); ObjectNode r = Json.obj().put("transactionId", txId);
        if ("SEARCH_SENT".equals(t.state)) { if (!arrived(t)) { r.put("state", "SEARCH_SENT"); r.putArray("trips"); return r; } t.state = "SEARCH_RESULTS"; }
        r.put("state", t.state);
        ArrayNode trips = r.putArray("trips");
        ObjectNode a = trips.addObject();
        a.put("bppId", "mock-bus-operator.ondc.example").put("bppUri", "https://mock.example/bpp").put("providerId", "SAMPLE-TRAVELS").put("operatorName", "Sample Travels (mock)").put("itemId", "ST-101-SEATER").put("fulfillmentId", "TRIP-101")
         .put("departureTime", "2026-11-01T21:30:00+05:30").put("arrivalTime", "2026-11-02T04:00:00+05:30").put("busType", "AC Seater").put("fare", 899).put("seatsAvailable", 18)
         .put("boardingStopId", "BLR-MAJESTIC").put("boardingStopName", "Majestic").put("droppingStopId", "MAA-KOYAMBEDU").put("droppingStopName", "Koyambedu");
        ObjectNode b = trips.addObject();
        b.put("bppId", "mock-bus-operator.ondc.example").put("bppUri", "https://mock.example/bpp").put("providerId", "SAMPLE-TRAVELS").put("operatorName", "Sample Travels (mock)").put("itemId", "ST-102-SLEEPER").put("fulfillmentId", "TRIP-102")
         .put("departureTime", "2026-11-01T22:30:00+05:30").put("arrivalTime", "2026-11-02T05:00:00+05:30").put("busType", "AC Sleeper").put("fare", 1299).put("seatsAvailable", 6)
         .put("boardingStopId", "BLR-MAJESTIC").put("boardingStopName", "Majestic").put("droppingStopId", "MAA-KOYAMBEDU").put("droppingStopName", "Koyambedu");
        return r;
    }
    @Override public JsonNode intercitySelectStep1(String txId, JsonNode body) throws Exception {
        Tx t = tx(txId); t.state = "SELECT_SENT"; t.reads = 0; t.extra.set("select1", body.deepCopy()); t.itemId = body.path("itemId").asText(null);
        int seats = body.path("seats").isArray() ? Math.max(1, body.path("seats").size()) : 1; t.ticketCount = seats; t.fare = (t.itemId != null && t.itemId.contains("SLEEPER") ? 1299 : 899) * seats;
        return Json.obj().put("message", "select_1 sent. Poll /draft-quote for fare + seat map.");
    }
    @Override public JsonNode intercityDraftQuote(String txId) throws Exception {
        Tx t = tx(txId); ObjectNode r = Json.obj().put("transactionId", txId);
        if ("SELECT_SENT".equals(t.state)) { if (!arrived(t)) { r.put("state", "SELECT_SENT"); return r; } t.state = "DRAFT_QUOTE"; }
        r.put("state", t.state).put("fare", t.fare).put("currency", "INR");
        ArrayNode seats = r.putArray("seatMap"); for (String s : List.of("A1", "A2", "B1", "B2", "C1")) seats.addObject().put("id", s).put("label", s).put("available", true).put("fare", t.fare / t.ticketCount);
        return r;
    }
    @Override public JsonNode intercitySelectStep2(String txId) throws Exception { Tx t = tx(txId); t.state = "SELECT2_SENT"; t.reads = 0; return Json.obj().put("message", "select_2 sent. Poll /quote."); }
    @Override public JsonNode intercityQuote(String txId) throws Exception {
        Tx t = tx(txId); ObjectNode r = Json.obj().put("transactionId", txId);
        if ("SELECT2_SENT".equals(t.state)) { if (!arrived(t)) { r.put("state", "SELECT2_SENT"); return r; } t.state = "QUOTE_RECEIVED"; }
        r.put("state", t.state).put("fare", t.fare).put("currency", "INR");
        return r;
    }
    @Override public JsonNode intercityInit(String txId, JsonNode body) throws Exception { Tx t = tx(txId); t.state = "INIT_RECEIVED"; t.extra.set("init", body.deepCopy()); return Json.obj().put("message", "init sent. Wait for on_init, then pay and /confirm."); }
    @Override public JsonNode intercityStatus(String txId) throws Exception {
        Tx t = tx(txId); ObjectNode r = Json.obj().put("transactionId", txId).put("state", t.state).put("orderId", t.orderId).put("fare", t.fare);
        r.putArray("qrTokens"); r.put("note", "Mock intercity stops at INIT: payment is a card/UPI checkout in a real BAP, so confirm is not simulated here.");
        return r;
    }

    // ── entry pass ─────────────────────────────────────────────────────────
    @Override public JsonNode entryPassDestinations() {
        ObjectNode r = Json.obj(); ArrayNode d = r.putArray("destinations");
        d.addObject().put("city", "Delhi").put("cityCode", "std:011").put("count", 12); d.addObject().put("city", "Agra").put("cityCode", "std:0562").put("count", 3);
        return r;
    }
    @Override public JsonNode entryPassSearch(JsonNode body) {
        Tx t = new Tx(); t.domain = "entry-pass"; t.extra = body.deepCopy(); String id = id(); txs.put(id, t);
        return Json.obj().put("transactionId", id).put("message", "Entry-pass search sent. Poll /results.");
    }
    @Override public JsonNode entryPassResults(String txId) throws Exception {
        Tx t = tx(txId); ObjectNode r = Json.obj().put("transactionId", txId);
        if ("SEARCH_SENT".equals(t.state)) { if (!arrived(t)) { r.put("state", "SEARCH_SENT"); r.putArray("venues"); return r; } t.state = "SEARCH_RESULTS"; }
        r.put("state", t.state);
        ArrayNode venues = r.putArray("venues");
        ObjectNode v = venues.addObject();
        v.put("bppId", "mock-asi.ondc.example").put("bppUri", "https://mock.example/bpp").put("providerId", "ASI-DELHI").put("name", "Red Fort (mock)").put("fulfillmentId", "SLOT-RF-AM").put("slotStart", "2026-11-01T09:00:00+05:30").put("slotEnd", "2026-11-01T12:00:00+05:30");
        ArrayNode items = v.putArray("items");
        items.addObject().put("itemId", "red-fort-IND-ADULT").put("parentItemId", "red-fort").put("name", "Indian · Adult").put("fare", 35);
        items.addObject().put("itemId", "red-fort-IND-CHILD").put("parentItemId", "red-fort").put("name", "Indian · Child").put("fare", 0);
        items.addObject().put("itemId", "red-fort-FOR-ADULT").put("parentItemId", "red-fort").put("name", "Foreign national · Adult").put("fare", 550);
        return r;
    }
    @Override public JsonNode entryPassSelectStep1(String txId, JsonNode body) throws Exception {
        Tx t = tx(txId); t.state = "SELECT_SENT"; t.reads = 0; t.extra.set("select1", body.deepCopy());
        double fare = 0; int count = 0;
        for (JsonNode l : body.path("lines")) { int c = l.path("count").asInt(1); count += c; String id = l.path("itemId").asText(""); fare += c * (id.contains("FOR") ? 550 : id.contains("CHILD") ? 0 : 35); }
        t.fare = fare; t.ticketCount = Math.max(1, count);
        return Json.obj().put("message", "select_1 sent. Poll /draft-quote.");
    }
    @Override public JsonNode entryPassDraftQuote(String txId) throws Exception {
        Tx t = tx(txId); ObjectNode r = Json.obj().put("transactionId", txId);
        if ("SELECT_SENT".equals(t.state)) { if (!arrived(t)) { r.put("state", "SELECT_SENT"); return r; } t.state = "DRAFT_QUOTE"; }
        r.put("state", t.state).put("fare", t.fare).put("currency", "INR").put("passes", t.ticketCount);
        return r;
    }
    @Override public JsonNode entryPassSelectStep2(String txId) throws Exception { Tx t = tx(txId); t.state = "SELECT2_SENT"; t.reads = 0; return Json.obj().put("message", "select_2 sent. Poll /quote."); }
    @Override public JsonNode entryPassQuote(String txId) throws Exception {
        Tx t = tx(txId); ObjectNode r = Json.obj().put("transactionId", txId);
        if ("SELECT2_SENT".equals(t.state)) { if (!arrived(t)) { r.put("state", "SELECT2_SENT"); return r; } t.state = "QUOTE_RECEIVED"; }
        r.put("state", t.state).put("fare", t.fare).put("currency", "INR");
        return r;
    }
    @Override public JsonNode entryPassInit(String txId, JsonNode body) throws Exception { Tx t = tx(txId); t.state = "INIT_RECEIVED"; t.extra.set("init", body.deepCopy()); return Json.obj().put("message", "init sent. Pay, then /confirm."); }
    @Override public JsonNode entryPassStatus(String txId) throws Exception {
        Tx t = tx(txId); ObjectNode r = Json.obj().put("transactionId", txId).put("state", t.state).put("orderId", t.orderId).put("fare", t.fare);
        r.putArray("qrTokens"); r.put("note", "Mock entry pass stops at INIT: payment is a card/UPI checkout in a real BAP, so confirm is not simulated here.");
        return r;
    }
}
