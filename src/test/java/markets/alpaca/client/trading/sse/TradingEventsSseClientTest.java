package markets.alpaca.client.trading.sse;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import markets.alpaca.client.AlpacaClientFactory;
import markets.alpaca.client.AlpacaCredentials;
import markets.alpaca.client.openapi.trading.model.ActivityEventV2;
import markets.alpaca.client.sse.AlpacaSseEvent;
import markets.alpaca.client.sse.AlpacaSseListener;
import markets.alpaca.client.sse.AlpacaSseOptions;
import markets.alpaca.client.sse.AlpacaSseReconnectPolicy;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TradingEventsSseClientTest {

  private MockWebServer server;
  private OkHttpClient httpClient;

  @BeforeEach
  void setUp() throws IOException {
    server = new MockWebServer();
    server.start();
    httpClient = new OkHttpClient();
  }

  @AfterEach
  void tearDown() throws IOException {
    server.close();
  }

  @Test
  void opensAuthenticatedGeneratedRequestAndEmitsActivity() throws Exception {
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody("id: evt-1\ndata: " + fillJson() + "\n\n"));
    var apiClient =
        AlpacaClientFactory.tradingClient(
            new AlpacaCredentials("trading-key", "trading-secret"), httpClient);
    apiClient.setBasePath(baseUrl());
    var client = new TradingEventsSseClient(apiClient);
    var latch = new CountDownLatch(1);

    try (var subscription =
        client.subscribeToActivities(
            TradingActivitySseRequest.builder()
                .since(OffsetDateTime.parse("2026-10-01T00:00:00Z"))
                .until(OffsetDateTime.parse("2026-10-02T00:00:00Z"))
                .build(),
            new AlpacaSseListener<>() {
              @Override
              public void onEvent(AlpacaSseEvent<ActivityEventV2> event) {
                latch.countDown();
              }
            })) {
      assertTrue(latch.await(3, TimeUnit.SECONDS));
    }

    var request = server.takeRequest(3, TimeUnit.SECONDS);
    assertNotNull(request);
    assertTrue(request.getPath().startsWith("/v2beta1/events/activities?"));
    assertEquals("trading-key", request.getHeader("APCA-API-KEY-ID"));
    assertEquals("trading-secret", request.getHeader("APCA-API-SECRET-KEY"));
    assertEquals("text/event-stream", request.getHeader("Accept"));
  }

  @Test
  void rejectsInvalidCursorCombinationsBeforeOpeningConnection() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            TradingActivitySseRequest.builder()
                .until(OffsetDateTime.parse("2026-10-02T00:00:00Z"))
                .build());
    assertEquals(0, server.getRequestCount());
  }

  @Test
  void reconnectReplacesUnboundedDateCursorWithDocumentedEventIdCursor() throws Exception {
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody("id: 01J9RPMV5TKB8WX3M4F1KZ7QH2\ndata: " + fillJson() + "\n\n"));
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBodyDelay(5, TimeUnit.SECONDS)
            .setBody(":\n\n"));
    var apiClient =
        AlpacaClientFactory.tradingClient(
            new AlpacaCredentials("trading-key", "trading-secret"), httpClient);
    apiClient.setBasePath(baseUrl());
    var policy =
        AlpacaSseReconnectPolicy.builder()
            .initialAttempts(0)
            .establishedAttempts(1)
            .initialBackoff(Duration.ofMillis(1))
            .maxBackoff(Duration.ofMillis(1))
            .jitterRatio(0)
            .build();
    var client =
        new TradingEventsSseClient(
            apiClient, AlpacaSseOptions.builder().reconnectPolicy(policy).build());

    try (var subscription =
        client.subscribeToActivities(
            TradingActivitySseRequest.builder()
                .since(OffsetDateTime.parse("2026-10-01T00:00:00Z"))
                .build(),
            new AlpacaSseListener<>() {})) {
      var initial = server.takeRequest(3, TimeUnit.SECONDS);
      var reconnect = server.takeRequest(3, TimeUnit.SECONDS);

      assertNotNull(initial);
      assertNotNull(reconnect);
      assertNotNull(initial.getRequestUrl().queryParameter("since"));
      assertNull(reconnect.getRequestUrl().queryParameter("since"));
      assertEquals(
          "01J9RPMV5TKB8WX3M4F1KZ7QH2", reconnect.getRequestUrl().queryParameter("since_id"));
      assertEquals("01J9RPMV5TKB8WX3M4F1KZ7QH2", reconnect.getHeader("Last-Event-ID"));
    }
  }

  private String baseUrl() {
    String value = server.url("/").toString();
    return value.substring(0, value.length() - 1);
  }

  private static String fillJson() {
    return """
        {"activity_type":"FILL","at":"2026-10-01T12:00:00Z","currency":"USD",
        "event_id":"01K6G000000000000000000000","executed_at":"2026-10-01T12:00:00Z",
        "ref_id":"123e4567-e89b-12d3-a456-426614174000","settle_date":"2026-10-02",
        "status":"executed","details":{}}
        """
        .replace("\n", "");
  }
}
