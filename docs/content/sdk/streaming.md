---
id: streaming
title: Streaming & Events
---

The Java SDK includes handwritten streaming clients for stock market data, crypto market data,
news, trading updates, Market Data corporate-actions SSE, Trading account-activity SSE, and Broker
Events SSE.

Use the domain pages for REST workflows and the first streaming example for that API area:
[Market Data](./market-data), [Trading](./trading), and [Broker](./broker). Use this page for the
shared streaming model: listener callbacks, authentication confirmation, subscription confirmation,
reconnect behavior, callback executors, and the differences between WebSocket and SSE streams.

## Stream types

| Area | Client | Protocol | Notes |
|---|---|---|---|
| Market Data | `AlpacaStockStream` | WebSocket | Stock trades, quotes, bars, statuses, corrections, and cancel/error events |
| Market Data | `AlpacaCryptoStream` | WebSocket | Crypto trades, quotes, bars, and orderbooks |
| Market Data | `AlpacaNewsStream` | WebSocket | Real-time news articles |
| Market Data | `CorporateActionsSseClient` | Server-Sent Events | Typed corporate-action mutations, filters, history cursors, and live updates |
| Trading | `AlpacaTradingStream` | WebSocket | Order lifecycle updates for the authenticated account |
| Trading | `TradingEventsSseClient` | Server-Sent Events | Typed account activities, historical cursors, and live updates |
| Broker | `BrokerEventsSseClient` | Server-Sent Events | Broker account, trade, funding, journal, system, and related event streams |

Option live streaming is not currently exposed by this SDK. Option REST data is available from the
generated `OptionApi`.

## Callback model

All listener interfaces provide empty default methods. Override only the events your application
needs.

WebSocket callbacks run on OkHttp's reader thread by default. SSE callbacks are serialized per
subscription, and stream parsing waits for each callback to complete to provide backpressure. The
default SSE executor runs callbacks directly: event delivery runs on the transport thread, while
opening and terminal delivery that is not already queued behind an active callback starts through
separate bounded SDK dispatchers. The opening callback is admitted before `opened()` continuations
run, so a continuation may close the subscription without preventing ordered `onOpen` and
`onClosed` delivery. If a handler writes to a database, calls a network service, or performs blocking
work, use a factory overload that accepts an application-owned `Executor`.

Transport lifecycle does not wait for user callbacks: `close()` cancels transport work and waits
for lifecycle completion to settle before returning, while the serialized terminal listener
callback runs after callbacks already admitted. If a failure wins a concurrent terminal transition,
`close()` observes its settled exceptional completion without rethrowing it. Lifecycle timers also
remain independent of callback execution. Event callbacks backpressure response parsing, however,
so a blocked callback can delay detection of remote EOF and the following reconnect. Sharing one
single-thread executor across subscriptions intentionally serializes their callbacks.
Terminal listener delivery is admitted before lifecycle futures notify synchronous continuations,
but invocation waits until lifecycle completion is settled. Closing a subscription whose opening
callback is waiting in the bounded dispatcher releases its HTTP response thread; already-admitted
listener callbacks remain ordered and may complete later. Lifecycle completion is settled away from
the active callback path, so a callback may close the subscription even when a synchronous
completion continuation waits for the ordered terminal listener.

The SDK opening and terminal dispatchers have finite workers and queue capacity so blocked listeners
cannot create unbounded threads. Lifecycle completion also uses a fixed-size dispatcher; recursive
completion from one of its continuations is settled inline to avoid pool starvation. Keep
synchronous future continuations short, or use an async continuation with an application-owned
executor for blocking work. Opening-dispatch rejection fails the subscription with an
`AlpacaSseCallbackException`. Under terminal-dispatch saturation, lifecycle completion remains
authoritative, the pending terminal listener callback is rejected, and the SDK logs a warning.

The SSE transport retains the supplied OkHttp client's interceptors, proxy, TLS, dispatcher, and
connection pool, but disables inherited read and whole-call timeouts because they would terminate
healthy long-lived responses. Use `AlpacaSseOptions` for connection, idle, reconnect-cycle, and
whole-subscription deadlines.

If a listener throws a `RuntimeException`, the SDK logs it and considers the event delivered, so
its resume ID advances. A callback-executor rejection is terminal. Applications should handle
their own callback failures, persist work transactionally, and deduplicate by SSE event ID.

SSE subscriptions return immediately while the HTTP connection opens. Use `opened()` with a
caller-chosen timeout instead of building a startup latch; it yields immutable response URI, status,
and header metadata. `connection()` reports the most recently accepted connection and changes after
reconnects, while `opened()` always retains the initial connection.
Once response headers are accepted, `opened()` completes successfully and the ordered `onOpen`
callback remains ahead of a concurrent terminal callback.

```java
var connection = subscription.opened().get(10, TimeUnit.SECONDS);
System.out.printf("%d %s%n", connection.statusCode(), connection.uri());
```

Configure transport options without supplying an executor, or configure both through the top-level
client builder:

```java
var events = AlpacaClientFactory.tradingEventsSseClient(tradingClient, sseOptions);

var client = AlpacaClient.builder(credentials)
    .tradingSseOptions(sseOptions)
    .tradingSseCallbackExecutor(callbackExecutor)
    .dataSseOptions(sseOptions)
    .dataSseCallbackExecutor(callbackExecutor)
    .brokerSseOptions(brokerOptions)
    .brokerSseCallbackExecutor(callbackExecutor)
    .build();
```

The SDK never shuts down an application-owned callback executor.

## Stock data stream

Stock streams support trades, quotes, minute bars, daily bars, updated bars, trading statuses, and
LULD updates. Subscribing to trades also enables trade corrections and cancel/error events for the
same symbols.

```java
import markets.alpaca.client.AlpacaClientFactory;
import markets.alpaca.client.AlpacaCredentials;
import markets.alpaca.client.ws.AlpacaStreamEnvironment;
import markets.alpaca.client.ws.StockSource;
import markets.alpaca.client.ws.StockStreamListener;
import markets.alpaca.client.ws.StockSubscription;
import markets.alpaca.client.ws.model.StockTrade;

var credentials = AlpacaCredentials.fromTradingApiEnvironmentVariables();

var stockStream = AlpacaClientFactory.stockStream(
    credentials,
    StockSource.IEX,
    AlpacaStreamEnvironment.PRODUCTION,
    new StockStreamListener() {
        @Override
        public void onTrade(StockTrade trade) {
            System.out.println(trade);
        }

        @Override
        public void onError(int code, String message) {
            System.err.printf("stock stream error %d: %s%n", code, message);
        }
    });

stockStream.connect(StockSubscription.builder()
    .trades("AAPL")
    .quotes("AAPL")
    .build());
```

Use `StockSource.IEX` or `StockSource.SIP` according to your data entitlement.

## Crypto data stream

Crypto streams are available in the production stream environment and support trades, quotes, bars,
updated bars, daily bars, and orderbooks.

```java
import markets.alpaca.client.ws.AlpacaStreamEnvironment;
import markets.alpaca.client.ws.CryptoStreamListener;
import markets.alpaca.client.ws.CryptoSubscription;
import markets.alpaca.client.ws.model.CryptoOrderbook;

var cryptoStream = AlpacaClientFactory.cryptoStream(
    credentials,
    AlpacaStreamEnvironment.PRODUCTION,
    new CryptoStreamListener() {
        @Override
        public void onOrderbook(CryptoOrderbook orderbook) {
            System.out.println(orderbook);
        }
    });

cryptoStream.connect(CryptoSubscription.builder()
    .trades("BTC/USD")
    .quotes("BTC/USD")
    .orderbooks("BTC/USD")
    .build());
```

## News stream

Use `NewsSubscription` to subscribe to one or more symbols, or `"*"` for all available news.

```java
import markets.alpaca.client.ws.NewsStreamListener;
import markets.alpaca.client.ws.NewsSubscription;
import markets.alpaca.client.ws.model.NewsArticle;

var newsStream = AlpacaClientFactory.newsStream(
    credentials,
    AlpacaStreamEnvironment.PRODUCTION,
    new NewsStreamListener() {
        @Override
        public void onArticle(NewsArticle article) {
            System.out.println(article.headline());
        }
    });

newsStream.connect(NewsSubscription.builder().symbols("AAPL", "TSLA").build());
```

## Trading updates stream

The trading stream sends order lifecycle events for the authenticated account. Use the paper
stream with paper keys and the production stream with live keys.

```java
import markets.alpaca.client.ws.TradingEnvironment;
import markets.alpaca.client.ws.TradingStreamListener;
import markets.alpaca.client.ws.TradingSubscription;
import markets.alpaca.client.ws.model.TradeUpdate;

var tradingStream = AlpacaClientFactory.tradingStream(
    credentials,
    TradingEnvironment.PAPER,
    new TradingStreamListener() {
        @Override
        public void onTradeUpdate(TradeUpdate update) {
            System.out.println(update);
        }
    });

tradingStream.connect(TradingSubscription.TRADE_UPDATES);
```

## Authentication and subscription confirmation

Streams authenticate after the socket opens. You can either react to listener callbacks or block for
a short, caller-controlled timeout during startup.

```java
import java.time.Duration;

stockStream.connect(StockSubscription.builder().quotes("AAPL").build());

var auth = stockStream.waitForAuthenticationResult(Duration.ofSeconds(10));
if (!auth.isAuthenticated()) {
    throw new IllegalStateException(
        "stream authentication failed: " + auth.status()
            + " code=" + auth.code()
            + " message=" + auth.message());
}
```

Market data streams fire `onSubscriptionConfirmed(...)` after subscribe or unsubscribe requests.
Trading streams fire `onListening(...)` with the active stream names.

## Reconnect behavior

WebSocket clients reconnect automatically after unexpected disconnects using jittered exponential
backoff. The default policy tries 10 times, starts at 1 second, caps delay at 64 seconds, and adds
20% jitter.

```java
import java.time.Duration;
import markets.alpaca.client.ws.AlpacaStreamReconnectPolicy;

var reconnectPolicy = AlpacaStreamReconnectPolicy.builder()
    .initialBackoff(Duration.ofSeconds(2))
    .maxBackoff(Duration.ofSeconds(30))
    .maxAttempts(20)
    .build();

var resilientStream = AlpacaClientFactory.stockStream(
    credentials,
    StockSource.IEX,
    AlpacaStreamEnvironment.PRODUCTION,
    new StockStreamListener() {},
    reconnectPolicy);
```

Use `AlpacaStreamReconnectPolicy.disabled()` when an application-level supervisor should own
reconnect decisions.

## Market Data corporate-actions SSE

Corporate-action mutations use Server-Sent Events. Create the handwritten client through the
factory; the generated `subscribeToCorporateActionsEventsSSE` method buffers the response and is
not suitable for a live stream.

```java
import markets.alpaca.client.data.sse.CorporateActionsSseRequest;
import markets.alpaca.client.data.sse.MarketDataSseEnvironment;
import markets.alpaca.client.openapi.data.model.CorporateActionEvent;
import markets.alpaca.client.openapi.data.model.CorporateActionEventCashDividend;
import markets.alpaca.client.openapi.data.model.CorporateActionEventType;
import markets.alpaca.client.sse.AlpacaSseEvent;
import markets.alpaca.client.sse.AlpacaSseListener;

var corporateActions = AlpacaClientFactory.corporateActionsSseClient(
    dataClient, MarketDataSseEnvironment.PRODUCTION);

var request = CorporateActionsSseRequest.builder()
    .eventTypes(CorporateActionEventType.CASH_DIVIDEND_CORPORATEACTION_EVENT)
    .region(CorporateActionsSseRequest.Region.US)
    .build();

var subscription = corporateActions.subscribeToCorporateActions(
    request,
    new AlpacaSseListener<>() {
        @Override
        public void onEvent(AlpacaSseEvent<CorporateActionEvent> event) {
            if (event.data().getActualInstance()
                    instanceof CorporateActionEventCashDividend dividend) {
                System.out.printf("%s %s%n", event.id(), dividend.getCa().getSymbol());
            }
        }
    });
```

Select `PRODUCTION`, `SANDBOX`, or an explicit `MarketDataSseEnvironment.custom(...)` endpoint.
This stream endpoint is independent from the generated Market Data REST base URL, so selecting a
REST environment does not implicitly select the SSE environment.

Use `CorporateActionsSseRequest.fromEventId(...)` or `throughEventId(...)` for replay and bounded
history. Corporate-action event IDs are validated uppercase ULIDs. The endpoint defines inclusive
resume semantics, so reconnects can redeliver the cursor event; deduplicate by event ID when
effects must be exactly once. Unknown `event_type` values and payloads that do not validate against
their generated concrete schema fail the subscription instead of being guessed.

## Trading account-activity SSE

Account activities use Server-Sent Events and are separate from WebSocket `trade_updates`.
Create the handwritten client through the factory; generated `subscribeToActivitiesSSE` methods
buffer the response and are not suitable for a live stream.

```java
import markets.alpaca.client.sse.AlpacaSseEvent;
import markets.alpaca.client.sse.AlpacaSseListener;
import markets.alpaca.client.openapi.trading.model.ActivityEventV2;
import markets.alpaca.client.trading.sse.TradingEventsSseClient;

TradingEventsSseClient events =
    AlpacaClientFactory.tradingEventsSseClient(tradingClient);

var subscription = events.subscribeToActivities(
    new AlpacaSseListener<>() {
        @Override
        public void onEvent(AlpacaSseEvent<ActivityEventV2> event) {
            System.out.printf("%s %s%n", event.id(), event.data().getActivityType());
        }
    });

// Later:
subscription.close();
```

For a bounded historical range, pass a validated cursor request:

```java
import markets.alpaca.client.trading.sse.TradingActivitySseRequest;

var range = TradingActivitySseRequest.throughEventId(
    "01K6F000000000000000000000",
    "01K6G000000000000000000000");

var historical = events.subscribeToActivities(range, new AlpacaSseListener<>() {});
```

Live Trading SSE reconnects on transient failures by default. It follows the SSE protocol by
persisting completed `id:` fields (including data-less ID blocks) and sending the cursor as the
documented `since_id` query parameter and in `Last-Event-ID` on the replacement request. Resume
state advances after a payload is successfully decoded and dispatched, or when a completed
data-less `id:` block is observed. An empty `id:` resets an unbounded stream's cursor, so reconnect
does not restore the request's original `since` or `since_id`. Malformed Trading payloads do not
advance it. Date-bounded
requests retain their original date range on retry and can replay already processed events. Server
replay can be inclusive, so applications requiring exactly-once effects must deduplicate persisted
work by event ID. Because the Trading API requires `since_id` with `until_id`, an interrupted
ID-bounded stream fails closed if an empty `id:` removed its resume cursor. `until` or `untilId`
makes a request bounded;
normal EOF then completes the subscription instead of reconnecting. Configure retry budgets,
initial resume ID, idle timeout, and resource limits with `AlpacaSseOptions` factory overloads.
`AlpacaSseReconnectPolicy.maxElapsedTime(...)` bounds one initial-open or established reconnect
cycle. An established cycle remains bounded through backoff, reconnect headers, comments, and
silence until an event is delivered; `AlpacaSseOptions.maxDuration(...)` separately bounds the
lifetime of the whole subscription. `maxBackoff(...)` caps client exponential backoff, server SSE
`retry:` values, and HTTP `Retry-After` values. The connection timeout covers successful
response-header validation and bounded non-success response-body capture, so a server cannot keep
a subscription opening indefinitely by stalling an error body.

Malformed Trading activity payloads fail the subscription. Activity detail models are selected by
the OAS type/subtype mapping. The two schemas whose OAS definitions currently lack discriminants
(fixed-income redemption and rights distribution) use a unique-most-specific structural fallback;
ties fail explicitly instead of being guessed. The same decoder behavior applies to Broker Activity
V2 events. Until the upstream contract provides a dedicated CSD detail schema, `CSD` events retain
their activity type but expose details through `CSWActivityV2`; additional payload fields remain
available through `getAdditionalProperties()`.

## Broker Events SSE

Broker Events are exposed through the Broker SSE client, not a WebSocket stream.

```java
import markets.alpaca.client.AlpacaClientFactory;
import markets.alpaca.client.broker.sse.BrokerSseDateOptions;
import markets.alpaca.client.broker.sse.BrokerSseEventListener;
import markets.alpaca.client.openapi.broker.model.TradeUpdateEventV2;

var brokerEvents = AlpacaClientFactory.brokerEventsSseClient(brokerClient);
var subscription = brokerEvents.subscribeToTradeEvents(
    BrokerSseDateOptions.empty(),
    new BrokerSseEventListener<TradeUpdateEventV2>() {
        @Override
        public void onEvent(TradeUpdateEventV2 event) {
            System.out.println(event);
        }
    });

subscription.close();
```

Existing Broker client constructors preserve the previous one-connection behavior. Use the
`AlpacaSseOptions` factory overload to opt into resilient reconnect behavior. Broker listener
callbacks are serialized and provide backpressure; use a dedicated executor for blocking work.
`BrokerSseSubscription` implements the shared `AlpacaSseSubscription` lifecycle contract, while
retaining Broker-specific compatibility methods.
For compatibility, a malformed Broker event invokes `onFailure` for that event and the healthy
connection continues. Its SSE ID becomes the `Last-Event-ID` transport cursor after the callback.
Admin-action streams retain the generated `SubscribeToAdminActionSSE200ResponseInner` listener
type, while the required `type` field selects its concrete generated model without relying on the
ambiguous generated `oneOf` adapter.
Broker endpoints document endpoint-specific `since_id`/`since_ulid` query cursors, so applications
that require gap replay should reconnect with the appropriate request option and deduplicate.
Protocol and non-retryable HTTP failures are terminal;
retryable HTTP and transport failures follow the configured reconnect policy.

New listeners can override `onEventFailure(AlpacaSseDeserializationException)` to distinguish one
malformed event from terminal failures and inspect its event ID/type. `onRetryChanged(Duration)`
reports server `retry:` fields, `onHttpFailure(...)` exposes structured HTTP failures, and
`onClosed(AlpacaSseCloseResult)` exposes the normal close reason. Defaults preserve the original
callback shapes: malformed events pass their original cause, HTTP failures pass a bounded
compatibility response with no throwable, and user cancellation passes
`IOException("canceled")` with no response. Rich overrides receive one callback instead.

Applications upgrading Broker SSE from `0.1.4` should follow the
[SSE migration guide](./sse-migration), especially when they use `eventSource()`, inspect raw
failure types, or block inside callbacks.

The deprecated `/v1/events/transfers/status` operation is inventoried but intentionally has no
handwritten wrapper. Use Broker funding-status events for supported transfer/funding updates.
