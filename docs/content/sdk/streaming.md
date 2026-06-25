---
id: streaming
title: Streaming & Events
---

The Java SDK includes handwritten streaming clients for stock market data, crypto market data,
news, trading updates, and Broker Events SSE. WebSocket clients use listener callbacks; they do not
return data from the `connect(...)` call.

Use the domain pages for REST workflows and the first streaming example for that API area:
[Market Data](./market-data), [Trading](./trading), and [Broker](./broker). Use this page for the
shared streaming model: listener callbacks, authentication confirmation, subscription confirmation,
reconnect behavior, callback executors, and the differences between WebSocket streams and Broker
Events SSE.

## Stream types

| Area | Client | Protocol | Notes |
|---|---|---|---|
| Market Data | `AlpacaStockStream` | WebSocket | Stock trades, quotes, bars, statuses, corrections, and cancel/error events |
| Market Data | `AlpacaCryptoStream` | WebSocket | Crypto trades, quotes, bars, and orderbooks |
| Market Data | `AlpacaNewsStream` | WebSocket | Real-time news articles |
| Trading | `AlpacaTradingStream` | WebSocket | Order lifecycle updates for the authenticated account |
| Broker | `BrokerEventsSseClient` | Server-Sent Events | Broker account, trade, funding, journal, system, and related event streams |

Option live streaming is not currently exposed by this SDK. Option REST data is available from the
generated `OptionApi`.

## Callback model

All WebSocket listener interfaces provide empty default methods. Override only the events your
application needs.

Callbacks run on OkHttp's reader thread by default. If your handler writes to a database, calls a
network service, or performs blocking work, use a factory overload that accepts an
application-owned `Executor`.

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
