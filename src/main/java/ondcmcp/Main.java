package ondcmcp;

import ondcmcp.bap.BapClient;
import ondcmcp.bap.MockBapClient;
import ondcmcp.protocol.McpServer;
import ondcmcp.tools.*;
import ondcmcp.transport.HttpTransport;
import ondcmcp.transport.StdioTransport;

import java.util.Map;

/**
 * Entry point.
 * <pre>
 *   java -jar ondc-mcp-server.jar                         # stdio, built-in mock seller
 *   java -jar ondc-mcp-server.jar --transport http        # Streamable HTTP on 127.0.0.1:8765/mcp
 *   ONDC_MCP_BAP_CLASS=com.example.MyBap java -cp my-bap.jar:ondc-mcp-server.jar ondcmcp.Main
 * </pre>
 * Environment: ONDC_MCP_TRANSPORT (stdio|http), ONDC_MCP_HOST, ONDC_MCP_PORT,
 * ONDC_MCP_BEARER (optional bearer for the HTTP transport),
 * ONDC_MCP_BAP_CLASS (fully qualified BapClient implementation with a public no-arg
 * constructor; it reads its own configuration). Unset = mock seller.
 */
public final class Main {

    public static final String INSTRUCTIONS =
            "ONDC mobility agent tools. ONDC is asynchronous: search/select/init/confirm are sent, sellers reply on callbacks, "
            + "and the results/quote/status tools wait for those replies (pass waitSeconds to wait longer). Always read results before selecting, "
            + "use ids exactly as returned, and confirm with the rider before any tool that pays, books or cancels. Call ondc_server_info first "
            + "to learn whether this is the mock seller or a live network.";

    public static void main(String[] args) throws Exception {
        Map<String, String> env = System.getenv();
        String transport = arg(args, "--transport", env.getOrDefault("ONDC_MCP_TRANSPORT", "stdio"));
        int port = Integer.parseInt(arg(args, "--port", env.getOrDefault("ONDC_MCP_PORT", "8765")));
        String host = arg(args, "--host", env.getOrDefault("ONDC_MCP_HOST", "127.0.0.1"));

        BapClient bap = buildBap(env.get("ONDC_MCP_BAP_CLASS"));
        ToolRegistry tools = registry(bap);
        McpServer server = new McpServer(tools, INSTRUCTIONS);
        System.err.println("[ondc-mcp-server] " + McpServer.VERSION + " mode=" + bap.mode() + " transport=" + transport + " tools=" + tools.size());

        if ("http".equalsIgnoreCase(transport)) {
            new HttpTransport(server, env.get("ONDC_MCP_BEARER")).start(host, port);
            System.err.println("[ondc-mcp-server] listening on http://" + host + ":" + port + "/mcp");
            Thread.currentThread().join();
        } else {
            new StdioTransport(server).run(System.in, System.out);
        }
    }

    public static ToolRegistry registry(BapClient bap) {
        return new ToolRegistry().addAll(InfoTools.of(bap)).addAll(TransitTools.of(bap)).addAll(IntercityTools.of(bap)).addAll(EntryPassTools.of(bap));
    }

    static BapClient buildBap(String className) {
        if (className == null || className.isBlank()) return new MockBapClient();
        try {
            return (BapClient) Class.forName(className.trim()).getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            System.err.println("[ondc-mcp-server] could not load BAP client " + className + " (" + e + "); using the mock seller.");
            return new MockBapClient();
        }
    }

    private static String arg(String[] args, String name, String dflt) {
        for (int i = 0; i + 1 < args.length; i++) if (name.equals(args[i])) return args[i + 1];
        return dflt;
    }
}
