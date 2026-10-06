package markets.alpaca.client.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import markets.alpaca.client.AlpacaClientFactory;
import markets.alpaca.client.AlpacaCredentials;
import markets.alpaca.client.TradingApiEnvironment;
import markets.alpaca.client.data.sse.CorporateActionsSseRequest;
import markets.alpaca.client.openapi.data.api.CryptoApi;
import markets.alpaca.client.openapi.data.api.NewsApi;
import markets.alpaca.client.openapi.data.api.StockApi;
import markets.alpaca.client.openapi.data.model.CorporateActionEvent;
import markets.alpaca.client.openapi.data.model.CryptoHistoricalLoc;
import markets.alpaca.client.openapi.data.model.CryptoLatestLoc;
import markets.alpaca.client.openapi.trading.api.AccountsApi;
import markets.alpaca.client.openapi.trading.api.AssetsApi;
import markets.alpaca.client.openapi.trading.api.OrdersApi;
import markets.alpaca.client.openapi.trading.api.PortfolioHistoryApi;
import markets.alpaca.client.openapi.trading.api.PositionsApi;
import markets.alpaca.client.openapi.trading.model.ActivityEventV2;
import markets.alpaca.client.sse.AlpacaSseCloseResult;
import markets.alpaca.client.sse.AlpacaSseListener;
import markets.alpaca.client.sse.AlpacaSseOptions;
import markets.alpaca.client.sse.AlpacaSseState;
import markets.alpaca.client.trading.sse.TradingActivitySseRequest;
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
 * #      APCA_SSE_ACTIVITY_SINCE=2026-09-01T00:00:00Z
 * #      APCA_SSE_ACTIVITY_UNTIL=2026-09-01T01:00:00Z
 * #      APCA_SSE_CORPORATE_ACTIONS_SINCE=2026-09-01T00:00:00Z
 * #      APCA_SSE_CORPORATE_ACTIONS_UNTIL=2026-09-01T01:00:00Z
 * }</pre>
 *
 * <p>All tests are read-only — they never place orders or modify account state. They intentionally
 * mirror the read-only workflows in {@code examples/}.
 *
 * <p>Tests are skipped automatically when credentials are absent; they do not fail ordinary local
 * builds. Bounded replay tests also skip when their known-event windows are absent. {@code
 * -Palpaca.requireSseIntegration=true} makes missing credentials or replay windows fail the strict
 * release smoke test.
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

  private static OffsetDateTime replayBoundary(String name) {
    String value = credential(name);
    if (Boolean.getBoolean("alpaca.requireSseIntegration")) {
      assertNotNull(value, "Strict SSE integration requires " + name);
    } else {
      assumeTrue(value != null, "Skipping bounded SSE replay — set " + name);
    }
    try {
      return OffsetDateTime.parse(value);
    } catch (RuntimeException failure) {
      throw new AssertionError(name + " must be an RFC 3339 timestamp", failure);
    }
  }

  @BeforeAll
  static void setupClients() {
    String keyId = credential("APCA_TRADING_KEY_ID");
    String secretKey = credential("APCA_TRADING_SECRET_KEY");
    boolean credentialsPresent =
        keyId != null && !keyId.isBlank() && secretKey != null && !secretKey.isBlank();

    if (Boolean.getBoolean("alpaca.requireSseIntegration")) {
      assertTrue(
          credentialsPresent,
          "Strict SSE integration requires APCA_TRADING_KEY_ID and APCA_TRADING_SECRET_KEY");
    } else {
      assumeTrue(
          credentialsPresent,
          "Skipping integration tests — set APCA_TRADING_KEY_ID and APCA_TRADING_SECRET_KEY "
              + "(env vars or local.properties) to run");
    }

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

  /** Opens and closes the read-only Trading account-activity SSE stream. */
  @Test
  void tradingActivitySse_opensSuccessfully() throws Exception {
    var events = AlpacaClientFactory.tradingEventsSseClient(tradingClient);
    var subscription = events.subscribeToActivities(new AlpacaSseListener<>() {});
    try {
      var connection = subscription.opened().get(10, TimeUnit.SECONDS);
      assertEquals(200, connection.statusCode(), "Trading activity SSE must return HTTP 200");
      assertEquals(AlpacaSseState.OPEN, subscription.state());
    } finally {
      subscription.close();
      assertEquals(
          AlpacaSseCloseResult.Reason.USER_CLOSED,
          subscription.completion().get(10, TimeUnit.SECONDS).reason());
      assertEquals(AlpacaSseState.CLOSED, subscription.state());
    }
  }

  /** Opens and closes the read-only Market Data corporate-actions SSE stream. */
  @Test
  void corporateActionsSse_opensSuccessfully() throws Exception {
    var events = AlpacaClientFactory.corporateActionsSseClient(dataClient);
    var subscription = events.subscribeToCorporateActions(new AlpacaSseListener<>() {});
    try {
      var connection = subscription.opened().get(10, TimeUnit.SECONDS);
      assertEquals(200, connection.statusCode(), "Corporate-actions SSE must return HTTP 200");
      assertEquals(AlpacaSseState.OPEN, subscription.state());
    } finally {
      subscription.close();
      assertEquals(
          AlpacaSseCloseResult.Reason.USER_CLOSED,
          subscription.completion().get(10, TimeUnit.SECONDS).reason());
      assertEquals(AlpacaSseState.CLOSED, subscription.state());
    }
  }

  /** Decodes a Trading activity from a caller-supplied bounded replay window. */
  @Test
  void tradingActivitySse_decodesKnownReplayEvent() throws Exception {
    var request =
        TradingActivitySseRequest.builder()
            .since(replayBoundary("APCA_SSE_ACTIVITY_SINCE"))
            .until(replayBoundary("APCA_SSE_ACTIVITY_UNTIL"))
            .build();
    var options =
        AlpacaSseOptions.reconnectDisabled().toBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .maxDuration(Duration.ofSeconds(30))
            .build();
    var events = AlpacaClientFactory.tradingEventsSseClient(tradingClient, options);
    var firstEvent = new CompletableFuture<ActivityEventV2>();

    var subscription =
        events.subscribeToActivities(
            request,
            new AlpacaSseListener<>() {
              @Override
              public void onEvent(markets.alpaca.client.sse.AlpacaSseEvent<ActivityEventV2> event) {
                firstEvent.complete(event.data());
              }

              @Override
              public void onFailure(Throwable failure) {
                firstEvent.completeExceptionally(failure);
              }

              @Override
              public void onClosed(AlpacaSseCloseResult result) {
                firstEvent.completeExceptionally(
                    new AssertionError(
                        "Trading replay ended without an event: " + result.reason()));
              }
            });
    try {
      subscription.opened().get(10, TimeUnit.SECONDS);
      var event = firstEvent.get(30, TimeUnit.SECONDS);
      assertNotNull(event.getEventId(), "Trading replay event ID must not be null");
      assertFalse(event.getEventId().isBlank(), "Trading replay event ID must not be blank");
    } finally {
      subscription.close();
    }
  }

  /** Decodes a corporate action from a caller-supplied bounded replay window. */
  @Test
  void corporateActionsSse_decodesKnownReplayEvent() throws Exception {
    var request =
        CorporateActionsSseRequest.builder()
            .since(replayBoundary("APCA_SSE_CORPORATE_ACTIONS_SINCE"))
            .until(replayBoundary("APCA_SSE_CORPORATE_ACTIONS_UNTIL"))
            .build();
    var options =
        AlpacaSseOptions.reconnectDisabled().toBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .maxDuration(Duration.ofSeconds(30))
            .build();
    var events =
        AlpacaClientFactory.corporateActionsSseClient(
            dataClient,
            markets.alpaca.client.data.sse.MarketDataSseEnvironment.PRODUCTION,
            options);
    var firstEvent = new CompletableFuture<CorporateActionEvent>();

    var subscription =
        events.subscribeToCorporateActions(
            request,
            new AlpacaSseListener<>() {
              @Override
              public void onEvent(
                  markets.alpaca.client.sse.AlpacaSseEvent<CorporateActionEvent> event) {
                firstEvent.complete(event.data());
              }

              @Override
              public void onFailure(Throwable failure) {
                firstEvent.completeExceptionally(failure);
              }

              @Override
              public void onClosed(AlpacaSseCloseResult result) {
                firstEvent.completeExceptionally(
                    new AssertionError(
                        "Corporate-actions replay ended without an event: " + result.reason()));
              }
            });
    try {
      subscription.opened().get(10, TimeUnit.SECONDS);
      var event = firstEvent.get(30, TimeUnit.SECONDS);
      assertNotNull(event.getActualInstance(), "Corporate-action payload must be decoded");
    } finally {
      subscription.close();
    }
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
        api.stockBarSingle("AAPL", "1Day", start, end, 5, null, null, null, null, null, "asc");

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
            "asc");

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
