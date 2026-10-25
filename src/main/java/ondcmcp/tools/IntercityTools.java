package ondcmcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ondcmcp.Json;
import ondcmcp.bap.BapClient;

import java.util.List;
import java.util.Set;

import static ondcmcp.tools.Schema.*;
import static ondcmcp.tools.TransitTools.tool;

/** Intercity buses (ONDC TRV12): the two-step select (trip, then seats) that the spec requires. */
public final class IntercityTools {
    private IntercityTools() {}

    public static List<Tool> of(BapClient bap) {
        return List.of(
            tool("ondc_intercity_cities", "Cities with intercity bus supply on the network (name + ONDC city code).", object(props()), false, a -> bap.intercityCities()),

            tool("ondc_intercity_search",
                "Search intercity bus trips between two cities on a date. Returns a transactionId; read trips with ondc_intercity_results.",
                object(props("originName", str("Origin city name"), "originCityCode", str("Origin ONDC city code, e.g. std:080"), "originGps", str("Optional 'lat,lng'"),
                        "destinationName", str("Destination city name"), "destinationCityCode", str("Destination ONDC city code"), "destinationGps", str("Optional 'lat,lng'"),
                        "date", str("Travel date YYYY-MM-DD")),
                        "originName", "destinationName", "date"),
                false,
                a -> {
                    ObjectNode b = Json.obj();
                    for (String k : List.of("originName", "originGps", "originCityCode", "destinationName", "destinationGps", "destinationCityCode")) if (s(a, k) != null) b.put(k, s(a, k));
                    b.put("cityCode", s(a, "originCityCode", "std:080")); b.put("date", req(a, "date"));
                    return bap.intercitySearch(b);
                }),

            tool("ondc_intercity_results",
                "Trips that answered the search (operator, departure/arrival, bus type, fare, seats, boarding/dropping stops), waiting for sellers.",
                object(props("transactionId", str("Transaction id"), "waitSeconds", integer("Default 20, max 90"), "compact", bool("Trim for reading (default true)")), "transactionId"),
                false,
                a -> { JsonNode r = Polling.until(() -> bap.intercityResults(req(a, "transactionId")), Set.of("SEARCH_SENT"), integer(a, "waitSeconds", Polling.DEFAULT_WAIT_SECONDS)); return a.has("compact") && !flag(a, "compact") ? r : Json.compact(r); }),

            tool("ondc_intercity_select_trip",
                "Step 1 of selection: pick a trip (itemId + fulfillmentId from results) with boarding and dropping stops. Pass the trip's fields through exactly. Then read ondc_intercity_draft_quote for the seat map.",
                object(props("transactionId", str("Transaction id"), "trip", Json.obj().put("type", "object").put("description", "The trip object from ondc_intercity_results (bppId, bppUri, providerId, itemId, fulfillmentId, operatorName, departureTime, arrivalTime, boardingStopId/Name/Gps, boardingTime, droppingStopId/Name/Gps, droppingTime)"),
                        "seats", Json.obj().put("type", "array").put("description", "Optional seats [{id,label,fare}] when already known")),
                        "transactionId", "trip"),
                false,
                a -> { ObjectNode b = a.path("trip").isObject() ? a.path("trip").deepCopy() : Json.obj(); if (a.path("seats").isArray()) b.set("seats", a.path("seats")); return bap.intercitySelectStep1(req(a, "transactionId"), b); }),

            tool("ondc_intercity_draft_quote",
                "Seat map and draft fare after selecting a trip (waits for on_select_1).",
                object(props("transactionId", str("Transaction id"), "waitSeconds", integer("Default 20, max 90")), "transactionId"),
                false,
                a -> Polling.until(() -> bap.intercityDraftQuote(req(a, "transactionId")), Set.of("SELECT_SENT"), integer(a, "waitSeconds", Polling.DEFAULT_WAIT_SECONDS))),

            tool("ondc_intercity_select_seats",
                "Step 2 of selection: lock the chosen seats and get the final quote. Seats are passed in step 1's trip.seats on the reference BAP contract; this call just fires select_2. Then read ondc_intercity_quote.",
                object(props("transactionId", str("Transaction id")), "transactionId"),
                false,
                a -> bap.intercitySelectStep2(req(a, "transactionId"))),

            tool("ondc_intercity_quote", "Final fare after seat selection (waits for on_select_2).",
                object(props("transactionId", str("Transaction id"), "waitSeconds", integer("Default 20, max 90")), "transactionId"),
                false,
                a -> Polling.until(() -> bap.intercityQuote(req(a, "transactionId")), Set.of("SELECT2_SENT"), integer(a, "waitSeconds", Polling.DEFAULT_WAIT_SECONDS))),

            tool("ondc_intercity_init",
                "Send billing + one passenger per seat (name, age, gender, seatId, idType, idNumber, phone). After this the live BAP collects payment in the buyer app and confirms; the agent reads ondc_intercity_status.",
                object(props("transactionId", str("Transaction id"), "billingName", str("Billing name"), "billingPhone", str("Billing phone"), "billingEmail", str("Billing email"),
                        "passengers", Json.obj().put("type", "array").put("description", "[{name, age, gender(MALE|FEMALE|OTHER), seatId, idType, idNumber, phone}]")),
                        "transactionId", "billingName", "billingPhone", "passengers"),
                false,
                a -> { ObjectNode b = Json.obj().put("billingName", req(a, "billingName")).put("billingPhone", req(a, "billingPhone")); if (s(a, "billingEmail") != null) b.put("billingEmail", s(a, "billingEmail")); b.set("passengers", a.path("passengers")); return bap.intercityInit(req(a, "transactionId"), b); }),

            tool("ondc_intercity_status", "Booking state, order id, QR tokens, seats and quote breakup for an intercity booking.",
                object(props("transactionId", str("Transaction id"), "waitSeconds", integer("Default 10")), "transactionId"),
                false,
                a -> Polling.until(() -> bap.intercityStatus(req(a, "transactionId")), Set.of("CONFIRM_SENT"), integer(a, "waitSeconds", 10)))
        );
    }
}
