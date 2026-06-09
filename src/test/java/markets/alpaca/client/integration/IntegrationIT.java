package markets.alpaca.client.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.OffsetDateTime;
import java.util.List;
import markets.alpaca.client.AlpacaClientFactory;
import markets.alpaca.client.AlpacaCredentials;
import markets.alpaca.client.TradingApiEnvironment;
import markets.alpaca.client.openapi.data.api.CryptoApi;
import markets.alpaca.client.openapi.data.api.NewsApi;
import markets.alpaca.client.openapi.data.api.StockApi;
import markets.alpaca.client.openapi.data.model.CryptoHistoricalLoc;
import markets.alpaca.client.openapi.data.model.CryptoLatestLoc;
import markets.alpaca.client.openapi.data.model.Sort;
import markets.alpaca.client.openapi.trading.api.AccountsApi;
import markets.alpaca.client.openapi.trading.api.AssetsApi;
import markets.alpaca.client.openapi.trading.api.OrdersApi;
import markets.alpaca.client.openapi.trading.api.PortfolioHistoryApi;
import markets.alpaca.client.openapi.trading.api.PositionsApi;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Integration tests that exercise each Alpaca API client against the live paper-trading and
 * market-data endpoints.
 *
 * <h2>Running these tests</h2>
 *
 * <pre>{@code
 * # Via Gradle (recommended):
 * ./gradlew integrationTest
 *
 * # Credentials (pick one source):
 * # 1. local.properties (gitignored):
 * #      tradingApiKeyId=PK...
 * #      tradingApiSecretKey=...
 * #      tradingApiEnvironment=paper  # optional; paper is the default
 * # 2. Environment variables:
 * #      APCA_TRADING_KEY_ID=PK...
 * #      APCA_TRADING_SECRET_KEY=...
 * #      APCA_TRADING_ENVIRONMENT=paper
 * }</pre>
 *
 * <p>All tests are read-only — they never place orders or modify account state. They intentionally
 * mirror the read-only workflows in {@code examples/}.
 *
 * <p>Tests are skipped automatically when credentials are absent; they do not fail the build.
 */
@Tag("integration")
class IntegrationIT {

  private static markets.alpaca.client.openapi.trading.http.ApiClient tradingClient;
  private static markets.alpaca.client.openapi.data.http.ApiClient dataClient;

  /**
   * Reads a credential from the JVM system property injected by Gradle (see the {@code
   * integrationTest} task in {@code build.gradle}), falling back to the environment variable of the
   * same name for direct IDE runs.
   */
  private static String credential(String... keys) {
    for (String key : keys) {
      String v = System.getProperty(key);
      if (v != null && !v.isBlank()) return v;

      v = System.getenv(key);
      if (v != null && !v.isBlank()) return v;
    }
    return null;
  }

  private static TradingApiEnvironment tradingEnvironment() {
    String value = credential("APCA_TRADING_ENVIRONMENT");
    return value == null ? TradingApiEnvironment.PAPER : TradingApiEnvironment.from(value);
  }

  @BeforeAll
  static void setupClients() {
    String keyId = credential("APCA_TRADING_KEY_ID");
    String secretKey = credential("APCA_TRADING_SECRET_KEY");

    assumeTrue(
        keyId != null && !keyId.isBlank() && secretKey != null && !secretKey.isBlank(),
        "Skipping integration tests — set APCA_TRADING_KEY_ID and APCA_TRADING_SECRET_KEY "
            + "(env vars or local.properties) to run");

    var creds = new AlpacaCredentials(keyId, secretKey);

    tradingClient = AlpacaClientFactory.tradingClient(creds, tradingEnvironment());

    dataClient = AlpacaClientFactory.dataClient(creds);
  }

  // -------------------------------------------------------------------------
  // Trading API
  // -------------------------------------------------------------------------

  /**
   * Calls {@code GET /v2/account} — the fastest confirmation that credentials are valid and the
   * trading client is correctly wired.
   */
  @Test
  void tradingApi_getAccount_returnsValidAccount() throws Exception {
    var api = new AccountsApi(tradingClient);
    var account = api.getAccount();

    assertNotNull(account, "account must not be null");
    assertNotNull(account.getId(), "account.id must not be null");
    assertNotNull(account.getAccountNumber(), "account.accountNumber must not be null");
    assertFalse(account.getAccountNumber().isBlank(), "account.accountNumber must not be blank");
    assertEquals("USD", account.getCurrency(), "account.currency must be USD");
    assertFalse(account.getAccountBlocked(), "account must not be blocked");
  }

  /**
   * Calls {@code GET /v2/assets/AAPL} — confirms the assets endpoint is reachable and returns a
   * well-formed asset for a well-known symbol.
   */
  @Test
  void tradingApi_getAsset_aapl_returnsMatchingSymbol() throws Exception {
    var api = new AssetsApi(tradingClient);
    var asset = api.getV2AssetsSymbolOrAssetId("AAPL");

    assertNotNull(asset, "asset must not be null");
    assertNotNull(asset.getId(), "asset.id must not be null");
    assertEquals("AAPL", asset.getSymbol(), "asset.symbol must match the requested symbol");
  }

  /** Mirrors the example's open-orders read. This is safe because it only lists existing orders. */
  @Test
  void tradingApi_getOpenOrders_returnsList() throws Exception {
    var api = new OrdersApi(tradingClient);
    var orders =
        api.getAllOrders(
            "open", 50, null, null, "desc", true, "AAPL", null, List.of("us_equity"), null, null);

    assertNotNull(orders, "open orders list must not be null");
  }

  /** Mirrors the example's positions read. Accounts with no open positions are valid. */
  @Test
  void tradingApi_getOpenPositions_returnsList() throws Exception {
    var api = new PositionsApi(tradingClient);
    var positions = api.getAllOpenPositions();

    assertNotNull(positions, "positions list must not be null");
  }

  /** Mirrors the example's portfolio-history read. */
  @Test
  void tradingApi_getPortfolioHistory_returnsHistory() throws Exception {
    var api = new PortfolioHistoryApi(tradingClient);
    var history = api.getAccountPortfolioHistory("1M", "1D", null, null, null, null, null, null);

    assertNotNull(history, "portfolio history must not be null");
    assertNotNull(history.getEquity(), "portfolio history equity series must not be null");
  }

  // -------------------------------------------------------------------------
  // Market Data API
  // -------------------------------------------------------------------------

  /**
   * Calls {@code GET /v2/stocks/AAPL/bars/latest} — confirms that the data client authentication is
   * correctly wired and the endpoint returns a valid bar.
   */
  @Test
  void dataApi_stockLatestBar_aapl_returnsBar() throws Exception {
    var api = new StockApi(dataClient);
    var resp = api.stockLatestBarSingle("AAPL", null, null);

    assertNotNull(resp, "response must not be null");
    assertNotNull(resp.getBar(), "bar must not be null");
    assertNotNull(resp.getBar().getT(), "bar.timestamp must not be null");
    assertNotNull(resp.getBar().getC(), "bar.close must not be null");
    assertTrue(resp.getBar().getC() > 0, "bar.close must be positive");
  }

  /** Mirrors the example's historical stock bars read. */
  @Test
  void dataApi_stockHistoricalBars_aapl_returnsBars() throws Exception {
    var api = new StockApi(dataClient);
    var end = OffsetDateTime.now().minusMinutes(20);
    var start = end.minusDays(5);
    var resp =
        api.stockBarSingle(
            "AAPL", "1Day", start, end, 5, null, null, null, null, null, Sort.fromValue("asc"));

    assertNotNull(resp, "response must not be null");
    assertNotNull(resp.getBars(), "bars list must not be null");
    assertFalse(resp.getBars().isEmpty(), "AAPL historical bars must not be empty");
  }

  /** Mirrors the example's latest stock quote read. */
  @Test
  void dataApi_stockLatestQuote_aapl_returnsQuote() throws Exception {
    var api = new StockApi(dataClient);
    var resp = api.stockLatestQuoteSingle("AAPL", null, null);

    assertNotNull(resp, "response must not be null");
    assertNotNull(resp.getQuote(), "quote must not be null");
    assertNotNull(resp.getQuote().getT(), "quote.timestamp must not be null");
  }

  /** Mirrors the example's latest crypto bars read. */
  @Test
  void dataApi_cryptoLatestBars_returnsBars() throws Exception {
    var api = new CryptoApi(dataClient);
    var resp = api.cryptoLatestBars(CryptoLatestLoc.fromValue("us"), "BTC/USD,ETH/USD");

    assertNotNull(resp, "response must not be null");
    assertNotNull(resp.getBars(), "bars map must not be null");
    assertFalse(resp.getBars().isEmpty(), "crypto bars map must not be empty");
  }

  /** Mirrors the example's historical crypto bars read. */
  @Test
  void dataApi_cryptoHistoricalBars_returnsBars() throws Exception {
    var api = new CryptoApi(dataClient);
    var resp =
        api.cryptoBars(
            CryptoHistoricalLoc.fromValue("us"),
            "BTC/USD,ETH/USD",
            "1Hour",
            OffsetDateTime.now().minusDays(1),
            OffsetDateTime.now().minusMinutes(5),
            10,
            null,
            Sort.fromValue("asc"));

    assertNotNull(resp, "response must not be null");
    assertNotNull(resp.getBars(), "bars map must not be null");
  }

  /** Mirrors the example's news read. */
  @Test
  void dataApi_news_aapl_returnsNewsList() throws Exception {
    var api = new NewsApi(dataClient);
    var resp =
        api.news(OffsetDateTime.now().minusDays(7), null, "desc", "AAPL", 5, false, true, null);

    assertNotNull(resp, "response must not be null");
    assertNotNull(resp.getNews(), "news list must not be null");
  }
}
