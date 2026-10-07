# LLMS.md — using alpaca-java

Guidance for LLMs and coding bots writing Java applications with `alpaca-java`. For SDK repository
changes, read `AGENTS.md`.

## Rules

1. Use `AlpacaClient` for common Trading and Market Data workflows.
2. Use `AlpacaClientFactory` for generated REST clients, WebSocket streams, and Trading, Market
   Data corporate-actions, or Broker SSE.
3. Use `markets.alpaca.client.openapi.*` only when a handwritten facade lacks the endpoint.
4. Never create a generated `ApiClient` directly; factory methods configure the matching API's
   authentication and base URL.

## Credentials and client

Trading and Market Data use trading API keys; Broker uses separate HTTP Basic credentials.

```java
var tradingCredentials = AlpacaCredentials.fromTradingApiEnvironmentVariables();
var brokerCredentials = AlpacaCredentials.fromBrokerApiEnvironmentVariables();

var client = AlpacaClient.builder(tradingCredentials)
    .tradingEnvironment(TradingApiEnvironment.PAPER)
    .brokerEnvironment(BrokerApiEnvironment.SANDBOX)
    .brokerCredentials(brokerCredentials)
    .build();
```

Use production Trading or Broker environments only when explicitly requested.

## Common patterns

- Use `client.orders()` and `client.stocks()` before generated APIs. Use
  `listWithHttpInfo(...)` when response headers, status, or rate limits matter.
- For an uncovered endpoint, use `client.newTradingClient()`, `client.newDataClient()`, or
  `client.newBrokerClient()` to construct its generated API class. Configure generated clients
  before sharing them across threads; do not mutate them while requests are in flight.
- Generated `*WithHttpInfo(...)` methods expose pagination tokens. Use `AlpacaPagination` and
  `AlpacaPaginationOptions` to collect pages with limits and repeated-token handling.
- Wrap generated callback methods with `AlpacaFutures.trading`, `.data`, or `.broker` for
  `CompletableFuture` usage; use the corresponding `*Response` adapter when headers matter.
- Create WebSocket streams through `AlpacaClientFactory`, wait for authentication after connecting,
  keep callbacks non-blocking, and close subscriptions when finished.
- Use the handwritten Trading, Market Data corporate-actions, and Broker SSE clients, not generated
  buffered SSE methods. Trading and corporate-actions streams reconnect by default and transmit
  committed event IDs. Trading reconnects use the documented `since_id` query plus
  `Last-Event-ID`; corporate-actions use the OAS-documented standard header. Broker makes one
  connection unless explicit `AlpacaSseOptions` enable reconnects. Malformed Trading and
  corporate-action events are terminal. Broker reports a malformed event through `onFailure`,
  advances its resume cursor, and continues. Close subscriptions when finished and deduplicate by
  SSE event ID when exactly-once effects matter.
  Await startup with `subscription.opened()` and a caller-controlled timeout; inspect
  `connection()` for the latest accepted response metadata. Prefer options-only factory overloads
  unless callbacks need an application-owned executor. SSE callbacks are serialized and
  backpressured; listener exceptions are logged and still advance a delivered event's cursor, while
  callback-executor rejection is terminal. `maxBackoff` caps client, SSE `retry:`, and HTTP
  `Retry-After` delays; the connection timeout also bounds non-success response-body capture.
  `BrokerSseSubscription` implements the shared lifecycle interface. Prefer its `close()` method
  over the legacy `eventSource()` facade. For `0.1.4` upgrades, follow
  [`docs/content/sdk/sse-migration.md`](docs/content/sdk/sse-migration.md).
  Lifecycle timers and cancellation do not wait for callbacks, but an active data callback
  backpressures response parsing and can delay detection of remote EOF and the following reconnect.
  Keep synchronous `opened()`/`completion()` continuations short; use an async continuation with an
  application-owned executor for unrelated or potentially unbounded work. Do not combine a Trading
  initial resume ID with a date-bounded request, and keep it at or before `untilId` for an ID-bounded
  request.
  `DIVTXEX` details temporarily use `CDIVActivityV2` while retaining the original activity type.
- `AlpacaHttpConfig.defaultClient()` is the normal HTTP client. Retries are opt-in; default retry
  methods are only `GET`, `HEAD`, `OPTIONS`, and `TRACE`. Do not retry state-changing calls unless
  the workflow itself is idempotent.

## Lookup

Use the [hosted API reference](https://alpacahq.github.io/alpaca-java/api) for exact signatures and
generated models. Repository users can run `./gradlew generateJavadocs`.
