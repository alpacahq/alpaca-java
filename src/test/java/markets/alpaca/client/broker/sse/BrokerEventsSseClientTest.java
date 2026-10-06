package markets.alpaca.client.broker.sse;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import markets.alpaca.client.AlpacaClientFactory;
import markets.alpaca.client.AlpacaCredentials;
import markets.alpaca.client.openapi.broker.model.TradeUpdateEventV2;
import markets.alpaca.client.sse.AlpacaSseCloseResult;
import markets.alpaca.client.sse.AlpacaSseDeserializationException;
import markets.alpaca.client.sse.AlpacaSseHttpException;
import markets.alpaca.client.sse.AlpacaSseOptions;
import markets.alpaca.client.sse.AlpacaSseReconnectPolicy;
import markets.alpaca.client.sse.AlpacaSseState;
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
    String userAgent = request.getHeader("User-Agent");
    assertNotNull(userAgent);
    assertTrue(userAgent.startsWith("APCA-JAVA/"));
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
      assertEquals("text/event-stream", request.getHeader("Accept"));
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
    var responseBody = new AtomicReference<String>();
    var callbackThrowable = new AtomicReference<Throwable>();

    try (var subscription =
        sse.subscribeToTradeEvents(
            BrokerSseDateOptions.empty(),
            new BrokerSseEventListener<>() {
              @Override
              public void onFailure(Throwable throwable, okhttp3.Response response) {
                callbackThrowable.set(throwable);
                if (response != null) {
                  responseCode.set(response.code());
                  try {
                    if (response.body() != null) responseBody.set(response.body().string());
                  } catch (IOException failure) {
                    responseBody.set("failed to read: " + failure.getMessage());
                  }
                }
                failureLatch.countDown();
              }
            })) {
      assertTrue(failureLatch.await(3, TimeUnit.SECONDS), "failure callback must fire");
    }

    assertEquals(503, responseCode.get());
    assertEquals("temporarily unavailable", responseBody.get());
    assertNull(callbackThrowable.get());
  }

  @Test
  void richHttpFailureDoesNotAlsoInvokeLegacyFailure() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(403).setBody("forbidden"));
    var brokerClient = AlpacaClientFactory.brokerClient(CREDS, httpClient);
    brokerClient.setBasePath(serverBasePath());
    var sse = new BrokerEventsSseClient(brokerClient);
    var richFailure = new AtomicReference<AlpacaSseHttpException>();
    var responseCode = new AtomicReference<Integer>();
    var legacyFailures = new AtomicInteger();
    var delivered = new CountDownLatch(1);

    try (var ignored =
        sse.subscribeToTradeEvents(
            BrokerSseDateOptions.empty(),
            new BrokerSseEventListener<>() {
              @Override
              public void onHttpFailure(AlpacaSseHttpException failure, okhttp3.Response response) {
                richFailure.set(failure);
                responseCode.set(response.code());
                delivered.countDown();
              }

              @Override
              public void onFailure(Throwable throwable, okhttp3.Response response) {
                legacyFailures.incrementAndGet();
              }
            })) {
      assertTrue(delivered.await(3, TimeUnit.SECONDS));
    }

    assertEquals(403, richFailure.get().statusCode());
    assertEquals(403, responseCode.get());
    assertEquals(0, legacyFailures.get());
  }

  @Test
  void userClosePreservesLegacyCancellationCallbackAndStructuredCompletion() throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "text/event-stream")
            .setBodyDelay(5, TimeUnit.SECONDS)
            .setBody("data: {\"event_id\":\"late\"}\n\n"));
    var brokerClient = AlpacaClientFactory.brokerClient(CREDS, httpClient);
    brokerClient.setBasePath(serverBasePath());
    var sse = new BrokerEventsSseClient(brokerClient);
    var opened = new CountDownLatch(1);
    var failed = new CountDownLatch(1);
    var failure = new AtomicReference<Throwable>();
    var response = new AtomicReference<okhttp3.Response>();

    var subscription =
        sse.subscribeToTradeEvents(
            BrokerSseDateOptions.empty(),
            new BrokerSseEventListener<>() {
              @Override
              public void onOpen() {
                opened.countDown();
              }

              @Override
              public void onFailure(Throwable throwable, okhttp3.Response failedResponse) {
                failure.set(throwable);
                response.set(failedResponse);
                failed.countDown();
              }
            });
    assertTrue(opened.await(3, TimeUnit.SECONDS));
    subscription.close();

    assertEquals(
        AlpacaSseCloseResult.Reason.USER_CLOSED,
        subscription.completion().get(1, TimeUnit.SECONDS).reason());
    assertEquals(AlpacaSseState.CLOSED, subscription.state());
    assertTrue(failed.await(3, TimeUnit.SECONDS));
    var cancellation = assertInstanceOf(IOException.class, failure.get());
    assertEquals("canceled", cancellation.getMessage());
    assertNull(response.get());
  }

  @Test
  void richUserCloseDoesNotAlsoInvokeLegacyCancellationCallback() throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "text/event-stream")
            .setBodyDelay(5, TimeUnit.SECONDS)
            .setBody("data: {\"event_id\":\"late\"}\n\n"));
    var brokerClient = AlpacaClientFactory.brokerClient(CREDS, httpClient);
    brokerClient.setBasePath(serverBasePath());
    var sse = new BrokerEventsSseClient(brokerClient);
    var opened = new CountDownLatch(1);
    var richlyClosed = new CountDownLatch(1);
    var closeResult = new AtomicReference<AlpacaSseCloseResult>();
    var legacyFailures = new AtomicInteger();

    var subscription =
        sse.subscribeToTradeEvents(
            BrokerSseDateOptions.empty(),
            new BrokerSseEventListener<>() {
              @Override
              public void onOpen() {
                opened.countDown();
              }

              @Override
              public void onClosed(AlpacaSseCloseResult result) {
                closeResult.set(result);
                richlyClosed.countDown();
              }

              @Override
              public void onFailure(Throwable throwable, okhttp3.Response response) {
                legacyFailures.incrementAndGet();
              }
            });
    assertTrue(opened.await(3, TimeUnit.SECONDS));
    subscription.close();

    assertTrue(richlyClosed.await(3, TimeUnit.SECONDS));
    assertEquals(AlpacaSseCloseResult.Reason.USER_CLOSED, closeResult.get().reason());
    assertEquals(0, legacyFailures.get());
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
  void malformedSseEvent_reportsFailureAndContinuesStream() throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "text/event-stream")
            .setBody("data: not-json\n\ndata: {\"event_id\":\"evt-after-failure\"}\n\n"));

    var brokerClient = AlpacaClientFactory.brokerClient(CREDS, httpClient);
    brokerClient.setBasePath(serverBasePath());
    ExecutorService callbackExecutor =
        Executors.newSingleThreadExecutor(r -> new Thread(r, "broker-sse-failure-test"));
    var sse = new BrokerEventsSseClient(brokerClient, callbackExecutor);
    var failure = new AtomicReference<Throwable>();
    var callbackThreadName = new AtomicReference<String>();
    var failureLatch = new CountDownLatch(1);
    var eventLatch = new CountDownLatch(1);

    try (var subscription =
        sse.subscribeToTradeEvents(
            BrokerSseDateOptions.empty(),
            new BrokerSseEventListener<>() {
              @Override
              public void onFailure(Throwable throwable, okhttp3.Response response) {
                failure.compareAndSet(null, throwable);
                callbackThreadName.set(Thread.currentThread().getName());
                failureLatch.countDown();
              }

              @Override
              public void onEvent(TradeUpdateEventV2 event) {
                if ("evt-after-failure".equals(event.getEventId())) eventLatch.countDown();
              }
            })) {
      assertTrue(failureLatch.await(3, TimeUnit.SECONDS), "parse failure callback must fire");
      assertTrue(eventLatch.await(3, TimeUnit.SECONDS), "stream must continue after parse failure");
    } finally {
      callbackExecutor.shutdownNow();
    }

    assertNotNull(failure.get());
    assertFalse(failure.get() instanceof AlpacaSseDeserializationException);
    assertEquals("broker-sse-failure-test", callbackThreadName.get());
  }

  @Test
  void enrichedCallbacksDoNotDoubleDeliverLegacyCallbacks() throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "text/event-stream")
            .setBody(
                "retry: 25\n\n"
                    + "id: poison\n"
                    + "event: broker-activity\n"
                    + "data: not-json\n\n"));
    var brokerClient = AlpacaClientFactory.brokerClient(CREDS, httpClient);
    brokerClient.setBasePath(serverBasePath());
    var sse = new BrokerEventsSseClient(brokerClient);
    var retryChanged = new CountDownLatch(1);
    var eventFailed = new CountDownLatch(1);
    var richlyClosed = new CountDownLatch(1);
    var legacyFailures = new AtomicInteger();
    var legacyCloses = new AtomicInteger();
    var decodingFailure = new AtomicReference<AlpacaSseDeserializationException>();
    var closeResult = new AtomicReference<markets.alpaca.client.sse.AlpacaSseCloseResult>();

    try (var ignored =
        sse.subscribeToTradeEvents(
            BrokerSseDateOptions.empty(),
            new BrokerSseEventListener<>() {
              @Override
              public void onRetryChanged(Duration delay) {
                assertEquals(Duration.ofMillis(25), delay);
                retryChanged.countDown();
              }

              @Override
              public void onEventFailure(AlpacaSseDeserializationException failure) {
                decodingFailure.set(failure);
                eventFailed.countDown();
              }

              @Override
              public void onFailure(Throwable throwable, okhttp3.Response response) {
                legacyFailures.incrementAndGet();
              }

              @Override
              public void onClosed(markets.alpaca.client.sse.AlpacaSseCloseResult result) {
                closeResult.set(result);
                richlyClosed.countDown();
              }

              @Override
              public void onClosed() {
                legacyCloses.incrementAndGet();
              }
            })) {
      assertTrue(retryChanged.await(3, TimeUnit.SECONDS));
      assertTrue(eventFailed.await(3, TimeUnit.SECONDS));
      assertTrue(richlyClosed.await(3, TimeUnit.SECONDS));
    }

    assertEquals("poison", decodingFailure.get().eventId());
    assertEquals("broker-activity", decodingFailure.get().eventType());
    assertEquals(
        markets.alpaca.client.sse.AlpacaSseCloseResult.Reason.REMOTE_END,
        closeResult.get().reason());
    assertEquals(0, legacyFailures.get());
    assertEquals(0, legacyCloses.get());
  }

  @Test
  void malformedSseEventAdvancesResumeCursorWhenReconnectIsEnabled() throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "text/event-stream")
            .setBody("id: poison\ndata: not-json\n\n"));
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "text/event-stream")
            .setBody("id: good\ndata: {\"event_id\":\"evt-after-reconnect\"}\n\n"));

    var brokerClient = AlpacaClientFactory.brokerClient(CREDS, httpClient);
    brokerClient.setBasePath(serverBasePath());
    var options =
        AlpacaSseOptions.builder()
            .reconnectPolicy(
                AlpacaSseReconnectPolicy.builder()
                    .initialBackoff(Duration.ofMillis(1))
                    .maxBackoff(Duration.ofMillis(1))
                    .jitterRatio(0)
                    .build())
            .build();
    var sse = new BrokerEventsSseClient(brokerClient, options, Runnable::run);
    var failureLatch = new CountDownLatch(1);
    var eventLatch = new CountDownLatch(1);

    try (var ignored =
        sse.subscribeToTradeEvents(
            BrokerSseDateOptions.empty(),
            new BrokerSseEventListener<>() {
              @Override
              public void onFailure(Throwable throwable, okhttp3.Response response) {
                failureLatch.countDown();
                throw new IllegalStateException("application decode-failure callback failed");
              }

              @Override
              public void onEvent(TradeUpdateEventV2 event) {
                if ("evt-after-reconnect".equals(event.getEventId())) eventLatch.countDown();
              }
            })) {
      assertTrue(failureLatch.await(3, TimeUnit.SECONDS));
      assertTrue(eventLatch.await(3, TimeUnit.SECONDS));
    }

    assertNull(server.takeRequest().getHeader("Last-Event-ID"));
    assertEquals("poison", server.takeRequest().getHeader("Last-Event-ID"));
  }

  @Test
  void getAccountActivityEventAsync_readsOneSseMessage() throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "text/event-stream")
            .setBody("data: " + fillActivityJson() + "\n\n"));
    var brokerClient = AlpacaClientFactory.brokerClient(CREDS, httpClient);
    brokerClient.setBasePath(serverBasePath());
    var sse = new BrokerEventsSseClient(brokerClient);
    var accountId = UUID.fromString("123e4567-e89b-12d3-a456-426614174002");

    var event =
        sse.getAccountActivityEventAsync(accountId, "01K6G000000000000000000000")
            .get(3, TimeUnit.SECONDS);

    assertEquals("FILL", event.getActivityType());
    assertEquals(accountId, event.getAccountId());
    var request = server.takeRequest(3, TimeUnit.SECONDS);
    assertNotNull(request);
    assertEquals(
        "/v2beta1/accounts/" + accountId + "/events/activities/01K6G000000000000000000000",
        request.getPath());
  }

  private String serverBasePath() {
    String url = server.url("/").toString();
    return url.substring(0, url.length() - 1);
  }

  private static String fillActivityJson() {
    return """
        {"account_id":"123e4567-e89b-12d3-a456-426614174002","activity_type":"FILL",
        "at":"2026-10-01T12:00:00Z","currency":"USD",
        "event_id":"01K6G000000000000000000000","executed_at":"2026-10-01T12:00:00Z",
        "ref_id":"123e4567-e89b-12d3-a456-426614174000","settle_date":"2026-10-02",
        "status":"executed","details":{}}
        """
        .replace("\n", "");
  }
}
