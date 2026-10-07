package markets.alpaca.client.sse.internal;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import markets.alpaca.client.http.AlpacaRetryInterceptor;
import markets.alpaca.client.sse.AlpacaSseCallbackException;
import markets.alpaca.client.sse.AlpacaSseCloseResult;
import markets.alpaca.client.sse.AlpacaSseConnectionInfo;
import markets.alpaca.client.sse.AlpacaSseDeserializationException;
import markets.alpaca.client.sse.AlpacaSseEvent;
import markets.alpaca.client.sse.AlpacaSseException;
import markets.alpaca.client.sse.AlpacaSseHttpException;
import markets.alpaca.client.sse.AlpacaSseListener;
import markets.alpaca.client.sse.AlpacaSseOptions;
import markets.alpaca.client.sse.AlpacaSseProtocolException;
import markets.alpaca.client.sse.AlpacaSseState;
import markets.alpaca.client.sse.AlpacaSseSubscription;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Dispatcher;
import okhttp3.Headers;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;

/** Internal SSE transport shared by the handwritten Trading, Market Data, and Broker clients. */
public final class SseTransport {

  private static final Logger LOG = Logger.getLogger(SseTransport.class.getName());
  private static final String ORIGINAL_RETRY_AFTER_HEADER = "X-Alpaca-Sse-Original-Retry-After";
  private static final Executor DIRECT_EXECUTOR = Runnable::run;
  private static final AtomicLong HTTP_THREAD_SEQUENCE = new AtomicLong();
  private static final int CALLBACK_DISPATCH_THREADS = 4;
  private static final int CALLBACK_DISPATCH_QUEUE_CAPACITY = 256;
  private static final ScheduledExecutorService SCHEDULER = createScheduler();
  private static final ExecutorService OPENING_CALLBACK_EXECUTOR = createOpeningCallbackExecutor();
  private static final ExecutorService TERMINAL_CALLBACK_EXECUTOR =
      createTerminalCallbackExecutor();
  private static final ExecutorService COMPLETION_EXECUTOR = createCompletionExecutor();
  private static final ExecutorService LIFECYCLE_EXECUTOR =
      Executors.newCachedThreadPool(
          runnable -> {
            Thread thread = new Thread(runnable, "alpaca-sse-lifecycle");
            thread.setDaemon(true);
            return thread;
          });

  private SseTransport() {}

  static Dispatcher createHttpDispatcher() {
    var executor =
        Executors.newSingleThreadExecutor(
            runnable -> {
              Thread thread =
                  new Thread(runnable, "alpaca-sse-http-" + HTTP_THREAD_SEQUENCE.incrementAndGet());
              thread.setDaemon(true);
              return thread;
            });
    var dispatcher = new Dispatcher(executor);
    dispatcher.setMaxRequests(1);
    dispatcher.setMaxRequestsPerHost(1);
    return dispatcher;
  }

  private static Response restoreZeroRetryAfter(Interceptor.Chain chain) throws IOException {
    Response response = chain.proceed(chain.request());
    String originalRetryAfter = response.header(ORIGINAL_RETRY_AFTER_HEADER);
    if (originalRetryAfter == null) return response;
    return response
        .newBuilder()
        .removeHeader(ORIGINAL_RETRY_AFTER_HEADER)
        .header("Retry-After", originalRetryAfter)
        .build();
  }

  private static Response suppressImmediateZeroRetryAfterFollowUp(Interceptor.Chain chain)
      throws IOException {
    Response response = chain.proceed(chain.request());
    String retryAfter = response.header("Retry-After");
    Response.Builder sanitized = response.newBuilder().removeHeader(ORIGINAL_RETRY_AFTER_HEADER);
    if (retryAfter != null && retryAfter.matches("0+")) {
      // OkHttp otherwise retries this response immediately inside one Call, bypassing the SSE
      // reconnect policy. The application interceptor restores the server value before decoding.
      sanitized.header(ORIGINAL_RETRY_AFTER_HEADER, retryAfter).header("Retry-After", "1");
    }
    return sanitized.build();
  }

  static ScheduledThreadPoolExecutor createScheduler() {
    var scheduler =
        new ScheduledThreadPoolExecutor(
            1,
            runnable -> {
              Thread thread = new Thread(runnable, "alpaca-sse-scheduler");
              thread.setDaemon(true);
              return thread;
            });
    scheduler.setRemoveOnCancelPolicy(true);
    return scheduler;
  }

  static ThreadPoolExecutor createTerminalCallbackExecutor() {
    return createBoundedCallbackExecutor("alpaca-sse-terminal-callback");
  }

  static ThreadPoolExecutor createOpeningCallbackExecutor() {
    return createBoundedCallbackExecutor("alpaca-sse-opening-callback");
  }

  static ThreadPoolExecutor createCompletionExecutor() {
    // Do not queue terminal settlements behind synchronous continuations from other subscriptions.
    // Threads are reused when continuations return; callers are documented to keep them short.
    return new ThreadPoolExecutor(
        0,
        Integer.MAX_VALUE,
        60,
        TimeUnit.SECONDS,
        new SynchronousQueue<>(),
        runnable -> {
          Thread thread = new Thread(runnable, "alpaca-sse-completion");
          thread.setDaemon(true);
          return thread;
        },
        new ThreadPoolExecutor.AbortPolicy());
  }

  private static ThreadPoolExecutor createBoundedCallbackExecutor(String threadName) {
    return new ThreadPoolExecutor(
        CALLBACK_DISPATCH_THREADS,
        CALLBACK_DISPATCH_THREADS,
        0,
        TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(CALLBACK_DISPATCH_QUEUE_CAPACITY),
        runnable -> {
          Thread thread = new Thread(runnable, threadName);
          thread.setDaemon(true);
          return thread;
        },
        new ThreadPoolExecutor.AbortPolicy());
  }

  /**
   * Runs transport-lifecycle work independently of user callback executors.
   *
   * <p>This is for handwritten SSE adapters that must observe terminal transport state even when a
   * user-provided callback executor is blocked or rejects work.
   */
  public static void executeLifecycle(Runnable action) {
    LIFECYCLE_EXECUTOR.execute(Objects.requireNonNull(action, "action must not be null"));
  }

  public static <T> AlpacaSseSubscription open(
      OkHttpClient httpClient,
      Request request,
      AlpacaSseOptions options,
      boolean bounded,
      SseDecoder<T> decoder,
      AlpacaSseListener<T> listener) {
    return open(httpClient, request, options, bounded, decoder, listener, DIRECT_EXECUTOR);
  }

  public static <T> AlpacaSseSubscription open(
      OkHttpClient httpClient,
      Request request,
      AlpacaSseOptions options,
      boolean bounded,
      SseDecoder<T> decoder,
      AlpacaSseListener<T> listener,
      Executor callbackExecutor) {
    return open(
        httpClient,
        request,
        options,
        bounded,
        decoder,
        listener,
        callbackExecutor,
        SseDecodeFailurePolicy.TERMINATE);
  }

  public static <T> AlpacaSseSubscription open(
      OkHttpClient httpClient,
      Request request,
      AlpacaSseOptions options,
      boolean bounded,
      SseDecoder<T> decoder,
      AlpacaSseListener<T> listener,
      Executor callbackExecutor,
      SseDecodeFailurePolicy decodeFailurePolicy) {
    return open(
        httpClient,
        request,
        options,
        bounded,
        decoder,
        listener,
        callbackExecutor,
        decodeFailurePolicy,
        SseTransport::withLastEventIdHeader);
  }

  public static <T> AlpacaSseSubscription open(
      OkHttpClient httpClient,
      Request request,
      AlpacaSseOptions options,
      boolean bounded,
      SseDecoder<T> decoder,
      AlpacaSseListener<T> listener,
      Executor callbackExecutor,
      BiFunction<Request, String, Request> resumeRequest) {
    return open(
        httpClient,
        request,
        options,
        bounded,
        decoder,
        listener,
        callbackExecutor,
        SseDecodeFailurePolicy.TERMINATE,
        resumeRequest);
  }

  private static <T> AlpacaSseSubscription open(
      OkHttpClient httpClient,
      Request request,
      AlpacaSseOptions options,
      boolean bounded,
      SseDecoder<T> decoder,
      AlpacaSseListener<T> listener,
      Executor callbackExecutor,
      SseDecodeFailurePolicy decodeFailurePolicy,
      BiFunction<Request, String, Request> resumeRequest) {
    var session =
        new Session<>(
            httpClient,
            request,
            options,
            bounded,
            decoder,
            listener,
            callbackExecutor,
            decodeFailurePolicy,
            resumeRequest,
            SCHEDULER,
            OPENING_CALLBACK_EXECUTOR,
            TERMINAL_CALLBACK_EXECUTOR,
            COMPLETION_EXECUTOR,
            System::nanoTime);
    session.start();
    return session;
  }

  static <T> AlpacaSseSubscription openForTesting(
      OkHttpClient httpClient,
      Request request,
      AlpacaSseOptions options,
      boolean bounded,
      SseDecoder<T> decoder,
      AlpacaSseListener<T> listener,
      LongSupplier nanoTime) {
    return openForTesting(
        httpClient,
        request,
        options,
        bounded,
        decoder,
        listener,
        SCHEDULER,
        OPENING_CALLBACK_EXECUTOR,
        TERMINAL_CALLBACK_EXECUTOR,
        nanoTime);
  }

  static <T> AlpacaSseSubscription openForTesting(
      OkHttpClient httpClient,
      Request request,
      AlpacaSseOptions options,
      boolean bounded,
      SseDecoder<T> decoder,
      AlpacaSseListener<T> listener,
      ScheduledExecutorService scheduler,
      LongSupplier nanoTime) {
    return openForTesting(
        httpClient,
        request,
        options,
        bounded,
        decoder,
        listener,
        scheduler,
        OPENING_CALLBACK_EXECUTOR,
        TERMINAL_CALLBACK_EXECUTOR,
        nanoTime);
  }

  static <T> AlpacaSseSubscription openForTesting(
      OkHttpClient httpClient,
      Request request,
      AlpacaSseOptions options,
      boolean bounded,
      SseDecoder<T> decoder,
      AlpacaSseListener<T> listener,
      ScheduledExecutorService scheduler,
      Executor terminalCallbackExecutor,
      LongSupplier nanoTime) {
    return openForTesting(
        httpClient,
        request,
        options,
        bounded,
        decoder,
        listener,
        scheduler,
        OPENING_CALLBACK_EXECUTOR,
        terminalCallbackExecutor,
        nanoTime);
  }

  static <T> AlpacaSseSubscription openForTesting(
      OkHttpClient httpClient,
      Request request,
      AlpacaSseOptions options,
      boolean bounded,
      SseDecoder<T> decoder,
      AlpacaSseListener<T> listener,
      ScheduledExecutorService scheduler,
      Executor openingCallbackExecutor,
      Executor terminalCallbackExecutor,
      LongSupplier nanoTime) {
    return openForTesting(
        httpClient,
        request,
        options,
        bounded,
        decoder,
        listener,
        scheduler,
        openingCallbackExecutor,
        terminalCallbackExecutor,
        COMPLETION_EXECUTOR,
        nanoTime);
  }

  static <T> AlpacaSseSubscription openForTesting(
      OkHttpClient httpClient,
      Request request,
      AlpacaSseOptions options,
      boolean bounded,
      SseDecoder<T> decoder,
      AlpacaSseListener<T> listener,
      ScheduledExecutorService scheduler,
      Executor openingCallbackExecutor,
      Executor terminalCallbackExecutor,
      Executor completionExecutor,
      LongSupplier nanoTime) {
    var session =
        new Session<>(
            httpClient,
            request,
            options,
            bounded,
            decoder,
            listener,
            DIRECT_EXECUTOR,
            SseDecodeFailurePolicy.TERMINATE,
            SseTransport::withLastEventIdHeader,
            scheduler,
            openingCallbackExecutor,
            terminalCallbackExecutor,
            completionExecutor,
            nanoTime);
    session.start();
    return session;
  }

  static int runningHttpCallsForTesting(AlpacaSseSubscription subscription) {
    if (!(subscription instanceof Session<?> session)) {
      throw new IllegalArgumentException("subscription was not created by SseTransport");
    }
    return session.httpDispatcher.runningCallsCount();
  }

  public static Request withLastEventIdHeader(Request request, String resumeId) {
    Objects.requireNonNull(request, "request must not be null");
    validateLastEventId(resumeId);
    Headers.Builder headers = request.headers().newBuilder();
    headers.removeAll("Last-Event-ID");
    if (resumeId == null || resumeId.isEmpty()) {
      return request.newBuilder().headers(headers.build()).build();
    }
    headers.addUnsafeNonAscii("Last-Event-ID", resumeId);
    return request.newBuilder().headers(headers.build()).build();
  }

  private static void validateLastEventId(String id) {
    if (id == null) return;
    for (int index = 0; index < id.length(); index++) {
      char character = id.charAt(index);
      if (character <= 0x1f || character == 0x7f) {
        throw new AlpacaSseProtocolException(
            "SSE event ID contains an HTTP-incompatible control character");
      }
    }
  }

  private static final class Session<T> implements AlpacaSseSubscription, Callback {
    private final OkHttpClient httpClient;
    private final Dispatcher httpDispatcher;
    private final Request originalRequest;
    private final AlpacaSseOptions options;
    private final boolean bounded;
    private final SseDecoder<T> decoder;
    private final AlpacaSseListener<T> listener;
    private final Executor callbackExecutor;
    private final SseDecodeFailurePolicy decodeFailurePolicy;
    private final BiFunction<Request, String, Request> resumeRequest;
    private final ScheduledExecutorService scheduler;
    private final Executor openingCallbackExecutor;
    private final Executor terminalCallbackExecutor;
    private final Executor completionExecutor;
    private final LongSupplier nanoTime;
    private final Object lifecycleLock = new Object();
    private final SerialCallbackDispatcher callbackDispatcher = new SerialCallbackDispatcher();
    private final AtomicReference<AlpacaSseState> state =
        new AtomicReference<>(AlpacaSseState.CONNECTING);
    private final AtomicReference<String> lastEventId;
    private final AtomicReference<AlpacaSseConnectionInfo> connection = new AtomicReference<>();
    private final CompletableFuture<AlpacaSseConnectionInfo> openedFuture =
        new CompletableFuture<>();
    private final CompletableFuture<AlpacaSseCloseResult> completion = new CompletableFuture<>();

    // Guarded by lifecycleLock unless marked volatile for parser-loop visibility.
    private Call call;
    private boolean hasOpened;
    private int initialAttempt;
    private int reconnectAttempt;
    private long initialCycleStartedNanos;
    private long reconnectCycleStartedNanos;
    private Duration serverRetry;
    private ScheduledFuture<?> connectTimeout;
    private ScheduledFuture<?> idleTimeout;
    private ScheduledFuture<?> durationTimeout;
    private ScheduledFuture<?> reconnectTask;
    private ScheduledFuture<?> initialDeadline;
    private ScheduledFuture<?> reconnectDeadline;
    private Call timedOutCall;
    private long connectTimeoutGeneration;
    private long idleTimeoutGeneration;
    private long initialDeadlineGeneration;
    private long reconnectDeadlineGeneration;
    private Throwable initialDeadlineFailure;
    private Throwable reconnectDeadlineFailure;
    private volatile boolean terminal;

    Session(
        OkHttpClient httpClient,
        Request originalRequest,
        AlpacaSseOptions options,
        boolean bounded,
        SseDecoder<T> decoder,
        AlpacaSseListener<T> listener,
        Executor callbackExecutor,
        SseDecodeFailurePolicy decodeFailurePolicy,
        BiFunction<Request, String, Request> resumeRequest,
        ScheduledExecutorService scheduler,
        Executor openingCallbackExecutor,
        Executor terminalCallbackExecutor,
        Executor completionExecutor,
        LongSupplier nanoTime) {
      var httpClientBuilder =
          Objects.requireNonNull(httpClient, "httpClient must not be null").newBuilder();
      httpClientBuilder
          .interceptors()
          .removeIf(interceptor -> interceptor instanceof AlpacaRetryInterceptor);
      httpClientBuilder
          .addInterceptor(SseTransport::restoreZeroRetryAfter)
          .addNetworkInterceptor(SseTransport::suppressImmediateZeroRetryAfterFollowUp);
      this.httpDispatcher = createHttpDispatcher();
      this.httpClient =
          httpClientBuilder
              .dispatcher(httpDispatcher)
              .callTimeout(Duration.ZERO)
              .readTimeout(Duration.ZERO)
              .retryOnConnectionFailure(false)
              .build();
      this.originalRequest = Objects.requireNonNull(originalRequest, "request must not be null");
      this.options = Objects.requireNonNull(options, "options must not be null");
      this.bounded = bounded;
      this.decoder = Objects.requireNonNull(decoder, "decoder must not be null");
      this.listener = Objects.requireNonNull(listener, "listener must not be null");
      this.callbackExecutor =
          Objects.requireNonNull(callbackExecutor, "callbackExecutor must not be null");
      this.decodeFailurePolicy =
          Objects.requireNonNull(decodeFailurePolicy, "decodeFailurePolicy must not be null");
      this.resumeRequest = Objects.requireNonNull(resumeRequest, "resumeRequest must not be null");
      this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
      this.openingCallbackExecutor =
          Objects.requireNonNull(
              openingCallbackExecutor, "openingCallbackExecutor must not be null");
      this.terminalCallbackExecutor =
          Objects.requireNonNull(
              terminalCallbackExecutor, "terminalCallbackExecutor must not be null");
      this.completionExecutor =
          Objects.requireNonNull(completionExecutor, "completionExecutor must not be null");
      this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime must not be null");
      this.lastEventId = new AtomicReference<>(options.initialLastEventId());
    }

    void start() {
      Throwable schedulingFailure = null;
      synchronized (lifecycleLock) {
        if (terminal) return;
        try {
          initialCycleStartedNanos = nanoTime.getAsLong();
          startInitialDeadlineLocked();
          if (options.maxDuration() != null) {
            durationTimeout =
                scheduler.schedule(
                    () ->
                        LIFECYCLE_EXECUTOR.execute(
                            () -> fail(new SocketTimeoutException("SSE maximum duration elapsed"))),
                    durationToNanos(options.maxDuration()),
                    TimeUnit.NANOSECONDS);
          }
        } catch (RuntimeException failure) {
          schedulingFailure =
              new AlpacaSseProtocolException("Failed to schedule SSE lifecycle deadline", failure);
        }
      }
      if (schedulingFailure != null) {
        fail(schedulingFailure);
        return;
      }
      connect(null);
    }

    private void connect(Throwable precedingFailure) {
      Throwable expiredFailure = null;
      Throwable preparationFailure = null;
      Call nextCall = null;
      synchronized (lifecycleLock) {
        if (terminal) return;
        reconnectTask = null;
        if (precedingFailure != null && budgetExpiredLocked(hasOpened)) {
          expiredFailure = precedingFailure;
        } else {
          try {
            String resumeId = lastEventId.get();
            Request resumedRequest =
                Objects.requireNonNull(
                    resumeRequest.apply(originalRequest, resumeId),
                    "resumeRequest must not return null");
            Call preparedCall = httpClient.newCall(resumedRequest);
            Duration connectDeadline = remainingBudgetLocked(hasOpened, options.connectTimeout());
            long timeoutGeneration = ++connectTimeoutGeneration;
            ScheduledFuture<?> preparedTimeout =
                scheduler.schedule(
                    () -> cancelConnectIfCurrent(preparedCall, timeoutGeneration),
                    durationToNanos(connectDeadline),
                    TimeUnit.NANOSECONDS);
            state.set(hasOpened ? AlpacaSseState.RECONNECTING : AlpacaSseState.CONNECTING);
            call = preparedCall;
            timedOutCall = null;
            connectTimeout = preparedTimeout;
            nextCall = preparedCall;
          } catch (RuntimeException failure) {
            preparationFailure =
                new AlpacaSseProtocolException("Failed to prepare SSE connection attempt", failure);
          }
        }
      }
      if (expiredFailure != null) {
        fail(expiredFailure);
        return;
      }
      if (preparationFailure != null) {
        fail(preparationFailure);
        return;
      }
      if (nextCall == null) return;
      synchronized (lifecycleLock) {
        if (terminal || call != nextCall) return;
      }
      try {
        nextCall.enqueue(this);
      } catch (RuntimeException failure) {
        synchronized (lifecycleLock) {
          if (call == nextCall) {
            call = null;
            cancel(connectTimeout);
            connectTimeout = null;
            connectTimeoutGeneration++;
          }
        }
        fail(new AlpacaSseProtocolException("Failed to enqueue SSE connection attempt", failure));
      }
    }

    private void cancelConnectIfCurrent(Call expectedCall, long expectedGeneration) {
      synchronized (lifecycleLock) {
        if (!terminal && call == expectedCall && connectTimeoutGeneration == expectedGeneration) {
          timedOutCall = expectedCall;
          expectedCall.cancel();
        }
      }
    }

    @Override
    public void onFailure(Call failedCall, IOException failure) {
      Throwable reportedFailure;
      synchronized (lifecycleLock) {
        if (failedCall != call || terminal) return;
        reportedFailure = connectFailureLocked(failedCall, failure);
        cancel(connectTimeout);
        connectTimeout = null;
        connectTimeoutGeneration++;
        call = null;
      }
      retryOrFail(reportedFailure, null);
    }

    @Override
    public void onResponse(Call responseCall, Response response) {
      if (!isCurrent(responseCall)) {
        response.close();
        return;
      }
      try (response) {
        if (response.code() == 204) {
          Throwable timeout = completeConnectPhase(responseCall);
          if (timeout != null) {
            retryOrFail(timeout, null);
            return;
          }
          closeNormally(
              new AlpacaSseCloseResult(
                  AlpacaSseCloseResult.Reason.HTTP_NO_CONTENT, "server returned HTTP 204"));
          return;
        }
        if (response.code() != 200) {
          AlpacaSseHttpException failure = httpFailure(response);
          Throwable timeout = completeConnectPhase(responseCall);
          if (timeout != null) {
            retryOrFail(timeout, null);
            return;
          }
          if (isRetryable(response.code())) {
            retryOrFail(failure, failure.retryAfter());
          } else {
            fail(failure);
          }
          return;
        }
        MediaType contentType = response.body() == null ? null : response.body().contentType();
        if (contentType == null
            || !"text".equalsIgnoreCase(contentType.type())
            || !"event-stream".equalsIgnoreCase(contentType.subtype())) {
          Throwable timeout = completeConnectPhase(responseCall);
          if (timeout != null) {
            retryOrFail(timeout, null);
            return;
          }
          fail(
              new AlpacaSseProtocolException(
                  "Expected text/event-stream but received "
                      + (contentType == null ? "no Content-Type" : contentType)));
          return;
        }
        Throwable timeout = completeConnectPhase(responseCall);
        if (timeout != null) {
          retryOrFail(timeout, null);
          return;
        }

        AlpacaSseConnectionInfo connectionInfo =
            new AlpacaSseConnectionInfo(
                response.request().url().uri(), response.code(), response.headers().toMultimap());
        resetIdleTimeout();
        boolean reconnect;
        EnqueuedCallback openCallback;
        synchronized (lifecycleLock) {
          if (terminal || call != responseCall) return;
          reconnect = hasOpened;
          connection.set(connectionInfo);
          hasOpened = true;
          if (!reconnect) completeInitialCycleLocked();
          state.set(AlpacaSseState.OPEN);
          openCallback =
              callbackDispatcher.enqueue(
                  reconnect ? "onReconnected" : "onOpen",
                  reconnect ? listener::onReconnected : listener::onOpen);
        }
        if (!startIndependently(openCallback)) return;
        if (!reconnect) openedFuture.complete(connectionInfo);
        if (!awaitOpening(openCallback)) return;
        readBody(response.body());
        if (terminal) return;
        if (bounded) {
          closeNormally(
              new AlpacaSseCloseResult(
                  AlpacaSseCloseResult.Reason.BOUNDED_END, "bounded SSE response ended"));
        } else if (options.reconnectPolicy().establishedAttempts() == 0) {
          closeNormally(
              new AlpacaSseCloseResult(
                  AlpacaSseCloseResult.Reason.REMOTE_END, "SSE response ended"));
        } else {
          retryOrFail(new IOException("SSE response ended unexpectedly"), null);
        }
      } catch (AlpacaSseException failure) {
        fail(failure);
      } catch (Exception failure) {
        retryOrFail(connectFailure(responseCall, failure), null);
      }
    }

    private Throwable completeConnectPhase(Call expectedCall) {
      synchronized (lifecycleLock) {
        if (terminal || call != expectedCall) return null;
        cancel(connectTimeout);
        connectTimeout = null;
        connectTimeoutGeneration++;
        return connectFailureLocked(expectedCall, null);
      }
    }

    private Throwable connectFailure(Call expectedCall, Throwable failure) {
      synchronized (lifecycleLock) {
        return connectFailureLocked(expectedCall, failure);
      }
    }

    private Throwable connectFailureLocked(Call expectedCall, Throwable failure) {
      if (timedOutCall != expectedCall) return failure;
      timedOutCall = null;
      var timeout = new SocketTimeoutException("SSE connection deadline elapsed");
      if (failure != null) timeout.initCause(failure);
      return timeout;
    }

    private void readBody(ResponseBody body) throws IOException {
      if (body == null) throw new AlpacaSseProtocolException("SSE response has no body");
      resetIdleTimeout();
      SseParser parser =
          new SseParser(
              new SseParser.Handler() {
                @Override
                public void onEvent(String id, String type, String data) {
                  validateLastEventId(id);
                  T value;
                  try {
                    value = decoder.decode(data);
                    if (value == null) {
                      throw new NullPointerException("decoder returned null");
                    }
                  } catch (Exception failure) {
                    var decodingFailure =
                        new AlpacaSseDeserializationException(
                            "Failed to decode SSE "
                                + type
                                + " event"
                                + (id == null ? "" : " with ID " + id),
                            failure,
                            id,
                            type);
                    if (decodeFailurePolicy == SseDecodeFailurePolicy.REPORT_AND_CONTINUE) {
                      dispatchNonTerminal(
                          "onFailure",
                          () -> {
                            try {
                              listener.onFailure(decodingFailure);
                            } finally {
                              commitDecodedEventId(id);
                            }
                          });
                      return;
                    }
                    throw decodingFailure;
                  }
                  dispatchNonTerminal(
                      "onEvent",
                      () -> {
                        try {
                          listener.onEvent(new AlpacaSseEvent<>(value, id, type));
                        } finally {
                          commitDeliveredEvent(id);
                        }
                      });
                }

                @Override
                public void onLastEventId(String id) {
                  validateLastEventId(id);
                  commitLastEventId(id);
                }

                @Override
                public void onComment(String comment) {
                  dispatchNonTerminal("onComment", () -> listener.onComment(comment));
                }

                @Override
                public void onRetry(long milliseconds) {
                  Duration delay = Duration.ofMillis(milliseconds);
                  synchronized (lifecycleLock) {
                    if (terminal) return;
                    serverRetry = delay;
                  }
                  dispatchNonTerminal("onRetryChanged", () -> listener.onRetryChanged(delay));
                }
              },
              options.maxLineBytes(),
              options.maxEventBytes(),
              lastEventId.get());
      BufferedSource source = body.source();
      byte[] buffer = new byte[8192];
      while (!terminal) {
        int read = source.read(buffer);
        if (read == -1) break;
        resetIdleTimeout();
        parser.accept(buffer, read);
      }
      if (!terminal) parser.finish();
    }

    private void retryOrFail(Throwable failure, Duration retryAfter) {
      int attempt = 0;
      Duration delay = null;
      Throwable schedulingFailure = null;
      synchronized (lifecycleLock) {
        if (terminal) return;
        try {
          call = null;
          cancel(idleTimeout);
          idleTimeout = null;
          idleTimeoutGeneration++;
          boolean established = hasOpened;
          long now = nanoTime.getAsLong();
          if (established) {
            if (reconnectCycleStartedNanos == 0) {
              reconnectCycleStartedNanos = now;
              startReconnectDeadlineLocked(failure);
            } else {
              reconnectDeadlineFailure = failure;
            }
          } else {
            if (initialCycleStartedNanos == 0) {
              initialCycleStartedNanos = now;
              startInitialDeadlineLocked();
            }
            initialDeadlineFailure = failure;
          }
          attempt = established ? ++reconnectAttempt : ++initialAttempt;
          boolean serverDirectedDelay = retryAfter != null || serverRetry != null;
          Duration candidateDelay =
              retryAfter != null
                  ? retryAfter
                  : serverRetry != null
                      ? serverRetry
                      : options.reconnectPolicy().delayForAttempt(attempt);
          candidateDelay = boundReconnectDelay(candidateDelay, serverDirectedDelay);
          if (!options.reconnectPolicy().allowsAttempt(established, attempt)
              || !budgetAllowsDelayLocked(established, candidateDelay)) {
            attempt = -1;
          } else {
            delay = candidateDelay;
            state.set(AlpacaSseState.RECONNECTING);
          }
        } catch (RuntimeException rejected) {
          schedulingFailure =
              new AlpacaSseProtocolException("Failed to schedule SSE reconnect deadline", rejected);
        }
      }
      if (schedulingFailure != null) {
        fail(schedulingFailure);
        return;
      }
      if (attempt == -1) {
        fail(failure);
        return;
      }
      int callbackAttempt = attempt;
      Duration callbackDelay = delay;
      try {
        if (!dispatchNonTerminal(
            "onReconnecting", () -> listener.onReconnecting(callbackAttempt, callbackDelay))) {
          return;
        }
      } catch (AlpacaSseCallbackException callbackFailure) {
        fail(callbackFailure);
        return;
      }
      Throwable reconnectSchedulingFailure = null;
      synchronized (lifecycleLock) {
        if (terminal) return;
        try {
          reconnectTask =
              scheduler.schedule(
                  () -> connect(failure),
                  Math.max(0, callbackDelay.toMillis()),
                  TimeUnit.MILLISECONDS);
        } catch (RuntimeException rejected) {
          reconnectSchedulingFailure =
              new AlpacaSseProtocolException("Failed to schedule SSE reconnect", rejected);
        }
      }
      if (reconnectSchedulingFailure != null) fail(reconnectSchedulingFailure);
    }

    private Duration boundReconnectDelay(Duration delay, boolean serverDirected) {
      if (serverDirected && delay.compareTo(options.reconnectPolicy().initialBackoff()) < 0) {
        delay = options.reconnectPolicy().initialBackoff();
      }
      Duration maximum = options.reconnectPolicy().maxBackoff();
      return delay.compareTo(maximum) > 0 ? maximum : delay;
    }

    private void startInitialDeadlineLocked() {
      Duration maximum = options.reconnectPolicy().maxElapsedTime();
      if (maximum == null) return;
      long generation = ++initialDeadlineGeneration;
      initialDeadline =
          scheduler.schedule(
              () -> LIFECYCLE_EXECUTOR.execute(() -> failInitialDeadline(generation)),
              durationToNanos(maximum),
              TimeUnit.NANOSECONDS);
    }

    private void failInitialDeadline(long expectedGeneration) {
      Throwable failure;
      synchronized (lifecycleLock) {
        if (terminal
            || initialCycleStartedNanos == 0
            || initialDeadlineGeneration != expectedGeneration) {
          return;
        }
        failure = initialDeadlineFailure;
      }
      if (failure == null) {
        failure = new SocketTimeoutException("SSE initial elapsed-time budget exhausted");
      }
      fail(failure, null, null, expectedGeneration);
    }

    private void startReconnectDeadlineLocked(Throwable precedingFailure) {
      Duration maximum = options.reconnectPolicy().maxElapsedTime();
      if (maximum == null) return;
      reconnectDeadlineFailure = precedingFailure;
      long generation = ++reconnectDeadlineGeneration;
      reconnectDeadline =
          scheduler.schedule(
              () -> LIFECYCLE_EXECUTOR.execute(() -> failReconnectDeadline(generation)),
              durationToNanos(maximum),
              TimeUnit.NANOSECONDS);
    }

    private void failReconnectDeadline(long expectedGeneration) {
      Throwable failure;
      synchronized (lifecycleLock) {
        if (terminal
            || reconnectCycleStartedNanos == 0
            || reconnectDeadlineGeneration != expectedGeneration) {
          return;
        }
        failure = reconnectDeadlineFailure;
      }
      if (failure == null) {
        failure = new SocketTimeoutException("SSE reconnect elapsed-time budget exhausted");
      }
      fail(failure, null, expectedGeneration, null);
    }

    private AlpacaSseHttpException httpFailure(Response response) throws IOException {
      ResponseBody body = response.body();
      String text = "";
      if (body != null) {
        BufferedSource source = body.source();
        long limit = options.maxErrorBodyBytes();
        source.request(limit + 1);
        long available = Math.min(source.getBuffer().size(), limit);
        text = source.getBuffer().readUtf8(available);
      }
      String requestId = response.header("APCA-Request-ID");
      if (requestId == null) requestId = response.header("X-Request-ID");
      return new AlpacaSseHttpException(
          response.code(),
          requestId,
          response.headers().toMultimap(),
          parseRetryAfter(response.header("Retry-After")),
          text);
    }

    private void resetIdleTimeout() {
      if (options.idleTimeout() == null) return;
      synchronized (lifecycleLock) {
        if (terminal) return;
        cancel(idleTimeout);
        long generation = ++idleTimeoutGeneration;
        idleTimeout =
            scheduler.schedule(
                () ->
                    LIFECYCLE_EXECUTOR.execute(
                        () ->
                            failIdleTimeout(
                                generation,
                                new SocketTimeoutException("SSE idle timeout elapsed"))),
                durationToNanos(options.idleTimeout()),
                TimeUnit.NANOSECONDS);
      }
    }

    private boolean dispatchNonTerminal(String name, Runnable callback) {
      EnqueuedCallback enqueued;
      synchronized (lifecycleLock) {
        if (terminal) return false;
        enqueued = callbackDispatcher.enqueue(name, callback);
      }
      startAndAwait(enqueued);
      return true;
    }

    private EnqueuedCallback admitTerminal(String name, Runnable callback) {
      return callbackDispatcher.enqueue(
          name,
          () -> {
            awaitTerminalSettlement();
            callback.run();
          });
    }

    private void startTerminal(EnqueuedCallback enqueued) {
      if (!enqueued.startDrain()) return;
      try {
        terminalCallbackExecutor.execute(
            () -> {
              try {
                start(enqueued);
              } catch (AlpacaSseCallbackException failure) {
                LOG.log(
                    Level.WARNING,
                    "SSE terminal callback executor failed: " + enqueued.task().name,
                    failure);
              }
            });
      } catch (RejectedExecutionException failure) {
        callbackDispatcher.rejectPending(failure);
        LOG.log(
            Level.WARNING,
            "SSE terminal callback dispatcher rejected "
                + enqueued.task().name
                + "; lifecycle completion is unaffected",
            failure);
      }
    }

    private void startAndAwait(EnqueuedCallback enqueued) {
      start(enqueued);
      await(enqueued);
    }

    private boolean startIndependently(EnqueuedCallback enqueued) {
      if (!enqueued.startDrain()) return !terminal;
      var handoff = new CompletableFuture<Void>();
      try {
        openingCallbackExecutor.execute(
            () -> {
              try {
                callbackExecutor.execute(
                    () -> {
                      handoff.complete(null);
                      callbackDispatcher.drain();
                    });
                handoff.complete(null);
              } catch (RuntimeException failure) {
                var rejected =
                    failure instanceof RejectedExecutionException rejection
                        ? rejection
                        : new RejectedExecutionException(
                            "SSE callback executor failed " + enqueued.task().name, failure);
                callbackDispatcher.rejectPending(rejected);
                handoff.completeExceptionally(
                    new AlpacaSseCallbackException(
                        "SSE callback executor rejected " + enqueued.task().name, rejected));
              }
            });
      } catch (RuntimeException failure) {
        var rejected =
            failure instanceof RejectedExecutionException rejection
                ? rejection
                : new RejectedExecutionException(
                    "SSE opening callback dispatcher failed " + enqueued.task().name, failure);
        callbackDispatcher.rejectPending(rejected);
        handoff.completeExceptionally(
            new AlpacaSseCallbackException(
                "SSE opening callback dispatcher rejected " + enqueued.task().name, rejected));
      }
      try {
        CompletableFuture.anyOf(handoff, completion).get();
        if (terminal) return false;
        handoff.get();
        return true;
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
        throw new AlpacaSseCallbackException(
            "Interrupted while starting " + enqueued.task().name, failure);
      } catch (ExecutionException failure) {
        if (terminal) return false;
        throw new AlpacaSseCallbackException(
            "Failed to start " + enqueued.task().name, failure.getCause());
      }
    }

    private boolean awaitOpening(EnqueuedCallback enqueued) {
      try {
        CompletableFuture.anyOf(enqueued.task().completion, completion).get();
        if (terminal) return false;
        enqueued.task().completion.get();
        return true;
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
        throw new AlpacaSseCallbackException(
            "Interrupted while dispatching " + enqueued.task().name, failure);
      } catch (ExecutionException failure) {
        if (terminal) return false;
        throw new AlpacaSseCallbackException(
            "Failed to dispatch " + enqueued.task().name, failure.getCause());
      }
    }

    private void await(EnqueuedCallback enqueued) {
      try {
        enqueued.task().completion.get();
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
        throw new AlpacaSseCallbackException(
            "Interrupted while dispatching " + enqueued.task().name, failure);
      } catch (ExecutionException failure) {
        throw new AlpacaSseCallbackException(
            "Failed to dispatch " + enqueued.task().name, failure.getCause());
      }
    }

    private void start(EnqueuedCallback enqueued) {
      if (!enqueued.startDrain()) return;
      try {
        callbackExecutor.execute(callbackDispatcher::drain);
      } catch (RejectedExecutionException failure) {
        callbackDispatcher.rejectPending(failure);
        throw new AlpacaSseCallbackException(
            "SSE callback executor rejected " + enqueued.task().name, failure);
      }
    }

    @Override
    public void close() {
      closeNormally(
          new AlpacaSseCloseResult(AlpacaSseCloseResult.Reason.USER_CLOSED, "subscription closed"));
    }

    private void closeNormally(AlpacaSseCloseResult result) {
      boolean settle;
      AlpacaSseConnectionInfo acceptedConnection;
      synchronized (lifecycleLock) {
        settle = !terminal;
        if (settle) {
          terminal = true;
          cancelActiveWorkLocked();
          state.set(AlpacaSseState.CLOSED);
          acceptedConnection = hasOpened ? connection.get() : null;
        } else {
          acceptedConnection = null;
        }
      }
      if (!settle) {
        awaitTerminalSettlement();
        return;
      }
      EnqueuedCallback terminalCallback =
          admitTerminal("onClosed", () -> listener.onClosed(result));
      startTerminal(terminalCallback);
      settleCompletion(() -> completion.complete(result));
      awaitTerminalSettlement();
      if (acceptedConnection != null) {
        openedFuture.complete(acceptedConnection);
      } else if (!openedFuture.isDone()) {
        if (result.reason() == AlpacaSseCloseResult.Reason.USER_CLOSED) {
          openedFuture.cancel(false);
        } else {
          openedFuture.completeExceptionally(
              new AlpacaSseException("SSE subscription closed before opening: " + result.reason()));
        }
      }
    }

    private void fail(Throwable failure) {
      fail(failure, null, null, null);
    }

    private void failIdleTimeout(long expectedGeneration, Throwable failure) {
      fail(failure, expectedGeneration, null, null);
    }

    private void fail(
        Throwable failure,
        Long expectedIdleGeneration,
        Long expectedReconnectGeneration,
        Long expectedInitialGeneration) {
      AlpacaSseConnectionInfo acceptedConnection;
      synchronized (lifecycleLock) {
        if (terminal
            || (expectedIdleGeneration != null
                && idleTimeoutGeneration != expectedIdleGeneration.longValue())
            || (expectedReconnectGeneration != null
                && (reconnectCycleStartedNanos == 0
                    || reconnectDeadlineGeneration != expectedReconnectGeneration.longValue()))
            || (expectedInitialGeneration != null
                && (initialCycleStartedNanos == 0
                    || initialDeadlineGeneration != expectedInitialGeneration.longValue()))) {
          return;
        }
        terminal = true;
        cancelActiveWorkLocked();
        state.set(AlpacaSseState.FAILED);
        acceptedConnection = hasOpened ? connection.get() : null;
      }
      EnqueuedCallback terminalCallback =
          admitTerminal("onFailure", () -> listener.onFailure(failure));
      startTerminal(terminalCallback);
      settleCompletion(() -> completion.completeExceptionally(failure));
      awaitTerminalSettlement();
      if (acceptedConnection != null) {
        openedFuture.complete(acceptedConnection);
      } else {
        openedFuture.completeExceptionally(failure);
      }
    }

    private void settleCompletion(Runnable settlement) {
      completionExecutor.execute(settlement);
    }

    private void awaitTerminalSettlement() {
      boolean interrupted = false;
      while (!completion.isDone()) {
        interrupted |= Thread.interrupted();
        LockSupport.parkNanos(100_000L);
      }
      if (interrupted) Thread.currentThread().interrupt();
    }

    private void cancelActiveWorkLocked() {
      if (call != null) call.cancel();
      call = null;
      httpDispatcher.cancelAll();
      httpDispatcher.executorService().shutdown();
      timedOutCall = null;
      cancel(connectTimeout);
      connectTimeout = null;
      connectTimeoutGeneration++;
      cancel(idleTimeout);
      idleTimeout = null;
      idleTimeoutGeneration++;
      cancel(durationTimeout);
      durationTimeout = null;
      cancel(reconnectTask);
      reconnectTask = null;
      cancel(initialDeadline);
      initialDeadline = null;
      initialDeadlineFailure = null;
      initialDeadlineGeneration++;
      cancel(reconnectDeadline);
      reconnectDeadline = null;
      reconnectDeadlineFailure = null;
      reconnectDeadlineGeneration++;
    }

    private boolean isCurrent(Call expectedCall) {
      synchronized (lifecycleLock) {
        return !terminal && call == expectedCall;
      }
    }

    private void commitLastEventId(String id) {
      synchronized (lifecycleLock) {
        if (terminal) return;
        lastEventId.set(id);
      }
    }

    private void commitDeliveredEvent(String id) {
      synchronized (lifecycleLock) {
        lastEventId.set(id);
        if (!terminal) resetReconnectCycleLocked();
      }
    }

    private void commitDecodedEventId(String id) {
      synchronized (lifecycleLock) {
        lastEventId.set(id);
        if (!terminal) resetReconnectCycleLocked();
      }
    }

    private void completeInitialCycleLocked() {
      initialCycleStartedNanos = 0;
      cancel(initialDeadline);
      initialDeadline = null;
      initialDeadlineFailure = null;
      initialDeadlineGeneration++;
    }

    private void resetReconnectCycleLocked() {
      reconnectAttempt = 0;
      reconnectCycleStartedNanos = 0;
      cancel(reconnectDeadline);
      reconnectDeadline = null;
      reconnectDeadlineFailure = null;
      reconnectDeadlineGeneration++;
    }

    private boolean budgetAllowsDelayLocked(boolean established, Duration delay) {
      Duration maximum = options.reconnectPolicy().maxElapsedTime();
      if (maximum == null) return true;
      Duration elapsed = elapsedLocked(established);
      try {
        return elapsed.plus(delay).compareTo(maximum) < 0;
      } catch (ArithmeticException overflow) {
        return false;
      }
    }

    private boolean budgetExpiredLocked(boolean established) {
      Duration maximum = options.reconnectPolicy().maxElapsedTime();
      return maximum != null && elapsedLocked(established).compareTo(maximum) >= 0;
    }

    private Duration remainingBudgetLocked(boolean established, Duration fallback) {
      Duration maximum = options.reconnectPolicy().maxElapsedTime();
      if (maximum == null) return fallback;
      Duration remaining = maximum.minus(elapsedLocked(established));
      if (remaining.isZero() || remaining.isNegative()) return Duration.ZERO;
      return remaining.compareTo(fallback) < 0 ? remaining : fallback;
    }

    private Duration elapsedLocked(boolean established) {
      long started = established ? reconnectCycleStartedNanos : initialCycleStartedNanos;
      if (started == 0) return Duration.ZERO;
      return Duration.ofNanos(Math.max(0, nanoTime.getAsLong() - started));
    }

    private final class SerialCallbackDispatcher {
      private final ArrayDeque<CallbackTask> queue = new ArrayDeque<>();
      private boolean draining;

      synchronized EnqueuedCallback enqueue(String name, Runnable callback) {
        CallbackTask task = new CallbackTask(name, callback);
        queue.add(task);
        boolean startDrain = !draining;
        if (startDrain) draining = true;
        return new EnqueuedCallback(task, startDrain);
      }

      void drain() {
        while (true) {
          CallbackTask task;
          synchronized (this) {
            task = queue.poll();
            if (task == null) {
              draining = false;
              return;
            }
          }
          try {
            task.callback.run();
            task.completion.complete(null);
          } catch (RuntimeException failure) {
            LOG.log(Level.WARNING, "SSE listener callback failed: " + task.name, failure);
            task.completion.complete(null);
          } catch (Throwable failure) {
            LOG.log(Level.SEVERE, "SSE listener callback failed fatally: " + task.name, failure);
            task.completion.completeExceptionally(failure);
          }
        }
      }

      synchronized void rejectPending(RejectedExecutionException failure) {
        draining = false;
        CallbackTask task;
        while ((task = queue.poll()) != null) {
          task.completion.completeExceptionally(failure);
        }
      }
    }

    private final class CallbackTask {
      private final String name;
      private final Runnable callback;
      private final CompletableFuture<Void> completion = new CompletableFuture<>();

      private CallbackTask(String name, Runnable callback) {
        this.name = name;
        this.callback = callback;
      }
    }

    private final class EnqueuedCallback {
      private final CallbackTask task;
      private final boolean startDrain;

      private EnqueuedCallback(CallbackTask task, boolean startDrain) {
        this.task = task;
        this.startDrain = startDrain;
      }

      private CallbackTask task() {
        return task;
      }

      private boolean startDrain() {
        return startDrain;
      }
    }

    @Override
    public Request request() {
      return originalRequest;
    }

    @Override
    public AlpacaSseState state() {
      return state.get();
    }

    @Override
    public Optional<String> lastEventId() {
      return Optional.ofNullable(lastEventId.get()).filter(id -> !id.isEmpty());
    }

    @Override
    public Optional<AlpacaSseConnectionInfo> connection() {
      return Optional.ofNullable(connection.get());
    }

    @Override
    public CompletableFuture<AlpacaSseConnectionInfo> opened() {
      return defensiveCopy(openedFuture);
    }

    @Override
    public CompletableFuture<AlpacaSseCloseResult> completion() {
      return completion.copy();
    }

    private static boolean isRetryable(int status) {
      return status == 408
          || status == 425
          || status == 429
          || status == 500
          || status == 502
          || status == 503
          || status == 504;
    }

    private static Duration parseRetryAfter(String value) {
      if (value == null || value.isBlank()) return null;
      try {
        return Duration.ofSeconds(Math.max(0, Long.parseLong(value.trim())));
      } catch (NumberFormatException ignored) {
        try {
          Duration duration =
              Duration.between(
                  ZonedDateTime.now(),
                  ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME));
          return duration.isNegative() ? Duration.ZERO : duration;
        } catch (DateTimeParseException invalidDate) {
          return null;
        }
      }
    }

    private static void cancel(ScheduledFuture<?> future) {
      if (future != null) future.cancel(false);
    }

    private static <T> CompletableFuture<T> defensiveCopy(CompletableFuture<T> source) {
      var copy = new CompletableFuture<T>();
      source.whenComplete(
          (value, failure) -> {
            if (source.isCancelled()) {
              copy.cancel(false);
            } else if (failure != null) {
              copy.completeExceptionally(failure);
            } else {
              copy.complete(value);
            }
          });
      return copy;
    }

    private static long durationToNanos(Duration duration) {
      try {
        return duration.toNanos();
      } catch (ArithmeticException overflow) {
        return Long.MAX_VALUE;
      }
    }
  }
}
