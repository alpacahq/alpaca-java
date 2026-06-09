package markets.alpaca.client.ws;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import markets.alpaca.client.AlpacaCredentials;
import markets.alpaca.client.ws.model.NewsArticle;
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

/** Unit tests for {@link AlpacaNewsStream} using {@link MockWebServer}. */
@Timeout(10)
class AlpacaNewsStreamTest {

  private static final AlpacaCredentials CREDS = new AlpacaCredentials("test-key", "test-secret");

  private MockWebServer server;
  private OkHttpClient httpClient;

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

  private AlpacaNewsStream streamWith(NewsStreamListener listener) {
    return new AlpacaNewsStream(
        httpClient, CREDS, server.url("/v1beta1/news").toString(), listener);
  }

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

  @Test
  void publicConstructor_acceptsProductionEnvironment() {
    assertNotNull(
        new AlpacaNewsStream(
            httpClient, CREDS, AlpacaStreamEnvironment.PRODUCTION, new NewsStreamListener() {}));
  }

  @Test
  void publicConstructor_rejectsSandboxEnvironment() {
    var thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new AlpacaNewsStream(
                    httpClient,
                    CREDS,
                    AlpacaStreamEnvironment.SANDBOX,
                    new NewsStreamListener() {}));

    assertEquals(
        "News stream is only available on AlpacaStreamEnvironment.PRODUCTION", thrown.getMessage());
  }

  @Test
  void articleMessage_callsOnArticle() throws InterruptedException {
    var latch = new CountDownLatch(1);
    var captured = new AtomicReference<NewsArticle>();

    String articleMsg =
        "[{\"T\":\"n\",\"id\":12345,"
            + "\"headline\":\"Alpaca Launches Java Client\","
            + "\"summary\":\"Great news for Java developers\","
            + "\"author\":\"Test Author\","
            + "\"created_at\":\"2024-01-15T14:30:00Z\","
            + "\"updated_at\":\"2024-01-15T14:30:00Z\","
            + "\"url\":\"https://example.com/article\","
            + "\"content\":\"<p>Full text here</p>\","
            + "\"symbols\":[\"APCA\",\"TSLA\"],"
            + "\"source\":\"benzinga\"}]";

    enqueueAuthThenMessage(articleMsg);

    try (var stream =
        streamWith(
            new NewsStreamListener() {
              @Override
              public void onArticle(NewsArticle a) {
                captured.set(a);
                latch.countDown();
              }
            })) {
      stream.connect();
      assertTrue(latch.await(5, TimeUnit.SECONDS), "onArticle was not called");
    }

    assertNotNull(captured.get());
    assertEquals(12345L, captured.get().id());
    assertEquals("Alpaca Launches Java Client", captured.get().headline());
    assertEquals("Test Author", captured.get().author());
    assertEquals("benzinga", captured.get().source());
    assertEquals(2, captured.get().symbols().size());
    assertEquals("APCA", captured.get().symbols().get(0));
  }

  @Test
  void subscribe_sendsNewsChannelPayload() throws InterruptedException {
    var subscribeLatch = new CountDownLatch(1);
    var capturedSub = new AtomicReference<String>();

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
                    } else if (text.contains("\"action\":\"subscribe\"")) {
                      capturedSub.set(text);
                      subscribeLatch.countDown();
                    }
                  }

                  @Override
                  public void onClosing(WebSocket ws, int code, String reason) {
                    ws.close(code, reason);
                  }
                }));

    var authLatch = new CountDownLatch(1);
    var stream =
        streamWith(
            new NewsStreamListener() {
              @Override
              public void onAuthenticated() {
                authLatch.countDown();
              }
            });

    stream.connect();
    assertTrue(authLatch.await(5, TimeUnit.SECONDS));
    stream.subscribe(NewsSubscription.builder().symbols("AAPL", "TSLA").build());
    assertTrue(subscribeLatch.await(5, TimeUnit.SECONDS), "subscribe was not sent");

    String sub = capturedSub.get();
    JsonPayloadAssertions.assertPayload(sub, "subscribe", Map.of("news", List.of("AAPL", "TSLA")));

    stream.close();
  }

  @Test
  void connectWithSubscription_buffersSubscriptionUntilAuthenticationCompletes()
      throws InterruptedException {
    var subscribeLatch = new CountDownLatch(1);
    var capturedSub = new AtomicReference<String>();

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
                    } else if (text.contains("\"action\":\"subscribe\"")) {
                      capturedSub.set(text);
                      subscribeLatch.countDown();
                    }
                  }

                  @Override
                  public void onClosing(WebSocket ws, int code, String reason) {
                    ws.close(code, reason);
                  }
                }));

    try (var stream = streamWith(new NewsStreamListener() {})) {
      stream.connect(NewsSubscription.builder().symbols("AAPL", "TSLA").build());
      assertTrue(subscribeLatch.await(5, TimeUnit.SECONDS), "subscribe was not sent");
    }

    String sub = capturedSub.get();
    JsonPayloadAssertions.assertPayload(sub, "subscribe", Map.of("news", List.of("AAPL", "TSLA")));
  }

  @Test
  void wildcardSubscription_usesStarSymbol() throws InterruptedException {
    var subscribeLatch = new CountDownLatch(1);
    var capturedSub = new AtomicReference<String>();

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
                    } else if (text.contains("\"action\":\"subscribe\"")) {
                      capturedSub.set(text);
                      subscribeLatch.countDown();
                    }
                  }

                  @Override
                  public void onClosing(WebSocket ws, int code, String reason) {
                    ws.close(code, reason);
                  }
                }));

    var authLatch = new CountDownLatch(1);
    var stream =
        streamWith(
            new NewsStreamListener() {
              @Override
              public void onAuthenticated() {
                authLatch.countDown();
              }
            });

    stream.connect();
    assertTrue(authLatch.await(5, TimeUnit.SECONDS));
    stream.subscribe(NewsSubscription.builder().symbols("*").build());
    assertTrue(subscribeLatch.await(5, TimeUnit.SECONDS));

    JsonPayloadAssertions.assertPayload(
        capturedSub.get(), "subscribe", Map.of("news", List.of("*")));

    stream.close();
  }
}
