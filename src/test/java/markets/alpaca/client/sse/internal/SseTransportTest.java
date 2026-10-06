package markets.alpaca.client.sse.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
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
}
