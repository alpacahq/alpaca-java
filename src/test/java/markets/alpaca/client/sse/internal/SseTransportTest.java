package markets.alpaca.client.sse.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import markets.alpaca.client.sse.AlpacaSseCallbackException;
import markets.alpaca.client.sse.AlpacaSseCloseResult;
import markets.alpaca.client.sse.AlpacaSseDeserializationException;
import markets.alpaca.client.sse.AlpacaSseEvent;
import markets.alpaca.client.sse.AlpacaSseHttpException;
import markets.alpaca.client.sse.AlpacaSseListener;
import markets.alpaca.client.sse.AlpacaSseOptions;
import markets.alpaca.client.sse.AlpacaSseReconnectPolicy;
import markets.alpaca.client.sse.AlpacaSseState;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SseTransportTest {

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
  void retriesTransientResponseAndResumesAfterDeliveredEvent() throws Exception {
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody("id: evt-1\ndata: first\n\n"));
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "text/event-stream; charset=utf-8")
            .setBody("id: evt-2\ndata: second\n\n"));
    var events = new java.util.concurrent.CopyOnWriteArrayList<String>();
    var latch = new CountDownLatch(2);
    var options =
        AlpacaSseOptions.builder()
            .reconnectPolicy(
                AlpacaSseReconnectPolicy.builder()
                    .initialBackoff(Duration.ofMillis(1))
                    .maxBackoff(Duration.ofMillis(1))
                    .jitterRatio(0)
                    .build())
            .build();

    try (var subscription =
        SseTransport.open(
            httpClient,
            new Request.Builder().url(server.url("/events")).build(),
            options,
            false,
            data -> data,
            new AlpacaSseListener<>() {
              @Override
              public void onEvent(AlpacaSseEvent<String> event) {
                events.add(event.data());
                latch.countDown();
              }
            })) {
      assertTrue(latch.await(3, TimeUnit.SECONDS));
      assertEquals("evt-2", subscription.lastEventId().orElseThrow());
    }

    assertNull(server.takeRequest().getHeader("Last-Event-ID"));
    assertEquals("evt-1", server.takeRequest().getHeader("Last-Event-ID"));
    assertEquals(java.util.List.of("first", "second"), events);
  }

  @Test
  void reconnectsWithAUnicodeLastEventId() throws Exception {
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody("id: évènement-一\ndata: first\n\n"));
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody("data: second\n\n"));
    var events = new CountDownLatch(2);

    try (var subscription =
        SseTransport.open(
            httpClient,
            new Request.Builder().url(server.url("/events")).build(),
            reconnectOptions(Duration.ofMillis(1)),
            false,
            data -> data,
            new AlpacaSseListener<>() {
              @Override
              public void onEvent(AlpacaSseEvent<String> event) {
                events.countDown();
              }
            })) {
      assertTrue(events.await(3, TimeUnit.SECONDS));
    }

    assertNull(server.takeRequest().getHeader("Last-Event-ID"));
    assertEquals("évènement-一", server.takeRequest().getHeader("Last-Event-ID"));
  }

  @Test
  void illegalStreamCursorFailsInsteadOfStrandingReconnect() throws Exception {
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody("id: invalid\u0001cursor\ndata: value\n\n"));
    var delivered = new AtomicBoolean();

    var subscription =
        SseTransport.open(
            httpClient,
            new Request.Builder().url(server.url("/events")).build(),
            reconnectOptions(Duration.ofMillis(1)),
            false,
            data -> data,
            new AlpacaSseListener<>() {
              @Override
              public void onEvent(AlpacaSseEvent<String> event) {
                delivered.set(true);
              }
            });

    var failure =
        assertThrows(
            ExecutionException.class, () -> subscription.completion().get(3, TimeUnit.SECONDS));
    assertInstanceOf(
        markets.alpaca.client.sse.AlpacaSseProtocolException.class, failure.getCause());
    assertEquals(AlpacaSseState.FAILED, subscription.state());
    assertFalse(delivered.get());
  }

  @Test
  void resumeRequestFailureTerminatesInsteadOfRemainingReconnecting() throws Exception {
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody("id: evt-1\ndata: first\n\n"));
    var firstEvent = new CountDownLatch(1);

    var subscription =
        SseTransport.open(
            httpClient,
            new Request.Builder().url(server.url("/events")).build(),
            reconnectOptions(Duration.ofMillis(1)),
            false,
            data -> data,
            new AlpacaSseListener<>() {
              @Override
              public void onEvent(AlpacaSseEvent<String> event) {
                firstEvent.countDown();
              }
            },
            Runnable::run,
            (request, eventId) -> {
              if (eventId != null) throw new IllegalArgumentException("invalid resume cursor");
              return request;
            });

    assertTrue(firstEvent.await(3, TimeUnit.SECONDS));
    var failure =
        assertThrows(
            ExecutionException.class, () -> subscription.completion().get(3, TimeUnit.SECONDS));
    assertInstanceOf(
        markets.alpaca.client.sse.AlpacaSseProtocolException.class, failure.getCause());
    assertEquals(AlpacaSseState.FAILED, subscription.state());
  }

  @Test
  void openedCompletesBeforeCallbackWithImmutableConnectionMetadata() throws Exception {
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setHeader("X-Connection", "initial")
            .setBody(""));
    var subscriptionReady = new CountDownLatch(1);
    var callbackFinished = new CountDownLatch(1);
    var subscriptionReference =
        new AtomicReference<markets.alpaca.client.sse.AlpacaSseSubscription>();
    var callbackState = new AtomicReference<markets.alpaca.client.sse.AlpacaSseState>();

    var subscription =
        SseTransport.open(
            httpClient,
            new Request.Builder().url(server.url("/events")).build(),
            AlpacaSseOptions.reconnectDisabled(),
            true,
            data -> data,
            new AlpacaSseListener<>() {
              @Override
              public void onOpen() {
                try {
                  assertTrue(subscriptionReady.await(3, TimeUnit.SECONDS));
                  var active = subscriptionReference.get();
                  active.opened().join();
                  callbackState.set(active.state());
                } catch (InterruptedException failure) {
                  Thread.currentThread().interrupt();
                } finally {
                  callbackFinished.countDown();
                }
              }
            });
    subscriptionReference.set(subscription);
    subscriptionReady.countDown();

    assertTrue(callbackFinished.await(3, TimeUnit.SECONDS));
    var connection = subscription.opened().get(3, TimeUnit.SECONDS);
    assertEquals(markets.alpaca.client.sse.AlpacaSseState.OPEN, callbackState.get());
    assertEquals(server.url("/events").uri(), connection.uri());
    assertEquals(200, connection.statusCode());
    assertEquals("initial", connection.header("X-Connection").orElseThrow());
    assertEquals(connection, subscription.connection().orElseThrow());
    assertThrows(
        UnsupportedOperationException.class,
        () -> connection.headers().put("another", java.util.List.of("value")));
    assertThrows(
        UnsupportedOperationException.class,
        () -> connection.headers().values().iterator().next().add("another"));
    subscription.close();
  }

  @Test
  void reconnectUpdatesCurrentConnectionWithoutReplacingOpenedResult() throws Exception {
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setHeader("X-Connection", "initial")
            .setBody("data: first\n\n"));
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setHeader("X-Connection", "replacement")
            .setBody("data: second\n\n"));
    var events = new CountDownLatch(2);

    try (var subscription =
        SseTransport.open(
            httpClient,
            new Request.Builder().url(server.url("/events")).build(),
            reconnectOptions(Duration.ofMillis(1)),
            false,
            data -> data,
            new AlpacaSseListener<>() {
              @Override
              public void onEvent(AlpacaSseEvent<String> event) {
                events.countDown();
              }
            })) {
      assertTrue(events.await(3, TimeUnit.SECONDS));
      assertEquals(
          "initial",
          subscription.opened().get(3, TimeUnit.SECONDS).header("X-Connection").orElseThrow());
      assertEquals(
          "replacement",
          subscription.connection().orElseThrow().header("X-Connection").orElseThrow());
    }
  }

  @Test
  void openedFailsWhenRequestFailsBeforeOpening() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(403).setBody("forbidden"));

    var subscription =
        SseTransport.open(
            httpClient,
            new Request.Builder().url(server.url("/events")).build(),
            AlpacaSseOptions.reconnectDisabled(),
            false,
            data -> data,
            new AlpacaSseListener<>() {});

    var failure =
        assertThrows(
            ExecutionException.class, () -> subscription.opened().get(3, TimeUnit.SECONDS));
    assertInstanceOf(AlpacaSseHttpException.class, failure.getCause());
    assertTrue(subscription.connection().isEmpty());
  }

  @Test
  void openedFailsWhenStreamClosesNormallyBeforeOpening() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(204));

    var subscription =
        SseTransport.open(
            httpClient,
            new Request.Builder().url(server.url("/events")).build(),
            AlpacaSseOptions.reconnectDisabled(),
            false,
            data -> data,
            new AlpacaSseListener<>() {});

    assertThrows(ExecutionException.class, () -> subscription.opened().get(3, TimeUnit.SECONDS));
    assertTrue(subscription.connection().isEmpty());
  }

  @Test
  void openedIsExceptionalWhenClosedBeforeResponse() throws Exception {
    server.enqueue(new MockResponse().setHeadersDelay(5, TimeUnit.SECONDS));

    var subscription =
        SseTransport.open(
            httpClient,
            new Request.Builder().url(server.url("/events")).build(),
            AlpacaSseOptions.reconnectDisabled(),
            false,
            data -> data,
            new AlpacaSseListener<>() {});
    CompletableFuture<?> opened = subscription.opened();
    subscription.close();

    assertTrue(opened.isCompletedExceptionally());
    assertTrue(subscription.connection().isEmpty());
  }

  @Test
  void terminalHttpFailureCapturesMetadata() throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(403)
            .setHeader("APCA-Request-ID", "request-1")
            .setBody("forbidden"));
    var failure = new AtomicReference<Throwable>();
    var latch = new CountDownLatch(1);

    SseTransport.open(
        httpClient,
        new Request.Builder().url(server.url("/events")).build(),
        AlpacaSseOptions.defaults(),
        false,
        data -> data,
        new AlpacaSseListener<>() {
          @Override
          public void onFailure(Throwable throwable) {
            failure.set(throwable);
            latch.countDown();
          }
        });

    assertTrue(latch.await(3, TimeUnit.SECONDS));
    var httpFailure = assertInstanceOf(AlpacaSseHttpException.class, failure.get());
    assertEquals(403, httpFailure.statusCode());
    assertEquals("request-1", httpFailure.requestId());
    assertEquals("forbidden", httpFailure.responseBody());
  }

  @Test
  void connectTimeoutBoundsStalledErrorBodyAndPreservesCancellationCause() throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(403)
            .setBodyDelay(5, TimeUnit.SECONDS)
            .setBody("forbidden"));
    var options =
        AlpacaSseOptions.reconnectDisabled().toBuilder()
            .connectTimeout(Duration.ofMillis(100))
            .build();

    var subscription =
        SseTransport.open(
            httpClient,
            new Request.Builder().url(server.url("/events")).build(),
            options,
            false,
            data -> data,
            new AlpacaSseListener<>() {});

    var failure =
        assertThrows(
            ExecutionException.class, () -> subscription.completion().get(2, TimeUnit.SECONDS));
    var timeout = assertInstanceOf(SocketTimeoutException.class, failure.getCause());
    assertNotNull(timeout.getCause());
    assertEquals(AlpacaSseState.FAILED, subscription.state());
  }

  @Test
  void startedConnectTimeoutCannotCancelAnAcceptedResponse() throws Exception {
    server.enqueue(
        new MockResponse()
            .setHeadersDelay(75, TimeUnit.MILLISECONDS)
            .setBodyDelay(75, TimeUnit.MILLISECONDS)
            .setHeader("Content-Type", "text/event-stream")
            .setBody("data: accepted\n\n"));
    var options =
        AlpacaSseOptions.reconnectDisabled().toBuilder()
            .connectTimeout(Duration.ofMillis(25))
            .build();
    var event = new CountDownLatch(1);
    var scheduler = new GatedScheduler();

    try {
      var subscription =
          SseTransport.openForTesting(
              httpClient,
              new Request.Builder().url(server.url("/events")).build(),
              options,
              false,
              data -> data,
              new AlpacaSseListener<>() {
                @Override
                public void onEvent(AlpacaSseEvent<String> delivered) {
                  event.countDown();
                }
              },
              scheduler,
              System::nanoTime);

      assertTrue(scheduler.taskStarted.await(1, TimeUnit.SECONDS));
      subscription.opened().get(1, TimeUnit.SECONDS);
      scheduler.releaseTask.countDown();

      assertTrue(event.await(1, TimeUnit.SECONDS));
      assertNotEquals(AlpacaSseState.FAILED, subscription.state());
      subscription.close();
    } finally {
      scheduler.releaseTask.countDown();
      scheduler.shutdownNow();
    }
  }

  @Test
  void serverRetryDelayIsCappedByMaxBackoff() throws Exception {
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody("retry: 5000\n\n"));
    var reconnecting = new CountDownLatch(1);
    var delay = new AtomicReference<Duration>();

    var subscription =
        SseTransport.open(
            httpClient,
            new Request.Builder().url(server.url("/events")).build(),
            reconnectOptions(Duration.ofMillis(25)),
            false,
            data -> data,
            new AlpacaSseListener<>() {
              @Override
              public void onReconnecting(int attempt, Duration reconnectDelay) {
                delay.set(reconnectDelay);
                reconnecting.countDown();
              }
            });

    assertTrue(reconnecting.await(3, TimeUnit.SECONDS));
    subscription.close();
    assertEquals(Duration.ofMillis(25), delay.get());
  }

  @Test
  void numericRetryAfterIsCappedByMaxBackoff() throws Exception {
    assertRetryAfterCapped("5");
  }

  @Test
  void httpDateRetryAfterIsCappedByMaxBackoff() throws Exception {
    assertRetryAfterCapped(
        ZonedDateTime.now().plusMinutes(1).format(DateTimeFormatter.RFC_1123_DATE_TIME));
  }

  @Test
  void resumesFromCommittedDataLessId() throws Exception {
    server.enqueue(
        new MockResponse().setHeader("Content-Type", "text/event-stream").setBody("id: evt-1\n\n"));
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody("data: resumed\n\n"));
    var latch = new CountDownLatch(1);

    try (var subscription =
        SseTransport.open(
            httpClient,
            new Request.Builder().url(server.url("/events")).build(),
            reconnectOptions(Duration.ofMillis(1)),
            false,
            data -> data,
            new AlpacaSseListener<>() {
              @Override
              public void onEvent(AlpacaSseEvent<String> event) {
                latch.countDown();
              }
            })) {
      assertTrue(latch.await(3, TimeUnit.SECONDS));
      assertEquals("evt-1", subscription.lastEventId().orElseThrow());
    }

    assertNull(server.takeRequest().getHeader("Last-Event-ID"));
    assertEquals("evt-1", server.takeRequest().getHeader("Last-Event-ID"));
  }

  @Test
  void idleTimeoutDoesNotRunDuringReconnectBackoff() throws Exception {
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody("data: first\n\n"));
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody("data: second\n\n"));
    var events = new CountDownLatch(2);
    var failure = new AtomicReference<Throwable>();
    var options =
        reconnectOptions(Duration.ofMillis(150)).toBuilder()
            .idleTimeout(Duration.ofMillis(40))
            .build();

    try (var ignored =
        SseTransport.open(
            httpClient,
            new Request.Builder().url(server.url("/events")).build(),
            options,
            false,
            data -> data,
            new AlpacaSseListener<>() {
              @Override
              public void onEvent(AlpacaSseEvent<String> event) {
                events.countDown();
              }

              @Override
              public void onFailure(Throwable throwable) {
                failure.set(throwable);
              }
            })) {
      assertTrue(events.await(3, TimeUnit.SECONDS));
      assertNull(failure.get());
    }
  }

  @Test
  void idleTimeoutRunsWhileOpenCallbackIsBlocked() throws Exception {
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody("data: late\n\n")
            .setBodyDelay(5, TimeUnit.SECONDS));
    var callbackEntered = new CountDownLatch(1);
    var releaseCallback = new CountDownLatch(1);
    var options =
        AlpacaSseOptions.reconnectDisabled().toBuilder()
            .idleTimeout(Duration.ofMillis(100))
            .build();

    var subscription =
        SseTransport.open(
            httpClient,
            new Request.Builder().url(server.url("/events")).build(),
            options,
            false,
            data -> data,
            new AlpacaSseListener<>() {
              @Override
              public void onOpen() {
                callbackEntered.countDown();
                try {
                  releaseCallback.await();
                } catch (InterruptedException failure) {
                  Thread.currentThread().interrupt();
                }
              }
            });

    try {
      subscription.opened().get(1, TimeUnit.SECONDS);
      assertTrue(callbackEntered.await(1, TimeUnit.SECONDS));
      var failure =
          assertThrows(
              ExecutionException.class, () -> subscription.completion().get(1, TimeUnit.SECONDS));
      assertInstanceOf(SocketTimeoutException.class, failure.getCause());
    } finally {
      releaseCallback.countDown();
    }
  }

  @Test
  void inheritedCallTimeoutDoesNotLimitAnAcceptedStream() throws Exception {
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(": keepalive\n\n".repeat(100))
            .throttleBody(1, 10, TimeUnit.MILLISECONDS));
    OkHttpClient clientWithCallTimeout =
        httpClient.newBuilder().callTimeout(Duration.ofMillis(100)).build();

    var subscription =
        SseTransport.open(
            clientWithCallTimeout,
            new Request.Builder().url(server.url("/events")).build(),
            AlpacaSseOptions.reconnectDisabled(),
            false,
            data -> data,
            new AlpacaSseListener<>() {});

    subscription.opened().get(1, TimeUnit.SECONDS);
    assertThrows(
        TimeoutException.class, () -> subscription.completion().get(300, TimeUnit.MILLISECONDS));
    assertEquals(AlpacaSseState.OPEN, subscription.state());
    subscription.close();
  }

  @Test
  void finiteEstablishedRetryBudgetExhaustsOnRepeatedEmptyConnections() throws Exception {
    for (int i = 0; i < 3; i++) {
      server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(""));
    }
    var failure = new CountDownLatch(1);
    var options =
        AlpacaSseOptions.builder()
            .reconnectPolicy(
                AlpacaSseReconnectPolicy.builder()
                    .initialAttempts(0)
                    .establishedAttempts(2)
                    .initialBackoff(Duration.ofMillis(1))
                    .maxBackoff(Duration.ofMillis(1))
                    .jitterRatio(0)
                    .build())
            .build();

    try (var ignored =
        SseTransport.open(
            httpClient,
            new Request.Builder().url(server.url("/events")).build(),
            options,
            false,
            data -> data,
            new AlpacaSseListener<>() {
              @Override
              public void onFailure(Throwable throwable) {
                failure.countDown();
              }
            })) {
      assertTrue(failure.await(3, TimeUnit.SECONDS));
      assertEquals(3, server.getRequestCount());
    }
  }

  @Test
  void nullDecodeFailsWithoutAdvancingResumeCursor() throws Exception {
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody("id: skipped\ndata: null\n\n"));
    var failure = new AtomicReference<Throwable>();
    var latch = new CountDownLatch(1);

    try (var subscription =
        SseTransport.open(
            httpClient,
            new Request.Builder().url(server.url("/events")).build(),
            AlpacaSseOptions.reconnectDisabled(),
            false,
            data -> null,
            new AlpacaSseListener<Object>() {
              @Override
              public void onFailure(Throwable throwable) {
                failure.set(throwable);
                latch.countDown();
              }
            })) {
      assertTrue(latch.await(3, TimeUnit.SECONDS));
      assertInstanceOf(AlpacaSseDeserializationException.class, failure.get());
      assertTrue(subscription.lastEventId().isEmpty());
    }
  }

  @Test
  void terminalTimeoutCompletesLifecycleBeforeSerializedFailureCallback() throws Exception {
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody("data: slow\n\n"));
    var eventEntered = new CountDownLatch(1);
    var releaseEvent = new CountDownLatch(1);
    var failureDelivered = new CountDownLatch(1);
    var inEvent = new AtomicBoolean();
    var callbacksOverlapped = new AtomicBoolean();
    var options =
        AlpacaSseOptions.reconnectDisabled().toBuilder()
            .maxDuration(Duration.ofMillis(150))
            .build();

    try (var subscription =
        SseTransport.open(
            httpClient,
            new Request.Builder().url(server.url("/events")).build(),
            options,
            false,
            data -> data,
            new AlpacaSseListener<>() {
              @Override
              public void onEvent(AlpacaSseEvent<String> event) {
                inEvent.set(true);
                eventEntered.countDown();
                try {
                  releaseEvent.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException failure) {
                  Thread.currentThread().interrupt();
                } finally {
                  inEvent.set(false);
                }
              }

              @Override
              public void onFailure(Throwable throwable) {
                callbacksOverlapped.set(inEvent.get());
                failureDelivered.countDown();
              }
            })) {
      assertTrue(eventEntered.await(3, TimeUnit.SECONDS));
      var completionFailure =
          assertThrows(
              ExecutionException.class, () -> subscription.completion().get(1, TimeUnit.SECONDS));
      assertInstanceOf(java.net.SocketTimeoutException.class, completionFailure.getCause());
      assertEquals(AlpacaSseState.FAILED, subscription.state());
      assertFalse(failureDelivered.await(100, TimeUnit.MILLISECONDS));
      releaseEvent.countDown();
      assertTrue(failureDelivered.await(3, TimeUnit.SECONDS));
      assertFalse(callbacksOverlapped.get());
    }
  }

  @Test
  void activeEventCallbackBackpressuresRemoteEndDetectionAndReconnect() throws Exception {
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody("id: first\ndata: one\n\n"));
    server.enqueue(
        new MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: two\n\n"));
    var eventEntered = new CountDownLatch(1);
    var releaseEvent = new CountDownLatch(1);
    var secondEvent = new CountDownLatch(1);
    var eventCount = new AtomicInteger();
    var policy =
        AlpacaSseReconnectPolicy.builder()
            .initialAttempts(0)
            .establishedAttempts(1)
            .initialBackoff(Duration.ofMillis(10))
            .maxBackoff(Duration.ofMillis(10))
            .jitterRatio(0)
            .build();
    var options = AlpacaSseOptions.builder().reconnectPolicy(policy).build();

    try (var subscription =
        SseTransport.open(
            httpClient,
            new Request.Builder().url(server.url("/events")).build(),
            options,
            false,
            data -> data,
            new AlpacaSseListener<>() {
              @Override
              public void onEvent(AlpacaSseEvent<String> event) {
                if (eventCount.incrementAndGet() == 1) {
                  eventEntered.countDown();
                  try {
                    releaseEvent.await(3, TimeUnit.SECONDS);
                  } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                  }
                } else {
                  secondEvent.countDown();
                }
              }
            })) {
      assertTrue(eventEntered.await(3, TimeUnit.SECONDS));
      assertEquals(1, server.getRequestCount());
      assertFalse(secondEvent.await(100, TimeUnit.MILLISECONDS));

      releaseEvent.countDown();
      assertTrue(secondEvent.await(3, TimeUnit.SECONDS));
      assertEquals(2, server.getRequestCount());
    } finally {
      releaseEvent.countDown();
    }
  }

  @Test
  void closeFromEventCallbackCompletesWithoutDeadlockAndSuppressesSameChunkEvent()
      throws Exception {
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody("id: first\ndata: one\n\nid: second\ndata: two\n\n"));
    var subscriptionReady = new CountDownLatch(1);
    var callbackFinished = new CountDownLatch(1);
    var closed = new CountDownLatch(1);
    var eventCount = new AtomicInteger();
    var subscriptionReference =
        new AtomicReference<markets.alpaca.client.sse.AlpacaSseSubscription>();

    var subscription =
        SseTransport.open(
            httpClient,
            new Request.Builder().url(server.url("/events")).build(),
            AlpacaSseOptions.reconnectDisabled(),
            false,
            data -> data,
            new AlpacaSseListener<>() {
              @Override
              public void onEvent(AlpacaSseEvent<String> event) {
                try {
                  assertTrue(subscriptionReady.await(3, TimeUnit.SECONDS));
                  eventCount.incrementAndGet();
                  var active = subscriptionReference.get();
                  active.close();
                  assertEquals(
                      AlpacaSseCloseResult.Reason.USER_CLOSED, active.completion().join().reason());
                } catch (InterruptedException failure) {
                  Thread.currentThread().interrupt();
                } finally {
                  callbackFinished.countDown();
                }
              }

              @Override
              public void onClosed(AlpacaSseCloseResult result) {
                closed.countDown();
              }
            });
    subscriptionReference.set(subscription);
    subscriptionReady.countDown();

    assertTrue(callbackFinished.await(3, TimeUnit.SECONDS));
    assertTrue(closed.await(3, TimeUnit.SECONDS));
    assertEquals(1, eventCount.get());
    assertEquals("first", subscription.lastEventId().orElseThrow());
    assertEquals(AlpacaSseState.CLOSED, subscription.state());
  }

  @Test
  void externalCloseReturnsWhileEventCallbackIsActive() throws Exception {
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody("data: slow\n\n"));
    var eventEntered = new CountDownLatch(1);
    var releaseEvent = new CountDownLatch(1);
    var closed = new CountDownLatch(1);
    ExecutorService callbackExecutor = Executors.newSingleThreadExecutor();

    try {
      var subscription =
          SseTransport.open(
              httpClient,
              new Request.Builder().url(server.url("/events")).build(),
              AlpacaSseOptions.reconnectDisabled(),
              false,
              data -> data,
              new AlpacaSseListener<>() {
                @Override
                public void onEvent(AlpacaSseEvent<String> event) {
                  eventEntered.countDown();
                  try {
                    releaseEvent.await(3, TimeUnit.SECONDS);
                  } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                  }
                }

                @Override
                public void onClosed(AlpacaSseCloseResult result) {
                  closed.countDown();
                }
              },
              callbackExecutor);

      assertTrue(eventEntered.await(3, TimeUnit.SECONDS));
      CompletableFuture.runAsync(subscription::close).get(1, TimeUnit.SECONDS);
      assertEquals(
          AlpacaSseCloseResult.Reason.USER_CLOSED,
          subscription.completion().get(1, TimeUnit.SECONDS).reason());
      assertFalse(closed.await(100, TimeUnit.MILLISECONDS));
      releaseEvent.countDown();
      assertTrue(closed.await(3, TimeUnit.SECONDS));
    } finally {
      releaseEvent.countDown();
      callbackExecutor.shutdownNow();
    }
  }

  @Test
  void terminalCallbackWaitsForDelayedCompletionSettlement() throws Exception {
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody("data: slow\n\n"));
    var eventEntered = new CountDownLatch(1);
    var releaseEvent = new CountDownLatch(1);
    var completionQueued = new CountDownLatch(1);
    var closed = new CountDownLatch(1);
    var completionTask = new AtomicReference<Runnable>();
    var completionDoneInCallback = new AtomicBoolean();
    var subscriptionReference =
        new AtomicReference<markets.alpaca.client.sse.AlpacaSseSubscription>();
    ScheduledThreadPoolExecutor scheduler = SseTransport.createScheduler();
    Executor direct = Runnable::run;
    Executor delayedCompletion =
        task -> {
          completionTask.set(task);
          completionQueued.countDown();
        };

    try {
      var subscription =
          SseTransport.openForTesting(
              httpClient,
              new Request.Builder().url(server.url("/events")).build(),
              AlpacaSseOptions.reconnectDisabled(),
              false,
              data -> data,
              new AlpacaSseListener<>() {
                @Override
                public void onEvent(AlpacaSseEvent<String> event) {
                  eventEntered.countDown();
                  try {
                    releaseEvent.await(3, TimeUnit.SECONDS);
                  } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                  }
                }

                @Override
                public void onClosed(AlpacaSseCloseResult result) {
                  completionDoneInCallback.set(subscriptionReference.get().completion().isDone());
                  closed.countDown();
                }
              },
              scheduler,
              direct,
              direct,
              delayedCompletion,
              System::nanoTime);
      subscriptionReference.set(subscription);

      assertTrue(eventEntered.await(3, TimeUnit.SECONDS));
      var close = CompletableFuture.runAsync(subscription::close);
      assertTrue(completionQueued.await(1, TimeUnit.SECONDS));
      releaseEvent.countDown();

      assertFalse(closed.await(100, TimeUnit.MILLISECONDS));
      assertFalse(subscription.completion().isDone());
      completionTask.get().run();

      close.get(1, TimeUnit.SECONDS);
      assertTrue(closed.await(1, TimeUnit.SECONDS));
      assertTrue(completionDoneInCallback.get());
    } finally {
      releaseEvent.countDown();
      scheduler.shutdownNow();
    }
  }

  @Test
  void closeReturnsBeforeBlockingTerminalCallback() throws Exception {
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBodyDelay(5, TimeUnit.SECONDS)
            .setBody("data: late\n\n"));
    var terminalEntered = new CountDownLatch(1);
    var releaseTerminal = new CountDownLatch(1);
    var subscription =
        SseTransport.open(
            httpClient,
            new Request.Builder().url(server.url("/events")).build(),
            AlpacaSseOptions.reconnectDisabled(),
            false,
            data -> data,
            new AlpacaSseListener<>() {
              @Override
              public void onClosed(AlpacaSseCloseResult result) {
                terminalEntered.countDown();
                try {
                  releaseTerminal.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException failure) {
                  Thread.currentThread().interrupt();
                }
              }
            });
    subscription.opened().get(3, TimeUnit.SECONDS);

    try {
      CompletableFuture.runAsync(subscription::close).get(1, TimeUnit.SECONDS);
      assertEquals(
          AlpacaSseCloseResult.Reason.USER_CLOSED,
          subscription.completion().get(1, TimeUnit.SECONDS).reason());
      assertTrue(terminalEntered.await(1, TimeUnit.SECONDS));
    } finally {
      releaseTerminal.countDown();
    }
  }

  @Test
  void productionExecutorsRemoveCanceledTimersAndBoundCallbackWork() throws Exception {
    ScheduledThreadPoolExecutor scheduler = SseTransport.createScheduler();
    ThreadPoolExecutor openingExecutor = SseTransport.createOpeningCallbackExecutor();
    ThreadPoolExecutor terminalExecutor = SseTransport.createTerminalCallbackExecutor();
    ThreadPoolExecutor completionExecutor = SseTransport.createCompletionExecutor();
    var releaseWorkers = new CountDownLatch(1);
    var workersStarted = new CountDownLatch(terminalExecutor.getMaximumPoolSize());
    var releaseCompletionWorkers = new CountDownLatch(1);
    var completionWorkersStarted = new CountDownLatch(completionExecutor.getMaximumPoolSize());

    try {
      ScheduledFuture<?> delayed = scheduler.schedule(() -> {}, 1, TimeUnit.HOURS);
      assertEquals(1, scheduler.getQueue().size());
      delayed.cancel(false);
      assertTrue(scheduler.getRemoveOnCancelPolicy());
      assertEquals(0, scheduler.getQueue().size());
      assertEquals(terminalExecutor.getMaximumPoolSize(), openingExecutor.getMaximumPoolSize());
      assertEquals(
          terminalExecutor.getQueue().remainingCapacity(),
          openingExecutor.getQueue().remainingCapacity());
      assertEquals(terminalExecutor.getMaximumPoolSize(), completionExecutor.getMaximumPoolSize());

      for (int index = 0; index < terminalExecutor.getMaximumPoolSize(); index++) {
        terminalExecutor.execute(
            () -> {
              workersStarted.countDown();
              try {
                releaseWorkers.await();
              } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
              }
            });
      }
      assertTrue(workersStarted.await(1, TimeUnit.SECONDS));
      int queueCapacity = terminalExecutor.getQueue().remainingCapacity();
      for (int index = 0; index < queueCapacity; index++) {
        terminalExecutor.execute(() -> {});
      }
      assertThrows(RejectedExecutionException.class, () -> terminalExecutor.execute(() -> {}));
      assertEquals(terminalExecutor.getMaximumPoolSize(), terminalExecutor.getLargestPoolSize());

      for (int index = 0; index < completionExecutor.getMaximumPoolSize(); index++) {
        completionExecutor.execute(
            () -> {
              completionWorkersStarted.countDown();
              try {
                releaseCompletionWorkers.await();
              } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
              }
            });
      }
      assertTrue(completionWorkersStarted.await(1, TimeUnit.SECONDS));
      int queuedCompletions = 300;
      for (int index = 0; index < queuedCompletions; index++) {
        completionExecutor.execute(() -> {});
      }
      assertEquals(queuedCompletions, completionExecutor.getQueue().size());
      assertEquals(
          completionExecutor.getMaximumPoolSize(), completionExecutor.getLargestPoolSize());
      releaseCompletionWorkers.countDown();
      awaitCondition(() -> completionExecutor.getQueue().isEmpty(), Duration.ofSeconds(1));
    } finally {
      releaseWorkers.countDown();
      releaseCompletionWorkers.countDown();
      scheduler.shutdownNow();
      openingExecutor.shutdownNow();
      terminalExecutor.shutdownNow();
      completionExecutor.shutdownNow();
    }
  }

  @Test
  void rejectedTerminalDispatchDoesNotChangeLifecycleCompletion() throws Exception {
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBodyDelay(5, TimeUnit.SECONDS)
            .setBody("data: late\n\n"));
    ScheduledThreadPoolExecutor scheduler = SseTransport.createScheduler();
    Executor rejectingTerminalExecutor =
        ignored -> {
          throw new RejectedExecutionException("saturated");
        };
    var closed = new AtomicBoolean();

    try {
      var subscription =
          SseTransport.openForTesting(
              httpClient,
              new Request.Builder().url(server.url("/events")).build(),
              AlpacaSseOptions.reconnectDisabled(),
              false,
              data -> data,
              new AlpacaSseListener<>() {
                @Override
                public void onClosed(AlpacaSseCloseResult result) {
                  closed.set(true);
                }
              },
              scheduler,
              rejectingTerminalExecutor,
              System::nanoTime);
      subscription.opened().get(1, TimeUnit.SECONDS);

      subscription.close();

      assertEquals(
          AlpacaSseCloseResult.Reason.USER_CLOSED,
          subscription.completion().get(1, TimeUnit.SECONDS).reason());
      assertEquals(AlpacaSseState.CLOSED, subscription.state());
      assertFalse(closed.get());
    } finally {
      scheduler.shutdownNow();
    }
  }

  @Test
  void rejectedOpeningDispatchFailsWithoutStrandingAcceptedConnection() throws Exception {
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBodyDelay(5, TimeUnit.SECONDS)
            .setBody(":\n\n"));
    ScheduledThreadPoolExecutor scheduler = SseTransport.createScheduler();
    Executor rejectingOpeningExecutor =
        ignored -> {
          throw new RejectedExecutionException("saturated");
        };
    ThreadPoolExecutor terminalExecutor = SseTransport.createTerminalCallbackExecutor();

    try {
      var subscription =
          SseTransport.openForTesting(
              httpClient,
              new Request.Builder().url(server.url("/events")).build(),
              AlpacaSseOptions.reconnectDisabled(),
              false,
              data -> data,
              new AlpacaSseListener<>() {},
              scheduler,
              rejectingOpeningExecutor,
              terminalExecutor,
              System::nanoTime);

      assertNotNull(subscription.opened().get(1, TimeUnit.SECONDS));
      var failure =
          assertThrows(
              ExecutionException.class, () -> subscription.completion().get(1, TimeUnit.SECONDS));
      assertInstanceOf(AlpacaSseCallbackException.class, failure.getCause());
      assertEquals(AlpacaSseState.FAILED, subscription.state());
    } finally {
      scheduler.shutdownNow();
      terminalExecutor.shutdownNow();
    }
  }

  @Test
  void closingWhileOpeningDispatchIsQueuedReleasesHttpCallbackThread() throws Exception {
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBodyDelay(5, TimeUnit.SECONDS)
            .setBody(":\n\n"));
    ScheduledThreadPoolExecutor scheduler = SseTransport.createScheduler();
    ThreadPoolExecutor openingExecutor =
        new ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1),
            new ThreadPoolExecutor.AbortPolicy());
    ThreadPoolExecutor terminalExecutor = SseTransport.createTerminalCallbackExecutor();
    var openingWorkerStarted = new CountDownLatch(1);
    var releaseOpeningWorker = new CountDownLatch(1);
    var openedCallback = new AtomicBoolean();
    openingExecutor.execute(
        () -> {
          openingWorkerStarted.countDown();
          try {
            releaseOpeningWorker.await();
          } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
          }
        });
    assertTrue(openingWorkerStarted.await(1, TimeUnit.SECONDS));

    var subscription =
        SseTransport.openForTesting(
            httpClient,
            new Request.Builder().url(server.url("/events")).build(),
            AlpacaSseOptions.reconnectDisabled(),
            false,
            data -> data,
            new AlpacaSseListener<>() {
              @Override
              public void onOpen() {
                openedCallback.set(true);
              }
            },
            scheduler,
            openingExecutor,
            terminalExecutor,
            System::nanoTime);

    try {
      awaitCondition(
          () -> subscription.connection().isPresent() && openingExecutor.getQueue().size() == 1,
          Duration.ofSeconds(2));

      subscription.close();

      assertEquals(
          AlpacaSseCloseResult.Reason.USER_CLOSED,
          subscription.completion().get(1, TimeUnit.SECONDS).reason());
      awaitCondition(() -> httpClient.dispatcher().runningCallsCount() == 0, Duration.ofSeconds(2));
      assertFalse(openedCallback.get());
    } finally {
      releaseOpeningWorker.countDown();
      subscription.close();
      scheduler.shutdownNow();
      openingExecutor.shutdownNow();
      terminalExecutor.shutdownNow();
    }
  }

  @Test
  void rejectedSchedulerTerminatesWithAProtocolFailure() throws Exception {
    ScheduledThreadPoolExecutor scheduler = SseTransport.createScheduler();
    scheduler.shutdownNow();

    var subscription =
        SseTransport.openForTesting(
            httpClient,
            new Request.Builder().url(server.url("/events")).build(),
            AlpacaSseOptions.reconnectDisabled(),
            false,
            data -> data,
            new AlpacaSseListener<>() {},
            scheduler,
            System::nanoTime);

    var failure =
        assertThrows(
            ExecutionException.class, () -> subscription.completion().get(1, TimeUnit.SECONDS));
    assertInstanceOf(
        markets.alpaca.client.sse.AlpacaSseProtocolException.class, failure.getCause());
    assertEquals(AlpacaSseState.FAILED, subscription.state());
  }

  @Test
  void configuredCursorRejectsHttpControlCharactersButAllowsUnicode() {
    assertDoesNotThrow(() -> AlpacaSseOptions.builder().initialLastEventId("évènement-一").build());
    for (String invalid : java.util.List.of("\u0000", "\t", "\r", "\n", "\u001f", "\u007f")) {
      assertThrows(
          IllegalArgumentException.class,
          () -> AlpacaSseOptions.builder().initialLastEventId("cursor" + invalid).build());
    }
  }

  @Test
  void blockedSubscriptionCallbackDoesNotStallAnotherSubscriptionTimeout() throws Exception {
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody("data: slow\n\n"));
    var eventEntered = new CountDownLatch(1);
    var releaseEvent = new CountDownLatch(1);
    var first =
        SseTransport.open(
            httpClient,
            new Request.Builder().url(server.url("/first")).build(),
            AlpacaSseOptions.reconnectDisabled(),
            false,
            data -> data,
            new AlpacaSseListener<>() {
              @Override
              public void onEvent(AlpacaSseEvent<String> event) {
                eventEntered.countDown();
                try {
                  releaseEvent.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException failure) {
                  Thread.currentThread().interrupt();
                }
              }
            });
    assertTrue(eventEntered.await(3, TimeUnit.SECONDS));

    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBodyDelay(5, TimeUnit.SECONDS)
            .setBody("data: late\n\n"));
    var secondFailure = new CountDownLatch(1);
    var second =
        SseTransport.open(
            httpClient,
            new Request.Builder().url(server.url("/second")).build(),
            AlpacaSseOptions.reconnectDisabled().toBuilder()
                .maxDuration(Duration.ofMillis(150))
                .build(),
            false,
            data -> data,
            new AlpacaSseListener<>() {
              @Override
              public void onFailure(Throwable failure) {
                secondFailure.countDown();
              }
            });

    try {
      var failure =
          assertThrows(
              ExecutionException.class, () -> second.completion().get(1, TimeUnit.SECONDS));
      assertInstanceOf(java.net.SocketTimeoutException.class, failure.getCause());
      assertTrue(secondFailure.await(1, TimeUnit.SECONDS));
    } finally {
      releaseEvent.countDown();
      first.close();
      second.close();
    }
  }

  @Test
  void listenerFailureIsLoggedAndDeliveredEventCursorAdvances() throws Exception {
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody("id: delivered\ndata: value\n\n"));

    var subscription =
        SseTransport.open(
            httpClient,
            new Request.Builder().url(server.url("/events")).build(),
            AlpacaSseOptions.reconnectDisabled(),
            true,
            data -> data,
            new AlpacaSseListener<>() {
              @Override
              public void onEvent(AlpacaSseEvent<String> event) {
                throw new IllegalStateException("application callback failed");
              }
            });

    assertEquals(
        AlpacaSseCloseResult.Reason.BOUNDED_END,
        subscription.completion().get(3, TimeUnit.SECONDS).reason());
    assertEquals("delivered", subscription.lastEventId().orElseThrow());
  }

  @Test
  void callbackExecutorRejectionFailsSubscription() throws Exception {
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody("data: value\n\n"));

    var subscription =
        SseTransport.open(
            httpClient,
            new Request.Builder().url(server.url("/events")).build(),
            AlpacaSseOptions.reconnectDisabled(),
            true,
            data -> data,
            new AlpacaSseListener<>() {},
            command -> {
              throw new RejectedExecutionException("rejected for test");
            });

    var failure =
        assertThrows(
            ExecutionException.class, () -> subscription.completion().get(3, TimeUnit.SECONDS));
    assertInstanceOf(AlpacaSseCallbackException.class, failure.getCause());
    assertEquals(AlpacaSseState.FAILED, subscription.state());
    assertTrue(subscription.lastEventId().isEmpty());
  }

  @Test
  void reconnectCallbackExecutorRejectionAfterPreHeaderFailureFailsSubscription() throws Exception {
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
    var options =
        AlpacaSseOptions.builder()
            .reconnectPolicy(
                AlpacaSseReconnectPolicy.builder()
                    .initialAttempts(1)
                    .establishedAttempts(0)
                    .initialBackoff(Duration.ofMillis(1))
                    .maxBackoff(Duration.ofMillis(1))
                    .jitterRatio(0)
                    .build())
            .build();

    var subscription =
        SseTransport.open(
            httpClient,
            new Request.Builder().url(server.url("/events")).build(),
            options,
            false,
            data -> data,
            new AlpacaSseListener<>() {},
            command -> {
              throw new RejectedExecutionException("rejected reconnect callback");
            });

    var failure =
        assertThrows(
            ExecutionException.class, () -> subscription.completion().get(2, TimeUnit.SECONDS));
    assertInstanceOf(AlpacaSseCallbackException.class, failure.getCause());
    assertEquals(AlpacaSseState.FAILED, subscription.state());
    assertEquals(1, server.getRequestCount());
  }

  @Test
  void reconnectCallbackExecutorRejectionAfterEstablishedStreamFailsSubscription()
      throws Exception {
    server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(""));
    var callbackCount = new AtomicInteger();

    var subscription =
        SseTransport.open(
            httpClient,
            new Request.Builder().url(server.url("/events")).build(),
            reconnectOptions(Duration.ofMillis(1)),
            false,
            data -> data,
            new AlpacaSseListener<>() {},
            command -> {
              if (callbackCount.getAndIncrement() == 0) {
                command.run();
              } else {
                throw new RejectedExecutionException("rejected established reconnect callback");
              }
            });

    subscription.opened().get(1, TimeUnit.SECONDS);
    var failure =
        assertThrows(
            ExecutionException.class, () -> subscription.completion().get(2, TimeUnit.SECONDS));
    assertInstanceOf(AlpacaSseCallbackException.class, failure.getCause());
    assertEquals(AlpacaSseState.FAILED, subscription.state());
    assertEquals(1, server.getRequestCount());
  }

  @Test
  void closeBeforeOpenSettlesCompletionBeforeOpenedContinuations() throws Exception {
    server.enqueue(new MockResponse().setHeadersDelay(5, TimeUnit.SECONDS));
    var subscription =
        SseTransport.open(
            httpClient,
            new Request.Builder().url(server.url("/events")).build(),
            AlpacaSseOptions.reconnectDisabled(),
            false,
            data -> data,
            new AlpacaSseListener<>() {});
    var observedCompletion =
        subscription
            .opened()
            .handle((connection, failure) -> subscription.completion().join().reason());

    CompletableFuture.runAsync(subscription::close).get(1, TimeUnit.SECONDS);

    assertEquals(
        AlpacaSseCloseResult.Reason.USER_CLOSED, observedCompletion.get(1, TimeUnit.SECONDS));
    assertTrue(subscription.opened().isCompletedExceptionally());
  }

  @Test
  void failureBeforeOpenSettlesCompletionBeforeOpenedContinuations() throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(403)
            .setHeadersDelay(100, TimeUnit.MILLISECONDS)
            .setBody("forbidden"));
    var subscription =
        SseTransport.open(
            httpClient,
            new Request.Builder().url(server.url("/events")).build(),
            AlpacaSseOptions.reconnectDisabled(),
            false,
            data -> data,
            new AlpacaSseListener<>() {});
    var observedCompletion =
        subscription
            .opened()
            .handle(
                (connection, failure) -> {
                  assertNotNull(failure);
                  try {
                    subscription.completion().join();
                    fail("failed subscription completion must be exceptional");
                  } catch (CompletionException expected) {
                    // The important contract is that this synchronous continuation cannot block.
                  }
                  return subscription.completion().isDone();
                });

    assertTrue(observedCompletion.get(2, TimeUnit.SECONDS));
    assertEquals(AlpacaSseState.FAILED, subscription.state());
  }

  @Test
  void concurrentCloseCallersReturnOnlyAfterCompletionSettles() {
    assertTimeoutPreemptively(
        Duration.ofSeconds(30),
        () -> {
          for (int iteration = 0; iteration < 100; iteration++) {
            server.enqueue(new MockResponse().setHeadersDelay(5, TimeUnit.SECONDS));
            var subscription =
                SseTransport.open(
                    httpClient,
                    new Request.Builder().url(server.url("/events")).build(),
                    AlpacaSseOptions.reconnectDisabled(),
                    false,
                    data -> data,
                    new AlpacaSseListener<>() {});
            var ready = new CountDownLatch(2);
            var start = new CountDownLatch(1);
            ExecutorService closers = Executors.newFixedThreadPool(2);
            try {
              var first =
                  closers.submit(
                      () -> {
                        ready.countDown();
                        start.await();
                        subscription.close();
                        return subscription.completion().isDone();
                      });
              var second =
                  closers.submit(
                      () -> {
                        ready.countDown();
                        start.await();
                        subscription.close();
                        return subscription.completion().isDone();
                      });
              assertTrue(ready.await(1, TimeUnit.SECONDS));
              start.countDown();
              assertTrue(first.get(1, TimeUnit.SECONDS));
              assertTrue(second.get(1, TimeUnit.SECONDS));
            } finally {
              closers.shutdownNow();
            }
            assertEquals(
                AlpacaSseCloseResult.Reason.USER_CLOSED, subscription.completion().join().reason());
          }
        });
  }

  @Test
  void closingFromOpenedContinuationPreservesAcceptedConnectionAndCallbackOrder() throws Exception {
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setHeadersDelay(100, TimeUnit.MILLISECONDS)
            .setBodyDelay(5, TimeUnit.SECONDS)
            .setBody(":\n\n"));
    var callbacks = new java.util.concurrent.CopyOnWriteArrayList<String>();
    var closed = new CountDownLatch(1);
    var subscription =
        SseTransport.open(
            httpClient,
            new Request.Builder().url(server.url("/events")).build(),
            AlpacaSseOptions.reconnectDisabled(),
            false,
            data -> data,
            new AlpacaSseListener<>() {
              @Override
              public void onOpen() {
                callbacks.add("open");
              }

              @Override
              public void onClosed(AlpacaSseCloseResult result) {
                callbacks.add("closed");
                closed.countDown();
              }
            });

    var accepted = subscription.opened();
    var closedFromOpened =
        accepted.thenRun(
            () -> {
              subscription.close();
              try {
                assertTrue(
                    closed.await(1, TimeUnit.SECONDS),
                    "onClosed must run while the synchronous opened continuation is active");
              } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for onClosed", failure);
              }
            });

    closedFromOpened.get(2, TimeUnit.SECONDS);
    assertNotNull(accepted.get(1, TimeUnit.SECONDS));
    assertEquals(
        AlpacaSseCloseResult.Reason.USER_CLOSED,
        subscription.completion().get(1, TimeUnit.SECONDS).reason());
    assertTrue(closed.await(2, TimeUnit.SECONDS));
    assertEquals(java.util.List.of("open", "closed"), callbacks);
  }

  @Test
  void completionContinuationCanAwaitClosedCallback() throws Exception {
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBodyDelay(5, TimeUnit.SECONDS)
            .setBody(":\n\n"));
    var closed = new CountDownLatch(1);
    var subscription =
        SseTransport.open(
            httpClient,
            new Request.Builder().url(server.url("/events")).build(),
            AlpacaSseOptions.reconnectDisabled(),
            false,
            data -> data,
            new AlpacaSseListener<>() {
              @Override
              public void onClosed(AlpacaSseCloseResult result) {
                closed.countDown();
              }
            });
    subscription.opened().get(1, TimeUnit.SECONDS);
    var continuation =
        subscription
            .completion()
            .thenRun(
                () -> {
                  try {
                    assertTrue(
                        closed.await(3, TimeUnit.SECONDS),
                        "onClosed must run while the synchronous completion continuation is active");
                  } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("interrupted while waiting for onClosed", failure);
                  }
                });

    var close = CompletableFuture.runAsync(subscription::close);

    continuation.get(4, TimeUnit.SECONDS);
    close.get(1, TimeUnit.SECONDS);
    assertEquals(AlpacaSseState.CLOSED, subscription.state());
  }

  @Test
  void completionContinuationCanAwaitClosedWhenEventCallbackClosesSubscription() throws Exception {
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody("data: event\n\n")
            .setBodyDelay(100, TimeUnit.MILLISECONDS));
    var releaseEvent = new CountDownLatch(1);
    var eventReturned = new CountDownLatch(1);
    var closed = new CountDownLatch(1);
    var subscriptionRef = new AtomicReference<markets.alpaca.client.sse.AlpacaSseSubscription>();
    var subscription =
        SseTransport.open(
            httpClient,
            new Request.Builder().url(server.url("/events")).build(),
            AlpacaSseOptions.reconnectDisabled(),
            false,
            data -> data,
            new AlpacaSseListener<>() {
              @Override
              public void onEvent(AlpacaSseEvent<String> event) {
                try {
                  assertTrue(releaseEvent.await(1, TimeUnit.SECONDS));
                  subscriptionRef.get().close();
                  eventReturned.countDown();
                } catch (InterruptedException failure) {
                  Thread.currentThread().interrupt();
                  throw new AssertionError("interrupted before closing from onEvent", failure);
                }
              }

              @Override
              public void onClosed(AlpacaSseCloseResult result) {
                closed.countDown();
              }
            });
    subscriptionRef.set(subscription);
    var continuation =
        subscription
            .completion()
            .thenRun(
                () -> {
                  try {
                    assertTrue(
                        closed.await(3, TimeUnit.SECONDS),
                        "onClosed must run while the synchronous completion continuation is active");
                  } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("interrupted while waiting for onClosed", failure);
                  }
                });
    releaseEvent.countDown();

    assertTrue(eventReturned.await(2, TimeUnit.SECONDS));
    continuation.get(4, TimeUnit.SECONDS);
    assertEquals(AlpacaSseState.CLOSED, subscription.state());
  }

  @Test
  void blockedCompletionContinuationsDoNotStallOtherSubscriptions() throws Exception {
    ThreadPoolExecutor completionExecutor = SseTransport.createCompletionExecutor();
    int workerCount = completionExecutor.getMaximumPoolSize();
    completionExecutor.shutdownNow();
    var blockers = new markets.alpaca.client.sse.AlpacaSseSubscription[workerCount];
    var releaseContinuations = new CountDownLatch(1);
    var continuationsEntered = new CountDownLatch(workerCount);
    ExecutorService closeExecutor = Executors.newFixedThreadPool(workerCount);
    ExecutorService targetCloseExecutor = Executors.newSingleThreadExecutor();
    markets.alpaca.client.sse.AlpacaSseSubscription target = null;
    for (int index = 0; index <= workerCount; index++) {
      server.enqueue(new MockResponse().setHeadersDelay(5, TimeUnit.SECONDS));
    }

    try {
      for (int index = 0; index < workerCount; index++) {
        blockers[index] =
            SseTransport.open(
                httpClient,
                new Request.Builder().url(server.url("/blocker-" + index)).build(),
                AlpacaSseOptions.reconnectDisabled(),
                false,
                data -> data,
                new AlpacaSseListener<>() {});
        blockers[index]
            .completion()
            .thenRun(
                () -> {
                  continuationsEntered.countDown();
                  try {
                    releaseContinuations.await();
                  } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                  }
                });
        closeExecutor.execute(blockers[index]::close);
      }

      assertTrue(continuationsEntered.await(2, TimeUnit.SECONDS));
      target =
          SseTransport.open(
              httpClient,
              new Request.Builder().url(server.url("/target")).build(),
              AlpacaSseOptions.reconnectDisabled(),
              false,
              data -> data,
              new AlpacaSseListener<>() {});

      CompletableFuture.runAsync(target::close, targetCloseExecutor).get(1, TimeUnit.SECONDS);
      assertTrue(target.completion().isDone());
    } finally {
      releaseContinuations.countDown();
      closeExecutor.shutdownNow();
      targetCloseExecutor.shutdownNow();
      for (var blocker : blockers) {
        if (blocker != null) blocker.close();
      }
      if (target != null) target.close();
    }
  }

  @Test
  void completionContinuationsCanCloseOtherSubscriptionsWithoutDeadlock() throws Exception {
    ThreadPoolExecutor completionExecutor = SseTransport.createCompletionExecutor();
    int workerCount = completionExecutor.getMaximumPoolSize();
    completionExecutor.shutdownNow();
    var parents = new markets.alpaca.client.sse.AlpacaSseSubscription[workerCount];
    var children = new markets.alpaca.client.sse.AlpacaSseSubscription[workerCount];
    var continuations = new CompletableFuture<?>[workerCount];
    var closes = new CompletableFuture<?>[workerCount];
    var continuationsEntered = new CountDownLatch(workerCount);
    ExecutorService closeExecutor = Executors.newFixedThreadPool(workerCount);
    for (int index = 0; index < workerCount * 2; index++) {
      server.enqueue(new MockResponse().setHeadersDelay(5, TimeUnit.SECONDS));
    }

    try {
      for (int index = 0; index < workerCount; index++) {
        parents[index] =
            SseTransport.open(
                httpClient,
                new Request.Builder().url(server.url("/parent-" + index)).build(),
                AlpacaSseOptions.reconnectDisabled(),
                false,
                data -> data,
                new AlpacaSseListener<>() {});
        children[index] =
            SseTransport.open(
                httpClient,
                new Request.Builder().url(server.url("/child-" + index)).build(),
                AlpacaSseOptions.reconnectDisabled(),
                false,
                data -> data,
                new AlpacaSseListener<>() {});
        int subscriptionIndex = index;
        continuations[index] =
            parents[index]
                .completion()
                .thenRun(
                    () -> {
                      continuationsEntered.countDown();
                      try {
                        assertTrue(continuationsEntered.await(2, TimeUnit.SECONDS));
                      } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(
                            "interrupted while synchronizing completion workers", failure);
                      }
                      children[subscriptionIndex].close();
                    });
      }

      for (int index = 0; index < workerCount; index++) {
        closes[index] = CompletableFuture.runAsync(parents[index]::close, closeExecutor);
      }
      for (var continuation : continuations) {
        continuation.get(3, TimeUnit.SECONDS);
      }
      for (var close : closes) {
        close.get(3, TimeUnit.SECONDS);
      }
      for (var child : children) {
        assertTrue(child.completion().isDone());
      }
    } finally {
      closeExecutor.shutdownNow();
      for (var parent : parents) {
        if (parent != null) parent.close();
      }
      for (var child : children) {
        if (child != null) child.close();
      }
    }
  }

  @Test
  void exceptionalCompletionContinuationCanAwaitFailureCallback() throws Exception {
    server.enqueue(
        new MockResponse()
            .setHeadersDelay(100, TimeUnit.MILLISECONDS)
            .setHeader("Content-Type", "application/json")
            .setBody("{}"));
    var failed = new CountDownLatch(1);
    var subscription =
        SseTransport.open(
            httpClient,
            new Request.Builder().url(server.url("/events")).build(),
            AlpacaSseOptions.reconnectDisabled(),
            false,
            data -> data,
            new AlpacaSseListener<>() {
              @Override
              public void onFailure(Throwable failure) {
                failed.countDown();
              }
            });
    var continuation =
        subscription
            .completion()
            .handle(
                (result, failure) -> {
                  try {
                    assertTrue(
                        failed.await(3, TimeUnit.SECONDS),
                        "onFailure must run while the synchronous completion continuation is active");
                  } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(
                        "interrupted while waiting for onFailure", interrupted);
                  }
                  return null;
                });

    continuation.get(4, TimeUnit.SECONDS);
    var failure =
        assertThrows(
            ExecutionException.class, () -> subscription.completion().get(1, TimeUnit.SECONDS));
    assertInstanceOf(
        markets.alpaca.client.sse.AlpacaSseProtocolException.class, failure.getCause());
    assertEquals(AlpacaSseState.FAILED, subscription.state());
  }

  @Test
  void closingFromReconnectedCallbackPreservesLifecycleOrder() throws Exception {
    server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(""));
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBodyDelay(5, TimeUnit.SECONDS)
            .setBody(":\n\n"));
    var callbacks = new java.util.concurrent.CopyOnWriteArrayList<String>();
    var closed = new CountDownLatch(1);
    var subscriptionReady = new CountDownLatch(1);
    var subscriptionRef = new AtomicReference<markets.alpaca.client.sse.AlpacaSseSubscription>();
    var subscription =
        SseTransport.open(
            httpClient,
            new Request.Builder().url(server.url("/events")).build(),
            reconnectOptions(Duration.ofMillis(100)),
            false,
            data -> data,
            new AlpacaSseListener<>() {
              @Override
              public void onOpen() {
                callbacks.add("open");
              }

              @Override
              public void onReconnected() {
                callbacks.add("reconnected");
                try {
                  subscriptionReady.await();
                } catch (InterruptedException failure) {
                  Thread.currentThread().interrupt();
                  throw new AssertionError("interrupted before subscription publication", failure);
                }
                subscriptionRef.get().close();
              }

              @Override
              public void onClosed(AlpacaSseCloseResult result) {
                callbacks.add("closed");
                closed.countDown();
              }
            });
    subscriptionRef.set(subscription);
    subscriptionReady.countDown();

    assertTrue(closed.await(3, TimeUnit.SECONDS));
    assertEquals(
        AlpacaSseCloseResult.Reason.USER_CLOSED,
        subscription.completion().get(1, TimeUnit.SECONDS).reason());
    assertTrue(subscription.opened().isDone());
    assertEquals(java.util.List.of("open", "reconnected", "closed"), callbacks);
  }

  @Test
  void closeAfterFailureReturnsWithExceptionalCompletionAlreadySettled() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(403).setBody("forbidden"));
    var subscription =
        SseTransport.open(
            httpClient,
            new Request.Builder().url(server.url("/events")).build(),
            AlpacaSseOptions.reconnectDisabled(),
            false,
            data -> data,
            new AlpacaSseListener<>() {});

    assertThrows(
        ExecutionException.class, () -> subscription.completion().get(2, TimeUnit.SECONDS));
    subscription.close();

    assertTrue(subscription.completion().isCompletedExceptionally());
    assertEquals(AlpacaSseState.FAILED, subscription.state());
  }

  @Test
  void immediateCloseStressDoesNotLeaveConnectingSubscriptions() {
    assertTimeoutPreemptively(
        Duration.ofSeconds(30),
        () -> {
          for (int iteration = 0; iteration < 100; iteration++) {
            server.enqueue(new MockResponse().setHeadersDelay(5, TimeUnit.SECONDS));
            var subscription =
                SseTransport.open(
                    httpClient,
                    new Request.Builder().url(server.url("/events")).build(),
                    AlpacaSseOptions.reconnectDisabled(),
                    false,
                    data -> data,
                    new AlpacaSseListener<>() {});
            subscription.close();
            assertEquals(
                AlpacaSseCloseResult.Reason.USER_CLOSED,
                subscription.completion().get(1, TimeUnit.SECONDS).reason());
            assertEquals(AlpacaSseState.CLOSED, subscription.state());
          }
        });
  }

  @Test
  void closeDuringBackoffCancelsPendingReconnect() throws Exception {
    server.enqueue(
        new MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: one\n\n"));
    var reconnecting = new CountDownLatch(1);

    var subscription =
        SseTransport.open(
            httpClient,
            new Request.Builder().url(server.url("/events")).build(),
            reconnectOptions(Duration.ofMillis(100)),
            false,
            data -> data,
            new AlpacaSseListener<>() {
              @Override
              public void onReconnecting(int attempt, Duration delay) {
                reconnecting.countDown();
              }
            });
    assertTrue(reconnecting.await(3, TimeUnit.SECONDS));
    subscription.close();

    assertNotNull(server.takeRequest(1, TimeUnit.SECONDS));
    assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS));
  }

  @Test
  void initialElapsedBudgetBoundsAnInFlightConnectionAttempt() throws Exception {
    server.enqueue(new MockResponse().setHeadersDelay(5, TimeUnit.SECONDS));
    var options =
        AlpacaSseOptions.builder()
            .connectTimeout(Duration.ofSeconds(2))
            .reconnectPolicy(
                AlpacaSseReconnectPolicy.builder()
                    .maxElapsedTime(Duration.ofMillis(100))
                    .initialBackoff(Duration.ofMillis(20))
                    .maxBackoff(Duration.ofMillis(20))
                    .jitterRatio(0)
                    .build())
            .build();

    var subscription =
        SseTransport.open(
            httpClient,
            new Request.Builder().url(server.url("/events")).build(),
            options,
            false,
            data -> data,
            new AlpacaSseListener<>() {});

    assertThrows(
        ExecutionException.class, () -> subscription.completion().get(2, TimeUnit.SECONDS));
    assertEquals(1, server.getRequestCount());
  }

  @Test
  void initialElapsedBudgetRunsWhileReconnectingCallbackIsBlocked() throws Exception {
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
    var callbackEntered = new CountDownLatch(1);
    var releaseCallback = new CountDownLatch(1);
    var options =
        AlpacaSseOptions.builder()
            .reconnectPolicy(
                AlpacaSseReconnectPolicy.builder()
                    .maxElapsedTime(Duration.ofMillis(100))
                    .initialBackoff(Duration.ofMillis(1))
                    .maxBackoff(Duration.ofMillis(1))
                    .jitterRatio(0)
                    .build())
            .build();

    var subscription =
        SseTransport.open(
            httpClient,
            new Request.Builder().url(server.url("/events")).build(),
            options,
            false,
            data -> data,
            new AlpacaSseListener<>() {
              @Override
              public void onReconnecting(int attempt, Duration delay) {
                callbackEntered.countDown();
                try {
                  releaseCallback.await();
                } catch (InterruptedException failure) {
                  Thread.currentThread().interrupt();
                }
              }
            });

    try {
      assertTrue(callbackEntered.await(1, TimeUnit.SECONDS));
      assertThrows(
          ExecutionException.class, () -> subscription.completion().get(1, TimeUnit.SECONDS));
      assertEquals(AlpacaSseState.FAILED, subscription.state());
    } finally {
      releaseCallback.countDown();
    }
  }

  @Test
  void establishedElapsedBudgetSpansReconnectsUntilAnEventIsDelivered() throws Exception {
    server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(""));
    server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(""));
    var options =
        AlpacaSseOptions.builder()
            .reconnectPolicy(
                AlpacaSseReconnectPolicy.builder()
                    .maxElapsedTime(Duration.ofMillis(150))
                    .initialBackoff(Duration.ofMillis(80))
                    .maxBackoff(Duration.ofMillis(80))
                    .jitterRatio(0)
                    .build())
            .build();

    var subscription =
        SseTransport.open(
            httpClient,
            new Request.Builder().url(server.url("/events")).build(),
            options,
            false,
            data -> data,
            new AlpacaSseListener<>() {});

    subscription.opened().get(1, TimeUnit.SECONDS);
    assertThrows(
        ExecutionException.class, () -> subscription.completion().get(2, TimeUnit.SECONDS));
    assertEquals(2, server.getRequestCount());
  }

  @Test
  void establishedElapsedBudgetExpiresAfterReconnectHeadersWithoutAnEvent() throws Exception {
    server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(""));
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(": keepalive\n\n".repeat(100))
            .throttleBody(1, 10, TimeUnit.MILLISECONDS));
    var options =
        AlpacaSseOptions.builder()
            .reconnectPolicy(
                AlpacaSseReconnectPolicy.builder()
                    .maxElapsedTime(Duration.ofMillis(150))
                    .initialBackoff(Duration.ofMillis(10))
                    .maxBackoff(Duration.ofMillis(10))
                    .jitterRatio(0)
                    .build())
            .build();

    var subscription =
        SseTransport.open(
            httpClient,
            new Request.Builder().url(server.url("/events")).build(),
            options,
            false,
            data -> data,
            new AlpacaSseListener<>() {});

    subscription.opened().get(1, TimeUnit.SECONDS);
    assertThrows(
        ExecutionException.class, () -> subscription.completion().get(2, TimeUnit.SECONDS));
    assertEquals(AlpacaSseState.FAILED, subscription.state());
    assertEquals(2, server.getRequestCount());
  }

  @Test
  void deliveredEventClearsEstablishedReconnectDeadline() throws Exception {
    server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(""));
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody("data: recovered\n\n" + ": keepalive\n\n".repeat(100))
            .throttleBody(1, 2, TimeUnit.MILLISECONDS));
    var options =
        AlpacaSseOptions.builder()
            .reconnectPolicy(
                AlpacaSseReconnectPolicy.builder()
                    .maxElapsedTime(Duration.ofMillis(200))
                    .initialBackoff(Duration.ofMillis(1))
                    .maxBackoff(Duration.ofMillis(1))
                    .jitterRatio(0)
                    .build())
            .build();
    var delivered = new CountDownLatch(1);

    var subscription =
        SseTransport.open(
            httpClient,
            new Request.Builder().url(server.url("/events")).build(),
            options,
            false,
            data -> data,
            new AlpacaSseListener<>() {
              @Override
              public void onEvent(AlpacaSseEvent<String> event) {
                delivered.countDown();
              }
            });

    assertTrue(delivered.await(1, TimeUnit.SECONDS));
    assertThrows(
        TimeoutException.class, () -> subscription.completion().get(300, TimeUnit.MILLISECONDS));
    assertEquals(AlpacaSseState.OPEN, subscription.state());
    subscription.close();
  }

  @Test
  void staleReconnectDeadlineCannotFailALaterReconnectCycle() throws Exception {
    server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(""));
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody("data: recovered\n\n: " + "x".repeat(20) + "\n\n")
            .throttleBody(1, 3, TimeUnit.MILLISECONDS));
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(": keepalive\n\n".repeat(100))
            .throttleBody(1, 5, TimeUnit.MILLISECONDS));
    var options =
        AlpacaSseOptions.builder()
            .reconnectPolicy(
                AlpacaSseReconnectPolicy.builder()
                    .maxElapsedTime(Duration.ofMillis(300))
                    .initialBackoff(Duration.ofMillis(1))
                    .maxBackoff(Duration.ofMillis(1))
                    .jitterRatio(0)
                    .build())
            .build();
    var reconnects = new CountDownLatch(2);

    var subscription =
        SseTransport.open(
            httpClient,
            new Request.Builder().url(server.url("/events")).build(),
            options,
            false,
            data -> data,
            new AlpacaSseListener<>() {
              @Override
              public void onReconnecting(int attempt, Duration delay) {
                reconnects.countDown();
              }
            });

    assertTrue(reconnects.await(2, TimeUnit.SECONDS));
    assertThrows(
        TimeoutException.class, () -> subscription.completion().get(200, TimeUnit.MILLISECONDS));
    assertEquals(AlpacaSseState.OPEN, subscription.state());
    assertEquals(3, server.getRequestCount());
    subscription.close();
  }

  @Test
  void elapsedBudgetExpiryBeforeScheduledReconnectPreservesPreviousFailure() throws Exception {
    server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(""));
    var nanoTime = new AtomicLong(1);
    var options =
        AlpacaSseOptions.builder()
            .reconnectPolicy(
                AlpacaSseReconnectPolicy.builder()
                    .maxElapsedTime(Duration.ofMillis(100))
                    .initialBackoff(Duration.ofMillis(1))
                    .maxBackoff(Duration.ofMillis(1))
                    .jitterRatio(0)
                    .build())
            .build();

    var subscription =
        SseTransport.openForTesting(
            httpClient,
            new Request.Builder().url(server.url("/events")).build(),
            options,
            false,
            data -> data,
            new AlpacaSseListener<>() {
              @Override
              public void onReconnecting(int attempt, Duration delay) {
                nanoTime.addAndGet(Duration.ofMillis(200).toNanos());
              }
            },
            nanoTime::get);

    subscription.opened().get(1, TimeUnit.SECONDS);
    var failure =
        assertThrows(
            ExecutionException.class, () -> subscription.completion().get(2, TimeUnit.SECONDS));
    assertInstanceOf(IOException.class, failure.getCause());
    assertEquals("SSE response ended unexpectedly", failure.getCause().getMessage());
    assertEquals(1, server.getRequestCount());
  }

  private void assertRetryAfterCapped(String retryAfter) throws Exception {
    server.enqueue(new MockResponse().setResponseCode(503).setHeader("Retry-After", retryAfter));
    var reconnecting = new CountDownLatch(1);
    var delay = new AtomicReference<Duration>();
    var options =
        AlpacaSseOptions.builder()
            .reconnectPolicy(
                AlpacaSseReconnectPolicy.builder()
                    .initialAttempts(1)
                    .establishedAttempts(0)
                    .initialBackoff(Duration.ofMillis(25))
                    .maxBackoff(Duration.ofMillis(25))
                    .jitterRatio(0)
                    .build())
            .build();

    var subscription =
        SseTransport.open(
            httpClient,
            new Request.Builder().url(server.url("/events")).build(),
            options,
            false,
            data -> data,
            new AlpacaSseListener<>() {
              @Override
              public void onReconnecting(int attempt, Duration reconnectDelay) {
                delay.set(reconnectDelay);
                reconnecting.countDown();
              }
            });

    assertTrue(reconnecting.await(3, TimeUnit.SECONDS));
    subscription.close();
    assertEquals(Duration.ofMillis(25), delay.get());
  }

  private static AlpacaSseOptions reconnectOptions(Duration backoff) {
    return AlpacaSseOptions.builder()
        .reconnectPolicy(
            AlpacaSseReconnectPolicy.builder()
                .initialBackoff(backoff)
                .maxBackoff(backoff)
                .jitterRatio(0)
                .build())
        .build();
  }

  private static void awaitCondition(BooleanSupplier condition, Duration timeout)
      throws InterruptedException, TimeoutException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() >= deadline) {
        throw new TimeoutException("condition was not met within " + timeout);
      }
      Thread.sleep(10);
    }
  }

  private static final class GatedScheduler extends ScheduledThreadPoolExecutor {
    private final AtomicBoolean gateNextTask = new AtomicBoolean(true);
    private final CountDownLatch taskStarted = new CountDownLatch(1);
    private final CountDownLatch releaseTask = new CountDownLatch(1);

    private GatedScheduler() {
      super(1);
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
      if (!gateNextTask.compareAndSet(true, false)) {
        return super.schedule(command, delay, unit);
      }
      return super.schedule(
          () -> {
            taskStarted.countDown();
            try {
              releaseTask.await();
            } catch (InterruptedException failure) {
              Thread.currentThread().interrupt();
              return;
            }
            command.run();
          },
          delay,
          unit);
    }
  }
}
