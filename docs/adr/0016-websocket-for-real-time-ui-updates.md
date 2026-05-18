# ADR-0016: WebSocket for Real-Time UI Updates

## Status
Accepted

## Context
The UI needs real-time market data updates without polling. Price ticks arrive via Kafka, but the browser cannot consume Kafka directly. Options: WebSocket, Server-Sent Events (SSE), long polling.

## Decision
Use Ktor's WebSocket support in the gateway to push real-time price updates to the UI. The `PriceBroadcaster` class manages instrument-level subscriptions:

- Clients connect via WebSocket and subscribe to specific instrument IDs
- `PriceBroadcaster` maintains a `ConcurrentHashMap<instrumentId, Set<WebSocketServerSession>>`
- When a price tick arrives, it is serialized to JSON and broadcast to all sessions subscribed to that instrument
- Dead sessions are detected (send failure) and automatically removed
- WebSocket ping/pong: 30s ping period, 10s timeout

The UI implements auto-reconnect with exponential backoff (max 20 attempts) and displays a "Reconnecting..." banner during disconnection.

## Applies when
- Adding a real-time UI feature (price tick, alert badge, risk recalc, position update).
- Tempted to add a polling loop in the UI, or a Server-Sent Events endpoint, or to expose Kafka to the browser.

## Rules
- **DO** publish new real-time streams via `PriceBroadcaster`-style instrument/topic-subscription patterns on the gateway WebSocket endpoint.
- **DO** key subscriptions by a stable id (instrument, book, alert channel). Maintain `ConcurrentHashMap<key, Set<WebSocketServerSession>>`.
- **DO** detect dead sessions on send failure and remove them — no zombie sessions.
- **DO** implement auto-reconnect with exponential backoff in the UI client and surface a "Reconnecting..." indicator.
- **DO** test WebSocket flows in Playwright by intercepting `ws://` traffic — not just unit tests.
- **DON'T** poll the gateway for live data when a WebSocket subscription exists.
- **DON'T** expose Kafka directly to the browser.
- **DON'T** assume a single gateway replica. Horizontal scaling requires sticky sessions (current) or a pub/sub backplane (future) — design subscription handlers accordingly.

## Consequences

### Positive
- True push — no polling overhead, updates arrive within milliseconds of the Kafka event
- Instrument-level subscriptions avoid sending irrelevant data to clients
- Dead session cleanup prevents memory leaks from disconnected clients
- Built into Ktor — no additional WebSocket library needed

### Negative
- WebSocket connections are stateful — complicates horizontal scaling (mitigated by sticky sessions or a pub/sub backplane)
- Each connected client holds a server-side session in memory
- WebSocket protocol can be blocked by some corporate proxies (fallback to SSE not implemented)

### Alternatives Considered
- **Server-Sent Events (SSE)**: Simpler (HTTP-based, unidirectional), but lacks bidirectional communication for subscription management. Would require separate subscribe/unsubscribe endpoints.
- **Long polling**: Works through any proxy, but higher latency and server load due to repeated connection setup.
- **Direct Kafka consumption (kafka.js)**: Exposes Kafka to the browser, which is a security and operational concern.
