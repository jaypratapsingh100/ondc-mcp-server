package ondcmcp.bap;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * What the MCP tools need from a buyer app (BAP). Beckn/ONDC is asynchronous — every action
 * is "send, then a callback lands later" — so a BAP exposes "send" endpoints and "read the
 * current state" endpoints. This interface mirrors that: {@code *Send} methods fire the
 * action, {@code *Read} methods return whatever has arrived so far; tools poll the readers.
 *
 * <p>{@link MockBapClient} is the built-in implementation (in-memory seller for development
 * and tests). To drive a real network, implement this interface against your BAP's API and
 * load it with {@code ONDC_MCP_BAP_CLASS}; every tool works unchanged.</p>
 */
public interface BapClient {

    String mode();

    // ── Transit (TRV11 metro/bus) ───────────────────────────────────────────
    JsonNode transitSearch(String startGps, String endGps, String vehicleCategory, String cityCode, String startStopCode, String endStopCode) throws Exception;
    JsonNode transitResults(String txId) throws Exception;
    JsonNode transitSelect(String txId, String bppId, String bppUri, String providerId, String itemId, int ticketCount, String startStopCode, String endStopCode) throws Exception;
    JsonNode transitQuote(String txId) throws Exception;
    JsonNode transitInit(String txId, String name, String phone, String email) throws Exception;
    JsonNode transitPayWallet(String txId) throws Exception;
    JsonNode transitConfirm(String txId, String paymentTransactionId) throws Exception;
    JsonNode transitStatus(String txId) throws Exception;
    JsonNode transitCancel(String txId, String reasonCode) throws Exception;
    JsonNode transitMyTickets() throws Exception;

    // ── Intercity bus (TRV12) ──────────────────────────────────────────────
    JsonNode intercityCities() throws Exception;
    JsonNode intercitySearch(JsonNode body) throws Exception;
    JsonNode intercityResults(String txId) throws Exception;
    JsonNode intercitySelectStep1(String txId, JsonNode body) throws Exception;
    JsonNode intercityDraftQuote(String txId) throws Exception;
    JsonNode intercitySelectStep2(String txId) throws Exception;
    JsonNode intercityQuote(String txId) throws Exception;
    JsonNode intercityInit(String txId, JsonNode body) throws Exception;
    JsonNode intercityStatus(String txId) throws Exception;

    // ── Entry pass (TRV14) ─────────────────────────────────────────────────
    JsonNode entryPassDestinations() throws Exception;
    JsonNode entryPassSearch(JsonNode body) throws Exception;
    JsonNode entryPassResults(String txId) throws Exception;
    JsonNode entryPassSelectStep1(String txId, JsonNode body) throws Exception;
    JsonNode entryPassDraftQuote(String txId) throws Exception;
    JsonNode entryPassSelectStep2(String txId) throws Exception;
    JsonNode entryPassQuote(String txId) throws Exception;
    JsonNode entryPassInit(String txId, JsonNode body) throws Exception;
    JsonNode entryPassStatus(String txId) throws Exception;
}
