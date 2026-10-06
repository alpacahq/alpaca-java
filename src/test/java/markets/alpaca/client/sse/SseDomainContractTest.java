package markets.alpaca.client.sse;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;
import markets.alpaca.client.AlpacaClientFactory;
import markets.alpaca.client.AlpacaCredentials;
import markets.alpaca.client.data.sse.CorporateActionsSseClient;
import markets.alpaca.client.data.sse.CorporateActionsSseRequest;
import markets.alpaca.client.data.sse.MarketDataSseEnvironment;
import markets.alpaca.client.openapi.data.model.CorporateActionEvent;
import markets.alpaca.client.openapi.trading.model.ActivityEventV2;
import markets.alpaca.client.trading.sse.TradingActivitySseRequest;
import markets.alpaca.client.trading.sse.TradingEventsSseClient;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

class SseDomainContractTest {

  private static final AlpacaCredentials CREDENTIALS =
      new AlpacaCredentials("test-key", "test-secret");
  private static final String SINCE_ID = "01J9RPMV5TKB8WX3M4F1KZ7QH2";
  private static final String UNTIL_ID = "01J9RVB6Y4ZK8M3N7QD2WX1RFP";

  @TestFactory
  Stream<DynamicTest> subscriptionsOpenResumeAndCancelConsistently() {
    return dynamicTests(
        domain -> {
          try (var server = new MockWebServer()) {
            server.start();
            server.enqueue(
                new MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(":\n\n".repeat(1_000))
                    .throttleBody(1, 1, TimeUnit.SECONDS));
            var options = AlpacaSseOptions.builder().initialLastEventId(SINCE_ID).build();
            var subscription = domain.open(server, options, false, event -> {});
            try {
              assertEquals(200, subscription.opened().get(3, TimeUnit.SECONDS).statusCode());
              var request = server.takeRequest(3, TimeUnit.SECONDS);
              assertNotNull(request);
              assertEquals(SINCE_ID, request.getHeader("Last-Event-ID"));
              if (domain.resumeWithQueryParameter()) {
                assertEquals(SINCE_ID, request.getRequestUrl().queryParameter("since_id"));
              }
            } finally {
              subscription.close();
            }
            assertEquals(
                AlpacaSseCloseResult.Reason.USER_CLOSED,
                subscription.completion().get(3, TimeUnit.SECONDS).reason());
          }
        });
  }

  @TestFactory
  Stream<DynamicTest> eventEnvelopesRemainOrderedAcrossDomains() {
    return dynamicTests(
        domain -> {
          try (var server = new MockWebServer()) {
            server.start();
            server.enqueue(
                new MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(
                        "id: first\ndata: "
                            + domain.payload()
                            + "\n\nid: second\ndata: "
                            + domain.payload()
                            + "\n\n"));
            var eventIds = new CopyOnWriteArrayList<String>();
            var subscription =
                domain.open(
                    server,
                    AlpacaSseOptions.reconnectDisabled(),
                    false,
                    event -> eventIds.add(event.id()));
            try {
              subscription.completion().get(3, TimeUnit.SECONDS);
            } finally {
              subscription.close();
            }
            assertEquals(List.of("first", "second"), eventIds);
            assertEquals("second", subscription.lastEventId().orElseThrow());
          }
        });
  }

  @TestFactory
  Stream<DynamicTest> boundedRequestsCompleteAtEofAcrossDomains() {
    return dynamicTests(
        domain -> {
          try (var server = new MockWebServer()) {
            server.start();
            server.enqueue(
                new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(""));
            var subscription = domain.open(server, AlpacaSseOptions.defaults(), true, event -> {});
            assertEquals(200, subscription.opened().get(3, TimeUnit.SECONDS).statusCode());
            assertEquals(
                AlpacaSseCloseResult.Reason.BOUNDED_END,
                subscription.completion().get(3, TimeUnit.SECONDS).reason());
          }
        });
  }

  @TestFactory
  Stream<DynamicTest> reconnectsSendTheDeliveredEventIdAcrossDomains() {
    return dynamicTests(
        domain -> {
          try (var server = new MockWebServer()) {
            server.start();
            server.enqueue(
                new MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("id: delivered\ndata: " + domain.payload() + "\n\n"));
            server.enqueue(
                new MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBodyDelay(5, TimeUnit.SECONDS)
                    .setBody(":\n\n"));
            var policy =
                AlpacaSseReconnectPolicy.builder()
                    .initialAttempts(0)
                    .establishedAttempts(1)
                    .initialBackoff(Duration.ofMillis(1))
                    .maxBackoff(Duration.ofMillis(1))
                    .jitterRatio(0)
                    .build();
            var subscription =
                domain.open(
                    server,
                    AlpacaSseOptions.builder().reconnectPolicy(policy).build(),
                    false,
                    event -> {});
            try {
              assertNotNull(server.takeRequest(3, TimeUnit.SECONDS));
              var reconnect = server.takeRequest(3, TimeUnit.SECONDS);
              assertNotNull(reconnect);
              assertEquals("delivered", reconnect.getHeader("Last-Event-ID"));
              if (domain.resumeWithQueryParameter()) {
                assertEquals("delivered", reconnect.getRequestUrl().queryParameter("since_id"));
              }
            } finally {
              subscription.close();
            }
          }
        });
  }

  @TestFactory
  Stream<DynamicTest> retryExhaustionFailsAcrossDomains() {
    return dynamicTests(
        domain -> {
          try (var server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(503).setBody("unavailable"));
            server.enqueue(new MockResponse().setResponseCode(503).setBody("unavailable"));
            var policy =
                AlpacaSseReconnectPolicy.builder()
                    .initialAttempts(1)
                    .establishedAttempts(0)
                    .initialBackoff(Duration.ofMillis(1))
                    .maxBackoff(Duration.ofMillis(1))
                    .jitterRatio(0)
                    .build();
            var subscription =
                domain.open(
                    server,
                    AlpacaSseOptions.builder().reconnectPolicy(policy).build(),
                    false,
                    event -> {});
            var failure =
                assertThrows(
                    ExecutionException.class,
                    () -> subscription.completion().get(3, TimeUnit.SECONDS));
            assertInstanceOf(AlpacaSseHttpException.class, failure.getCause());
            assertEquals(2, server.getRequestCount());
          }
        });
  }

  @TestFactory
  Stream<DynamicTest> resourceLimitsFailAcrossDomains() {
    return dynamicTests(
        domain -> {
          try (var server = new MockWebServer()) {
            server.start();
            server.enqueue(
                new MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("data: " + domain.payload() + "\n\n"));
            var options =
                AlpacaSseOptions.reconnectDisabled().toBuilder().maxEventBytes(16).build();
            var subscription = domain.open(server, options, false, event -> {});
            var failure =
                assertThrows(
                    ExecutionException.class,
                    () -> subscription.completion().get(3, TimeUnit.SECONDS));
            assertInstanceOf(AlpacaSseProtocolException.class, failure.getCause());
            assertEquals(AlpacaSseState.FAILED, subscription.state());
          }
        });
  }

  private static Stream<DynamicTest> dynamicTests(DomainAssertion assertion) {
    return domainCases().stream()
        .map(domain -> DynamicTest.dynamicTest(domain.name(), () -> assertion.verify(domain)));
  }

  private static List<DomainCase> domainCases() {
    return List.of(
        new DomainCase(
            "Trading account activities",
            SseDomainContractTest::tradingPayload,
            true,
            (server, options, bounded, onEvent) -> {
              var apiClient =
                  AlpacaClientFactory.tradingClient(
                      CREDENTIALS, new OkHttpClient.Builder().build());
              apiClient.setBasePath(baseUrl(server));
              var client = new TradingEventsSseClient(apiClient, options);
              var request =
                  bounded
                      ? TradingActivitySseRequest.throughEventId(SINCE_ID, UNTIL_ID)
                      : TradingActivitySseRequest.live();
              return client.subscribeToActivities(
                  request,
                  new AlpacaSseListener<>() {
                    @Override
                    public void onEvent(AlpacaSseEvent<ActivityEventV2> event) {
                      onEvent.accept(event);
                    }
                  });
            }),
        new DomainCase(
            "Market Data corporate actions",
            SseDomainContractTest::corporateActionPayload,
            false,
            (server, options, bounded, onEvent) -> {
              var apiClient =
                  AlpacaClientFactory.dataClient(CREDENTIALS, new OkHttpClient.Builder().build());
              var client =
                  new CorporateActionsSseClient(
                      apiClient, MarketDataSseEnvironment.custom(baseUrl(server)), options);
              var request =
                  bounded
                      ? CorporateActionsSseRequest.throughEventId(SINCE_ID, UNTIL_ID)
                      : CorporateActionsSseRequest.live();
              return client.subscribeToCorporateActions(
                  request,
                  new AlpacaSseListener<>() {
                    @Override
                    public void onEvent(AlpacaSseEvent<CorporateActionEvent> event) {
                      onEvent.accept(event);
                    }
                  });
            }));
  }

  private static String tradingPayload() {
    return """
        {"activity_type":"FILL","at":"2026-10-01T12:00:00Z","currency":"USD",
        "event_id":"01K6G000000000000000000000","executed_at":"2026-10-01T12:00:00Z",
        "ref_id":"123e4567-e89b-12d3-a456-426614174000","settle_date":"2026-10-02",
        "status":"executed","details":{}}
        """
        .replace("\n", "");
  }

  private static String corporateActionPayload() {
    return """
        {"action":"insert","at":"2026-10-01T12:00:00Z",
        "event_id":"01K6G000000000000000000000","region":"us",
        "event_type":"cash_dividend_corporateaction_event",
        "ca":{"id":"123e4567-e89b-12d3-a456-426614174000",
        "process_date":"2026-10-01","cusip":"037833100","ex_date":"2026-10-02",
        "foreign":false,"rate":0.25,"special":false,"symbol":"AAPL"}}
        """
        .replace("\n", "");
  }

  private static String baseUrl(MockWebServer server) {
    String value = server.url("/").toString();
    return value.substring(0, value.length() - 1);
  }

  private record DomainCase(
      String name,
      Payload payloadFactory,
      boolean resumeWithQueryParameter,
      SubscriptionOpener opener) {
    String payload() {
      return payloadFactory.get();
    }

    AlpacaSseSubscription open(
        MockWebServer server,
        AlpacaSseOptions options,
        boolean bounded,
        Consumer<AlpacaSseEvent<?>> onEvent)
        throws Exception {
      return opener.open(server, options, bounded, onEvent);
    }
  }

  @FunctionalInterface
  private interface Payload {
    String get();
  }

  @FunctionalInterface
  private interface SubscriptionOpener {
    AlpacaSseSubscription open(
        MockWebServer server,
        AlpacaSseOptions options,
        boolean bounded,
        Consumer<AlpacaSseEvent<?>> onEvent)
        throws Exception;
  }

  @FunctionalInterface
  private interface DomainAssertion {
    void verify(DomainCase domain) throws Exception;
  }
}
