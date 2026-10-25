package ondcmcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ondcmcp.Json;
import ondcmcp.bap.BapClient;

import java.util.List;
import java.util.Set;

import static ondcmcp.tools.Schema.*;
import static ondcmcp.tools.TransitTools.tool;

/** Entry passes for monuments, museums and events (ONDC TRV14). */
public final class EntryPassTools {
    private EntryPassTools() {}

    public static List<Tool> of(BapClient bap) {
        return List.of(
            tool("ondc_entrypass_destinations", "Cities with entry-pass supply (monuments, museums, events) and how many venues each has.", object(props()), false, a -> bap.entryPassDestinations()),

            tool("ondc_entrypass_search",
                "Search venues/experiences in a city, optionally by name or category (Heritage, Museum, Concert, Cultural-Event). Returns a transactionId; read with ondc_entrypass_results.",
                object(props("cityCode", str("ONDC city code, e.g. std:011"), "venueName", str("Optional venue name filter"), "venueGps", str("Optional 'lat,lng'"), "categoryCode", str("Optional category")), "cityCode"),
                false,
                a -> { ObjectNode b = Json.obj().put("cityCode", req(a, "cityCode")); for (String k : List.of("venueName", "venueGps", "categoryCode")) if (s(a, k) != null) b.put(k, s(a, k)); return bap.entryPassSearch(b); }),

            tool("ondc_entrypass_results",
                "Venues, slots and ticket types (adult/child, Indian/foreign…) that answered the search, waiting for sellers.",
                object(props("transactionId", str("Transaction id"), "waitSeconds", integer("Default 20, max 90"), "compact", bool("Trim for reading (default true)")), "transactionId"),
                false,
                a -> { JsonNode r = Polling.until(() -> bap.entryPassResults(req(a, "transactionId")), Set.of("SEARCH_SENT"), integer(a, "waitSeconds", Polling.DEFAULT_WAIT_SECONDS)); return a.has("compact") && !flag(a, "compact") ? r : Json.compact(r); }),

            tool("ondc_entrypass_select",
                "Step 1: choose a venue slot and ticket lines. lines = [{itemId, parentItemId, count, addOns?:[{id,count}]}] using ids from results. Then read ondc_entrypass_draft_quote.",
                object(props("transactionId", str("Transaction id"), "bppId", str("Seller id from results"), "bppUri", str("Seller URI from results"), "providerId", str("Provider id"),
                        "fulfillmentId", str("Slot/fulfillment id"), "slotStart", str("ISO slot start"), "slotEnd", str("ISO slot end"),
                        "lines", Json.obj().put("type", "array").put("description", "[{itemId, parentItemId, count, addOns?}]")),
                        "transactionId", "providerId", "fulfillmentId", "lines"),
                false,
                a -> { ObjectNode b = Json.obj(); for (String k : List.of("bppId", "bppUri", "providerId", "fulfillmentId", "slotStart", "slotEnd")) if (s(a, k) != null) b.put(k, s(a, k)); b.set("lines", a.path("lines")); return bap.entryPassSelectStep1(req(a, "transactionId"), b); }),

            tool("ondc_entrypass_draft_quote", "Draft fare after step 1 (waits for on_select_1).",
                object(props("transactionId", str("Transaction id"), "waitSeconds", integer("Default 20, max 90")), "transactionId"),
                false,
                a -> Polling.until(() -> bap.entryPassDraftQuote(req(a, "transactionId")), Set.of("SELECT_SENT"), integer(a, "waitSeconds", Polling.DEFAULT_WAIT_SECONDS))),

            tool("ondc_entrypass_select_confirm_lines", "Step 2: lock the lines and get the final quote. Then read ondc_entrypass_quote.",
                object(props("transactionId", str("Transaction id")), "transactionId"), false, a -> bap.entryPassSelectStep2(req(a, "transactionId"))),

            tool("ondc_entrypass_quote", "Final quote (waits for on_select_2).",
                object(props("transactionId", str("Transaction id"), "waitSeconds", integer("Default 20, max 90")), "transactionId"),
                false,
                a -> Polling.until(() -> bap.entryPassQuote(req(a, "transactionId")), Set.of("SELECT2_SENT"), integer(a, "waitSeconds", Polling.DEFAULT_WAIT_SECONDS))),

            tool("ondc_entrypass_init",
                "Send billing and visitor details ([{name, age, gender, idType, idNumber}]). Payment and confirm happen in the app on a real BAP; read ondc_entrypass_status afterwards.",
                object(props("transactionId", str("Transaction id"), "billingName", str("Billing name"), "billingPhone", str("Billing phone"), "billingEmail", str("Billing email"),
                        "visitorDetails", Json.obj().put("type", "array").put("description", "[{name, age, gender, idType, idNumber}]")),
                        "transactionId", "billingName", "billingPhone"),
                false,
                a -> { ObjectNode b = Json.obj().put("billingName", req(a, "billingName")).put("billingPhone", req(a, "billingPhone")); if (s(a, "billingEmail") != null) b.put("billingEmail", s(a, "billingEmail")); if (a.path("visitorDetails").isArray()) b.set("visitorDetails", a.path("visitorDetails")); return bap.entryPassInit(req(a, "transactionId"), b); }),

            tool("ondc_entrypass_status", "Pass state, order id, QR tokens, pass statuses and validity.",
                object(props("transactionId", str("Transaction id"), "waitSeconds", integer("Default 10")), "transactionId"),
                false,
                a -> Polling.until(() -> bap.entryPassStatus(req(a, "transactionId")), Set.of("CONFIRM_SENT"), integer(a, "waitSeconds", 10)))
        );
    }
}
