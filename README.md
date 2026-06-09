# alpaca-java-client

[![Java 17+](https://img.shields.io/badge/Java-17%2B-007396?logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/17/)
[![License](https://img.shields.io/badge/License-Apache--2.0-blue.svg)](LICENSE)

Java client for [Alpaca Markets](https://alpaca.markets) APIs.

REST clients are generated at build time from Alpaca OpenAPI specs. WebSocket stream clients are handwritten and committed in `src/main/java/markets/alpaca/client/ws/`.

Covers three REST APIs:

| API             | Auth                    | Base URL                                    |
|-----------------|-------------------------|---------------------------------------------|
| **Broker**      | HTTP Basic (key:secret) | `https://broker-api.sandbox.alpaca.markets` |
| **Market Data** | Header key pair         | `https://data.alpaca.markets`               |
| **Trading**     | Header key pair         | `https://paper-api.alpaca.markets`          |

Also includes WebSocket clients for stock data, crypto data, news, and trading updates.
WebSocket price and fractional size fields use `BigDecimal` to avoid floating-point precision loss.
Broker Events SSE endpoints are exposed through a handwritten OkHttp SSE wrapper so live streams do not use the blocking generated REST methods.

## Requirements

- Java 17+
- No OAS spec files required — by default the build fetches the official Alpaca public specs from `docs.alpaca.markets`.

## Getting started

Generated sources are not committed — they are produced at build time from the OAS specs.

The simplest setup requires no configuration at all:

```bash
./gradlew build    # fetches the official Alpaca specs automatically
```

To use local spec files instead (e.g. when working against the private `alpaca-docs-private` repo), copy `local.properties.example` to `local.properties` (gitignored) and configure the paths once:

```bash
cp local.properties.example local.properties
# edit local.properties: uncomment oasRoot= or individual brokerSpec= / dataSpec= / tradingSpec=
./gradlew build
```

## IDE setup

Generated sources are not checked in. Before opening the project in IntelliJ IDEA or VS Code, run:

```bash
./gradlew generateApis
```

Until you run this, the IDE will show unresolved-import errors for all `broker`, `data`, and `trading` classes. IntelliJ's Gradle plugin picks up the generated source roots automatically once the task has run.

## Javadoc

Generate API documentation, including the REST clients produced from the configured OpenAPI specs:

```bash
./gradlew generateJavadocs
```

The HTML output is written to `build/docs/javadoc/index.html`.

## AI usage guide

If you are using an LLM or coding bot to write application code with this SDK, start with
[`LLMS.md`](LLMS.md). It is a compact usage guide for choosing between `AlpacaClient`, handwritten
helpers, generated REST clients, WebSocket streams, SSE clients, pagination, and async adapters.

## Usage

```java
import markets.alpaca.client.AlpacaClient;
import markets.alpaca.client.AlpacaCredentials;
import markets.alpaca.client.BrokerApiEnvironment;
import markets.alpaca.client.trading.ListOrdersRequest;
import markets.alpaca.client.data.StockTradesRequest;
import markets.alpaca.client.TradingApiEnvironment;
import markets.alpaca.client.openapi.data.model.StockHistoricalFeed;

var tradingCredentials = AlpacaCredentials.fromTradingApiEnvironmentVariables();
var brokerCredentials = AlpacaCredentials.fromBrokerApiEnvironmentVariables();

var client = AlpacaClient.builder(tradingCredentials)
    .tradingEnvironment(TradingApiEnvironment.PRODUCTION)
    .brokerEnvironment(BrokerApiEnvironment.SANDBOX)
    .brokerCredentials(brokerCredentials)
    .build();

// --- Trading API ---
var openOrders = client.orders().list(ListOrdersRequest.builder()
    .status(ListOrdersRequest.Status.OPEN)
    .symbols("AAPL")
    .limit(50)
    .build());

// --- Market Data API ---
var trades = client.stocks().trades(StockTradesRequest.builder()
    .symbols("AAPL", "MSFT")
    .feed(StockHistoricalFeed.IEX)
    .limit(100)
    .build());

// --- Broker API ---
var broker = client.newBrokerClient();  // fresh mutable generated client for escape-hatch APIs
var accounts = new markets.alpaca.client.openapi.broker.api.AccountsApi(broker);

// Generated APIs remain available for endpoints without handwritten helpers.
var trading = client.newTradingClient();
var data = client.newDataClient();
```

### Credentials

`AlpacaClient.builder(credentials)` uses the supplied `AlpacaCredentials` pair for Trading,
Market Data, and Broker clients by default. Override any API explicitly when credentials differ:

```java
var dataCredentials = AlpacaCredentials.fromEnvironmentVariables(
    "APCA_DATA_KEY_ID",
    "APCA_DATA_SECRET_KEY");

var client = AlpacaClient.builder(tradingCredentials)
    .dataCredentials(dataCredentials)
    .brokerCredentials(brokerCredentials)
    .build();
```

`AlpacaCredentials.fromTradingApiEnvironmentVariables()` reads from `APCA_TRADING_KEY_ID` and
`APCA_TRADING_SECRET_KEY`. `AlpacaCredentials.fromBrokerApiEnvironmentVariables()` reads from
`APCA_BROKER_KEY_ID` and `APCA_BROKER_SECRET_KEY`.

If your application uses different environment variable names, call
`AlpacaCredentials.fromEnvironmentVariables("MY_KEY_ID", "MY_SECRET_KEY")`.

### CompletableFuture REST calls

Generated REST APIs expose callback-based async methods. Use `AlpacaFutures` to adapt them to `CompletableFuture`:

```java
import markets.alpaca.client.rest.AlpacaFutures;

var accountsApi = new markets.alpaca.client.openapi.trading.api.AccountsApi(trading);

var accountFuture = AlpacaFutures.trading(callback -> accountsApi.getAccountAsync(callback));
accountFuture.thenAccept(account -> System.out.println(account.getAccountNumber()));

var responseFuture = AlpacaFutures.tradingResponse(callback -> accountsApi.getAccountAsync(callback));
responseFuture.thenAccept(response -> {
    System.out.println(response.statusCode());
    System.out.println(response.body());
});
```

### Pagination helpers

Generated REST APIs expose `*WithHttpInfo(...)` methods for status codes and headers. Use `AlpacaPagination` to keep `next_page_token` loops and rate-limit header parsing in one place:

```java
import markets.alpaca.client.rest.AlpacaPagination;
import markets.alpaca.client.openapi.data.model.NewsResp;

var newsApi = new markets.alpaca.client.openapi.data.api.NewsApi(data);

var allNews = AlpacaPagination.collectDataItems(
    pageToken -> newsApi.newsWithHttpInfo(
        null, null, null, "AAPL", 50, false, null, pageToken),
    NewsResp::getNextPageToken,
    NewsResp::getNews
);

var pages = AlpacaPagination.collectDataPages(
    pageToken -> newsApi.newsWithHttpInfo(
        null, null, null, "AAPL", 50, false, null, pageToken),
    NewsResp::getNextPageToken
);

pages.forEach(page -> page.rateLimit().remaining().ifPresent(System.out::println));
```

### WebSocket streams

```java
import markets.alpaca.client.ws.AlpacaStreamEnvironment;
import markets.alpaca.client.ws.AlpacaStreamAuthResult;
import markets.alpaca.client.ws.StockSource;
import markets.alpaca.client.ws.StockStreamListener;
import markets.alpaca.client.ws.StockSubscription;
import markets.alpaca.client.ws.model.StockTrade;

var stream = AlpacaClientFactory.stockStream(
    creds,
    StockSource.IEX,
    AlpacaStreamEnvironment.PRODUCTION,
    new StockStreamListener() {
        @Override public void onTrade(StockTrade trade) {
            System.out.println(trade);
        }
    }
);

stream.connect(StockSubscription.builder().trades("AAPL").build());

AlpacaStreamAuthResult auth =
    stream.waitForAuthenticationResult(java.time.Duration.ofSeconds(5));
if (!auth.isAuthenticated()) {
    throw new IllegalStateException(
        "stock stream did not authenticate: " + auth.status()
            + " code=" + auth.code()
            + " message=" + auth.message());
}
```

### Broker Events SSE

```java
import markets.alpaca.client.openapi.broker.model.TradeUpdateEventV2
import markets.alpaca.client.broker.sse.BrokerSseDateOptions;
import markets.alpaca.client.broker.sse.BrokerSseEventListener;

var brokerEvents = AlpacaClientFactory.brokerEventsSseClient(broker);
var subscription = brokerEvents.subscribeToTradeEvents(
    BrokerSseDateOptions.empty(),
    new BrokerSseEventListener<TradeUpdateEventV2>() {
        @Override public void onEvent(TradeUpdateEventV2 event) {
            System.out.println(event);
        }
    }
);

// later:
subscription.close();
```

### HTTP clients and resiliency

```java
import markets.alpaca.client.http.AlpacaHttpConfig;
import okhttp3.logging.HttpLoggingInterceptor;

var httpClient = AlpacaHttpConfig.loggingClient(HttpLoggingInterceptor.Level.BASIC);
var client = AlpacaClientFactory.client(creds, httpClient);
```

`AlpacaHttpConfig.defaultClient()` returns a shared OkHttp singleton, which is safe and recommended for most applications. OkHttp clients own connection pools and worker threads, so sharing one instance gives REST, WebSocket, and SSE calls connection reuse without creating extra pools.

Use the `AlpacaClient` builder when you need different behavior per API:

```java
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import markets.alpaca.client.AlpacaClient;
import okhttp3.ConnectionPool;

var tradingHttp = AlpacaHttpConfig.defaultBuilder()
    .readTimeout(Duration.ofSeconds(20))
    .build();

var dataHttp = AlpacaHttpConfig.defaultBuilder()
    .readTimeout(Duration.ofSeconds(10))
    .connectionPool(new ConnectionPool(20, 5, TimeUnit.MINUTES))
    .build();

var brokerHttp = AlpacaHttpConfig.defaultBuilder()
    .connectTimeout(Duration.ofSeconds(5))
    .build();

var client = AlpacaClient.builder(creds)
    .tradingHttpClient(tradingHttp)
    .dataHttpClient(dataHttp)
    .brokerHttpClient(brokerHttp)
    .build();

var trading = client.newTradingClient();
var data    = client.newDataClient();
var broker  = client.newBrokerClient();

var stockStream = AlpacaClientFactory.stockStream(
    creds,
    markets.alpaca.client.ws.StockSource.IEX,
    markets.alpaca.client.ws.AlpacaStreamEnvironment.PRODUCTION,
    new markets.alpaca.client.ws.StockStreamListener() {},
    dataHttp
);
```

For proxy, private-network, or test harness setups, configure base URLs before clients are built:

```java
var client = AlpacaClient.builder(creds)
    .tradingBaseUrl("https://trading-proxy.example")
    .dataBaseUrl("https://data-proxy.example")
    .brokerBaseUrl("https://broker-proxy.example")
    .build();

var stockStream = AlpacaClientFactory.stockStream(
    creds,
    markets.alpaca.client.ws.StockSource.IEX,
    "wss://stream-proxy.example",
    new markets.alpaca.client.ws.StockStreamListener() {}
);
```

OkHttp's built-in `retryOnConnectionFailure` is enabled by default and handles some transport-level failures, such as stale pooled connections. It does not retry application responses like HTTP `429` or `5xx`. For opt-in application-level retries, use `AlpacaRetryInterceptor`:

```java
import java.time.Duration;
import markets.alpaca.client.http.AlpacaRetryEvent;
import markets.alpaca.client.http.AlpacaRetryListener;
import markets.alpaca.client.http.AlpacaRetryPolicy;

var retryPolicy = AlpacaRetryPolicy.builder()
    .maxAttempts(3)
    .initialDelay(Duration.ofMillis(250))
    .maxDelay(Duration.ofSeconds(5))
    .jitterRatio(0.2)
    .listener(new AlpacaRetryListener() {
        @Override
        public void onRetry(AlpacaRetryEvent event) {
            metrics.increment("alpaca.http.retry");
        }

        @Override
        public void onGiveUp(AlpacaRetryEvent event) {
            metrics.increment("alpaca.http.retry.exhausted");
        }
    })
    .build();

var retryingHttp = AlpacaHttpConfig.retryingClient(retryPolicy);

var client = AlpacaClient.builder(creds)
    .dataHttpClient(retryingHttp)
    .build();
```

Keep retry policies conservative:

- `AlpacaRetryInterceptor` retries only idempotent methods by default: `GET`, `HEAD`, `OPTIONS`, and `TRACE`.
- The default retryable statuses are `408`, `425`, `429`, `500`, `502`, `503`, and `504`.
- `Retry-After` is honored when present, including both seconds and HTTP-date values. Otherwise, exponential backoff with jitter is used to avoid retry bursts.
- Be careful retrying order placement, account creation, transfers, or other state-changing operations unless your workflow is explicitly idempotent.
- Set finite connect, read, and write timeouts. For Broker Events SSE, the wrapper uses an infinite read timeout internally because live event streams are intentionally long-lived.
- Use separate `OkHttpClient` instances when APIs have materially different latency, timeout, proxy, certificate, logging, or connection-pool requirements. Otherwise, prefer the shared singleton.
- Add request correlation and metrics in interceptors rather than mutating generated `ApiClient` instances while requests are in flight.
- Close WebSocket streams and SSE subscriptions when no longer needed. WebSocket streams automatically reconnect with jittered exponential backoff after unexpected disconnects and restore subscriptions/listens after authentication. Use `AlpacaStreamReconnectPolicy.builder().jitterRatio(0)` when deterministic reconnect timing is required.

### Thread safety

The generated REST `ApiClient` instances are mutable: base path, default headers/cookies, authentication objects, JSON settings, and the underlying HTTP client can all be changed after construction. Configure each generated client before sharing it between threads, and avoid mutating it while requests are in flight. Create one configured `ApiClient` per API/environment/credential set, then share that instance read-only.

Prefer `AlpacaClient` for application-level use. It keeps the generated clients behind narrower workflow facades and returns a fresh generated client only when you explicitly call an escape-hatch method such as `newTradingClient()`.

WebSocket and SSE callbacks are invoked on OkHttp-managed threads. Keep callbacks non-blocking; dispatch expensive work to your own executor.

## Package structure

```
markets.alpaca.client
├── AlpacaClient             ← immutable facade for common workflows
├── AlpacaClientFactory      ← entry point — use this to create API clients
├── AlpacaCredentials        ← holds API key ID + secret
├── broker/sse/              ← handwritten Broker Events SSE wrapper
├── data/                    ← handwritten Market Data helpers
├── http/                    ← handwritten OkHttp/retry helpers
├── rest/                    ← handwritten REST utilities
├── trading/                 ← handwritten Trading helpers
├── ws/                      ← handwritten WebSocket stream clients and models
└── openapi/                 ← generated at build time
    ├── broker/{api,model,http}
    ├── data/{api,model,http}
    └── trading/{api,model,http}
```

> Generated code lives in `build/` (gitignored), is produced at build time, and uses
> `markets.alpaca.client.openapi/**`. Handwritten code is committed under
> `src/main/java/markets/alpaca/client/` outside the `openapi` namespace.

## Regenerating after spec changes

```bash
./gradlew generateApis          # regenerate only
./gradlew build                 # regenerate + compile
./gradlew compileExamples       # compile examples only
./gradlew --parallel build      # same, generators run in parallel
./gradlew printSpecVersions     # show version and last-modified for each spec
```

### OAS spec sources

By default the build fetches the official Alpaca public specs:

| Spec        | Default URL                                                |
|-------------|------------------------------------------------------------|
| Broker      | `https://docs.alpaca.markets/openapi/broker-api.json`      |
| Market Data | `https://docs.alpaca.markets/openapi/market-data-api.json` |
| Trading     | `https://docs.alpaca.markets/openapi/trading-api.json`     |

To override, each spec accepts a local file path or any `http`/`https` URL. Resolution order per spec (first match wins):

1. **Individual Gradle property**: `-PbrokerSpec=<path|url>`, `-PdataSpec=<path|url>`, `-PtradingSpec=<path|url>`
2. **Individual environment variable**: `APCA_BROKER_SPEC`, `APCA_DATA_SPEC`, `APCA_TRADING_SPEC`
3. **`local.properties`** individual key: `brokerSpec=`, `dataSpec=`, `tradingSpec=`
4. **Legacy `oasRoot`** (all three specs from one directory): `-PoasRoot=`, `APCA_OAS_ROOT=`, or `oasRoot=` in `local.properties`
5. **Default public URL** (see table above)

For local development with the private `alpaca-docs-private` repo, `local.properties` is the least friction:

```properties
# local.properties — point all three specs at the local oas/ directory
oasRoot=/path/to/alpaca-docs-private/oas
```

For CI without a local repo, no configuration is needed — the public URLs are the default.

## Testing

### Code checks

Run the full local verification gate before opening a PR:

```bash
./gradlew check
```

`check` runs unit tests, compiles the examples project, and executes the code-quality tools:

```bash
./gradlew spotlessCheck                 # formatting and import order
./gradlew checkstyleMain checkstyleTest # source style checks
./gradlew spotbugsMain                  # static bug analysis for handwritten classes
```

To apply the formatter locally:

```bash
./gradlew spotlessApply
```

The checks intentionally target committed handwritten sources and exclude generated OpenAPI output under `build/generated/`.

### Unit tests

```bash
./gradlew test
```

No credentials or network access required. Covers the REST helper classes (`AlpacaClientFactory`, `AlpacaCredentials`, `AlpacaHttpConfig`), Broker Events SSE wrapper, handwritten REST utilities, handwritten WebSocket streams, subscription objects, and WebSocket model deserialization with MockWebServer.

The root `check` and `build` tasks also compile the separate `examples/` included build. Examples are not packaged into the published library artifact, but compilation keeps them in sync with generated and handwritten APIs.

### Integration tests

```bash
./gradlew integrationTest
```

Calls the live Alpaca paper-trading, market-data, WebSocket, and Broker sandbox APIs. Tests skip automatically when credentials are absent — they never cause a build failure without them. Integration tests mirror the read-only workflows in `examples/`; state-changing example paths remain opt-in and are not run automatically.

**Credentials — pick one source:**

Option 1: `local.properties` (gitignored, recommended for local dev):
```properties
tradingApiKeyId=PKxxxxxxxxxxxxxxxxxx
tradingApiSecretKey=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
tradingApiEnvironment=paper
brokerApiKeyId=xxxxxxxxxxxxxxxxxx
brokerApiSecretKey=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
brokerApiEnvironment=sandbox
```

Option 2: environment variables (recommended for CI):
```bash
export APCA_TRADING_KEY_ID=PKxxxxxxxxxxxxxxxxxx
export APCA_TRADING_SECRET_KEY=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
export APCA_TRADING_ENVIRONMENT=paper
export APCA_BROKER_KEY_ID=xxxxxxxxxxxxxxxxxx
export APCA_BROKER_SECRET_KEY=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
export APCA_BROKER_ENVIRONMENT=sandbox
```

Paper API keys are available from [app.alpaca.markets](https://app.alpaca.markets) → Paper Trading → API Keys. Broker sandbox credentials are required only for Broker integration tests. All integration tests are read-only — no orders are placed, accounts created, or transfers initiated.

## How spec fixes work

The OAS specs contain minor issues that produce uncompilable Java. Rather than modifying the original specs, `build.gradle` applies fixes automatically before each generation run:

| Issue                                                                  | Spec            | Fix applied                           |
|------------------------------------------------------------------------|-----------------|---------------------------------------|
| Property with empty-string key `''`                                    | Broker          | `removeEmptyKeyProperties()`          |
| Discriminator property has `enum` → type mismatch                      | Broker          | `removeDiscriminatorEnums()`          |
| `ActivityV2DetailTRD.required` emits false-positive generator warnings | Broker, Trading | `removeActivityV2DetailTrdRequired()` |
| Schema marked `x-internal: true` → missing class                       | Data            | `removeInternalSchemaMarkers()`       |

If new spec issues appear after an upstream update, add fixes to the corresponding preprocess task in `build.gradle`.

## Dependencies

| Library                                  | Version | Purpose                              |
|------------------------------------------|---------|--------------------------------------|
| `com.squareup.okhttp3:okhttp`            | 5.4.0   | HTTP transport and WebSocket support |
| `com.squareup.okhttp3:okhttp-sse`        | 5.4.0   | Broker Events SSE support            |
| `com.google.code.gson:gson`              | 2.14.0  | JSON serialisation                   |
| `io.gsonfire:gson-fire`                  | 1.9.0   | Gson type adapters                   |
| `io.swagger.core.v3:swagger-annotations` | 2.2.49  | Model annotations (compile-only)     |
| `org.apache.commons:commons-lang3`       | 3.20.0  | Utilities                            |
