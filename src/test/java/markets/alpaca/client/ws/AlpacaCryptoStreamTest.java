package markets.alpaca.client.ws;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import markets.alpaca.client.AlpacaCredentials;
import markets.alpaca.client.ws.model.CryptoBar;
import markets.alpaca.client.ws.model.CryptoOrderbook;
import markets.alpaca.client.ws.model.CryptoQuote;
import markets.alpaca.client.ws.model.CryptoTrade;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Unit tests for {@link AlpacaCryptoStream} using {@link MockWebServer}.
 *
 * <p>The market-data protocol (connected → auth → data) is shared with {@link AlpacaStockStream}
 * and thoroughly tested in {@link AlpacaStockStreamTest}; these tests focus on crypto-specific
 * message dispatch.
 */
@Timeout(10)
class AlpacaCryptoStreamTest {

  private static final AlpacaCredentials CREDS = new AlpacaCredentials("test-key", "test-secret");

  private MockWebServer server;
  private OkHttpClient httpClient;

  private static void assertDecimal(String expected, BigDecimal actual) {
    assertEquals(0, new BigDecimal(expected).compareTo(actual));
  }

  @BeforeEach
  void setUp() throws IOException {
    server = new MockWebServer();
    server.start();
    httpClient =
        new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build();
  }

  @AfterEach
  void tearDown() throws IOException {
    server.close();
  }

  private AlpacaCryptoStream streamWith(CryptoStreamListener listener) {
    return new AlpacaCryptoStream(
        httpClient, CREDS, server.url("/v1beta3/crypto/us").toString(), listener);
  }

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
      assertTrue(opened.await(5, TimeUnit.SECONDS), "WebSocket was not opened");
    }

    void send(String json) {
      ws.get().send(json);
    }

    String pollMessage() throws InterruptedException {
      return poll(messages);
    }
  }

  private String poll(BlockingQueue<String> messages) throws InterruptedException {
    String msg = messages.poll(5, TimeUnit.SECONDS);
    assertNotNull(msg, "Expected a client WebSocket message");
    return msg;
  }

  /** Enqueues a mock server that completes auth and then sends {@code dataMessage}. */
  private void enqueueAuthThenMessage(String dataMessage) {
    server.enqueue(
        new MockResponse()
            .withWebSocketUpgrade(
                new WebSocketListener() {
                  private boolean authed = false;

                  @Override
                  public void onOpen(WebSocket ws, Response r) {
                    ws.send("[{\"T\":\"success\",\"msg\":\"connected\"}]");
                  }

                  @Override
                  public void onMessage(WebSocket ws, String text) {
                    if (!authed && text.contains("\"action\":\"auth\"")) {
                      authed = true;
                      ws.send("[{\"T\":\"success\",\"msg\":\"authenticated\"}]");
                      ws.send(dataMessage);
                    }
                  }

                  @Override
                  public void onClosing(WebSocket ws, int code, String reason) {
                    ws.close(code, reason);
                  }
                }));
  }

  private void enqueueAuthAndCaptureClientMessages(BlockingQueue<String> messages) {
    server.enqueue(
        new MockResponse()
            .withWebSocketUpgrade(
                new WebSocketListener() {
                  private boolean authed = false;

                  @Override
                  public void onOpen(WebSocket ws, Response r) {
                    ws.send("[{\"T\":\"success\",\"msg\":\"connected\"}]");
                  }

                  @Override
                  public void onMessage(WebSocket ws, String text) {
                    if (!authed && text.contains("\"action\":\"auth\"")) {
                      authed = true;
                      ws.send("[{\"T\":\"success\",\"msg\":\"authenticated\"}]");
                    } else {
                      messages.add(text);
                    }
                  }

                  @Override
                  public void onClosing(WebSocket ws, int code, String reason) {
                    ws.close(code, reason);
                  }
                }));
  }

  @Test
  void constructor_rejectsNullArguments() {
    assertThrows(
        NullPointerException.class,
        () ->
            new AlpacaCryptoStream(
                null, CREDS, AlpacaStreamEnvironment.PRODUCTION, new CryptoStreamListener() {}));
    assertThrows(
        NullPointerException.class,
        () ->
            new AlpacaCryptoStream(
                httpClient,
                null,
                AlpacaStreamEnvironment.PRODUCTION,
                new CryptoStreamListener() {}));
    assertThrows(
        NullPointerException.class,
        () ->
            new AlpacaCryptoStream(
                httpClient, CREDS, (AlpacaStreamEnvironment) null, new CryptoStreamListener() {}));
    assertThrows(
        NullPointerException.class,
        () -> new AlpacaCryptoStream(httpClient, CREDS, AlpacaStreamEnvironment.PRODUCTION, null));
    assertThrows(
        NullPointerException.class,
        () ->
            new AlpacaCryptoStream(
                httpClient, CREDS, (String) null, new CryptoStreamListener() {}));
    assertThrows(
        NullPointerException.class,
        () -> new AlpacaCryptoStream(httpClient, CREDS, server.url("/").toString(), null));
  }

  @Test
  void publicConstructor_acceptsProductionEnvironment() {
    assertNotNull(
        new AlpacaCryptoStream(
            httpClient, CREDS, AlpacaStreamEnvironment.PRODUCTION, new CryptoStreamListener() {}));
  }

  @Test
  void publicConstructor_rejectsSandboxEnvironment() {
    var thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new AlpacaCryptoStream(
                    httpClient,
                    CREDS,
                    AlpacaStreamEnvironment.SANDBOX,
                    new CryptoStreamListener() {}));

    assertEquals(
        "Crypto stream is only available on AlpacaStreamEnvironment.PRODUCTION",
        thrown.getMessage());
  }

  @Test
  void subscribe_sendsAllChannelPayloadAfterAuth() throws Exception {
    var messages = new LinkedBlockingQueue<String>();
    var authenticated = new CountDownLatch(1);
    enqueueAuthAndCaptureClientMessages(messages);

    try (var stream =
        streamWith(
            new CryptoStreamListener() {
              @Override
              public void onAuthenticated() {
                authenticated.countDown();
              }
            })) {
      stream.connect();
      assertTrue(authenticated.await(5, TimeUnit.SECONDS));

      stream.subscribe(
          CryptoSubscription.builder()
              .trades("BTC/USD")
              .quotes("ETH/USD")
              .bars("*")
              .dailyBars("SOL/USD")
              .updatedBars("DOGE/USD")
              .orderbooks("AVAX/USD")
              .build());

      String msg = poll(messages);
      JsonPayloadAssertions.assertPayload(
          msg,
          "subscribe",
          Map.of(
              "trades", List.of("BTC/USD"),
              "quotes", List.of("ETH/USD"),
              "bars", List.of("*"),
              "dailyBars", List.of("SOL/USD"),
              "updatedBars", List.of("DOGE/USD"),
              "orderbooks", List.of("AVAX/USD")));
    }
  }

  @Test
  void connectWithSubscription_buffersSubscriptionUntilAuthenticationCompletes() throws Exception {
    var ss = new ServerSocket();
    ss.enqueue();

    try (var stream = streamWith(new CryptoStreamListener() {})) {
      stream.connect(CryptoSubscription.builder().trades("BTC/USD").bars("*").build());
      ss.awaitOpen();

      ss.send("[{\"T\":\"success\",\"msg\":\"connected\"}]");
      String authMsg = ss.pollMessage();
      JsonPayloadAssertions.assertStringProperty(authMsg, "action", "auth");

      ss.send("[{\"T\":\"success\",\"msg\":\"authenticated\"}]");
      String subscribeMsg = ss.pollMessage();
      JsonPayloadAssertions.assertPayload(
          subscribeMsg, "subscribe", Map.of("trades", List.of("BTC/USD"), "bars", List.of("*")));
    }
  }

  @Test
  void unsubscribe_sendsUnsubscribePayload() throws Exception {
    var messages = new LinkedBlockingQueue<String>();
    var authenticated = new CountDownLatch(1);
    enqueueAuthAndCaptureClientMessages(messages);

    try (var stream =
        streamWith(
            new CryptoStreamListener() {
              @Override
              public void onAuthenticated() {
                authenticated.countDown();
              }
            })) {
      stream.connect();
      assertTrue(authenticated.await(5, TimeUnit.SECONDS));

      stream.unsubscribe(CryptoSubscription.builder().orderbooks("BTC/USD").build());

      String msg = poll(messages);
      JsonPayloadAssertions.assertPayload(
          msg, "unsubscribe", Map.of("orderbooks", List.of("BTC/USD")));
    }
  }

  @Test
  void emptySubscription_sendsNoMessage() throws Exception {
    var messages = new LinkedBlockingQueue<String>();
    var authenticated = new CountDownLatch(1);
    enqueueAuthAndCaptureClientMessages(messages);

    try (var stream =
        streamWith(
            new CryptoStreamListener() {
              @Override
              public void onAuthenticated() {
                authenticated.countDown();
              }
            })) {
      stream.connect();
      assertTrue(authenticated.await(5, TimeUnit.SECONDS));

      stream.subscribe(CryptoSubscription.builder().build());

      assertNull(messages.poll(300, TimeUnit.MILLISECONDS));
    }
  }

  @Test
  void authError_completesAuthenticationFalseAndCallsOnError() throws Exception {
    var errorLatch = new CountDownLatch(1);
    var code = new AtomicReference<Integer>();
    var message = new AtomicReference<String>();

    server.enqueue(
        new MockResponse()
            .withWebSocketUpgrade(
                new WebSocketListener() {
                  @Override
                  public void onOpen(WebSocket ws, Response r) {
                    ws.send("[{\"T\":\"success\",\"msg\":\"connected\"}]");
                  }

                  @Override
                  public void onMessage(WebSocket ws, String text) {
                    ws.send("[{\"T\":\"error\",\"code\":401,\"msg\":\"auth failed\"}]");
                  }

                  @Override
                  public void onClosing(WebSocket ws, int code, String reason) {
                    ws.close(code, reason);
                  }
                }));

    try (var stream =
        streamWith(
            new CryptoStreamListener() {
              @Override
              public void onError(int c, String m) {
                code.set(c);
                message.set(m);
                errorLatch.countDown();
              }
            })) {
      stream.connect();

      assertTrue(errorLatch.await(5, TimeUnit.SECONDS));
      assertFalse(stream.waitForAuthentication(java.time.Duration.ofMillis(100)));
      assertEquals(401, code.get());
      assertEquals("auth failed", message.get());
    }
  }

  @Test
  void unknownMessageType_isIgnored() throws Exception {
    var unexpected = new java.util.concurrent.atomic.AtomicBoolean(false);
    enqueueAuthThenMessage("[{\"T\":\"z\",\"S\":\"BTC/USD\"}]");

    try (var stream =
        streamWith(
            new CryptoStreamListener() {
              @Override
              public void onTrade(CryptoTrade t) {
                unexpected.set(true);
              }

              @Override
              public void onQuote(CryptoQuote q) {
                unexpected.set(true);
              }

              @Override
              public void onOrderbook(CryptoOrderbook o) {
                unexpected.set(true);
              }
            })) {
      stream.connect();
      assertTrue(stream.waitForAuthentication(java.time.Duration.ofSeconds(5)));
      Thread.sleep(200);
    }

    assertFalse(unexpected.get());
  }

  @Test
  void reconnect_resubscribesAndFiresReconnectCallbacks() throws Exception {
    var authenticated = new CountDownLatch(1);
    var reconnecting = new CountDownLatch(1);
    var reconnected = new CountDownLatch(1);
    var reconnectAttempt = new AtomicReference<Integer>();

    var ss1 = new ServerSocket();
    ss1.enqueue();
    var ss2 = new ServerSocket();
    ss2.enqueue();

    try (var stream =
        streamWith(
            new CryptoStreamListener() {
              @Override
              public void onAuthenticated() {
                authenticated.countDown();
              }

              @Override
              public void onReconnecting(int attempt) {
                reconnectAttempt.set(attempt);
                reconnecting.countDown();
              }

              @Override
              public void onReconnected() {
                reconnected.countDown();
              }
            })) {
      stream.connect();
      ss1.awaitOpen();
      ss1.send("[{\"T\":\"success\",\"msg\":\"connected\"}]");
      ss1.pollMessage(); // auth
      ss1.send("[{\"T\":\"success\",\"msg\":\"authenticated\"}]");
      assertTrue(authenticated.await(5, TimeUnit.SECONDS));

      stream.subscribe(CryptoSubscription.builder().orderbooks("BTC/USD").build());
      ss1.pollMessage();
      ss1.send("[{\"T\":\"subscription\",\"orderbooks\":[\"BTC/USD\"]}]");
      Thread.sleep(100);

      ss1.ws.get().close(1001, "server restart");
      assertTrue(reconnecting.await(5, TimeUnit.SECONDS));
      assertEquals(1, reconnectAttempt.get());

      ss2.awaitOpen();
      ss2.send("[{\"T\":\"success\",\"msg\":\"connected\"}]");
      ss2.pollMessage(); // auth
      ss2.send("[{\"T\":\"success\",\"msg\":\"authenticated\"}]");

      String resubscribe = ss2.pollMessage();
      JsonPayloadAssertions.assertPayload(
          resubscribe, "subscribe", Map.of("orderbooks", List.of("BTC/USD")));
      assertTrue(reconnected.await(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void malformedMessage_isIgnoredAndNextValidMessageStillDispatches() throws Exception {
    var latch = new CountDownLatch(1);
    var captured = new AtomicReference<CryptoTrade>();

    server.enqueue(
        new MockResponse()
            .withWebSocketUpgrade(
                new WebSocketListener() {
                  private boolean authed = false;

                  @Override
                  public void onOpen(WebSocket ws, Response r) {
                    ws.send("[{\"T\":\"success\",\"msg\":\"connected\"}]");
                  }

                  @Override
                  public void onMessage(WebSocket ws, String text) {
                    if (!authed && text.contains("\"action\":\"auth\"")) {
                      authed = true;
                      ws.send("not-json");
                      ws.send(
                          "[{\"T\":\"t\",\"S\":\"BTC/USD\",\"p\":27000.5,\"s\":0.1,"
                              + "\"t\":\"2024-01-15T14:30:00Z\",\"i\":123456,\"tks\":\"B\"}]");
                    }
                  }

                  @Override
                  public void onClosing(WebSocket ws, int code, String reason) {
                    ws.close(code, reason);
                  }
                }));

    try (var stream =
        streamWith(
            new CryptoStreamListener() {
              @Override
              public void onTrade(CryptoTrade t) {
                captured.set(t);
                latch.countDown();
              }
            })) {
      stream.connect();
      assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    assertEquals("BTC/USD", captured.get().symbol());
  }

  @Test
  void tradeMessage_callsOnTrade() throws InterruptedException {
    var latch = new CountDownLatch(1);
    var captured = new AtomicReference<CryptoTrade>();

    String msg =
        "[{\"T\":\"t\",\"S\":\"BTC/USD\",\"p\":27000.5,\"s\":0.1,"
            + "\"t\":\"2024-01-15T14:30:00Z\",\"i\":123456,\"tks\":\"B\"}]";
    enqueueAuthThenMessage(msg);

    try (var stream =
        streamWith(
            new CryptoStreamListener() {
              @Override
              public void onTrade(CryptoTrade t) {
                captured.set(t);
                latch.countDown();
              }
            })) {
      stream.connect();
      assertTrue(latch.await(5, TimeUnit.SECONDS), "onTrade was not called");
    }

    assertEquals("BTC/USD", captured.get().symbol());
    assertDecimal("27000.5", captured.get().price());
    assertEquals("B", captured.get().takerSide());
  }

  @Test
  void quoteMessage_callsOnQuote() throws InterruptedException {
    var latch = new CountDownLatch(1);
    var captured = new AtomicReference<CryptoQuote>();

    String msg =
        "[{\"T\":\"q\",\"S\":\"ETH/USD\",\"bp\":1800.0,\"bs\":2.5,"
            + "\"ap\":1801.0,\"as\":1.5,\"t\":\"2024-01-15T14:30:00Z\"}]";
    enqueueAuthThenMessage(msg);

    try (var stream =
        streamWith(
            new CryptoStreamListener() {
              @Override
              public void onQuote(CryptoQuote q) {
                captured.set(q);
                latch.countDown();
              }
            })) {
      stream.connect();
      assertTrue(latch.await(5, TimeUnit.SECONDS), "onQuote was not called");
    }

    assertEquals("ETH/USD", captured.get().symbol());
    assertDecimal("1800.0", captured.get().bidPrice());
  }

  @Test
  void minuteBarMessage_callsOnMinuteBar() throws InterruptedException {
    var latch = new CountDownLatch(1);
    var captured = new AtomicReference<CryptoBar>();

    String msg =
        "[{\"T\":\"b\",\"S\":\"BTC/USD\",\"o\":27000.0,\"h\":27100.0,"
            + "\"l\":26900.0,\"c\":27050.0,\"v\":5.5,\"t\":\"2024-01-15T14:30:00Z\",\"n\":93,\"vw\":27010.0}]";
    enqueueAuthThenMessage(msg);

    try (var stream =
        streamWith(
            new CryptoStreamListener() {
              @Override
              public void onMinuteBar(CryptoBar b) {
                captured.set(b);
                latch.countDown();
              }
            })) {
      stream.connect();
      assertTrue(latch.await(5, TimeUnit.SECONDS), "onMinuteBar was not called");
    }

    assertEquals("BTC/USD", captured.get().symbol());
    assertEquals(93, captured.get().tradeCount());
    assertDecimal("27010.0", captured.get().vwap());
  }

  @Test
  void orderbookMessage_callsOnOrderbook() throws InterruptedException {
    var latch = new CountDownLatch(1);
    var captured = new AtomicReference<CryptoOrderbook>();

    String msg =
        "[{\"T\":\"o\",\"S\":\"BTC/USD\",\"t\":\"2024-01-15T14:30:00Z\","
            + "\"b\":[{\"p\":27000.0,\"s\":0.5}],\"a\":[{\"p\":27010.0,\"s\":0.3}],\"r\":true}]";
    enqueueAuthThenMessage(msg);

    try (var stream =
        streamWith(
            new CryptoStreamListener() {
              @Override
              public void onOrderbook(CryptoOrderbook o) {
                captured.set(o);
                latch.countDown();
              }
            })) {
      stream.connect();
      assertTrue(latch.await(5, TimeUnit.SECONDS), "onOrderbook was not called");
    }

    assertEquals("BTC/USD", captured.get().symbol());
    assertTrue(captured.get().reset());
    assertEquals(1, captured.get().bids().size());
    assertDecimal("27000.0", captured.get().bids().get(0).price());
  }

  @Test
  void dailyBarMessage_callsOnDailyBar() throws InterruptedException {
    var latch = new CountDownLatch(1);

    String msg =
        "[{\"T\":\"d\",\"S\":\"ETH/USD\",\"o\":1800.0,\"h\":1850.0,"
            + "\"l\":1790.0,\"c\":1820.0,\"v\":100.0,\"t\":\"2024-01-15T00:00:00Z\",\"n\":500,\"vw\":1815.0}]";
    enqueueAuthThenMessage(msg);

    try (var stream =
        streamWith(
            new CryptoStreamListener() {
              @Override
              public void onDailyBar(CryptoBar b) {
                latch.countDown();
              }
            })) {
      stream.connect();
      assertTrue(latch.await(5, TimeUnit.SECONDS), "onDailyBar was not called");
    }
  }

  @Test
  void updatedBarMessage_callsOnUpdatedBar() throws InterruptedException {
    var latch = new CountDownLatch(1);
    var received = new AtomicReference<CryptoBar>();

    String msg =
        "[{\"T\":\"u\",\"S\":\"BTC/USD\",\"o\":44000.0,\"h\":45500.0,"
            + "\"l\":43800.0,\"c\":45200.0,\"v\":12.5,\"t\":\"2024-01-15T14:00:00Z\",\"n\":820,\"vw\":44950.0}]";
    enqueueAuthThenMessage(msg);

    try (var stream =
        streamWith(
            new CryptoStreamListener() {
              @Override
              public void onUpdatedBar(CryptoBar b) {
                received.set(b);
                latch.countDown();
              }
            })) {
      stream.connect();
      assertTrue(latch.await(5, TimeUnit.SECONDS), "onUpdatedBar was not called");
    }

    CryptoBar b = received.get();
    assertEquals("BTC/USD", b.symbol());
    assertDecimal("44000.0", b.open());
    assertDecimal("45500.0", b.high());
    assertDecimal("45200.0", b.close());
    assertEquals(820L, b.tradeCount());
  }
}
