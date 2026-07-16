# LLMS.md — using alpaca-java

This file is for LLMs and coding bots that need to write Java application code using `alpaca-java`.
If you are modifying this repository, read `AGENTS.md` instead.

## Core rule

Prefer the handwritten entry points first:

1. Use `AlpacaClient` for common application workflows.
2. Use `AlpacaClientFactory` to create generated REST clients, WebSocket streams, or Broker Events SSE clients.
3. Use generated `markets.alpaca.client.openapi.*` APIs only when a handwritten facade does not cover the endpoint.
4. Do not instantiate generated `ApiClient` classes directly. Use `AlpacaClientFactory` so authentication and base URLs are configured correctly.

## Credentials

Use `AlpacaCredentials`:

```java
import markets.alpaca.client.AlpacaCredentials;

var tradingCredentials = AlpacaCredentials.fromTradingApiEnvironmentVariables();
var brokerCredentials = AlpacaCredentials.fromBrokerApiEnvironmentVariables();
```

Trading and Market Data use header credentials. Broker uses HTTP Basic authentication. If an application uses custom environment variable names:

```java
var creds = AlpacaCredentials.fromEnvironmentVariables("MY_KEY_ID", "MY_SECRET_KEY");
```

## Start with AlpacaClient

```java
import markets.alpaca.client.AlpacaClient;
import markets.alpaca.client.AlpacaCredentials;
import markets.alpaca.client.BrokerApiEnvironment;
import markets.alpaca.client.TradingApiEnvironment;

var tradingCredentials = AlpacaCredentials.fromTradingApiEnvironmentVariables();
var brokerCredentials = AlpacaCredentials.fromBrokerApiEnvironmentVariables();

var client = AlpacaClient.builder(tradingCredentials)
    .tradingEnvironment(TradingApiEnvironment.PAPER)
    .brokerEnvironment(BrokerApiEnvironment.SANDBOX)
    .brokerCredentials(brokerCredentials)
    .build();
```

Use `TradingApiEnvironment.PAPER` for paper trading and `TradingApiEnvironment.PRODUCTION` for live trading. Use `BrokerApiEnvironment.SANDBOX` unless the user explicitly needs production Broker access.

## Trading orders

For common Trading order reads, use the handwritten orders facade:

```java
import markets.alpaca.client.trading.ListOrdersRequest;

var openOrders = client.orders().list(ListOrdersRequest.builder()
    .status(ListOrdersRequest.Status.OPEN)
    .symbols("AAPL")
    .limit(50)
    .build());
```

Use `listWithHttpInfo(...)` when status codes, headers, or rate-limit metadata are needed.

## Market Data stock trades

```java
import markets.alpaca.client.data.StockTradesRequest;
import markets.alpaca.client.openapi.data.model.StockHistoricalFeed;

var trades = client.stocks().trades(StockTradesRequest.builder()
    .symbols("AAPL", "MSFT")
    .feed(StockHistoricalFeed.IEX)
    .limit(100)
    .build());
```

Use `tradesForSymbol(...)` only when the request contains exactly one symbol.

## Generated REST escape hatch

Generated clients are available for endpoints without handwritten helpers:

```java
var trading = client.newTradingClient();
var ordersApi = new markets.alpaca.client.openapi.trading.api.OrdersApi(trading);

var data = client.newDataClient();
var newsApi = new markets.alpaca.client.openapi.data.api.NewsApi(data);

var broker = client.newBrokerClient();
var accountsApi = new markets.alpaca.client.openapi.broker.api.AccountsApi(broker);
```

The generated clients are mutable. Configure them before sharing across threads and avoid mutating them while requests are in flight.

## Pagination

Generated `*WithHttpInfo(...)` methods expose response headers and pagination tokens. Use `AlpacaPagination` to collect pages or flatten items:

```java
import markets.alpaca.client.rest.AlpacaPagination;
import markets.alpaca.client.openapi.data.model.NewsResp;

var newsApi = new markets.alpaca.client.openapi.data.api.NewsApi(client.newDataClient());

var articles = AlpacaPagination.collectDataItems(
    pageToken -> newsApi.newsWithHttpInfo(
        null, null, null, "AAPL", 50, false, null, pageToken),
    NewsResp::getNextPageToken,
    NewsResp::getNews
);
```

Use `AlpacaPaginationOptions` for page/item limits or repeated-token behavior.

## Async generated REST calls

Generated REST APIs expose callback-style async methods. Use `AlpacaFutures` for `CompletableFuture`:

```java
import markets.alpaca.client.rest.AlpacaFutures;

var accountsApi = new markets.alpaca.client.openapi.trading.api.AccountsApi(client.newTradingClient());
var accountFuture = AlpacaFutures.trading(callback -> accountsApi.getAccountAsync(callback));
```

Use `tradingResponse`, `dataResponse`, or `brokerResponse` when response status and headers are needed.

## WebSocket streams

Create streams with `AlpacaClientFactory`, connect with a subscription, and wait for authentication:

```java
import java.time.Duration;
import markets.alpaca.client.AlpacaClientFactory;
import markets.alpaca.client.ws.AlpacaStreamEnvironment;
import markets.alpaca.client.ws.StockSource;
import markets.alpaca.client.ws.StockStreamListener;
import markets.alpaca.client.ws.StockSubscription;
import markets.alpaca.client.ws.model.StockTrade;

var stream = AlpacaClientFactory.stockStream(
    tradingCredentials,
    StockSource.IEX,
    AlpacaStreamEnvironment.PRODUCTION,
    new StockStreamListener() {
        @Override public void onTrade(StockTrade trade) {
            System.out.println(trade);
        }
    });

stream.connect(StockSubscription.builder().trades("AAPL").build());

var auth = stream.waitForAuthenticationResult(Duration.ofSeconds(5));
if (!auth.isAuthenticated()) {
    throw new IllegalStateException("stream auth failed: " + auth.status() + " " + auth.message());
}
```

Keep callbacks non-blocking. Offload expensive work to an application executor. Close streams when finished.

## Broker Events SSE

Use the handwritten SSE wrapper instead of generated blocking SSE methods:

```java
import markets.alpaca.client.AlpacaClientFactory;
import markets.alpaca.client.broker.sse.BrokerSseDateOptions;
import markets.alpaca.client.broker.sse.BrokerSseEventListener;
import markets.alpaca.client.openapi.broker.model.TradeUpdateEventV2;

var brokerEvents = AlpacaClientFactory.brokerEventsSseClient(client.newBrokerClient());
var subscription = brokerEvents.subscribeToTradeEvents(
    BrokerSseDateOptions.empty(),
    new BrokerSseEventListener<TradeUpdateEventV2>() {
        @Override public void onEvent(TradeUpdateEventV2 event) {
            System.out.println(event);
        }
    });

// later
subscription.close();
```

## HTTP clients and retries

Use `AlpacaHttpConfig.defaultClient()` unless the application needs custom timeouts, proxies, logging, or retry policy. Application-level retries are opt-in:

```java
import markets.alpaca.client.http.AlpacaHttpConfig;
import markets.alpaca.client.http.AlpacaRetryPolicy;

var retryingHttp = AlpacaHttpConfig.retryingClient(
    AlpacaRetryPolicy.defaultPolicy());

var clientWithRetries = AlpacaClient.builder(tradingCredentials)
    .dataHttpClient(retryingHttp)
    .build();
```

Default retry methods are idempotent only: `GET`, `HEAD`, `OPTIONS`, and `TRACE`. Be careful retrying order placement, account creation, transfers, or other state-changing requests.

## Package map

- `markets.alpaca.client` — `AlpacaClient`, `AlpacaClientFactory`, credentials, environments.
- `markets.alpaca.client.trading` — handwritten Trading helpers.
- `markets.alpaca.client.data` — handwritten Market Data helpers.
- `markets.alpaca.client.rest` — futures, pagination, response metadata, rate-limit helpers.
- `markets.alpaca.client.http` — OkHttp defaults, logging, retry helpers.
- `markets.alpaca.client.broker.sse` — Broker Events SSE wrapper.
- `markets.alpaca.client.ws` — WebSocket streams, listeners, subscriptions, stream models.
- `markets.alpaca.client.openapi.broker` — generated Broker REST API, models, HTTP client.
- `markets.alpaca.client.openapi.data` — generated Market Data REST API, models, HTTP client.
- `markets.alpaca.client.openapi.trading` — generated Trading REST API, models, HTTP client.

## Documentation lookup

When exact signatures are needed, generate and inspect Javadocs:

```bash
./gradlew generateJavadocs
```

Start at `build/docs/javadoc/index.html`. The generated `openapi.*` packages include OAS-derived package, class, method, model, property, and response details.

## Avoid these mistakes

- Do not create generated `ApiClient` instances with `new ApiClient()` in application code.
- Do not use Broker credentials for Trading or Market Data unless the user explicitly says the same key pair is valid.
- Do not use production Trading or Broker environments unless explicitly requested.
- Do not block inside WebSocket or SSE callbacks.
- Do not retry non-idempotent operations unless the workflow is explicitly idempotent.
- Do not edit generated sources under `build/generated`.
