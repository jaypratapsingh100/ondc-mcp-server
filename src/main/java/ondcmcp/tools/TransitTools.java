package ondcmcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ondcmcp.Json;
import ondcmcp.bap.BapClient;

import java.util.List;
import java.util.Set;

import static ondcmcp.tools.Schema.*;

/** Metro and city-bus tickets (ONDC TRV11) end to end: search → results → select → quote → init → pay → confirm → status/cancel. */
public final class TransitTools {
    private TransitTools() {}

    public static List<Tool> of(BapClient bap) {
        return List.of(
            tool("ondc_transit_search",
                "Start a metro or city-bus ticket search between two GPS points. Returns a transactionId; results arrive asynchronously, so call ondc_transit_results next (it waits for them). "
                    + "vehicleCategory METRO or BUS; cityCode like std:011 (Delhi) or std:080 (Bengaluru).",
                object(props("startGps", str("'lat,lng' of the origin, e.g. 28.6328,77.2197"), "endGps", str("'lat,lng' of the destination"),
                        "vehicleCategory", enumOf("METRO or BUS", List.of("METRO", "BUS")), "cityCode", str("ONDC city code, default std:011"),
                        "startStopCode", str("Optional: exact boarding stop/station code"), "endStopCode", str("Optional: exact alighting stop/station code")),
                        "startGps", "endGps", "vehicleCategory"),
                false,
                a -> bap.transitSearch(req(a, "startGps"), req(a, "endGps"), req(a, "vehicleCategory"), s(a, "cityCode", "std:011"), s(a, "startStopCode"), s(a, "endStopCode"))),

            tool("ondc_transit_results",
                "Read the sellers and fare items that answered a transit search, waiting up to waitSeconds for the first on_search. Pick a provider+item from here for ondc_transit_select. "
                    + "compact=true (default) trims images/tags and long strings.",
                object(props("transactionId", str("From ondc_transit_search"), "waitSeconds", integer("How long to wait for sellers, default 20, max 90"), "compact", bool("Trim the catalogue for reading (default true)")), "transactionId"),
                false,
                a -> {
                    JsonNode r = Polling.until(() -> bap.transitResults(req(a, "transactionId")), Set.of("SEARCH_SENT"), integer(a, "waitSeconds", Polling.DEFAULT_WAIT_SECONDS));
                    return a.has("compact") && !flag(a, "compact") ? r : Json.compact(r);
                }),

            tool("ondc_transit_select",
                "Choose one fare item (ticket type) from one provider and ask the seller for a quote. Use providerId/itemId exactly as returned by ondc_transit_results; bppId/bppUri too when present. "
                    + "Then call ondc_transit_quote.",
                object(props("transactionId", str("Transaction id"), "providerId", str("Provider id from results"), "itemId", str("Item id from results"),
                        "ticketCount", integer("Number of tickets, default 1"), "bppId", str("Seller id from results (optional)"), "bppUri", str("Seller URI from results (optional)"),
                        "startStopCode", str("Optional boarding stop code override"), "endStopCode", str("Optional alighting stop code override")),
                        "transactionId", "providerId", "itemId"),
                false,
                a -> bap.transitSelect(req(a, "transactionId"), s(a, "bppId"), s(a, "bppUri"), req(a, "providerId"), req(a, "itemId"), integer(a, "ticketCount", 1), s(a, "startStopCode"), s(a, "endStopCode"))),

            tool("ondc_transit_quote",
                "Read the seller's quote (fare and breakup) for the selected item, waiting for on_select. If the state stays SELECT_SENT the seller has not answered yet or rejected (see error fields).",
                object(props("transactionId", str("Transaction id"), "waitSeconds", integer("Default 20, max 90")), "transactionId"),
                false,
                a -> Polling.until(() -> bap.transitQuote(req(a, "transactionId")), Set.of("SELECT_SENT"), integer(a, "waitSeconds", Polling.DEFAULT_WAIT_SECONDS))),

            tool("ondc_transit_init",
                "Send billing details (name, phone, email) to the seller — the ONDC /init step. Call after a quote. The BAP may refuse outside the operator's booking hours; relay that message.",
                object(props("transactionId", str("Transaction id"), "name", str("Rider name"), "phone", str("Rider phone"), "email", str("Rider email")), "transactionId"),
                false,
                a -> bap.transitInit(req(a, "transactionId"), s(a, "name"), s(a, "phone"), s(a, "email"))),

            tool("ondc_transit_pay_wallet",
                "Pay the quoted fare from the rider's wallet balance held by the BAP (the only payment path an agent can complete without a card UI). Fails with a clear message when the balance is short — then the rider pays in the app.",
                object(props("transactionId", str("Transaction id")), "transactionId"),
                true,
                a -> bap.transitPayWallet(req(a, "transactionId"))),

            tool("ondc_transit_confirm",
                "Place the order with the seller (ONDC /confirm). Requires payment to have been captured (wallet via ondc_transit_pay_wallet, or a paymentTransactionId from the app's checkout). "
                    + "This creates a real ticket on live networks — confirm with the rider first. Then call ondc_transit_status for the QR.",
                object(props("transactionId", str("Transaction id"), "paymentTransactionId", str("Payment reference when paid outside this agent (optional)")), "transactionId"),
                true,
                a -> bap.transitConfirm(req(a, "transactionId"), s(a, "paymentTransactionId"))),

            tool("ondc_transit_status",
                "Current booking state, order id, QR tokens and per-ticket statuses; also nudges the seller for a fresh on_status. Waits while the state is CONFIRM_SENT (ticket being issued).",
                object(props("transactionId", str("Transaction id"), "waitSeconds", integer("Default 20, max 90")), "transactionId"),
                false,
                a -> Polling.until(() -> bap.transitStatus(req(a, "transactionId")), Set.of("CONFIRM_SENT"), integer(a, "waitSeconds", Polling.DEFAULT_WAIT_SECONDS))),

            tool("ondc_transit_cancel",
                "Cancel a confirmed ticket (ONDC /cancel). Refund terms are the seller's; the response carries refund amount and fee when known. Confirm with the rider first.",
                object(props("transactionId", str("Transaction id"), "reasonCode", str("ONDC cancellation reason code, default 001 (rider changed plans)")), "transactionId"),
                true,
                a -> bap.transitCancel(req(a, "transactionId"), s(a, "reasonCode", "001"))),

            tool("ondc_transit_my_tickets",
                "The rider's own metro/bus tickets (the account whose token this server runs with): state, order id, validity, QR presence.",
                object(props()),
                false,
                a -> Json.compact(bap.transitMyTickets()))
        );
    }

    interface Body { JsonNode run(JsonNode a) throws Exception; }

    static Tool tool(String name, String desc, ObjectNode schema, boolean write, Body body) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return desc; }
            @Override public ObjectNode inputSchema() { return schema; }
            @Override public boolean write() { return write; }
            @Override public JsonNode call(JsonNode args) throws Exception {
                try { return body.run(args); }
                catch (ondcmcp.bap.BapException e) {
                    ObjectNode err = Json.obj().put("error", e.getMessage()); err.set("detail", e.detail); throw new ToolException(Json.write(err));
                }
            }
        };
    }
}
