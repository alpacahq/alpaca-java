package markets.alpaca.client.ws.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import markets.alpaca.client.AlpacaClientFactory;
import markets.alpaca.client.AlpacaCredentials;
import markets.alpaca.client.TradingApiEnvironment;
import markets.alpaca.client.ws.AlpacaCryptoStream;
import markets.alpaca.client.ws.AlpacaNewsStream;
import markets.alpaca.client.ws.AlpacaStreamEnvironment;
import markets.alpaca.client.ws.AlpacaTradingStream;
import markets.alpaca.client.ws.CryptoStreamListener;
import markets.alpaca.client.ws.CryptoSubscription;
import markets.alpaca.client.ws.NewsStreamListener;
import markets.alpaca.client.ws.NewsSubscription;
import markets.alpaca.client.ws.StockSource;
import markets.alpaca.client.ws.StockStreamListener;
import markets.alpaca.client.ws.StockSubscription;
import markets.alpaca.client.ws.TradingEnvironment;
import markets.alpaca.client.ws.TradingStreamListener;
import markets.alpaca.client.ws.TradingSubscription;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for all four Alpaca WebSocket streams.
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
 * <p>Each test connects to the real Alpaca WebSocket endpoints, authenticates with paper
 * credentials, subscribes to a channel, and verifies the server acknowledges the subscription. No
 * orders are placed. Tests skip automatically when credentials are absent.
 */
@Tag("integration")
class WebSocketIntegrationIT {

  private static final int TIMEOUT_S = 20;

  private static AlpacaCredentials creds;

  private static String credential(String... keys) {
    for (String key : keys) {
      String v = System.getProperty(key);
      if (v != null && !v.isBlank()) return v;

      v = System.getenv(key);
      if (v != null && !v.isBlank()) return v;
    }
    return null;
  }

  private static TradingEnvironment tradingStreamEnvironment() {
    String value = credential("APCA_TRADING_ENVIRONMENT");
    if (value == null) return TradingEnvironment.PAPER;

    return switch (TradingApiEnvironment.from(value)) {
      case PAPER -> TradingEnvironment.PAPER;
      case PRODUCTION -> TradingEnvironment.PRODUCTION;
    };
  }

  @BeforeAll
  static void loadCredentials() {
    String keyId = credential("APCA_TRADING_KEY_ID");
    String secretKey = credential("APCA_TRADING_SECRET_KEY");

    assumeTrue(
        keyId != null && !keyId.isBlank() && secretKey != null && !secretKey.isBlank(),
        "Skipping WebSocket integration tests — set APCA_TRADING_KEY_ID and "
            + "APCA_TRADING_SECRET_KEY (env vars or local.properties) to run");

    creds = new AlpacaCredentials(keyId, secretKey);
  }

  private static OkHttpClient buildClient() {
    return new OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(10))
        .readTimeout(Duration.ofSeconds(TIMEOUT_S))
        .build();
  }

  // -------------------------------------------------------------------------
  // Stock stream (IEX, sandbox)
  // -------------------------------------------------------------------------

  /**
   * Connects to the IEX stock stream (production endpoint, paper credentials), authenticates,
   * subscribes to AAPL trades, and verifies the server replies with a subscription confirmation.
   * The listener is dispatched through an application-owned executor to prove the executor overload
   * works against the real endpoint.
   *
   * <p>Note: paper-trading credentials authenticate against the production data stream URL ({@code
   * stream.data.alpaca.markets}). The {@link AlpacaStreamEnvironment#SANDBOX} variant uses {@code
   * stream.data.sandbox.alpaca.markets}, which requires broker-sandbox credentials and is not
   * tested here.
   */
  @Test
  void stockStream_iex_authenticatesAndReceivesSubscriptionConfirmation()
      throws InterruptedException {
    var authenticated = new CountDownLatch(1);
    var subscribed = new CountDownLatch(1);
    var callbackThreadName = new AtomicReference<String>();
    ExecutorService callbackExecutor =
        Executors.newSingleThreadExecutor(r -> new Thread(r, "alpaca-ws-integration-callback"));

    try (var stream =
        AlpacaClientFactory.stockStream(
            creds,
            StockSource.IEX,
            AlpacaStreamEnvironment.PRODUCTION,
            new StockStreamListener() {
              @Override
              public void onAuthenticated() {
                callbackThreadName.set(Thread.currentThread().getName());
                authenticated.countDown();
              }

              @Override
              public void onSubscriptionConfirmed(Map<String, List<String>> s) {
                subscribed.countDown();
              }

              @Override
              public void onError(int code, String message) {
                fail("Stock stream error " + code + ": " + message);
              }
            },
            buildClient(),
            callbackExecutor)) {
      stream.connect();

      assertTrue(
          authenticated.await(TIMEOUT_S, TimeUnit.SECONDS),
          "Stock stream did not authenticate within "
              + TIMEOUT_S
              + "s. "
              + "Check that paper credentials are valid.");
      assertEquals(
          "alpaca-ws-integration-callback",
          callbackThreadName.get(),
          "Stock stream callbacks should run on the supplied executor");

      stream.subscribe(StockSubscription.builder().trades("AAPL").build());

      assertTrue(
          subscribed.await(TIMEOUT_S, TimeUnit.SECONDS),
          "Stock stream did not confirm subscription within " + TIMEOUT_S + "s");
    } finally {
      callbackExecutor.shutdownNow();
    }
  }

  // -------------------------------------------------------------------------
  // Crypto stream (production)
  // -------------------------------------------------------------------------

  /**
   * Connects to the crypto production stream, authenticates, subscribes to BTC/USD trades, and
   * verifies subscription confirmation.
   */
  @Test
  void cryptoStream_production_authenticatesAndReceivesSubscriptionConfirmation()
      throws InterruptedException {
    var authenticated = new CountDownLatch(1);
    var subscribed = new CountDownLatch(1);

    try (var stream =
        new AlpacaCryptoStream(
            buildClient(),
            creds,
            AlpacaStreamEnvironment.PRODUCTION,
            new CryptoStreamListener() {
              @Override
              public void onAuthenticated() {
                authenticated.countDown();
              }

              @Override
              public void onSubscriptionConfirmed(Map<String, List<String>> s) {
                subscribed.countDown();
              }

              @Override
              public void onError(int code, String message) {
                fail("Crypto stream error " + code + ": " + message);
              }
            })) {
      stream.connect();

      assertTrue(
          authenticated.await(TIMEOUT_S, TimeUnit.SECONDS),
          "Crypto stream did not authenticate within " + TIMEOUT_S + "s");

      stream.subscribe(CryptoSubscription.builder().trades("BTC/USD").build());

      assertTrue(
          subscribed.await(TIMEOUT_S, TimeUnit.SECONDS),
          "Crypto stream did not confirm subscription within " + TIMEOUT_S + "s");
    }
  }

  // -------------------------------------------------------------------------
  // News stream (production)
  // -------------------------------------------------------------------------

  /**
   * Connects to the news production stream, authenticates, subscribes to all news (wildcard), and
   * verifies subscription confirmation.
   */
  @Test
  void newsStream_production_authenticatesAndReceivesSubscriptionConfirmation()
      throws InterruptedException {
    var authenticated = new CountDownLatch(1);
    var subscribed = new CountDownLatch(1);

    try (var stream =
        new AlpacaNewsStream(
            buildClient(),
            creds,
            AlpacaStreamEnvironment.PRODUCTION,
            new NewsStreamListener() {
              @Override
              public void onAuthenticated() {
                authenticated.countDown();
              }

              @Override
              public void onSubscriptionConfirmed(Map<String, List<String>> s) {
                subscribed.countDown();
              }

              @Override
              public void onError(int code, String message) {
                fail("News stream error " + code + ": " + message);
              }
            })) {
      stream.connect();

      assertTrue(
          authenticated.await(TIMEOUT_S, TimeUnit.SECONDS),
          "News stream did not authenticate within " + TIMEOUT_S + "s");

      stream.subscribe(NewsSubscription.builder().symbols("*").build());

      assertTrue(
          subscribed.await(TIMEOUT_S, TimeUnit.SECONDS),
          "News stream did not confirm subscription within " + TIMEOUT_S + "s");
    }
  }

  // -------------------------------------------------------------------------
  // Trading stream (paper)
  // -------------------------------------------------------------------------

  /**
   * Connects to the paper trading stream, authenticates, sends a listen request for trade_updates,
   * and verifies the server replies with a listening confirmation.
   */
  @Test
  void tradingStream_paper_authenticatesAndReceivesListeningConfirmation()
      throws InterruptedException {
    var authenticated = new CountDownLatch(1);
    var listening = new CountDownLatch(1);

    try (var stream =
        new AlpacaTradingStream(
            buildClient(),
            creds,
            tradingStreamEnvironment(),
            new TradingStreamListener() {
              @Override
              public void onAuthenticated() {
                authenticated.countDown();
              }

              @Override
              public void onListening(List<String> streams) {
                listening.countDown();
              }

              @Override
              public void onError(String message) {
                fail("Trading stream error: " + message);
              }
            })) {
      stream.connect();

      assertTrue(
          authenticated.await(TIMEOUT_S, TimeUnit.SECONDS),
          "Trading stream did not authenticate within "
              + TIMEOUT_S
              + "s. "
              + "Check that paper credentials are valid.");

      stream.listen(TradingSubscription.TRADE_UPDATES);

      assertTrue(
          listening.await(TIMEOUT_S, TimeUnit.SECONDS),
          "Trading stream did not confirm listening within " + TIMEOUT_S + "s");
    }
  }
}
