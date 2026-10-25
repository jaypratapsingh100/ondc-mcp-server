package ondcmcp.tools;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Beckn callbacks arrive seconds later; a tool call is synchronous. These helpers read the
 * BAP's current state until it leaves the "sent" state or the wait budget runs out, so the
 * model gets one answer instead of being told to poll.
 */
public final class Polling {
    private Polling() {}

    public static final int DEFAULT_WAIT_SECONDS = 20;
    public static final int MAX_WAIT_SECONDS = 90;

    /** Poll {@code read} every ~1.5 s until its {@code state} is not in {@code pendingStates}. */
    public static JsonNode until(Callable<JsonNode> read, Set<String> pendingStates, int waitSeconds) throws Exception {
        long deadline = System.currentTimeMillis() + Math.min(MAX_WAIT_SECONDS, Math.max(0, waitSeconds)) * 1000L;
        JsonNode last = read.call();
        while (pendingStates.contains(last.path("state").asText("")) && System.currentTimeMillis() < deadline) {
            Thread.sleep(sleepMs());
            last = read.call();
        }
        return last;
    }

    private static long sleepMs() { return "true".equals(System.getProperty("ondc.mcp.fastpoll")) ? 5 : 1500; }
}
