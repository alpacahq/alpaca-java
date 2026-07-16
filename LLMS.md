# LLMS.md — using alpaca-java

Guidance for LLMs and coding bots writing Java applications with `alpaca-java`. For SDK repository
changes, read `AGENTS.md`.

## Rules

1. Use `AlpacaClient` for common Trading and Market Data workflows.
2. Use `AlpacaClientFactory` for generated REST clients, WebSocket streams, and Broker Events SSE.
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
- Use the handwritten Broker SSE wrapper, not generated blocking SSE methods.
- `AlpacaHttpConfig.defaultClient()` is the normal HTTP client. Retries are opt-in; default retry
  methods are only `GET`, `HEAD`, `OPTIONS`, and `TRACE`. Do not retry state-changing calls unless
  the workflow itself is idempotent.

## Lookup

Use the [hosted API reference](https://alpacahq.github.io/alpaca-java/api) for exact signatures and
generated models. Repository users can run `./gradlew generateJavadocs`.
