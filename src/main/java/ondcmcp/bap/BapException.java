package ondcmcp.bap;

import com.fasterxml.jackson.databind.JsonNode;

/** A BAP refused a request (HTTP 4xx/5xx or a business rule). {@code detail} carries the BAP's body. */
public final class BapException extends Exception {
    public final JsonNode detail;
    public BapException(String message, JsonNode detail) { super(message); this.detail = detail; }
}
