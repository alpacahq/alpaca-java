package markets.alpaca.client.data.sse;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import markets.alpaca.client.AlpacaClientFactory;
import markets.alpaca.client.AlpacaCredentials;
import markets.alpaca.client.openapi.data.model.CorporateActionEvent;
import markets.alpaca.client.openapi.data.model.CorporateActionEventType;
import markets.alpaca.client.sse.AlpacaSseEvent;
import markets.alpaca.client.sse.AlpacaSseListener;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CorporateActionsSseClientTest {

  private static final String SINCE_ID = "01J9RPMV5TKB8WX3M4F1KZ7QH2";
  private static final String UNTIL_ID = "01J9RVB6Y4ZK8M3N7QD2WX1RFP";

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
  void usesOperationStreamEnvironmentAuthenticationAndTypedFilters() throws Exception {
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(
                "id: ca-1\ndata: " + CorporateActionEventDecoderTest.cashDividendJson() + "\n\n"));
    var dataClient =
        AlpacaClientFactory.dataClient(
            new AlpacaCredentials("data-key", "data-secret"), httpClient);
    var client =
        new CorporateActionsSseClient(dataClient, MarketDataSseEnvironment.custom(baseUrl()));
    var received = new CountDownLatch(1);

    var request =
        CorporateActionsSseRequest.builder()
            .eventTypes(CorporateActionEventType.CASH_DIVIDEND_CORPORATEACTION_EVENT)
            .region(CorporateActionsSseRequest.Region.US)
            .since(OffsetDateTime.parse("2026-10-01T00:00:00Z"))
            .until(OffsetDateTime.parse("2026-10-02T00:00:00Z"))
            .build();
    try (var subscription =
        client.subscribeToCorporateActions(
            request,
            new AlpacaSseListener<>() {
              @Override
              public void onEvent(AlpacaSseEvent<CorporateActionEvent> event) {
                received.countDown();
              }
            })) {
      assertTrue(received.await(3, TimeUnit.SECONDS));
    }

    var captured = server.takeRequest(3, TimeUnit.SECONDS);
    assertNotNull(captured);
    assertTrue(captured.getPath().startsWith("/v1beta1/events/corporate-actions?"));
    assertTrue(
        captured
            .getRequestUrl()
            .queryParameterValues("type")
            .contains("cash_dividend_corporateaction_event"));
    assertEquals("us", captured.getRequestUrl().queryParameter("region"));
    assertEquals("data-key", captured.getHeader("APCA-API-KEY-ID"));
    assertEquals("data-secret", captured.getHeader("APCA-API-SECRET-KEY"));
    assertEquals("text/event-stream", captured.getHeader("Accept"));
  }

  @Test
  void validatesEnvironmentAndCursorCombinationsBeforeOpening() {
    assertThrows(
        IllegalArgumentException.class,
        () -> MarketDataSseEnvironment.custom("wss://stream.example.test"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CorporateActionsSseRequest.builder()
                .until(OffsetDateTime.parse("2026-10-02T00:00:00Z"))
                .build());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CorporateActionsSseRequest.builder()
                .sinceId("event-1")
                .since(OffsetDateTime.parse("2026-10-01T00:00:00Z"))
                .build());
    assertThrows(
        IllegalArgumentException.class,
        () -> CorporateActionsSseRequest.fromEventId("not-a-corporate-action-ulid"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CorporateActionsSseRequest.builder()
                .since(OffsetDateTime.parse("2026-10-02T00:00:00Z"))
                .until(OffsetDateTime.parse("2026-10-01T00:00:00Z"))
                .build());
    assertThrows(
        IllegalArgumentException.class,
        () -> CorporateActionsSseRequest.throughEventId(UNTIL_ID, SINCE_ID));
  }

  private String baseUrl() {
    String value = server.url("/").toString();
    return value.substring(0, value.length() - 1);
  }
}
