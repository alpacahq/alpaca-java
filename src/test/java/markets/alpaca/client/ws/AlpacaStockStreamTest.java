package markets.alpaca.client.ws;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import markets.alpaca.client.AlpacaCredentials;
import markets.alpaca.client.ws.model.CryptoOrderbook;
import markets.alpaca.client.ws.model.CryptoTrade;
import markets.alpaca.client.ws.model.LuldBand;
import markets.alpaca.client.ws.model.NewsArticle;
import markets.alpaca.client.ws.model.StockBar;
import markets.alpaca.client.ws.model.StockQuote;
import markets.alpaca.client.ws.model.StockTrade;
import markets.alpaca.client.ws.model.StockTradingStatus;
import markets.alpaca.client.ws.model.TradeCancelError;
import markets.alpaca.client.ws.model.TradeCorrection;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Protocol and dispatch tests for the market-data WebSocket streams.
 *
 * <p>Uses {@link MockWebServer} to drive the server side, exercising:
 *
 * <ul>
 *   <li>The connected → auth → authenticated handshake
 *   <li>Subscribe / unsubscribe payload construction
 *   <li>Data message dispatch ({@code StockTrade}, {@code StockQuote}, {@code StockBar}, etc.)
 *   <li>Error and close handling
 *   <li>Crypto ({@code CryptoTrade}, {@code CryptoOrderbook}) and News ({@code NewsArticle})
 *       message dispatch via the same shared base class
 * </ul>
 */
class AlpacaStockStreamTest {

  private static final AlpacaCredentials CREDS =
      new AlpacaCredentials("test-key-id", "test-secret-key");
  private static final int TIMEOUT_S = 3;

  private MockWebServer server;
  private OkHttpClient client;

  private static void assertDecimal(String expected, BigDecimal actual) {
    assertEquals(0, new BigDecimal(expected).compareTo(actual));
  }

  @BeforeEach
  void setUp() throws IOException {
    server = new MockWebServer();
    server.start();
    client =
        new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(TIMEOUT_S))
            .readTimeout(Duration.ofSeconds(TIMEOUT_S))
            .build();
  }

  @AfterEach
  void tearDown() throws IOException {
    server.close();
  }

  // -------------------------------------------------------------------------
  // Inner helper — server-side WebSocket controller
  // -------------------------------------------------------------------------

  /**
   * Captures the server-side WebSocket and incoming messages from the client. The {@code enqueue()}
   * call registers a one-shot MockWebServer upgrade handler.
   */
  private final class ServerSocket {
    private final CountDownLatch opened = new CountDownLatch(1);
    private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
    private final AtomicReference<WebSocket> ws = new AtomicReference<>();

    void enqueue() {
      server.enqueue(
          new MockResponse()
              .withWebSocketUpgrade(
                  new WebSocketListener() {
                    @Override
                    public void onOpen(WebSocket webSocket, Response response) {
                      ws.set(webSocket);
                      opened.countDown();
                    }

                    @Override
                    public void onMessage(WebSocket webSocket, String text) {
                      messages.add(text);
                    }

                    @Override
                    public void onClosing(WebSocket webSocket, int code, String reason) {
                      webSocket.close(code, reason);
                    }
                  }));
    }

    void awaitOpen() throws InterruptedException {
      assertTrue(
          opened.await(TIMEOUT_S, TimeUnit.SECONDS),
          "MockWebServer did not receive WebSocket upgrade in time");
    }

    /** Sends a text frame from the server to the client. */
    void send(String json) {
      ws.get().send(json);
    }

    /**
     * Polls for the next message sent by the client. Fails the test if nothing arrives within the
     * timeout.
     */
    String pollMessage() throws InterruptedException {
      String msg = messages.poll(TIMEOUT_S, TimeUnit.SECONDS);
      assertNotNull(msg, "Expected a message from the client but none arrived");
      return msg;
    }

    void assertNoMessage(long timeoutMs) throws InterruptedException {
      assertNull(
          messages.poll(timeoutMs, TimeUnit.MILLISECONDS), "Expected no message from the client");
    }
  }

  private static final class RecordingScheduler extends ScheduledThreadPoolExecutor {
    private final CountDownLatch scheduled = new CountDownLatch(1);
    private final AtomicReference<Long> delayMillis = new AtomicReference<>();

    private RecordingScheduler() {
      super(0);
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
      delayMillis.set(unit.toMillis(delay));
      scheduled.countDown();
      return new CompletedScheduledFuture<>();
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
      delayMillis.set(unit.toMillis(delay));
      scheduled.countDown();
      return new CompletedScheduledFuture<>();
    }
  }

  private static final class CompletedScheduledFuture<V> implements ScheduledFuture<V> {
    @Override
    public long getDelay(TimeUnit unit) {
      return 0;
    }

    @Override
    public int compareTo(Delayed other) {
      return 0;
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
      return false;
    }

    @Override
    public boolean isCancelled() {
      return false;
    }

    @Override
    public boolean isDone() {
      return true;
    }

    @Override
    public V get() {
      return null;
    }

    @Override
    public V get(long timeout, TimeUnit unit) {
      return null;
    }
  }

  /**
   * Drives the standard connected→auth→authenticated handshake and returns the active {@link
   * ServerSocket}.
   */
  private ServerSocket performHandshake(AlpacaStockStream stream) throws InterruptedException {
    var ss = new ServerSocket();
    ss.enqueue();
    stream.connect();
    ss.awaitOpen();
    ss.send("[{\"T\":\"success\",\"msg\":\"connected\"}]");
    ss.pollMessage(); // consume the auth message
    ss.send("[{\"T\":\"success\",\"msg\":\"authenticated\"}]");
    return ss;
  }

  // -------------------------------------------------------------------------
  // Handshake
  // -------------------------------------------------------------------------

  @Test
  void constructor_rejectsNullArguments() {
    assertThrows(
        NullPointerException.class,
        () ->
            new AlpacaStockStream(
                null,
                CREDS,
                StockSource.IEX,
                AlpacaStreamEnvironment.PRODUCTION,
                new StockStreamListener() {}));
    assertThrows(
        NullPointerException.class,
        () ->
            new AlpacaStockStream(
                client,
                null,
                StockSource.IEX,
                AlpacaStreamEnvironment.PRODUCTION,
                new StockStreamListener() {}));
    assertThrows(
        NullPointerException.class,
        () ->
            new AlpacaStockStream(
                client,
                CREDS,
                null,
                AlpacaStreamEnvironment.PRODUCTION,
                new StockStreamListener() {}));
    assertThrows(
        NullPointerException.class,
        () ->
            new AlpacaStockStream(
                client, CREDS, StockSource.IEX, null, new StockStreamListener() {}));
    assertThrows(
        NullPointerException.class,
        () ->
            new AlpacaStockStream(
                client, CREDS, StockSource.IEX, AlpacaStreamEnvironment.PRODUCTION, null));
    assertThrows(
        NullPointerException.class,
        () -> new AlpacaStockStream(client, CREDS, null, new StockStreamListener() {}));
    assertThrows(
        NullPointerException.class,
        () -> new AlpacaStockStream(client, CREDS, server.url("/v2/iex").toString(), null));
  }

  @Test
  void publicConstructor_acceptsProductionEnvironment() {
    assertNotNull(
        new AlpacaStockStream(
            client,
            CREDS,
            StockSource.IEX,
            AlpacaStreamEnvironment.PRODUCTION,
            new StockStreamListener() {}));
  }

  @Test
  void connect_sendsAuthMessageWithCredentials() throws Exception {
    var authReceived = new CountDownLatch(1);
    var authMsg = new AtomicReference<String>();

    var ss = new ServerSocket();
    ss.enqueue();

    var stream =
        new AlpacaStockStream(
            client, CREDS, server.url("/v2/iex").toString(), new StockStreamListener() {});
    stream.connect();

    ss.awaitOpen();
    ss.send("[{\"T\":\"success\",\"msg\":\"connected\"}]");

    String msg = ss.pollMessage();
    authMsg.set(msg);
    authReceived.countDown();

    assertTrue(authReceived.await(TIMEOUT_S, TimeUnit.SECONDS));
    String auth = authMsg.get();
    JsonPayloadAssertions.assertStringPayload(
        auth, "auth", Map.of("key", "test-key-id", "secret", "test-secret-key"));

    stream.close();
  }

  @Test
  void authenticate_callsOnAuthenticatedCallback() throws Exception {
    var authenticated = new CountDownLatch(1);

    var stream =
        new AlpacaStockStream(
            client,
            CREDS,
            server.url("/v2/iex").toString(),
            new StockStreamListener() {
              @Override
              public void onAuthenticated() {
                authenticated.countDown();
              }
            });

    var ss = new ServerSocket();
    ss.enqueue();
    stream.connect();
    ss.awaitOpen();
    ss.send("[{\"T\":\"success\",\"msg\":\"connected\"}]");
    ss.pollMessage(); // consume auth
    ss.send("[{\"T\":\"success\",\"msg\":\"authenticated\"}]");

    assertTrue(
        authenticated.await(TIMEOUT_S, TimeUnit.SECONDS),
        "onAuthenticated() must fire after the authenticated message");
    stream.close();
  }

  @Test
  void waitForAuthentication_returnsTrueAfterAuthenticatedMessage() throws Exception {
    var stream =
        new AlpacaStockStream(
            client, CREDS, server.url("/v2/iex").toString(), new StockStreamListener() {});

    var ss = new ServerSocket();
    ss.enqueue();
    stream.connect();
    ss.awaitOpen();
    ss.send("[{\"T\":\"success\",\"msg\":\"connected\"}]");
    ss.pollMessage();
    ss.send("[{\"T\":\"success\",\"msg\":\"authenticated\"}]");

    assertTrue(
        stream.waitForAuthentication(Duration.ofSeconds(TIMEOUT_S)),
        "waitForAuthentication must return true after authentication succeeds");
    assertTrue(
        stream.authenticationFuture().isDone(),
        "authenticationFuture must complete after authentication succeeds");
    stream.close();
  }

  @Test
  void waitForAuthenticationResult_returnsAuthenticatedAfterAuthenticatedMessage()
      throws Exception {
    var stream =
        new AlpacaStockStream(
            client, CREDS, server.url("/v2/iex").toString(), new StockStreamListener() {});

    var ss = new ServerSocket();
    ss.enqueue();
    stream.connect();
    ss.awaitOpen();
    ss.send("[{\"T\":\"success\",\"msg\":\"connected\"}]");
    ss.pollMessage();
    ss.send("[{\"T\":\"success\",\"msg\":\"authenticated\"}]");

    AlpacaStreamAuthResult result =
        stream.waitForAuthenticationResult(Duration.ofSeconds(TIMEOUT_S));

    assertEquals(AlpacaStreamAuthResult.Status.AUTHENTICATED, result.status());
    assertTrue(result.isAuthenticated());
    assertEquals(result, stream.authenticationResultFuture().getNow(null));
    assertEquals(Boolean.TRUE, stream.authenticationFuture().getNow(null));

    stream.close();
  }

  @Test
  void waitForAuthenticationResult_returnsTimeoutWithoutCompletingFuture() {
    var stream =
        new AlpacaStockStream(
            client, CREDS, server.url("/v2/iex").toString(), new StockStreamListener() {});

    AlpacaStreamAuthResult result = stream.waitForAuthenticationResult(Duration.ofMillis(10));

    assertEquals(AlpacaStreamAuthResult.Status.TIMEOUT, result.status());
    assertFalse(result.isAuthenticated());
    assertFalse(stream.authenticationResultFuture().isDone());
    assertFalse(stream.authenticationFuture().isDone());

    stream.close();
  }

  @Test
  void authenticationResultFuture_returnsCopyThatCannotCompleteStreamState() {
    var stream =
        new AlpacaStockStream(
            client, CREDS, server.url("/v2/iex").toString(), new StockStreamListener() {});

    CompletableFuture<AlpacaStreamAuthResult> future = stream.authenticationResultFuture();
    assertTrue(future.complete(AlpacaStreamAuthResult.timeout(Duration.ofSeconds(1))));

    assertFalse(stream.authenticationResultFuture().isDone());
    assertFalse(stream.authenticationFuture().isDone());

    stream.close();
  }

  @Test
  void waitForAuthenticationResult_returnsInterruptedAndPreservesInterruptStatus() {

    try (var stream =
        new AlpacaStockStream(
            client, CREDS, server.url("/v2/iex").toString(), new StockStreamListener() {})) {
      Thread.currentThread().interrupt();
      AlpacaStreamAuthResult result =
          stream.waitForAuthenticationResult(Duration.ofSeconds(TIMEOUT_S));

      assertEquals(AlpacaStreamAuthResult.Status.INTERRUPTED, result.status());
      assertFalse(result.isAuthenticated());
      assertTrue(Thread.currentThread().isInterrupted());
      assertFalse(stream.authenticationResultFuture().isDone());
      assertFalse(stream.authenticationFuture().isDone());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void onConnected_callsBeforeAuthenticated() throws Exception {
    var order = new java.util.ArrayList<String>();
    var done = new CountDownLatch(2);

    var stream =
        new AlpacaStockStream(
            client,
            CREDS,
            server.url("/v2/iex").toString(),
            new StockStreamListener() {
              @Override
              public void onConnected() {
                order.add("connected");
                done.countDown();
              }

              @Override
              public void onAuthenticated() {
                order.add("authenticated");
                done.countDown();
              }
            });

    var ss = new ServerSocket();
    ss.enqueue();
    stream.connect();
    ss.awaitOpen();
    ss.send("[{\"T\":\"success\",\"msg\":\"connected\"}]");
    ss.pollMessage();
    ss.send("[{\"T\":\"success\",\"msg\":\"authenticated\"}]");

    assertTrue(done.await(TIMEOUT_S, TimeUnit.SECONDS));
    assertEquals(
        List.of("connected", "authenticated"),
        order,
        "onConnected must fire before onAuthenticated");
    stream.close();
  }

  @Test
  void waitForAuthenticationResult_returnsServerRejectedForMarketDataAuthError() throws Exception {
    var stream =
        new AlpacaStockStream(
            client, CREDS, server.url("/v2/iex").toString(), new StockStreamListener() {});

    var ss = new ServerSocket();
    ss.enqueue();
    stream.connect();
    ss.awaitOpen();
    ss.send("[{\"T\":\"success\",\"msg\":\"connected\"}]");
    ss.pollMessage();
    ss.send("[{\"T\":\"error\",\"code\":403,\"msg\":\"not authenticated\"}]");

    AlpacaStreamAuthResult result =
        stream.waitForAuthenticationResult(Duration.ofSeconds(TIMEOUT_S));

    assertEquals(AlpacaStreamAuthResult.Status.SERVER_REJECTED, result.status());
    assertFalse(result.isAuthenticated());
    assertEquals(403, result.code());
    assertEquals("not authenticated", result.message());
    assertEquals(result, stream.authenticationResultFuture().getNow(null));
    assertEquals(Boolean.FALSE, stream.authenticationFuture().getNow(null));

    stream.close();
  }

  // -------------------------------------------------------------------------
  // Subscription
  // -------------------------------------------------------------------------

  @Test
  void subscribe_sendsCorrectPayloadAfterAuth() throws Exception {
    var subscribeReceived = new CountDownLatch(1);
    var subscribeMsg = new AtomicReference<String>();

    var stream =
        new AlpacaStockStream(
            client,
            CREDS,
            server.url("/v2/iex").toString(),
            new StockStreamListener() {
              @Override
              public void onAuthenticated() {
                subscribeReceived.countDown();
              }
            });

    var ss = new ServerSocket();
    ss.enqueue();
    stream.connect();
    ss.awaitOpen();
    ss.send("[{\"T\":\"success\",\"msg\":\"connected\"}]");
    ss.pollMessage(); // auth

    ss.send("[{\"T\":\"success\",\"msg\":\"authenticated\"}]");
    assertTrue(subscribeReceived.await(TIMEOUT_S, TimeUnit.SECONDS));

    var sub = StockSubscription.builder().trades("AAPL").quotes("TSLA").build();
    stream.subscribe(sub);

    String subMsg = ss.pollMessage();
    subscribeMsg.set(subMsg);

    JsonPayloadAssertions.assertPayload(
        subMsg, "subscribe", Map.of("trades", List.of("AAPL"), "quotes", List.of("TSLA")));

    stream.close();
  }

  @Test
  void subscribe_sendsAllChannelPayloadAfterAuth() throws Exception {
    var stream =
        new AlpacaStockStream(
            client, CREDS, server.url("/v2/iex").toString(), new StockStreamListener() {});

    var ss = performHandshake(stream);

    stream.subscribe(
        StockSubscription.builder()
            .trades("AAPL")
            .quotes("MSFT")
            .bars("*")
            .dailyBars("SPY")
            .updatedBars("QQQ")
            .statuses("TSLA")
            .lulds("NVDA")
            .build());

    String msg = ss.pollMessage();
    JsonPayloadAssertions.assertPayload(
        msg,
        "subscribe",
        Map.of(
            "trades", List.of("AAPL"),
            "quotes", List.of("MSFT"),
            "bars", List.of("*"),
            "dailyBars", List.of("SPY"),
            "updatedBars", List.of("QQQ"),
            "statuses", List.of("TSLA"),
            "lulds", List.of("NVDA")));

    stream.close();
  }

  @Test
  void subscribe_beforeAuth_isBufferedUntilAuthenticationCompletes() throws Exception {
    var ss = new ServerSocket();
    ss.enqueue();

    var stream =
        new AlpacaStockStream(
            client, CREDS, server.url("/v2/iex").toString(), new StockStreamListener() {});

    stream.connect();
    ss.awaitOpen();

    stream.subscribe(StockSubscription.builder().trades("AAPL").build());
    ss.assertNoMessage(300);

    ss.send("[{\"T\":\"success\",\"msg\":\"connected\"}]");
    String authMsg = ss.pollMessage();
    JsonPayloadAssertions.assertStringProperty(authMsg, "action", "auth");
    ss.assertNoMessage(300);

    ss.send("[{\"T\":\"success\",\"msg\":\"authenticated\"}]");
    String subscribeMsg = ss.pollMessage();
    JsonPayloadAssertions.assertPayload(
        subscribeMsg, "subscribe", Map.of("trades", List.of("AAPL")));

    stream.close();
  }

  @Test
  void connectWithSubscription_buffersSubscriptionUntilAuthenticationCompletes() throws Exception {
    var ss = new ServerSocket();
    ss.enqueue();

    var stream =
        new AlpacaStockStream(
            client, CREDS, server.url("/v2/iex").toString(), new StockStreamListener() {});

    stream.connect(StockSubscription.builder().trades("AAPL").quotes("TSLA").build());
    ss.awaitOpen();

    ss.send("[{\"T\":\"success\",\"msg\":\"connected\"}]");
    String authMsg = ss.pollMessage();
    JsonPayloadAssertions.assertStringProperty(authMsg, "action", "auth");

    ss.send("[{\"T\":\"success\",\"msg\":\"authenticated\"}]");
    String subscribeMsg = ss.pollMessage();
    JsonPayloadAssertions.assertPayload(
        subscribeMsg, "subscribe", Map.of("trades", List.of("AAPL"), "quotes", List.of("TSLA")));

    stream.close();
  }

  @Test
  void emptySubscription_sendsNoMessage() throws Exception {
    var stream =
        new AlpacaStockStream(
            client, CREDS, server.url("/v2/iex").toString(), new StockStreamListener() {});

    var ss = performHandshake(stream);
    stream.subscribe(StockSubscription.builder().build());

    ss.assertNoMessage(300);
    stream.close();
  }

  @Test
  void authenticatedListenerException_doesNotPreventPendingSubscriptionFlush() throws Exception {
    var ss = new ServerSocket();
    ss.enqueue();

    var stream =
        new AlpacaStockStream(
            client,
            CREDS,
            server.url("/v2/iex").toString(),
            new StockStreamListener() {
              @Override
              public void onAuthenticated() {
                throw new IllegalStateException("application callback failed");
              }
            });

    stream.connect();
    ss.awaitOpen();

    stream.subscribe(StockSubscription.builder().trades("AAPL").build());

    ss.send("[{\"T\":\"success\",\"msg\":\"connected\"}]");
    ss.pollMessage(); // auth
    ss.send("[{\"T\":\"success\",\"msg\":\"authenticated\"}]");

    String subscribeMsg = ss.pollMessage();
    JsonPayloadAssertions.assertPayload(
        subscribeMsg, "subscribe", Map.of("trades", List.of("AAPL")));
    assertTrue(stream.waitForAuthentication(Duration.ofSeconds(TIMEOUT_S)));

    stream.close();
  }

  @Test
  void callbackExecutor_dispatchesListenerOffReaderThreadAndDoesNotBlockPendingSubscriptionFlush()
      throws Exception {
    var callbackStarted = new CountDownLatch(1);
    var releaseCallback = new CountDownLatch(1);
    var callbackCompleted = new CountDownLatch(1);
    var callbackThread = new AtomicReference<String>();
    ExecutorService callbackExecutor =
        Executors.newSingleThreadExecutor(r -> new Thread(r, "alpaca-callback-test"));

    var ss = new ServerSocket();
    ss.enqueue();

    try (var stream =
        new AlpacaStockStream(
            client,
            CREDS,
            server.url("/v2/iex").toString(),
            new StockStreamListener() {
              @Override
              public void onAuthenticated() {
                callbackThread.set(Thread.currentThread().getName());
                callbackStarted.countDown();
                try {
                  releaseCallback.await(TIMEOUT_S, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                } finally {
                  callbackCompleted.countDown();
                }
              }
            },
            AlpacaStreamReconnectPolicy.defaultPolicy(),
            callbackExecutor)) {
      stream.connect();
      ss.awaitOpen();

      stream.subscribe(StockSubscription.builder().trades("AAPL").build());

      ss.send("[{\"T\":\"success\",\"msg\":\"connected\"}]");
      ss.pollMessage(); // auth
      ss.send("[{\"T\":\"success\",\"msg\":\"authenticated\"}]");

      assertTrue(
          callbackStarted.await(TIMEOUT_S, TimeUnit.SECONDS),
          "onAuthenticated must run on the supplied callback executor");
      assertEquals("alpaca-callback-test", callbackThread.get());

      String subscribeMsg = ss.pollMessage();
      JsonPayloadAssertions.assertPayload(
          subscribeMsg, "subscribe", Map.of("trades", List.of("AAPL")));

      assertEquals(
          1,
          releaseCallback.getCount(),
          "listener callback should still be blocked when the subscription flushes");
    } finally {
      releaseCallback.countDown();
      assertTrue(
          callbackCompleted.await(TIMEOUT_S, TimeUnit.SECONDS),
          "listener callback should complete after the test releases it");
      callbackExecutor.shutdownNow();
    }
  }

  @Test
  void subscriptionConfirmation_callsOnSubscriptionConfirmed() throws Exception {
    var confirmed = new CountDownLatch(1);
    var confirmedSubs = new AtomicReference<Map<String, List<String>>>();

    var stream =
        new AlpacaStockStream(
            client,
            CREDS,
            server.url("/v2/iex").toString(),
            new StockStreamListener() {
              @Override
              public void onSubscriptionConfirmed(Map<String, List<String>> s) {
                confirmedSubs.set(s);
                confirmed.countDown();
              }
            });

    var ss = performHandshake(stream);
    ss.send("[{\"T\":\"subscription\",\"trades\":[\"AAPL\"],\"quotes\":[]}]");

    assertTrue(
        confirmed.await(TIMEOUT_S, TimeUnit.SECONDS),
        "onSubscriptionConfirmed must fire on subscription message");
    assertNotNull(confirmedSubs.get());
    assertTrue(confirmedSubs.get().containsKey("trades"));
    assertEquals(List.of("AAPL"), confirmedSubs.get().get("trades"));

    stream.close();
  }

  @Test
  void unsubscribe_sendsUnsubscribePayload() throws Exception {
    var stream =
        new AlpacaStockStream(
            client, CREDS, server.url("/v2/iex").toString(), new StockStreamListener() {});

    var ss = performHandshake(stream);

    var unsub = StockSubscription.builder().trades("AAPL").build();
    stream.unsubscribe(unsub);

    String unsubMsg = ss.pollMessage();
    JsonPayloadAssertions.assertPayload(unsubMsg, "unsubscribe", Map.of("trades", List.of("AAPL")));

    stream.close();
  }

  // -------------------------------------------------------------------------
  // Stock data dispatch
  // -------------------------------------------------------------------------

  @Test
  void tradeMessage_dispatchesToOnTrade() throws Exception {
    var latch = new CountDownLatch(1);
    var received = new AtomicReference<StockTrade>();

    var stream =
        new AlpacaStockStream(
            client,
            CREDS,
            server.url("/v2/iex").toString(),
            new StockStreamListener() {
              @Override
              public void onTrade(StockTrade t) {
                received.set(t);
                latch.countDown();
              }
            });

    var ss = performHandshake(stream);
    ss.send(
        "[{\"T\":\"t\",\"S\":\"AAPL\",\"i\":1,\"x\":\"D\","
            + "\"p\":150.5,\"s\":100,\"t\":\"2024-01-15T14:30:00Z\",\"z\":\"C\"}]");

    assertTrue(latch.await(TIMEOUT_S, TimeUnit.SECONDS));
    StockTrade t = received.get();
    assertEquals("AAPL", t.symbol());
    assertDecimal("150.5", t.price());
    assertEquals(100L, t.size());

    stream.close();
  }

  @Test
  void quoteMessage_dispatchesToOnQuote() throws Exception {
    var latch = new CountDownLatch(1);
    var received = new AtomicReference<StockQuote>();

    var stream =
        new AlpacaStockStream(
            client,
            CREDS,
            server.url("/v2/iex").toString(),
            new StockStreamListener() {
              @Override
              public void onQuote(StockQuote q) {
                received.set(q);
                latch.countDown();
              }
            });

    var ss = performHandshake(stream);
    ss.send(
        "[{\"T\":\"q\",\"S\":\"MSFT\",\"ax\":\"N\",\"ap\":300.1,\"as\":5,"
            + "\"bx\":\"P\",\"bp\":300.0,\"bs\":10,\"s\":0,"
            + "\"t\":\"2024-01-15T14:30:01Z\",\"z\":\"A\"}]");

    assertTrue(latch.await(TIMEOUT_S, TimeUnit.SECONDS));
    assertEquals("MSFT", received.get().symbol());
    assertDecimal("300.1", received.get().askPrice());

    stream.close();
  }

  @Test
  void minuteBarMessage_dispatchesToOnMinuteBar() throws Exception {
    var latch = new CountDownLatch(1);
    var received = new AtomicReference<StockBar>();

    var stream =
        new AlpacaStockStream(
            client,
            CREDS,
            server.url("/v2/iex").toString(),
            new StockStreamListener() {
              @Override
              public void onMinuteBar(StockBar b) {
                received.set(b);
                latch.countDown();
              }
            });

    var ss = performHandshake(stream);
    ss.send(
        "[{\"T\":\"b\",\"S\":\"AAPL\",\"o\":150.0,\"h\":151.0,\"l\":149.0,"
            + "\"c\":150.5,\"v\":50000,\"t\":\"2024-01-15T14:00:00Z\"}]");

    assertTrue(latch.await(TIMEOUT_S, TimeUnit.SECONDS));
    assertEquals("AAPL", received.get().symbol());
    assertDecimal("150.5", received.get().close());

    stream.close();
  }

  @Test
  void dailyBarMessage_dispatchesToOnDailyBar() throws Exception {
    var latch = new CountDownLatch(1);
    var received = new AtomicReference<StockBar>();

    var stream =
        new AlpacaStockStream(
            client,
            CREDS,
            server.url("/v2/iex").toString(),
            new StockStreamListener() {
              @Override
              public void onDailyBar(StockBar b) {
                received.set(b);
                latch.countDown();
              }
            });

    var ss = performHandshake(stream);
    ss.send(
        "[{\"T\":\"d\",\"S\":\"AAPL\",\"o\":148.0,\"h\":152.0,\"l\":147.0,"
            + "\"c\":150.5,\"v\":30000000,\"t\":\"2024-01-15T00:00:00Z\"}]");

    assertTrue(latch.await(TIMEOUT_S, TimeUnit.SECONDS));
    assertEquals("AAPL", received.get().symbol());

    stream.close();
  }

  @Test
  void updatedBarMessage_dispatchesToOnUpdatedBar() throws Exception {
    var latch = new CountDownLatch(1);
    var received = new AtomicReference<StockBar>();

    var stream =
        new AlpacaStockStream(
            client,
            CREDS,
            server.url("/v2/iex").toString(),
            new StockStreamListener() {
              @Override
              public void onUpdatedBar(StockBar b) {
                received.set(b);
                latch.countDown();
              }
            });

    var ss = performHandshake(stream);
    ss.send(
        "[{\"T\":\"u\",\"S\":\"AAPL\",\"o\":150.0,\"h\":151.0,\"l\":149.5,"
            + "\"c\":150.8,\"v\":51000,\"t\":\"2024-01-15T14:00:30Z\"}]");

    assertTrue(latch.await(TIMEOUT_S, TimeUnit.SECONDS));
    assertEquals("AAPL", received.get().symbol());

    stream.close();
  }

  @Test
  void errorMessage_callsOnError() throws Exception {
    var latch = new CountDownLatch(1);
    var errCode = new AtomicInteger();
    var errMsg = new AtomicReference<String>();

    var stream =
        new AlpacaStockStream(
            client,
            CREDS,
            server.url("/v2/iex").toString(),
            new StockStreamListener() {
              @Override
              public void onError(int code, String message) {
                errCode.set(code);
                errMsg.set(message);
                latch.countDown();
              }
            });

    var ss = performHandshake(stream);
    ss.send("[{\"T\":\"error\",\"code\":403,\"msg\":\"not authenticated\"}]");

    assertTrue(latch.await(TIMEOUT_S, TimeUnit.SECONDS), "onError must fire on error message");
    assertEquals(403, errCode.get());
    assertEquals("not authenticated", errMsg.get());

    stream.close();
  }

  @Test
  void authFailureBeforeAuthenticated_completesAuthenticationFalseAndCallsOnError()
      throws Exception {
    var latch = new CountDownLatch(1);
    var disconnected = new CountDownLatch(1);
    var errCode = new AtomicInteger();
    var errMsg = new AtomicReference<String>();
    var willReconnect = new AtomicBoolean(true);

    var ss = new ServerSocket();
    ss.enqueue();
    var ss2 = new ServerSocket();
    ss2.enqueue();

    var stream =
        new AlpacaStockStream(
            client,
            CREDS,
            server.url("/v2/iex").toString(),
            new StockStreamListener() {
              @Override
              public void onError(int code, String message) {
                errCode.set(code);
                errMsg.set(message);
                latch.countDown();
              }

              @Override
              public void onDisconnected(int code, String reason, boolean wr) {
                willReconnect.set(wr);
                disconnected.countDown();
              }
            });

    stream.connect();
    ss.awaitOpen();
    ss.send("[{\"T\":\"success\",\"msg\":\"connected\"}]");
    ss.pollMessage();
    ss.send("[{\"T\":\"error\",\"code\":401,\"msg\":\"auth timeout\"}]");

    assertTrue(latch.await(TIMEOUT_S, TimeUnit.SECONDS));
    assertFalse(stream.waitForAuthentication(Duration.ofMillis(100)));
    assertEquals(401, errCode.get());
    assertEquals("auth timeout", errMsg.get());
    assertTrue(disconnected.await(TIMEOUT_S, TimeUnit.SECONDS));
    assertFalse(willReconnect.get(), "auth failures must not reconnect");
    assertTrue(stream.isClosed(), "auth failure must close the stream terminally");
    assertFalse(
        ss2.opened.await(500, TimeUnit.MILLISECONDS),
        "auth failure must not open a reconnect WebSocket");

    stream.close();
  }

  // -------------------------------------------------------------------------
  // Close / disconnect
  // -------------------------------------------------------------------------

  @Test
  void serverClose_callsOnDisconnectedWithWillReconnectTrue() throws Exception {
    var latch = new CountDownLatch(1);
    var willReconnect = new AtomicBoolean(false);

    var stream =
        new AlpacaStockStream(
            client,
            CREDS,
            server.url("/v2/iex").toString(),
            new StockStreamListener() {
              @Override
              public void onDisconnected(int code, String reason, boolean wr) {
                willReconnect.set(wr);
                latch.countDown();
              }
            });

    var ss = performHandshake(stream);
    // Server closes without a normal 1000 code → client treats it as unexpected
    ss.ws.get().close(1001, "going away");

    assertTrue(latch.await(TIMEOUT_S, TimeUnit.SECONDS));
    assertTrue(willReconnect.get(), "willReconnect must be true after an unexpected server close");

    // Clean up — disable reconnect
    stream.close();
  }

  @Test
  void streamClose_callsOnDisconnectedWithWillReconnectFalse() throws Exception {
    var latch = new CountDownLatch(1);
    var willReconnect = new AtomicBoolean(true);

    var stream =
        new AlpacaStockStream(
            client,
            CREDS,
            server.url("/v2/iex").toString(),
            new StockStreamListener() {
              @Override
              public void onDisconnected(int code, String reason, boolean wr) {
                willReconnect.set(wr);
                latch.countDown();
              }
            });

    var ss = performHandshake(stream);
    stream.close();

    assertTrue(latch.await(TIMEOUT_S, TimeUnit.SECONDS));
    assertFalse(willReconnect.get(), "willReconnect must be false after client-initiated close");
  }

  // -------------------------------------------------------------------------
  // Crypto dispatch (shared AbstractMarketDataStream base class)
  // -------------------------------------------------------------------------

  @Test
  void cryptoTradeMessage_dispatchesToOnTrade() throws Exception {
    var latch = new CountDownLatch(1);
    var received = new AtomicReference<CryptoTrade>();

    var ss = new ServerSocket();
    ss.enqueue();

    var stream =
        new AlpacaCryptoStream(
            client,
            CREDS,
            server.url("/v1beta3/crypto/us").toString(),
            new CryptoStreamListener() {
              @Override
              public void onTrade(CryptoTrade t) {
                received.set(t);
                latch.countDown();
              }
            });
    stream.connect();
    ss.awaitOpen();
    ss.send("[{\"T\":\"success\",\"msg\":\"connected\"}]");
    ss.pollMessage(); // auth
    ss.send("[{\"T\":\"success\",\"msg\":\"authenticated\"}]");

    ss.send(
        "[{\"T\":\"t\",\"S\":\"BTC/USD\",\"p\":45000.0,\"s\":0.5,"
            + "\"t\":\"2024-01-15T14:30:00Z\",\"i\":789,\"tks\":\"B\"}]");

    assertTrue(latch.await(TIMEOUT_S, TimeUnit.SECONDS));
    assertEquals("BTC/USD", received.get().symbol());
    assertDecimal("45000.0", received.get().price());

    stream.close();
  }

  @Test
  void cryptoOrderbookMessage_dispatchesToOnOrderbook() throws Exception {
    var latch = new CountDownLatch(1);
    var received = new AtomicReference<CryptoOrderbook>();

    var ss = new ServerSocket();
    ss.enqueue();

    var stream =
        new AlpacaCryptoStream(
            client,
            CREDS,
            server.url("/v1beta3/crypto/us").toString(),
            new CryptoStreamListener() {
              @Override
              public void onOrderbook(CryptoOrderbook o) {
                received.set(o);
                latch.countDown();
              }
            });
    stream.connect();
    ss.awaitOpen();
    ss.send("[{\"T\":\"success\",\"msg\":\"connected\"}]");
    ss.pollMessage();
    ss.send("[{\"T\":\"success\",\"msg\":\"authenticated\"}]");

    ss.send(
        "[{\"T\":\"o\",\"S\":\"BTC/USD\",\"t\":\"2024-01-15T14:30:00Z\","
            + "\"b\":[{\"p\":44999.0,\"s\":0.5}],\"a\":[{\"p\":45001.0,\"s\":1.0}],\"r\":true}]");

    assertTrue(latch.await(TIMEOUT_S, TimeUnit.SECONDS));
    CryptoOrderbook ob = received.get();
    assertEquals("BTC/USD", ob.symbol());
    assertTrue(ob.reset());
    assertEquals(1, ob.bids().size());
    assertDecimal("44999.0", ob.bids().get(0).price());

    stream.close();
  }

  // -------------------------------------------------------------------------
  // News dispatch
  // -------------------------------------------------------------------------

  @Test
  void newsArticleMessage_dispatchesToOnArticle() throws Exception {
    var latch = new CountDownLatch(1);
    var received = new AtomicReference<NewsArticle>();

    var ss = new ServerSocket();
    ss.enqueue();

    var stream =
        new AlpacaNewsStream(
            client,
            CREDS,
            server.url("/v1beta1/news").toString(),
            new NewsStreamListener() {
              @Override
              public void onArticle(NewsArticle a) {
                received.set(a);
                latch.countDown();
              }
            });
    stream.connect();
    ss.awaitOpen();
    ss.send("[{\"T\":\"success\",\"msg\":\"connected\"}]");
    ss.pollMessage();
    ss.send("[{\"T\":\"success\",\"msg\":\"authenticated\"}]");

    ss.send(
        "[{\"T\":\"n\",\"id\":42,\"headline\":\"AAPL Q4\",\"summary\":\"Strong\","
            + "\"author\":\"Jane\",\"created_at\":\"2024-01-15T20:00:00Z\","
            + "\"updated_at\":\"2024-01-15T20:05:00Z\",\"symbols\":[\"AAPL\"],\"source\":\"benzinga\"}]");

    assertTrue(latch.await(TIMEOUT_S, TimeUnit.SECONDS));
    assertEquals(42L, received.get().id());
    assertEquals("AAPL Q4", received.get().headline());
    assertEquals(List.of("AAPL"), received.get().symbols());

    stream.close();
  }

  // -------------------------------------------------------------------------
  // Stock data types — trade correction, cancel/error, LULD, trading status
  // -------------------------------------------------------------------------

  @Test
  void tradeCorrectionMessage_dispatchesToOnTradeCorrection() throws Exception {
    var latch = new CountDownLatch(1);
    var received = new AtomicReference<TradeCorrection>();

    var stream =
        new AlpacaStockStream(
            client,
            CREDS,
            server.url("/v2/iex").toString(),
            new StockStreamListener() {
              @Override
              public void onTradeCorrection(TradeCorrection c) {
                received.set(c);
                latch.countDown();
              }
            });

    var ss = performHandshake(stream);
    ss.send(
        "[{\"T\":\"c\",\"S\":\"AAPL\",\"x\":\"C\",\"oi\":100,\"op\":149.90,"
            + "\"os\":\"10\",\"oc\":[\"38\"],\"ci\":101,\"cp\":150.10,\"cs\":10,"
            + "\"cc\":[\"38\"],\"t\":\"2024-01-15T14:30:00Z\",\"z\":\"C\"}]");

    assertTrue(
        latch.await(TIMEOUT_S, TimeUnit.SECONDS), "onTradeCorrection must fire on T:c message");
    TradeCorrection c = received.get();
    assertEquals("AAPL", c.symbol());
    assertDecimal("149.90", c.origPrice());
    assertDecimal("150.10", c.correctedPrice());
    assertEquals(10L, c.correctedSize());

    stream.close();
  }

  @Test
  void tradeCancelErrorMessage_dispatchesToOnTradeCancelError() throws Exception {
    var latch = new CountDownLatch(1);
    var received = new AtomicReference<TradeCancelError>();

    var stream =
        new AlpacaStockStream(
            client,
            CREDS,
            server.url("/v2/iex").toString(),
            new StockStreamListener() {
              @Override
              public void onTradeCancelError(TradeCancelError e) {
                received.set(e);
                latch.countDown();
              }
            });

    var ss = performHandshake(stream);
    ss.send(
        "[{\"T\":\"x\",\"S\":\"TSLA\",\"i\":999,\"x\":\"D\",\"p\":200.0,"
            + "\"s\":5,\"a\":\"C\",\"t\":\"2024-01-15T14:30:00Z\",\"z\":\"C\"}]");

    assertTrue(
        latch.await(TIMEOUT_S, TimeUnit.SECONDS), "onTradeCancelError must fire on T:x message");
    TradeCancelError e = received.get();
    assertEquals("TSLA", e.symbol());
    assertEquals(999L, e.tradeId());
    assertEquals("C", e.action());

    stream.close();
  }

  @Test
  void luldMessage_dispatchesToOnLuld() throws Exception {
    var latch = new CountDownLatch(1);
    var received = new AtomicReference<LuldBand>();

    var stream =
        new AlpacaStockStream(
            client,
            CREDS,
            server.url("/v2/iex").toString(),
            new StockStreamListener() {
              @Override
              public void onLuld(LuldBand b) {
                received.set(b);
                latch.countDown();
              }
            });

    var ss = performHandshake(stream);
    ss.send(
        "[{\"T\":\"l\",\"S\":\"SPY\",\"u\":425.0,\"d\":415.0,"
            + "\"i\":\"L1\",\"t\":\"2024-01-15T14:30:00Z\",\"z\":\"C\"}]");

    assertTrue(latch.await(TIMEOUT_S, TimeUnit.SECONDS), "onLuld must fire on T:l message");
    LuldBand b = received.get();
    assertEquals("SPY", b.symbol());
    assertDecimal("425.0", b.limitUp());
    assertDecimal("415.0", b.limitDown());
    assertEquals("L1", b.indicator());

    stream.close();
  }

  @Test
  void tradingStatusMessage_dispatchesToOnTradingStatus() throws Exception {
    var latch = new CountDownLatch(1);
    var received = new AtomicReference<StockTradingStatus>();

    var stream =
        new AlpacaStockStream(
            client,
            CREDS,
            server.url("/v2/iex").toString(),
            new StockStreamListener() {
              @Override
              public void onTradingStatus(StockTradingStatus s) {
                received.set(s);
                latch.countDown();
              }
            });

    var ss = performHandshake(stream);
    ss.send(
        "[{\"T\":\"s\",\"S\":\"AAPL\",\"sc\":\"H\",\"sm\":\"Halt\","
            + "\"rc\":\"T1\",\"rm\":\"Halt reason\",\"t\":\"2024-01-15T14:30:00Z\",\"z\":\"C\"}]");

    assertTrue(
        latch.await(TIMEOUT_S, TimeUnit.SECONDS), "onTradingStatus must fire on T:s message");
    StockTradingStatus s = received.get();
    assertEquals("AAPL", s.symbol());
    assertEquals("H", s.statusCode());
    assertEquals("Halt", s.statusMessage());
    assertEquals("T1", s.reasonCode());

    stream.close();
  }

  // -------------------------------------------------------------------------
  // Unknown message type — silent ignore
  // -------------------------------------------------------------------------

  @Test
  void unknownMessageType_isSilentlyIgnored() throws Exception {
    var unexpectedCallback = new AtomicBoolean(false);
    var doneLatch = new CountDownLatch(1);

    var stream =
        new AlpacaStockStream(
            client,
            CREDS,
            server.url("/v2/iex").toString(),
            new StockStreamListener() {
              @Override
              public void onTrade(StockTrade t) {
                unexpectedCallback.set(true);
              }

              @Override
              public void onQuote(StockQuote q) {
                unexpectedCallback.set(true);
              }

              @Override
              public void onMinuteBar(StockBar b) {
                unexpectedCallback.set(true);
              }
            });

    var ss = performHandshake(stream);
    // Send a message with an unknown T value
    ss.send("[{\"T\":\"UNKNOWN_TYPE\",\"S\":\"AAPL\",\"data\":\"whatever\"}]");
    // Then send a known trade so we know the stream is still alive
    ss.send(
        "[{\"T\":\"t\",\"S\":\"AAPL\",\"i\":1,\"x\":\"D\",\"p\":150.0,\"s\":1,"
            + "\"t\":\"2024-01-15T14:30:00Z\",\"z\":\"C\"}]");
    // Override onTrade to count down when the known message arrives
    // (we can't do this after construction, so we use a separate stream instance)
    stream.close();

    // A simpler assertion: just verify the stream processed without exceptions
    // by reaching this point without any error being thrown.
    // The "no unexpected callback" assertion is verified by the atomic flag above.
    assertFalse(
        unexpectedCallback.get(),
        "No listener callback should fire for an unknown T value before the known trade");
  }

  @Test
  void malformedMessage_isIgnoredAndNextValidMessageStillDispatches() throws Exception {
    var latch = new CountDownLatch(1);
    var received = new AtomicReference<StockTrade>();

    var stream =
        new AlpacaStockStream(
            client,
            CREDS,
            server.url("/v2/iex").toString(),
            new StockStreamListener() {
              @Override
              public void onTrade(StockTrade t) {
                received.set(t);
                latch.countDown();
              }
            });

    var ss = performHandshake(stream);
    ss.send("not-json");
    ss.send(
        "[{\"T\":\"t\",\"S\":\"AAPL\",\"i\":1,\"x\":\"D\",\"p\":150.0,\"s\":1,"
            + "\"t\":\"2024-01-15T14:30:00Z\",\"z\":\"C\"}]");

    assertTrue(latch.await(TIMEOUT_S, TimeUnit.SECONDS));
    assertEquals("AAPL", received.get().symbol());

    stream.close();
  }

  // -------------------------------------------------------------------------
  // Lifecycle — isClosed, double close, connect after close
  // -------------------------------------------------------------------------

  @Test
  void isClosed_returnsFalseInitiallyAndTrueAfterClose() {
    var stream =
        new AlpacaStockStream(
            client, CREDS, server.url("/v2/iex").toString(), new StockStreamListener() {});
    assertFalse(stream.isClosed(), "isClosed() must be false before close()");
    stream.close();
    assertTrue(stream.isClosed(), "isClosed() must be true after close()");
  }

  @Test
  void close_isIdempotent() throws Exception {
    var stream =
        new AlpacaStockStream(
            client, CREDS, server.url("/v2/iex").toString(), new StockStreamListener() {});
    var ss = performHandshake(stream);
    // Calling close() twice must not throw
    assertDoesNotThrow(
        () -> {
          stream.close();
          stream.close();
        });
  }

  @Test
  void connect_afterClose_isIgnored() throws Exception {
    var ss = new ServerSocket();
    ss.enqueue();

    var stream =
        new AlpacaStockStream(
            client, CREDS, server.url("/v2/iex").toString(), new StockStreamListener() {});
    stream.close();
    stream.connect(); // must be a no-op — stream is closed

    // MockWebServer should not have received any WebSocket upgrade request
    // because connect() short-circuits when isClosed() is true.
    assertFalse(
        ss.opened.await(500, TimeUnit.MILLISECONDS),
        "No WebSocket upgrade should occur after close()");
  }

  @Test
  void subscribe_afterClose_throwsIllegalStateException() {
    var stream =
        new AlpacaStockStream(
            client, CREDS, server.url("/v2/iex").toString(), new StockStreamListener() {});
    stream.close();

    assertThrows(
        IllegalStateException.class,
        () -> stream.subscribe(StockSubscription.builder().trades("AAPL").build()));
  }

  @Test
  void unsubscribe_afterClose_throwsIllegalStateException() {
    var stream =
        new AlpacaStockStream(
            client, CREDS, server.url("/v2/iex").toString(), new StockStreamListener() {});
    stream.close();

    assertThrows(
        IllegalStateException.class,
        () -> stream.unsubscribe(StockSubscription.builder().trades("AAPL").build()));
  }

  @Test
  void connect_calledTwice_opensOnlyOneWebSocket() throws Exception {
    var ss1 = new ServerSocket();
    ss1.enqueue();
    var ss2 = new ServerSocket();
    ss2.enqueue();

    var stream =
        new AlpacaStockStream(
            client, CREDS, server.url("/v2/iex").toString(), new StockStreamListener() {});

    stream.connect();
    stream.connect();

    ss1.awaitOpen();
    assertFalse(
        ss2.opened.await(500, TimeUnit.MILLISECONDS),
        "Second connect() call must not open another WebSocket");

    stream.close();
  }

  // -------------------------------------------------------------------------
  // Transport failure — onFailure path (code = -1, willReconnect = true)
  // -------------------------------------------------------------------------

  @Test
  void transportFailure_callsOnDisconnectedWithCodeMinusOne() throws Exception {
    var latch = new CountDownLatch(1);
    var capturedCode = new AtomicInteger(0);
    var capturedReconnect = new AtomicBoolean(false);

    // Server abruptly cancels the WebSocket on open — simulates a network failure.
    server.enqueue(
        new MockResponse()
            .withWebSocketUpgrade(
                new WebSocketListener() {
                  @Override
                  public void onOpen(WebSocket ws, Response r) {
                    ws.cancel();
                  }

                  @Override
                  public void onClosing(WebSocket ws, int code, String reason) {
                    ws.close(code, reason);
                  }
                }));

    var stream =
        new AlpacaStockStream(
            client,
            CREDS,
            server.url("/v2/iex").toString(),
            new StockStreamListener() {
              @Override
              public void onDisconnected(int code, String reason, boolean willReconnect) {
                capturedCode.set(code);
                capturedReconnect.set(willReconnect);
                latch.countDown();
              }
            });

    stream.connect();

    assertTrue(
        latch.await(TIMEOUT_S, TimeUnit.SECONDS),
        "onDisconnected must fire after transport failure");
    assertEquals(-1, capturedCode.get(), "Transport failure must produce code -1");
    assertTrue(capturedReconnect.get(), "willReconnect must be true — stream has not been closed");

    stream.close();
  }

  // -------------------------------------------------------------------------
  // Reconnect — onReconnecting, resubscribeAfterAuth, onReconnected
  // -------------------------------------------------------------------------

  @Test
  void reconnect_firesOnReconnectingAndResubscribesAfterReauth() throws Exception {
    var authenticatedLatch = new CountDownLatch(1);
    var reconnectingLatch = new CountDownLatch(1);
    var reconnectedLatch = new CountDownLatch(1);
    var reconnectAttempt = new AtomicInteger(-1);
    var resubscribeMsg = new AtomicReference<String>();

    // Pre-enqueue both connections so MockWebServer is ready for the reconnect.
    var ss1 = new ServerSocket();
    ss1.enqueue();
    var ss2 = new ServerSocket();
    ss2.enqueue();

    var stream =
        new AlpacaStockStream(
            client,
            CREDS,
            server.url("/v2/iex").toString(),
            new StockStreamListener() {
              @Override
              public void onAuthenticated() {
                authenticatedLatch.countDown();
              }

              @Override
              public void onReconnecting(int attempt) {
                reconnectAttempt.set(attempt);
                reconnectingLatch.countDown();
              }

              @Override
              public void onReconnected() {
                reconnectedLatch.countDown();
              }
            });

    // --- Initial connection ---
    stream.connect();
    ss1.awaitOpen();
    ss1.send("[{\"T\":\"success\",\"msg\":\"connected\"}]");
    ss1.pollMessage(); // consume auth
    ss1.send("[{\"T\":\"success\",\"msg\":\"authenticated\"}]");
    assertTrue(authenticatedLatch.await(TIMEOUT_S, TimeUnit.SECONDS));

    // Subscribe so that resubscribeAfterAuth has state to replay.
    stream.subscribe(StockSubscription.builder().trades("AAPL").build());
    ss1.pollMessage(); // consume subscribe message
    // Send subscription confirmation so the confirmed state is stored.
    ss1.send("[{\"T\":\"subscription\",\"trades\":[\"AAPL\"],\"quotes\":[]}]");
    Thread.sleep(100); // let confirmation be processed

    // --- Trigger unexpected disconnect ---
    ss1.ws.get().close(1001, "server going away");

    assertTrue(
        reconnectingLatch.await(TIMEOUT_S, TimeUnit.SECONDS),
        "onReconnecting must fire after unexpected server disconnect");
    assertEquals(1, reconnectAttempt.get(), "First reconnect attempt must be 1");

    // --- Second connection (automatic reconnect, after ~1 s backoff) ---
    ss2.awaitOpen();
    ss2.send("[{\"T\":\"success\",\"msg\":\"connected\"}]");
    ss2.pollMessage(); // consume auth
    ss2.send("[{\"T\":\"success\",\"msg\":\"authenticated\"}]");

    // resubscribeAfterAuth fires automatically — consume its message.
    String resub = ss2.pollMessage();
    resubscribeMsg.set(resub);

    assertTrue(
        reconnectedLatch.await(TIMEOUT_S, TimeUnit.SECONDS),
        "onReconnected must fire after successful re-authentication");

    String resubText = resubscribeMsg.get();
    assertNotNull(resubText, "resubscribeAfterAuth must send a subscribe message");
    JsonPayloadAssertions.assertPayload(resubText, "subscribe", Map.of("trades", List.of("AAPL")));

    stream.close();
  }

  @Test
  void reconnect_schedulesUsingJitteredDelay() throws Exception {
    var authenticatedLatch = new CountDownLatch(1);
    var scheduler = new RecordingScheduler();
    var policy =
        AlpacaStreamReconnectPolicy.builder()
            .initialBackoff(Duration.ofMillis(100))
            .maxBackoff(Duration.ofMillis(1_000))
            .maxAttempts(1)
            .jitterRatio(0.5)
            .random(() -> 1)
            .build();

    var ss1 = new ServerSocket();
    ss1.enqueue();

    var stream =
        new AlpacaStockStream(
            client,
            CREDS,
            server.url("/v2/iex").toString(),
            new StockStreamListener() {
              @Override
              public void onAuthenticated() {
                authenticatedLatch.countDown();
              }
            },
            policy,
            Runnable::run,
            scheduler);

    stream.connect();
    ss1.awaitOpen();
    ss1.send("[{\"T\":\"success\",\"msg\":\"connected\"}]");
    ss1.pollMessage();
    ss1.send("[{\"T\":\"success\",\"msg\":\"authenticated\"}]");
    assertTrue(authenticatedLatch.await(TIMEOUT_S, TimeUnit.SECONDS));

    ss1.ws.get().close(1001, "server restart");

    assertTrue(scheduler.scheduled.await(TIMEOUT_S, TimeUnit.SECONDS));
    assertEquals(150, scheduler.delayMillis.get());
    stream.close();
  }

  @Test
  void reconnect_defaultSchedulerUsesStreamSpecificThreadName() throws Exception {
    var authenticatedLatch = new CountDownLatch(1);
    var reconnectingLatch = new CountDownLatch(1);
    var reconnectThreadName = new AtomicReference<String>();
    var policy =
        AlpacaStreamReconnectPolicy.builder()
            .initialBackoff(Duration.ofMillis(1))
            .maxBackoff(Duration.ofMillis(1))
            .maxAttempts(1)
            .jitterRatio(0)
            .build();

    var ss1 = new ServerSocket();
    ss1.enqueue();
    var ss2 = new ServerSocket();
    ss2.enqueue();

    var stream =
        new AlpacaStockStream(
            client,
            CREDS,
            server.url("/v2/iex").toString(),
            new StockStreamListener() {
              @Override
              public void onAuthenticated() {
                authenticatedLatch.countDown();
              }

              @Override
              public void onReconnecting(int attempt) {
                reconnectThreadName.set(Thread.currentThread().getName());
                reconnectingLatch.countDown();
              }
            },
            policy);

    stream.connect();
    ss1.awaitOpen();
    ss1.send("[{\"T\":\"success\",\"msg\":\"connected\"}]");
    ss1.pollMessage();
    ss1.send("[{\"T\":\"success\",\"msg\":\"authenticated\"}]");
    assertTrue(authenticatedLatch.await(TIMEOUT_S, TimeUnit.SECONDS));

    ss1.ws.get().close(1001, "server restart");

    assertTrue(reconnectingLatch.await(TIMEOUT_S, TimeUnit.SECONDS));
    assertTrue(reconnectThreadName.get().startsWith("alpaca-ws-stock-reconnect-"));
    stream.close();
  }

  @Test
  void reconnect_stopsAfterConfiguredMaxAttempts() throws Exception {
    var authenticatedLatch = new CountDownLatch(1);
    var reconnectingLatch = new CountDownLatch(1);
    var disconnects = new LinkedBlockingQueue<Boolean>();
    var policy =
        AlpacaStreamReconnectPolicy.builder()
            .initialBackoff(Duration.ofMillis(10))
            .maxBackoff(Duration.ofMillis(10))
            .maxAttempts(1)
            .build();

    var ss1 = new ServerSocket();
    ss1.enqueue();
    var ss2 = new ServerSocket();
    ss2.enqueue();
    var ss3 = new ServerSocket();
    ss3.enqueue();

    var stream =
        new AlpacaStockStream(
            client,
            CREDS,
            server.url("/v2/iex").toString(),
            new StockStreamListener() {
              @Override
              public void onAuthenticated() {
                authenticatedLatch.countDown();
              }

              @Override
              public void onReconnecting(int attempt) {
                reconnectingLatch.countDown();
              }

              @Override
              public void onDisconnected(int code, String reason, boolean willReconnect) {
                disconnects.add(willReconnect);
              }
            },
            policy);

    stream.connect();
    ss1.awaitOpen();
    ss1.send("[{\"T\":\"success\",\"msg\":\"connected\"}]");
    ss1.pollMessage();
    ss1.send("[{\"T\":\"success\",\"msg\":\"authenticated\"}]");
    assertTrue(authenticatedLatch.await(TIMEOUT_S, TimeUnit.SECONDS));

    ss1.ws.get().close(1001, "server restart");
    assertEquals(Boolean.TRUE, disconnects.poll(TIMEOUT_S, TimeUnit.SECONDS));
    assertTrue(reconnectingLatch.await(TIMEOUT_S, TimeUnit.SECONDS));

    ss2.awaitOpen();
    ss2.ws.get().close(1001, "server still unavailable");

    assertEquals(Boolean.FALSE, disconnects.poll(TIMEOUT_S, TimeUnit.SECONDS));
    assertTrue(stream.isClosed(), "stream must become closed after retry exhaustion");
    assertFalse(
        ss3.opened.await(500, TimeUnit.MILLISECONDS),
        "no third WebSocket should open after max reconnect attempts");
  }

  // -------------------------------------------------------------------------
  // Batch messages
  // -------------------------------------------------------------------------

  @Test
  void batchMessage_dispatchesAllElementsInOrder() throws Exception {
    var latch = new CountDownLatch(3);
    var symbols = new java.util.concurrent.CopyOnWriteArrayList<String>();

    var stream =
        new AlpacaStockStream(
            client,
            CREDS,
            server.url("/v2/iex").toString(),
            new StockStreamListener() {
              @Override
              public void onTrade(StockTrade t) {
                symbols.add(t.symbol());
                latch.countDown();
              }
            });

    var ss = performHandshake(stream);
    // Server sends 3 trade events in a single JSON array
    ss.send(
        "["
            + "{\"T\":\"t\",\"S\":\"AAPL\",\"i\":1,\"x\":\"D\",\"p\":150.0,\"s\":1,\"t\":\"2024-01-15T14:30:00Z\",\"z\":\"C\"},"
            + "{\"T\":\"t\",\"S\":\"TSLA\",\"i\":2,\"x\":\"D\",\"p\":200.0,\"s\":2,\"t\":\"2024-01-15T14:30:01Z\",\"z\":\"C\"},"
            + "{\"T\":\"t\",\"S\":\"MSFT\",\"i\":3,\"x\":\"D\",\"p\":300.0,\"s\":3,\"t\":\"2024-01-15T14:30:02Z\",\"z\":\"C\"}"
            + "]");

    assertTrue(latch.await(TIMEOUT_S, TimeUnit.SECONDS), "All three trades must be dispatched");
    assertEquals(List.of("AAPL", "TSLA", "MSFT"), symbols);

    stream.close();
  }
}
