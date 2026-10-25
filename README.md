# ONDC MCP Server

A [Model Context Protocol](https://modelcontextprotocol.io) server that lets an AI agent
(Claude Desktop, Claude Code, Cursor, any MCP client) **search, quote, book and track
ONDC mobility orders**: metro and city-bus tickets (TRV11), intercity buses (TRV12) and
monument/event entry passes (TRV14), through a buyer app (BAP) on the
[ONDC](https://ondc.org) network, which speaks the [Beckn](https://becknprotocol.io) protocol.

It ships with a **mock seller**, so every flow runs without an ONDC subscription,
registry keys or a live BAP. To use a real network, plug in your own BAP client.

```
MCP client ──MCP──▶ ondc-mcp-server ──▶ BapClient ──▶ your BAP ──Beckn──▶ ONDC gateway / sellers
                                            │
                                            └──▶ MockBapClient (in-memory seller, no network)
```

## Why this exists

Beckn is asynchronous: a buyer sends `/search`, a seller answers on `/on_search` seconds
later, and so on for select → init → confirm → status. An agent tool call is synchronous.
This server hides that gap: every read tool (`*_results`, `*_quote`, `*_status`) polls the
BAP until the callback has landed (or a wait budget runs out) and returns one answer.

Tools expose exactly the ids the network uses (provider, item, fulfillment, transaction),
so an agent's actions can be audited step by step against the ONDC transaction log.

## Quick start

Requires Java 17+.

```bash
mvn -q package
java -jar target/ondc-mcp-server.jar        # stdio transport, mock seller
```

### Claude Code

```bash
claude mcp add ondc -- java -jar /path/to/ondc-mcp-server.jar
```

Then ask: *"Find metro tickets from Central to South Park and book two."*

### Claude Desktop

See [`examples/claude_desktop_config.json`](examples/claude_desktop_config.json).

### Streamable HTTP

```bash
ONDC_MCP_BEARER=change-me java -jar target/ondc-mcp-server.jar --transport http --port 8765
# POST http://127.0.0.1:8765/mcp  with  Authorization: Bearer change-me
```

The HTTP transport binds to `127.0.0.1` by default. Set a bearer before exposing it anywhere.

## Connecting a real BAP

Implement `ondcmcp.bap.BapClient` (one method per BAP action: search, results, select,
quote, init, confirm, status, cancel… for each domain) against your BAP's API, give it a
public no-arg constructor that reads its own configuration, and start the server with:

```bash
ONDC_MCP_BAP_CLASS=com.example.MyBapClient \
java -cp my-bap-client.jar:ondc-mcp-server.jar ondcmcp.Main
```

Throw `ondcmcp.bap.BapException` for refusals; the message reaches the model as a tool error.
Keep credentials in your client's own configuration, never in tool arguments.

Live bookings create **real tickets and real charges**. The tools that pay, confirm or
cancel are annotated `destructiveHint`, and the server instructions tell the model to
confirm with the rider first.

## Tools

| Flow | Tools (in order) |
|---|---|
| Orientation | `ondc_server_info` |
| Metro / bus (TRV11) | `ondc_transit_search` → `ondc_transit_results` → `ondc_transit_select` → `ondc_transit_quote` → `ondc_transit_init` → `ondc_transit_pay_wallet` → `ondc_transit_confirm` → `ondc_transit_status` · `ondc_transit_cancel` · `ondc_transit_my_tickets` |
| Intercity bus (TRV12) | `ondc_intercity_cities` → `ondc_intercity_search` → `ondc_intercity_results` → `ondc_intercity_select_trip` → `ondc_intercity_draft_quote` → `ondc_intercity_select_seats` → `ondc_intercity_quote` → `ondc_intercity_init` → `ondc_intercity_status` |
| Entry pass (TRV14) | `ondc_entrypass_destinations` → `ondc_entrypass_search` → `ondc_entrypass_results` → `ondc_entrypass_select` → `ondc_entrypass_draft_quote` → `ondc_entrypass_select_confirm_lines` → `ondc_entrypass_quote` → `ondc_entrypass_init` → `ondc_entrypass_status` |

Read tools accept `waitSeconds` (default 20, max 90) and `compact` (default true: strips
images, tags and long strings so a catalogue fits a context window).

## Protocol notes

* MCP protocol versions `2025-06-18`, `2025-03-26`, `2024-11-05`; methods `initialize`,
  `ping`, `tools/list`, `tools/call` (resources and prompts are empty lists).
* Transports: **stdio** (one JSON-RPC message per line) and **Streamable HTTP**, stateless
  (POST `/mcp`, JSON response; notifications get 202; GET is 405; no SSE).
* Tool-level failures come back as `isError: true` results with the BAP's message, never as
  protocol errors, so the model can read and react to them.
* No framework: Java 17, Jackson, `java.net.http`, `com.sun.net.httpserver`. One ~2 MB jar.

## Development

```bash
mvn test     # protocol, transports and full mock flows for all three domains
```

```
protocol/McpServer.java        JSON-RPC dispatch
transport/StdioTransport.java  stdio framing
transport/HttpTransport.java   Streamable HTTP (stateless) + bearer
bap/BapClient.java             the BAP actions the tools need
bap/MockBapClient.java         in-memory seller with an async-shaped state machine
tools/*Tools.java              one file per ONDC domain
```

## Status

* Transit (TRV11): search through cancel, including wallet payment.
* Intercity (TRV12) and entry pass (TRV14): search through `init`; payment and confirm
  happen in the buyer app's checkout.
* Cabs (TRV10), logistics (LOG10) and hotels (TRV13): not yet.

## License

MIT
