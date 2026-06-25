---
id: market-data
title: Market Data
---

The Market Data API provides historical and live data for stocks, crypto, news, and related market
datasets. Over 5 years of historical data is available for thousands of equity and cryptocurrency symbols.
Various data types are available such as bars/candles (OHLCV), trade data (price and sales), and quote data.
For crypto, there is also orderbook data. In this Java SDK, stock-trade helpers are available from `AlpacaClient`,
while the rest of the generated Market Data REST surface is available from `client.newDataClient()` or
`AlpacaClientFactory.dataClient(...)`.

## Subscription Plans

Most market data features are free to use. However, if you are a professional or institution, you may wish to expand
with the unlimited plan. Learn more about the subscriptions plans at [alpaca.markets/data](https://alpaca.markets/data).

## API keys and data feeds

You can sign up for API keys [here](https://app.alpaca.markets/signup). API keys allow you to access stock data.
Crypto REST data may be available without authentication at the API level. This SDK's factory and
facade entry points configure Market Data with credentials, which is recommended because keys
increase rate limits and are required for stock, option, and live data workflows.

Trading API credentials are also used for Market Data in this SDK:

```java
import markets.alpaca.client.AlpacaClient;
import markets.alpaca.client.AlpacaCredentials;

var credentials = AlpacaCredentials.fromTradingApiEnvironmentVariables();
var client = AlpacaClient.builder(credentials).build();
```

The `IEX` feed is commonly available for stock examples. Use feed values and entitlements that
match your Alpaca data subscription.

## Generated REST clients

Alpaca-py has separate historical clients for stocks, crypto, and options. In this Java SDK, create
one generated Market Data `ApiClient`, then choose the generated API class for the dataset:

```java
import markets.alpaca.client.AlpacaClientFactory;
import markets.alpaca.client.openapi.data.api.CryptoApi;
import markets.alpaca.client.openapi.data.api.OptionApi;
import markets.alpaca.client.openapi.data.api.StockApi;

var dataClient = AlpacaClientFactory.dataClient(credentials);

var stock = new StockApi(dataClient);
var crypto = new CryptoApi(dataClient);
var options = new OptionApi(dataClient);
```

Use `StockApi` for stock bars, trades, quotes, snapshots, and latest data. Use `CryptoApi` for
crypto bars, trades, quotes, snapshots, and orderbooks. Use `OptionApi` for option bars, trades,
quotes, snapshots, and latest data.

Multi-symbol generated responses are keyed by symbol or pair, even when a request contains a single
symbol. Single-symbol endpoints such as `stockLatestQuoteSingle(...)` return a single wrapper, while
multi-symbol endpoints such as `stockLatestQuotes(...)`, `cryptoLatestQuotes(...)`, and
`optionLatestQuotes(...)` return maps.

## Historical stock trades

Use the `AlpacaClient` stock facade for named historical stock-trade requests.

```java
import java.time.OffsetDateTime;
import markets.alpaca.client.data.StockTradesRequest;
import markets.alpaca.client.openapi.data.model.Sort;
import markets.alpaca.client.openapi.data.model.StockHistoricalFeed;

var end = OffsetDateTime.now().minusMinutes(20);
var start = end.minusDays(5);

var trades = client.stocks().tradesForSymbol(StockTradesRequest.builder()
    .symbols("AAPL")
    .start(start)
    .end(end)
    .feed(StockHistoricalFeed.IEX)
    .sort(Sort.ASC)
    .limit(100)
    .build());
```

For multiple symbols, call `client.stocks().trades(...)`. Multi-symbol generated responses are
keyed by symbol, so read the map for the symbol you want.

## Latest stock data

Latest bars and quotes are available from the generated `StockApi`. Multi-symbol latest quote
responses are keyed by stock symbol.

```java
import markets.alpaca.client.openapi.data.api.StockApi;
import markets.alpaca.client.openapi.data.model.StockLatestFeed;

var stock = new StockApi(client.newDataClient());

var latestBar = stock.stockLatestBarSingle("AAPL", null, null);
System.out.println(latestBar.getBar());

var latestQuote = stock.stockLatestQuoteSingle("AAPL", null, null);
System.out.println(latestQuote.getQuote());

var latestQuotes = stock.stockLatestQuotes("SPY,GLD,TLT", StockLatestFeed.IEX, null);
var gldAsk = latestQuotes.getQuotes().get("GLD").getAp();
```

## Historical bars

Historical bar requests use the generated API's endpoint-specific parameters.

```java
import java.time.OffsetDateTime;
import markets.alpaca.client.openapi.data.model.Sort;

var bars = stock.stockBarSingle(
    "AAPL",
    "1Day",
    OffsetDateTime.now().minusDays(30),
    OffsetDateTime.now().minusMinutes(5),
    30,
    null,
    null,
    null,
    null,
    null,
    Sort.ASC);

bars.getBars().forEach(System.out::println);
```

## Crypto data

Crypto REST data is exposed through the generated `CryptoApi`. Generated crypto responses that
cover one or more pairs are keyed by pair, such as `BTC/USD`.

```java
import java.time.OffsetDateTime;
import markets.alpaca.client.openapi.data.api.CryptoApi;
import markets.alpaca.client.openapi.data.model.CryptoHistoricalLoc;
import markets.alpaca.client.openapi.data.model.CryptoLatestLoc;
import markets.alpaca.client.openapi.data.model.Sort;

var crypto = new CryptoApi(client.newDataClient());

var latestQuote = crypto.cryptoLatestQuotes(CryptoLatestLoc.US, "ETH/USD");
System.out.println(latestQuote.getQuotes().get("ETH/USD").getAp());

var latestCryptoBars =
    crypto.cryptoLatestBars(CryptoLatestLoc.US, "BTC/USD,ETH/USD");

latestCryptoBars.getBars().forEach((symbol, bar) ->
    System.out.printf("%s %s%n", symbol, bar));

var orderbooks = crypto.cryptoLatestOrderbooks(CryptoLatestLoc.US, "BTC/USD");
System.out.println(orderbooks.getOrderbooks().get("BTC/USD"));

var historicalBars = crypto.cryptoBars(
    CryptoHistoricalLoc.US,
    "BTC/USD,ETH/USD",
    "1Day",
    OffsetDateTime.parse("2022-07-01T00:00:00Z"),
    OffsetDateTime.parse("2022-09-01T00:00:00Z"),
    null,
    null,
    Sort.ASC);
System.out.println(historicalBars.getBars().get("BTC/USD"));
```

## Option data

Option historical and latest REST data is available from the generated `OptionApi`. Use your
entitled option feed, such as `OptionFeed.OPRA` for OPRA data or `OptionFeed.INDICATIVE` where
indicative data is appropriate.

```java
import java.time.OffsetDateTime;
import markets.alpaca.client.openapi.data.api.OptionApi;
import markets.alpaca.client.openapi.data.model.OptionFeed;
import markets.alpaca.client.openapi.data.model.Sort;

var options = new OptionApi(client.newDataClient());

var optionSymbols = "AAPL260918C00200000,AAPL260918P00200000";

var optionQuotes = options.optionLatestQuotes(optionSymbols, OptionFeed.OPRA);
optionQuotes.getQuotes().forEach((symbol, quote) ->
    System.out.printf("%s ask=%s%n", symbol, quote.getAp()));

var optionBars = options.optionBars(
    optionSymbols,
    "1Day",
    OffsetDateTime.now().minusDays(30),
    OffsetDateTime.now().minusMinutes(15),
    100,
    null,
    Sort.ASC);

optionBars.getBars().forEach((symbol, bars) ->
    System.out.printf("%s bars=%d%n", symbol, bars.size()));
```

## News

Use the generated `NewsApi` for historical news searches. The response includes a
`next_page_token` for pagination.

```java
import java.time.OffsetDateTime;
import markets.alpaca.client.openapi.data.api.NewsApi;

var news = new NewsApi(client.newDataClient());
var response = news.news(
    OffsetDateTime.now().minusDays(7),
    null,
    "desc",
    "AAPL",
    5,
    false,
    true,
    null);

response.getNews().forEach(article -> System.out.println(article.getHeadline()));
```

## Pagination

Generated REST APIs expose `*WithHttpInfo(...)` methods when you need headers, status codes, or
page tokens. Use `AlpacaPagination` to keep page-token loops in one place.

```java
import markets.alpaca.client.rest.AlpacaPagination;
import markets.alpaca.client.openapi.data.model.NewsResp;

var allNews = AlpacaPagination.collectDataItems(
    pageToken -> news.newsWithHttpInfo(
        null, null, null, "AAPL", 50, false, true, pageToken),
    NewsResp::getNextPageToken,
    NewsResp::getNews);
```

## Live market data

Live stock, crypto, and news streams are handwritten WebSocket clients. They call listener methods
as events arrive, and `connect(...)` starts the stream rather than returning a data payload.

```java
import markets.alpaca.client.AlpacaClientFactory;
import markets.alpaca.client.ws.AlpacaStreamEnvironment;
import markets.alpaca.client.ws.StockSource;
import markets.alpaca.client.ws.StockStreamListener;
import markets.alpaca.client.ws.StockSubscription;
import markets.alpaca.client.ws.model.StockQuote;

var stream = AlpacaClientFactory.stockStream(
    credentials,
    StockSource.IEX,
    AlpacaStreamEnvironment.PRODUCTION,
    new StockStreamListener() {
        @Override
        public void onQuote(StockQuote quote) {
            System.out.println(quote);
        }
    });

stream.connect(StockSubscription.builder().quotes("AAPL").build());
```

Option live streaming is not currently exposed by this SDK. Use generated `OptionApi` for option
REST data, and add a handwritten option stream client under `src/main/java/markets/alpaca/client/ws/`
if WebSocket option data is needed.

For crypto and news examples, authentication confirmation, listener executors, reconnect behavior,
and subscription lifecycle details, see [Streaming and events](./streaming).

See `examples/src/main/java/markets/alpaca/client/examples/MarketDataExample.java` and
`examples/src/main/java/markets/alpaca/client/examples/PaginationExample.java` for runnable
workflows.
