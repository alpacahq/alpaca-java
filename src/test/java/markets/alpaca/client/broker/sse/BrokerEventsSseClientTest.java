package markets.alpaca.client.broker.sse;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import markets.alpaca.client.AlpacaClientFactory;
import markets.alpaca.client.AlpacaCredentials;
import markets.alpaca.client.openapi.broker.model.TradeUpdateEventV2;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BrokerEventsSseClientTest {

  private static final AlpacaCredentials CREDS =
      new AlpacaCredentials("broker-key", "broker-secret");

  private MockWebServer server;
  private OkHttpClient httpClient;

  @BeforeEach
  void setUp() throws IOException {
    server = new MockWebServer();
    server.start();
    httpClient =
        new OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build();
  }

  @AfterEach
  void tearDown() throws IOException {
    server.close();
  }

  @Test
  void subscribeToTradeEvents_opensSseRequestAndDispatchesTypedEvent() throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "text/event-stream")
            .setBody("data: {\"event_id\":\"evt-1\"}\n\n"));

    var brokerClient = AlpacaClientFactory.brokerClient(CREDS, httpClient);
    brokerClient.setBasePath(serverBasePath());
    var sse = new BrokerEventsSseClient(brokerClient);
    var received = new AtomicReference<TradeUpdateEventV2>();
    var eventLatch = new CountDownLatch(1);

    try (var subscription =
        sse.subscribeToTradeEvents(
            BrokerSseDateOptions.builder()
                .since(LocalDate.of(2026, 6, 1))
                .sinceId("since-ulid")
                .build(),
            new BrokerSseEventListener<>() {
              @Override
              public void onEvent(TradeUpdateEventV2 event) {
                received.set(event);
                eventLatch.countDown();
              }
            })) {
      assertTrue(eventLatch.await(3, TimeUnit.SECONDS), "typed SSE event must be delivered");
    }

    var request = server.takeRequest(3, TimeUnit.SECONDS);
    assertNotNull(request, "server must receive the SSE request");
    assertEquals("/v2/events/trades?since=2026-06-01&since_id=since-ulid", request.getPath());
    assertEquals("text/event-stream", request.getHeader("Accept"));
    assertNotNull(request.getHeader("Authorization"), "Broker Basic auth must be applied");
    assertNotNull(received.get());
    assertEquals("evt-1", received.get().getEventId());
  }

  @Test
  void subscribeToNonTradingActivities_optionsObjectBuildsRequestQuery() throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "text/event-stream")
            .setBody(""));

    var brokerClient = AlpacaClientFactory.brokerClient(CREDS, httpClient);
    brokerClient.setBasePath(serverBasePath());
    var sse = new BrokerEventsSseClient(brokerClient);
    var groupId = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

    try (var subscription =
        sse.subscribeToNonTradingActivities(
            BrokerSseNonTradingActivitiesOptions.builder()
                .id("activity-id")
                .since(LocalDate.of(2026, 6, 1))
                .until(LocalDate.of(2026, 6, 2))
                .sinceId(10)
                .untilId(20)
                .sinceUlid("since-ulid")
                .untilUlid("until-ulid")
                .includePreprocessing(true)
                .groupId(groupId)
                .build(),
            new BrokerSseEventListener<>() {})) {
      var request = server.takeRequest(3, TimeUnit.SECONDS);
      assertNotNull(request, "server must receive the SSE request");
      assertEquals(
          "/v1/events/nta"
              + "?id=activity-id"
              + "&since=2026-06-01"
              + "&until=2026-06-02"
              + "&since_id=10"
              + "&until_id=20"
              + "&since_ulid=since-ulid"
              + "&until_ulid=until-ulid"
              + "&include_preprocessing=true"
              + "&group_id=123e4567-e89b-12d3-a456-426614174000",
          request.getPath());
    }
  }

  @Test
  void subscribeToTradeEvents_rejectsNullOptions() {
    var brokerClient = AlpacaClientFactory.brokerClient(CREDS, httpClient);
    brokerClient.setBasePath(serverBasePath());
    var sse = new BrokerEventsSseClient(brokerClient);

    assertThrows(
        NullPointerException.class,
        () -> sse.subscribeToTradeEvents(null, new BrokerSseEventListener<>() {}));
  }

  @Test
  void subscribeToTradeEvents_nonSuccessfulResponseCallsFailureCallback() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(503).setBody("temporarily unavailable"));

    var brokerClient = AlpacaClientFactory.brokerClient(CREDS, httpClient);
    brokerClient.setBasePath(serverBasePath());
    var sse = new BrokerEventsSseClient(brokerClient);
    var failureLatch = new CountDownLatch(1);
    var responseCode = new AtomicReference<Integer>();

    try (var subscription =
        sse.subscribeToTradeEvents(
            BrokerSseDateOptions.empty(),
            new BrokerSseEventListener<>() {
              @Override
              public void onFailure(Throwable throwable, okhttp3.Response response) {
                if (response != null) responseCode.set(response.code());
                failureLatch.countDown();
              }
            })) {
      assertTrue(failureLatch.await(3, TimeUnit.SECONDS), "failure callback must fire");
    }

    assertEquals(503, responseCode.get());
  }

  @Test
  void callbackExecutor_dispatchesSseCallbacksOffEventSourceThread() throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "text/event-stream")
            .setBody("data: {\"event_id\":\"evt-1\"}\n\n"));

    var brokerClient = AlpacaClientFactory.brokerClient(CREDS, httpClient);
    brokerClient.setBasePath(serverBasePath());
    ExecutorService callbackExecutor =
        Executors.newSingleThreadExecutor(r -> new Thread(r, "broker-sse-callback-test"));
    var sse = new BrokerEventsSseClient(brokerClient, callbackExecutor);
    var callbackThreadName = new AtomicReference<String>();
    var eventLatch = new CountDownLatch(1);

    try (var subscription =
        sse.subscribeToTradeEvents(
            BrokerSseDateOptions.empty(),
            new BrokerSseEventListener<>() {
              @Override
              public void onEvent(TradeUpdateEventV2 event) {
                callbackThreadName.set(Thread.currentThread().getName());
                eventLatch.countDown();
              }
            })) {
      assertTrue(eventLatch.await(3, TimeUnit.SECONDS), "typed SSE event must be delivered");
    } finally {
      callbackExecutor.shutdownNow();
    }

    assertEquals("broker-sse-callback-test", callbackThreadName.get());
  }

  @Test
  void malformedSseEvent_callsFailureCallback() throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "text/event-stream")
            .setBody("data: not-json\n\n"));

    var brokerClient = AlpacaClientFactory.brokerClient(CREDS, httpClient);
    brokerClient.setBasePath(serverBasePath());
    ExecutorService callbackExecutor =
        Executors.newSingleThreadExecutor(r -> new Thread(r, "broker-sse-failure-test"));
    var sse = new BrokerEventsSseClient(brokerClient, callbackExecutor);
    var failure = new AtomicReference<Throwable>();
    var callbackThreadName = new AtomicReference<String>();
    var failureLatch = new CountDownLatch(1);

    try (var subscription =
        sse.subscribeToTradeEvents(
            BrokerSseDateOptions.empty(),
            new BrokerSseEventListener<>() {
              @Override
              public void onFailure(Throwable throwable, okhttp3.Response response) {
                failure.set(throwable);
                callbackThreadName.set(Thread.currentThread().getName());
                failureLatch.countDown();
              }
            })) {
      assertTrue(failureLatch.await(3, TimeUnit.SECONDS), "parse failure callback must fire");
    } finally {
      callbackExecutor.shutdownNow();
    }

    assertNotNull(failure.get());
    assertEquals("broker-sse-failure-test", callbackThreadName.get());
  }

  private String serverBasePath() {
    String url = server.url("/").toString();
    return url.substring(0, url.length() - 1);
  }
}
