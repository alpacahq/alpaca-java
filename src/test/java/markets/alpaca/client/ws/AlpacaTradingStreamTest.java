package markets.alpaca.client.ws;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import markets.alpaca.client.AlpacaCredentials;
import markets.alpaca.client.ws.model.TradeUpdate;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okio.ByteString;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Protocol and dispatch tests for the trading WebSocket stream ({@code /stream}).
 *
 * <p>The trading stream differs from the market-data streams in three ways:
 *
 * <ol>
 *   <li>Auth fires immediately on WebSocket open (no "connected" message first).
 *   <li>Auth uses {@code action: authenticate} with credentials under a {@code data} key.
 *   <li>Messages use a {@code stream} discriminator instead of {@code T}.
 * </ol>
 */
class AlpacaTradingStreamTest {

  private static final AlpacaCredentials CREDS = new AlpacaCredentials("paper-key", "paper-secret");
  private static final int TIMEOUT_S = 3;

  private MockWebServer server;
  private OkHttpClient client;

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
  // Inner helper
  // -------------------------------------------------------------------------

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
                    public void onOpen(WebSocket w, Response r) {
                      ws.set(w);
                      opened.countDown();
                    }

                    @Override
                    public void onMessage(WebSocket w, String text) {
                      messages.add(text);
                    }

                    @Override
                    public void onClosing(WebSocket w, int code, String reason) {
                      w.close(code, reason);
                    }
                  }));
    }

    void awaitOpen() throws InterruptedException {
      assertTrue(
          opened.await(TIMEOUT_S, TimeUnit.SECONDS),
          "Server did not receive WebSocket upgrade in time");
    }

    void send(String json) {
      ws.get().send(json);
    }

    void close(int code, String reason) {
      ws.get().close(code, reason);
    }

    String pollMessage() throws InterruptedException {
      String msg = messages.poll(TIMEOUT_S, TimeUnit.SECONDS);
      assertNotNull(msg, "Expected a message from the client but none arrived");
      return msg;
    }

    void assertNoMessage(long timeoutMillis) throws InterruptedException {
      String msg = messages.poll(timeoutMillis, TimeUnit.MILLISECONDS);
      assertNull(msg, "Expected no client message but received: " + msg);
    }
  }

  /** Drives the full auth handshake and returns the active ServerSocket. */
  private ServerSocket performHandshake(AlpacaTradingStream stream) throws InterruptedException {
    var ss = new ServerSocket();
    ss.enqueue();
    stream.connect();
    ss.awaitOpen();
    // The trading stream sends auth immediately on WebSocket open — no "connected" first.
    String authMsg = ss.pollMessage();
    JsonPayloadAssertions.assertStringProperty(authMsg, "action", "authenticate");
    ss.send("{\"stream\":\"authorization\",\"data\":{\"status\":\"authorized\"}}");
    return ss;
  }

  // -------------------------------------------------------------------------
  // Auth protocol
  // -------------------------------------------------------------------------

  @Test
  void constructor_rejectsNullArguments() {
    assertThrows(
        NullPointerException.class,
        () ->
            new AlpacaTradingStream(
                null, CREDS, TradingEnvironment.PAPER, new TradingStreamListener() {}));
    assertThrows(
        NullPointerException.class,
        () ->
            new AlpacaTradingStream(
                client, null, TradingEnvironment.PAPER, new TradingStreamListener() {}));
    assertThrows(
        NullPointerException.class,
        () ->
            new AlpacaTradingStream(
                client, CREDS, (TradingEnvironment) null, new TradingStreamListener() {}));
    assertThrows(
        NullPointerException.class,
        () -> new AlpacaTradingStream(client, CREDS, TradingEnvironment.PAPER, null));
    assertThrows(
        NullPointerException.class,
        () ->
            new AlpacaTradingStream(client, CREDS, (String) null, new TradingStreamListener() {}));
    assertThrows(
        NullPointerException.class,
        () -> new AlpacaTradingStream(client, CREDS, server.url("/stream").toString(), null));
  }

  @Test
  void publicConstructor_acceptsPaperEnvironment() {
    assertNotNull(
        new AlpacaTradingStream(
            client, CREDS, TradingEnvironment.PAPER, new TradingStreamListener() {}));
  }

  @Test
  void connect_sendsAuthImmediatelyOnOpen() throws Exception {
    var ss = new ServerSocket();
    ss.enqueue();

    var stream =
        new AlpacaTradingStream(
            client, CREDS, server.url("/stream").toString(), new TradingStreamListener() {});
    stream.connect();
    ss.awaitOpen();

    String msg = ss.pollMessage();
    JsonPayloadAssertions.assertDataStringPayload(
        msg, "authenticate", Map.of("key_id", "paper-key", "secret_key", "paper-secret"));

    stream.close();
  }

  @Test
  void authorized_callsOnAuthenticated() throws Exception {
    var latch = new CountDownLatch(1);

    var stream =
        new AlpacaTradingStream(
            client,
            CREDS,
            server.url("/stream").toString(),
            new TradingStreamListener() {
              @Override
              public void onAuthenticated() {
                latch.countDown();
              }
            });

    var ss = new ServerSocket();
    ss.enqueue();
    stream.connect();
    ss.awaitOpen();
    ss.pollMessage(); // consume authenticate
    ss.send("{\"stream\":\"authorization\",\"data\":{\"status\":\"authorized\"}}");

    assertTrue(
        latch.await(TIMEOUT_S, TimeUnit.SECONDS),
        "onAuthenticated must fire when authorization status is 'authorized'");
    stream.close();
  }

  @Test
  void unauthorized_callsOnError() throws Exception {
    var latch = new CountDownLatch(1);
    var disconnected = new CountDownLatch(1);
    var errMsg = new AtomicReference<String>();
    var willReconnect = new AtomicBoolean(true);

    var stream =
        new AlpacaTradingStream(
            client,
            CREDS,
            server.url("/stream").toString(),
            new TradingStreamListener() {
              @Override
              public void onError(String message) {
                errMsg.set(message);
                latch.countDown();
              }

              @Override
              public void onDisconnected(int code, String reason, boolean wr) {
                willReconnect.set(wr);
                disconnected.countDown();
              }
            });

    var ss = new ServerSocket();
    ss.enqueue();
    var ss2 = new ServerSocket();
    ss2.enqueue();
    stream.connect();
    ss.awaitOpen();
    ss.pollMessage();
    ss.send("{\"stream\":\"authorization\",\"data\":{\"status\":\"unauthorized\"}}");

    assertTrue(
        latch.await(TIMEOUT_S, TimeUnit.SECONDS), "onError must fire for unauthorized status");
    assertNotNull(errMsg.get());
    assertTrue(
        errMsg.get().contains("authentication failed"),
        "Error message should indicate auth failure");
    assertTrue(disconnected.await(TIMEOUT_S, TimeUnit.SECONDS));
    assertFalse(willReconnect.get(), "auth failures must not reconnect");
    assertTrue(stream.isClosed(), "auth failure must close the stream terminally");
    assertFalse(
        ss2.opened.await(500, TimeUnit.MILLISECONDS),
        "auth failure must not open a reconnect WebSocket");

    stream.close();
  }

  @Test
  void waitForAuthentication_returnsFalseAfterUnauthorized() throws Exception {
    var stream =
        new AlpacaTradingStream(
            client, CREDS, server.url("/stream").toString(), new TradingStreamListener() {});

    var ss = new ServerSocket();
    ss.enqueue();
    stream.connect();
    ss.awaitOpen();
    ss.pollMessage();
    ss.send("{\"stream\":\"authorization\",\"data\":{\"status\":\"unauthorized\"}}");

    assertFalse(
        stream.waitForAuthentication(Duration.ofSeconds(TIMEOUT_S)),
        "waitForAuthentication must return false after authentication fails");
    assertTrue(
        stream.authenticationFuture().isDone(),
        "authenticationFuture must complete after authentication fails");

    stream.close();
  }

  @Test
  void waitForAuthenticationResult_returnsServerRejectedAfterUnauthorized() throws Exception {
    var stream =
        new AlpacaTradingStream(
            client, CREDS, server.url("/stream").toString(), new TradingStreamListener() {});

    var ss = new ServerSocket();
    ss.enqueue();
    stream.connect();
    ss.awaitOpen();
    ss.pollMessage();
    ss.send("{\"stream\":\"authorization\",\"data\":{\"status\":\"unauthorized\"}}");

    AlpacaStreamAuthResult result =
        stream.waitForAuthenticationResult(Duration.ofSeconds(TIMEOUT_S));

    assertEquals(AlpacaStreamAuthResult.Status.SERVER_REJECTED, result.status());
    assertFalse(result.isAuthenticated());
    assertNull(result.code());
    assertEquals("authentication failed: unauthorized", result.message());
    assertEquals(result, stream.authenticationResultFuture().getNow(null));
    assertEquals(Boolean.FALSE, stream.authenticationFuture().getNow(null));

    stream.close();
  }

  @Test
  void listen_afterUnauthorized_throwsIllegalStateException() throws Exception {
    var stream =
        new AlpacaTradingStream(
            client, CREDS, server.url("/stream").toString(), new TradingStreamListener() {});

    var ss = new ServerSocket();
    ss.enqueue();
    stream.connect();
    ss.awaitOpen();
    ss.pollMessage();
    ss.send("{\"stream\":\"authorization\",\"data\":{\"status\":\"unauthorized\"}}");

    AlpacaStreamAuthResult result =
        stream.waitForAuthenticationResult(Duration.ofSeconds(TIMEOUT_S));
    assertEquals(AlpacaStreamAuthResult.Status.SERVER_REJECTED, result.status());

    assertThrows(
        IllegalStateException.class, () -> stream.listen(TradingSubscription.TRADE_UPDATES));

    stream.close();
  }

  @Test
  void listen_afterRejectedAuthenticationResultWhileCallbackIsRunning_throwsIllegalStateException()
      throws Exception {
    CountDownLatch callbackExecutorEntered = new CountDownLatch(1);
    CountDownLatch releaseCallbackExecutor = new CountDownLatch(1);
    Executor blockingExecutor =
        command -> {
          callbackExecutorEntered.countDown();
          try {
            assertTrue(
                releaseCallbackExecutor.await(TIMEOUT_S, TimeUnit.SECONDS),
                "Callback executor was not released in time");
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
          }
          command.run();
        };

    try (var stream =
        new AlpacaTradingStream(
            client,
            CREDS,
            server.url("/stream").toString(),
            new TradingStreamListener() {},
            AlpacaStreamReconnectPolicy.defaultPolicy(),
            blockingExecutor)) {
      var ss = new ServerSocket();
      ss.enqueue();
      stream.connect();
      ss.awaitOpen();
      ss.pollMessage();
      ss.send("{\"stream\":\"authorization\",\"data\":{\"status\":\"unauthorized\"}}");

      AlpacaStreamAuthResult result =
          stream.waitForAuthenticationResult(Duration.ofSeconds(TIMEOUT_S));
      assertEquals(AlpacaStreamAuthResult.Status.SERVER_REJECTED, result.status());
      assertTrue(
          callbackExecutorEntered.await(TIMEOUT_S, TimeUnit.SECONDS),
          "Unauthorized callback did not start");

      assertThrows(
          IllegalStateException.class, () -> stream.listen(TradingSubscription.TRADE_UPDATES));
    } finally {
      releaseCallbackExecutor.countDown();
    }
  }

  @Test
  void authenticationResultFuture_returnsClosedWhenClosedBeforeAuthentication() throws Exception {
    var stream =
        new AlpacaTradingStream(
            client, CREDS, server.url("/stream").toString(), new TradingStreamListener() {});

    stream.close();

    AlpacaStreamAuthResult result =
        stream.authenticationResultFuture().get(TIMEOUT_S, TimeUnit.SECONDS);

    assertEquals(AlpacaStreamAuthResult.Status.CLOSED, result.status());
    assertFalse(result.isAuthenticated());
    assertEquals("client closed", result.message());
    assertEquals(Boolean.FALSE, stream.authenticationFuture().getNow(null));
  }

  @Test
  void listen_afterClose_throwsIllegalStateException() {
    var stream =
        new AlpacaTradingStream(
            client, CREDS, server.url("/stream").toString(), new TradingStreamListener() {});
    stream.close();

    assertThrows(
        IllegalStateException.class, () -> stream.listen(TradingSubscription.TRADE_UPDATES));
  }

  @Test
  void authenticationResultFuture_keepsCloseReasonWhenServerClosesBeforeAuthentication()
      throws Exception {
    var stream =
        new AlpacaTradingStream(
            client,
            CREDS,
            server.url("/stream").toString(),
            new TradingStreamListener() {},
            AlpacaStreamReconnectPolicy.disabled());

    var ss = new ServerSocket();
    ss.enqueue();
    stream.connect();
    ss.awaitOpen();
    ss.close(1008, "policy violation");

    AlpacaStreamAuthResult result =
        stream.authenticationResultFuture().get(TIMEOUT_S, TimeUnit.SECONDS);

    assertEquals(AlpacaStreamAuthResult.Status.CLOSED, result.status());
    assertEquals("policy violation", result.message());
    assertEquals(Boolean.FALSE, stream.authenticationFuture().getNow(null));
  }

  @Test
  void authorizationMessage_withoutData_isIgnored() throws Exception {
    var authenticated = new AtomicBoolean(false);
    var error = new AtomicBoolean(false);

    var stream =
        new AlpacaTradingStream(
            client,
            CREDS,
            server.url("/stream").toString(),
            new TradingStreamListener() {
              @Override
              public void onAuthenticated() {
                authenticated.set(true);
              }

              @Override
              public void onError(String message) {
                error.set(true);
              }
            });

    var ss = new ServerSocket();
    ss.enqueue();
    stream.connect();
    ss.awaitOpen();
    ss.pollMessage();
    ss.send("{\"stream\":\"authorization\"}");

    Thread.sleep(200);
    assertFalse(authenticated.get());
    assertFalse(error.get());

    stream.close();
  }

  @Test
  void authorizationMessage_withMissingStatus_callsOnErrorWithBlankStatus() throws Exception {
    var latch = new CountDownLatch(1);
    var errMsg = new AtomicReference<String>();

    var stream =
        new AlpacaTradingStream(
            client,
            CREDS,
            server.url("/stream").toString(),
            new TradingStreamListener() {
              @Override
              public void onError(String message) {
                errMsg.set(message);
                latch.countDown();
              }
            });

    var ss = new ServerSocket();
    ss.enqueue();
    stream.connect();
    ss.awaitOpen();
    ss.pollMessage();
    ss.send("{\"stream\":\"authorization\",\"data\":{}}");

    assertTrue(latch.await(TIMEOUT_S, TimeUnit.SECONDS));
    assertEquals("authentication failed: ", errMsg.get());

    stream.close();
  }

  // -------------------------------------------------------------------------
  // Listen / subscribe
  // -------------------------------------------------------------------------

  @Test
  void listen_sendsListenMessage() throws Exception {
    var stream =
        new AlpacaTradingStream(
            client, CREDS, server.url("/stream").toString(), new TradingStreamListener() {});

    var ss = performHandshake(stream);
    stream.listen(TradingSubscription.TRADE_UPDATES);

    String listenMsg = ss.pollMessage();
    JsonPayloadAssertions.assertDataPayload(
        listenMsg, "listen", Map.of("streams", List.of("trade_updates")));

    stream.close();
  }

  @Test
  void listeningMessage_callsOnListening() throws Exception {
    var latch = new CountDownLatch(1);
    var confirmedStreams = new AtomicReference<List<String>>();

    var stream =
        new AlpacaTradingStream(
            client,
            CREDS,
            server.url("/stream").toString(),
            new TradingStreamListener() {
              @Override
              public void onListening(List<String> streams) {
                confirmedStreams.set(streams);
                latch.countDown();
              }
            });

    var ss = performHandshake(stream);
    ss.send("{\"stream\":\"listening\",\"data\":{\"streams\":[\"trade_updates\"]}}");

    assertTrue(
        latch.await(TIMEOUT_S, TimeUnit.SECONDS), "onListening must fire on listening message");
    assertNotNull(confirmedStreams.get());
    assertEquals(List.of("trade_updates"), confirmedStreams.get());

    stream.close();
  }

  @Test
  void listeningMessage_withoutData_callsOnListeningWithEmptyList() throws Exception {
    var latch = new CountDownLatch(1);
    var confirmedStreams = new AtomicReference<List<String>>();

    var stream =
        new AlpacaTradingStream(
            client,
            CREDS,
            server.url("/stream").toString(),
            new TradingStreamListener() {
              @Override
              public void onListening(List<String> streams) {
                confirmedStreams.set(streams);
                latch.countDown();
              }
            });

    var ss = performHandshake(stream);
    ss.send("{\"stream\":\"listening\"}");

    assertTrue(latch.await(TIMEOUT_S, TimeUnit.SECONDS));
    assertEquals(List.of(), confirmedStreams.get());

    stream.close();
  }

  @Test
  void listeningMessage_withDataButNoStreams_callsOnListeningWithEmptyList() throws Exception {
    var latch = new CountDownLatch(1);
    var confirmedStreams = new AtomicReference<List<String>>();

    var stream =
        new AlpacaTradingStream(
            client,
            CREDS,
            server.url("/stream").toString(),
            new TradingStreamListener() {
              @Override
              public void onListening(List<String> streams) {
                confirmedStreams.set(streams);
                latch.countDown();
              }
            });

    var ss = performHandshake(stream);
    ss.send("{\"stream\":\"listening\",\"data\":{}}");

    assertTrue(latch.await(TIMEOUT_S, TimeUnit.SECONDS));
    assertEquals(List.of(), confirmedStreams.get());

    stream.close();
  }

  // -------------------------------------------------------------------------
  // Data dispatch
  // -------------------------------------------------------------------------

  @Test
  void tradeUpdateMessage_callsOnTradeUpdate() throws Exception {
    var latch = new CountDownLatch(1);
    var received = new AtomicReference<TradeUpdate>();

    var stream =
        new AlpacaTradingStream(
            client,
            CREDS,
            server.url("/stream").toString(),
            new TradingStreamListener() {
              @Override
              public void onTradeUpdate(TradeUpdate u) {
                received.set(u);
                latch.countDown();
              }
            });

    var ss = performHandshake(stream);
    ss.send(
        "{\"stream\":\"trade_updates\",\"data\":{"
            + "\"event\":\"fill\",\"execution_id\":\"exec-1\","
            + "\"price\":\"150.50\",\"qty\":\"10\",\"position_qty\":\"10\","
            + "\"timestamp\":\"2024-01-15T14:30:02Z\","
            + "\"order\":{\"id\":\"order-1\",\"symbol\":\"AAPL\",\"side\":\"buy\","
            + "           \"type\":\"limit\",\"status\":\"filled\",\"extended_hours\":false}}}");

    assertTrue(
        latch.await(TIMEOUT_S, TimeUnit.SECONDS),
        "onTradeUpdate must fire on trade_updates message");
    TradeUpdate u = received.get();
    assertEquals("fill", u.event());
    assertEquals("exec-1", u.executionId());
    assertEquals("150.50", u.price());
    assertNotNull(u.order());
    assertEquals("AAPL", u.order().symbol());

    stream.close();
  }

  @Test
  void tradeUpdate_withoutData_isIgnored() throws Exception {
    var callback = new AtomicBoolean(false);

    var stream =
        new AlpacaTradingStream(
            client,
            CREDS,
            server.url("/stream").toString(),
            new TradingStreamListener() {
              @Override
              public void onTradeUpdate(TradeUpdate u) {
                callback.set(true);
              }
            });

    var ss = performHandshake(stream);
    ss.send("{\"stream\":\"trade_updates\"}");

    Thread.sleep(200);
    assertFalse(callback.get());

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
        new AlpacaTradingStream(
            client,
            CREDS,
            server.url("/stream").toString(),
            new TradingStreamListener() {
              @Override
              public void onDisconnected(int code, String reason, boolean wr) {
                willReconnect.set(wr);
                latch.countDown();
              }
            });

    var ss = performHandshake(stream);
    ss.ws.get().close(1001, "server going away");

    assertTrue(latch.await(TIMEOUT_S, TimeUnit.SECONDS));
    assertTrue(willReconnect.get(), "willReconnect must be true after unexpected server close");

    stream.close();
  }

  @Test
  void streamClose_callsOnDisconnectedWithWillReconnectFalse() throws Exception {
    var latch = new CountDownLatch(1);
    var willReconnect = new AtomicBoolean(true);

    var stream =
        new AlpacaTradingStream(
            client,
            CREDS,
            server.url("/stream").toString(),
            new TradingStreamListener() {
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
  // Binary frame handling
  // -------------------------------------------------------------------------

  @Test
  void binaryFrame_isDispatchedAsText() throws Exception {
    // The paper-trading endpoint sends binary (not text) WebSocket frames.
    // AbstractAlpacaStream.handleBytes decodes them as UTF-8 and forwards to handleText.
    var latch = new CountDownLatch(1);
    var received = new AtomicReference<TradeUpdate>();

    server.enqueue(
        new MockResponse()
            .withWebSocketUpgrade(
                new WebSocketListener() {
                  @Override
                  public void onOpen(WebSocket ws, Response r) {
                    // Send auth response as a text frame first
                    ws.send("{\"stream\":\"authorization\",\"data\":{\"status\":\"authorized\"}}");
                  }

                  @Override
                  public void onMessage(WebSocket ws, String text) {
                    // Send the trade_update as a binary frame to simulate the paper endpoint
                    String json =
                        "{\"stream\":\"trade_updates\",\"data\":{"
                            + "\"event\":\"fill\",\"execution_id\":\"exec-bin\","
                            + "\"price\":\"155.00\",\"qty\":\"5\",\"position_qty\":\"5\","
                            + "\"timestamp\":\"2024-01-15T15:00:00Z\","
                            + "\"order\":{\"id\":\"ord-1\",\"symbol\":\"MSFT\",\"side\":\"buy\","
                            + "           \"type\":\"market\",\"status\":\"filled\",\"extended_hours\":false}}}";
                    ws.send(ByteString.encodeUtf8(json));
                  }

                  @Override
                  public void onClosing(WebSocket ws, int code, String reason) {
                    ws.close(code, reason);
                  }
                }));

    var stream =
        new AlpacaTradingStream(
            client,
            CREDS,
            server.url("/stream").toString(),
            new TradingStreamListener() {
              @Override
              public void onTradeUpdate(TradeUpdate u) {
                received.set(u);
                latch.countDown();
              }
            });

    stream.connect();

    assertTrue(
        latch.await(TIMEOUT_S, TimeUnit.SECONDS),
        "onTradeUpdate must fire when payload arrives as a binary frame");
    assertEquals("exec-bin", received.get().executionId());
    assertEquals("MSFT", received.get().order().symbol());

    stream.close();
  }

  // -------------------------------------------------------------------------
  // action:error handling
  // -------------------------------------------------------------------------

  @Test
  void actionError_callsOnError() throws Exception {
    var latch = new CountDownLatch(1);
    var errMsg = new AtomicReference<String>();

    var stream =
        new AlpacaTradingStream(
            client,
            CREDS,
            server.url("/stream").toString(),
            new TradingStreamListener() {
              @Override
              public void onError(String message) {
                errMsg.set(message);
                latch.countDown();
              }
            });

    var ss = performHandshake(stream);
    ss.send("{\"action\":\"error\",\"data\":{\"error_message\":\"rate limit exceeded\"}}");

    assertTrue(
        latch.await(TIMEOUT_S, TimeUnit.SECONDS),
        "onError must fire when the server sends an action:error message");
    assertEquals("rate limit exceeded", errMsg.get());

    stream.close();
  }

  @Test
  void actionError_withoutData_callsOnErrorWithUnknownMessage() throws Exception {
    var latch = new CountDownLatch(1);
    var errMsg = new AtomicReference<String>();

    var stream =
        new AlpacaTradingStream(
            client,
            CREDS,
            server.url("/stream").toString(),
            new TradingStreamListener() {
              @Override
              public void onError(String message) {
                errMsg.set(message);
                latch.countDown();
              }
            });

    var ss = performHandshake(stream);
    ss.send("{\"action\":\"error\"}");

    assertTrue(latch.await(TIMEOUT_S, TimeUnit.SECONDS));
    assertEquals("unknown error", errMsg.get());

    stream.close();
  }

  @Test
  void actionError_withDataButNoMessage_callsOnErrorWithUnknownMessage() throws Exception {
    var latch = new CountDownLatch(1);
    var errMsg = new AtomicReference<String>();

    var stream =
        new AlpacaTradingStream(
            client,
            CREDS,
            server.url("/stream").toString(),
            new TradingStreamListener() {
              @Override
              public void onError(String message) {
                errMsg.set(message);
                latch.countDown();
              }
            });

    var ss = performHandshake(stream);
    ss.send("{\"action\":\"error\",\"data\":{}}");

    assertTrue(latch.await(TIMEOUT_S, TimeUnit.SECONDS));
    assertEquals("unknown error", errMsg.get());

    stream.close();
  }

  @Test
  void malformedAndUnknownMessages_areIgnoredAndNextValidMessageStillDispatches() throws Exception {
    var latch = new CountDownLatch(1);
    var received = new AtomicReference<TradeUpdate>();

    var stream =
        new AlpacaTradingStream(
            client,
            CREDS,
            server.url("/stream").toString(),
            new TradingStreamListener() {
              @Override
              public void onTradeUpdate(TradeUpdate u) {
                received.set(u);
                latch.countDown();
              }
            });

    var ss = performHandshake(stream);
    ss.send("not-json");
    ss.send("{\"stream\":\"unknown\",\"data\":{}}");
    ss.send(
        "{\"stream\":\"trade_updates\",\"data\":{"
            + "\"event\":\"fill\",\"execution_id\":\"exec-after-error\","
            + "\"price\":\"150.50\",\"qty\":\"10\",\"position_qty\":\"10\","
            + "\"timestamp\":\"2024-01-15T14:30:02Z\","
            + "\"order\":{\"id\":\"order-1\",\"symbol\":\"AAPL\",\"side\":\"buy\","
            + "           \"type\":\"limit\",\"status\":\"filled\",\"extended_hours\":false}}}");

    assertTrue(latch.await(TIMEOUT_S, TimeUnit.SECONDS));
    assertEquals("exec-after-error", received.get().executionId());

    stream.close();
  }

  // -------------------------------------------------------------------------
  // listen() before authentication — pendingStreams queued and replayed
  // -------------------------------------------------------------------------

  @Test
  void listen_beforeAuth_isReplayedAfterAuthentication() throws Exception {
    var listenMsgLatch = new CountDownLatch(1);
    var listenMsg = new AtomicReference<String>();

    var ss = new ServerSocket();
    ss.enqueue();

    var stream =
        new AlpacaTradingStream(
            client, CREDS, server.url("/stream").toString(), new TradingStreamListener() {});

    // Call listen() BEFORE connect() — the send() call is a no-op because
    // the WebSocket is not open yet, but pendingStreams is stored.
    stream.listen(TradingSubscription.TRADE_UPDATES);
    stream.connect();

    ss.awaitOpen();
    // Consume the immediate authenticate message
    ss.pollMessage();
    // Send the authorized response — this triggers replay of pendingStreams
    ss.send("{\"stream\":\"authorization\",\"data\":{\"status\":\"authorized\"}}");

    // The stream must now auto-send the queued listen message
    String msg = ss.pollMessage();
    listenMsg.set(msg);
    listenMsgLatch.countDown();

    assertTrue(
        listenMsgLatch.await(TIMEOUT_S, TimeUnit.SECONDS),
        "Queued listen must be sent after authentication completes");
    String listen = listenMsg.get();
    JsonPayloadAssertions.assertDataPayload(
        listen, "listen", Map.of("streams", List.of("trade_updates")));

    stream.close();
  }

  @Test
  void connectWithSubscription_queuesListenAndSendsAfterAuthorized() throws Exception {
    var ss = new ServerSocket();
    ss.enqueue();

    var stream =
        new AlpacaTradingStream(
            client, CREDS, server.url("/stream").toString(), new TradingStreamListener() {});

    stream.connect(TradingSubscription.TRADE_UPDATES);
    ss.awaitOpen();

    String authMsg = ss.pollMessage();
    JsonPayloadAssertions.assertStringProperty(authMsg, "action", "authenticate");

    ss.send("{\"stream\":\"authorization\",\"data\":{\"status\":\"authorized\"}}");
    String listenMsg = ss.pollMessage();
    JsonPayloadAssertions.assertDataPayload(
        listenMsg, "listen", Map.of("streams", List.of("trade_updates")));

    stream.close();
  }

  @Test
  void listen_afterConnectBeforeAuthorization_isQueuedUntilAuthorized() throws Exception {
    var ss = new ServerSocket();
    ss.enqueue();

    var stream =
        new AlpacaTradingStream(
            client, CREDS, server.url("/stream").toString(), new TradingStreamListener() {});

    stream.connect();
    ss.awaitOpen();

    String authMsg = ss.pollMessage();
    JsonPayloadAssertions.assertStringProperty(authMsg, "action", "authenticate");

    stream.listen(TradingSubscription.TRADE_UPDATES);
    ss.assertNoMessage(200);

    ss.send("{\"stream\":\"authorization\",\"data\":{\"status\":\"authorized\"}}");
    String listenMsg = ss.pollMessage();
    JsonPayloadAssertions.assertDataPayload(
        listenMsg, "listen", Map.of("streams", List.of("trade_updates")));

    stream.close();
  }

  @Test
  void listen_withMultipleStreams_sendsAllStreams() throws Exception {
    var stream =
        new AlpacaTradingStream(
            client, CREDS, server.url("/stream").toString(), new TradingStreamListener() {});

    var ss = performHandshake(stream);
    stream.listen(
        new TradingSubscription(
            new java.util.LinkedHashSet<>(List.of("trade_updates", "account_updates"))));

    String listen = ss.pollMessage();
    JsonPayloadAssertions.assertDataPayloadUnordered(
        listen, "listen", Map.of("streams", Set.of("trade_updates", "account_updates")));

    stream.close();
  }

  @Test
  void authenticatedListenerException_doesNotPreventQueuedListenReplay() throws Exception {
    var ss = new ServerSocket();
    ss.enqueue();

    var stream =
        new AlpacaTradingStream(
            client,
            CREDS,
            server.url("/stream").toString(),
            new TradingStreamListener() {
              @Override
              public void onAuthenticated() {
                throw new IllegalStateException("application callback failed");
              }
            });

    stream.listen(TradingSubscription.TRADE_UPDATES);
    stream.connect();

    ss.awaitOpen();
    ss.pollMessage(); // authenticate
    ss.send("{\"stream\":\"authorization\",\"data\":{\"status\":\"authorized\"}}");

    String listenMsg = ss.pollMessage();
    JsonPayloadAssertions.assertDataPayload(
        listenMsg, "listen", Map.of("streams", List.of("trade_updates")));
    assertTrue(stream.waitForAuthentication(Duration.ofSeconds(TIMEOUT_S)));

    stream.close();
  }

  // -------------------------------------------------------------------------
  // Reconnect — onReconnecting, pendingStreams re-sent, onReconnected
  // -------------------------------------------------------------------------

  @Test
  void reconnect_replaysListenAndFiresOnReconnected() throws Exception {
    var authenticatedLatch = new CountDownLatch(1);
    var reconnectingLatch = new CountDownLatch(1);
    var reconnectedLatch = new CountDownLatch(1);
    var reconnectAttempt = new AtomicReference<>(-1);
    var relistenMsg = new AtomicReference<String>();

    var ss1 = new ServerSocket();
    ss1.enqueue();
    var ss2 = new ServerSocket();
    ss2.enqueue();

    var stream =
        new AlpacaTradingStream(
            client,
            CREDS,
            server.url("/stream").toString(),
            new TradingStreamListener() {
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
    ss1.pollMessage(); // consume authenticate
    ss1.send("{\"stream\":\"authorization\",\"data\":{\"status\":\"authorized\"}}");
    assertTrue(authenticatedLatch.await(TIMEOUT_S, TimeUnit.SECONDS));

    // Subscribe so pendingStreams is populated for replay after reconnect.
    stream.listen(TradingSubscription.TRADE_UPDATES);
    ss1.pollMessage(); // consume listen message

    // --- Trigger unexpected disconnect ---
    ss1.ws.get().close(1001, "server going away");

    assertTrue(
        reconnectingLatch.await(TIMEOUT_S, TimeUnit.SECONDS),
        "onReconnecting must fire after unexpected disconnect");
    assertEquals(1, (int) reconnectAttempt.get(), "First reconnect attempt must be 1");

    // --- Second connection (automatic reconnect) ---
    ss2.awaitOpen();
    ss2.pollMessage(); // consume authenticate
    ss2.send("{\"stream\":\"authorization\",\"data\":{\"status\":\"authorized\"}}");

    // pendingStreams is replayed automatically after re-auth
    String relisten = ss2.pollMessage();
    relistenMsg.set(relisten);

    assertTrue(
        reconnectedLatch.await(TIMEOUT_S, TimeUnit.SECONDS),
        "onReconnected must fire after successful re-authentication");

    String msg = relistenMsg.get();
    assertNotNull(msg, "pendingStreams must be re-sent after reconnect");
    JsonPayloadAssertions.assertDataPayload(
        msg, "listen", Map.of("streams", List.of("trade_updates")));

    stream.close();
  }
}
