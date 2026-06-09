package markets.alpaca.client.examples;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import markets.alpaca.client.AlpacaClientFactory;
import markets.alpaca.client.data.StockTradesRequest;
import markets.alpaca.client.openapi.data.api.CryptoApi;
import markets.alpaca.client.openapi.data.api.NewsApi;
import markets.alpaca.client.openapi.data.api.StockApi;
import markets.alpaca.client.openapi.data.model.CryptoHistoricalLoc;
import markets.alpaca.client.openapi.data.model.CryptoLatestLoc;
import markets.alpaca.client.openapi.data.model.Sort;
import markets.alpaca.client.openapi.data.model.StockHistoricalFeed;
import markets.alpaca.client.ws.AlpacaStreamEnvironment;
import markets.alpaca.client.ws.StockSource;
import markets.alpaca.client.ws.StockStreamListener;
import markets.alpaca.client.ws.StockSubscription;
import markets.alpaca.client.ws.model.StockQuote;

/**
 * Market Data workflow: request historical/latest stock and crypto data, read news, and optionally
 * subscribe to live quotes.
 */
public final class MarketDataExample {
  private MarketDataExample() {}

  public static void main(String[] args) throws Exception {
    var credentials = ExampleSupport.tradingCredentials();
    var dataClient = AlpacaClientFactory.dataClient(credentials);

    var stock = new StockApi(dataClient);
    var stocks = AlpacaClientFactory.stocks(dataClient);
    var crypto = new CryptoApi(dataClient);
    var news = new NewsApi(dataClient);

    String stockSymbol = ExampleSupport.env("APCA_EXAMPLE_SYMBOL", "AAPL");
    String cryptoSymbols = ExampleSupport.env("APCA_EXAMPLE_CRYPTO_SYMBOLS", "BTC/USD,ETH/USD");

    ExampleSupport.printSection("Latest Stock Bar");
    var latestBar = stock.stockLatestBarSingle(stockSymbol, null, null);
    System.out.println(latestBar.getBar());

    ExampleSupport.printSection("Historical Stock Bars");
    var end = OffsetDateTime.now().minusMinutes(20);
    var start = end.minusDays(5);
    var bars =
        stock.stockBarSingle(
            stockSymbol,
            "1Day",
            start,
            end,
            5,
            null,
            null,
            null,
            null,
            null,
            Sort.fromValue("asc"));
    bars.getBars().forEach(System.out::println);

    ExampleSupport.printSection("Latest Stock Quote");
    var quote = stock.stockLatestQuoteSingle(stockSymbol, null, null);
    System.out.println(quote.getQuote());

    ExampleSupport.printSection("Historical Stock Trades");
    var trades =
        stocks.tradesForSymbol(
            StockTradesRequest.builder()
                .symbols(stockSymbol)
                .start(start)
                .end(end)
                .limit(5)
                .feed(StockHistoricalFeed.IEX)
                .sort(Sort.ASC)
                .build());
    System.out.printf("trades=%d%n", trades.getTrades() == null ? 0 : trades.getTrades().size());

    ExampleSupport.printSection("Latest Crypto Bars");
    var latestCryptoBars = crypto.cryptoLatestBars(CryptoLatestLoc.fromValue("us"), cryptoSymbols);
    latestCryptoBars.getBars().forEach((symbol, bar) -> System.out.printf("%s %s%n", symbol, bar));

    ExampleSupport.printSection("Historical Crypto Bars");
    var cryptoBars =
        crypto.cryptoBars(
            CryptoHistoricalLoc.fromValue("us"),
            cryptoSymbols,
            "1Hour",
            OffsetDateTime.now().minusDays(1),
            OffsetDateTime.now().minusMinutes(5),
            10,
            null,
            Sort.fromValue("asc"));
    cryptoBars
        .getBars()
        .forEach(
            (symbol, symbolBars) -> System.out.printf("%s bars=%d%n", symbol, symbolBars.size()));

    ExampleSupport.printSection("News");
    var newsResp =
        news.news(
            OffsetDateTime.now().minusDays(7), null, "desc", stockSymbol, 5, false, true, null);
    newsResp.getNews().forEach(article -> System.out.printf("%s%n", article.getHeadline()));

    if (ExampleSupport.enabled("APCA_EXAMPLE_STREAM")) {
      streamQuotes(credentials, stockSymbol);
    } else {
      ExampleSupport.printSection("Live Stock Stream");
      System.out.println("Skipped. Set APCA_EXAMPLE_STREAM=true to subscribe to live quotes.");
    }
  }

  private static void streamQuotes(
      markets.alpaca.client.AlpacaCredentials credentials, String symbol)
      throws InterruptedException {
    ExampleSupport.printSection("Live Stock Stream");

    var receivedQuote = new CountDownLatch(1);
    var callbackExecutor =
        Executors.newSingleThreadExecutor(r -> new Thread(r, "alpaca-example-stream-callbacks"));

    var stream =
        AlpacaClientFactory.stockStream(
            credentials,
            StockSource.IEX,
            AlpacaStreamEnvironment.PRODUCTION,
            new StockStreamListener() {
              @Override
              public void onQuote(StockQuote quote) {
                System.out.println(quote);
                receivedQuote.countDown();
              }

              @Override
              public void onError(int code, String message) {
                System.err.printf("stream error %d: %s%n", code, message);
              }
            },
            callbackExecutor);

    try {
      stream.connect(StockSubscription.builder().quotes(symbol).build());
      if (!stream.waitForAuthentication(Duration.ofSeconds(10))) {
        throw new IllegalStateException("stock stream did not authenticate");
      }
      receivedQuote.await(20, TimeUnit.SECONDS);
    } finally {
      stream.close();
      callbackExecutor.shutdownNow();
    }
  }
}
