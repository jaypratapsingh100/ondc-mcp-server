package ondcmcp.tools;

import ondcmcp.Json;
import ondcmcp.bap.BapClient;

import java.util.List;

import static ondcmcp.tools.Schema.*;
import static ondcmcp.tools.TransitTools.tool;

/** Orientation tools: which mode the server runs in and how the Beckn flows go. */
public final class InfoTools {
    private InfoTools() {}

    public static List<Tool> of(BapClient bap) {
        return List.of(
            tool("ondc_server_info",
                "Which backend this server talks to (mock seller, or a live BAP URL), what each domain supports, and the step order for each flow. Call first when unsure.",
                object(props()), false,
                a -> {
                    var r = Json.obj().put("mode", bap.mode()).put("protocol", "ONDC / Beckn — asynchronous: every action is sent, the seller answers on a callback, and the *_results/*_quote/*_status tools wait for that callback.");
                    var flows = r.putObject("flows");
                    flows.put("transit (TRV11 metro/bus)", "search → results → select → quote → init → pay_wallet (or app payment) → confirm → status (QR) → cancel");
                    flows.put("intercity (TRV12)", "cities → search → results → select_trip → draft_quote (seat map) → select_seats → quote → init → [app pays + confirms] → status");
                    flows.put("entry pass (TRV14)", "destinations → search → results → select → draft_quote → select_confirm_lines → quote → init → [app pays + confirms] → status");
                    r.put("money", "Live bookings create real tickets and charge real money. Confirm with the rider before ondc_transit_pay_wallet, ondc_transit_confirm or ondc_transit_cancel.");
                    r.put("ids", "Use provider/item/fulfillment ids exactly as returned; never invent one.");
                    return r;
                })
        );
    }
}
