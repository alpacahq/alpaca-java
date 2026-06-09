package markets.alpaca.client.ws.internal;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import markets.alpaca.client.ws.AlpacaStreamAuthResult;
import markets.alpaca.client.ws.AlpacaStreamReconnectPolicy;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Base class for all Alpaca WebSocket stream clients.
 *
 * <p>Manages the OkHttp WebSocket connection lifecycle, automatic reconnection with jittered
 * exponential backoff, and a thread-safe close flag. Subclasses implement the protocol specifics
 * via template methods.
 *
 * <p><b>Thread safety:</b> {@link #close()} is safe to call from any thread. All template methods
 * are invoked on OkHttp's internal I/O thread.
 */
public abstract class AbstractAlpacaStream implements AutoCloseable {

  private static final Logger LOG = Logger.getLogger(AbstractAlpacaStream.class.getName());
  private static final Executor DIRECT_CALLBACK_EXECUTOR = Runnable::run;
  private static final AtomicInteger SCHEDULER_IDS = new AtomicInteger(0);

  private final OkHttpClient httpClient;
  private final AlpacaStreamReconnectPolicy reconnectPolicy;
  private final Executor callbackExecutor;
  private final ScheduledExecutorService scheduler;
  private final boolean ownsScheduler;
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final AtomicBoolean connectStarted = new AtomicBoolean(false);
  private final AtomicInteger reconnectAttempt = new AtomicInteger(0);
  private final CompletableFuture<AlpacaStreamAuthResult> authenticationResultFuture =
      new CompletableFuture<>();
  private final CompletableFuture<Boolean> authenticationFuture =
      authenticationResultFuture
          .thenApply(AlpacaStreamAuthResult::isAuthenticated)
          .toCompletableFuture();

  private volatile WebSocket webSocket;

  protected AbstractAlpacaStream(OkHttpClient httpClient) {
    this(httpClient, AlpacaStreamReconnectPolicy.defaultPolicy());
  }

  protected AbstractAlpacaStream(
      OkHttpClient httpClient, AlpacaStreamReconnectPolicy reconnectPolicy) {
    this(httpClient, reconnectPolicy, DIRECT_CALLBACK_EXECUTOR);
  }

  protected AbstractAlpacaStream(
      OkHttpClient httpClient,
      AlpacaStreamReconnectPolicy reconnectPolicy,
      Executor callbackExecutor) {
    this(httpClient, reconnectPolicy, callbackExecutor, "stream");
  }

  protected AbstractAlpacaStream(
      OkHttpClient httpClient,
      AlpacaStreamReconnectPolicy reconnectPolicy,
      Executor callbackExecutor,
      String streamName) {
    this(httpClient, reconnectPolicy, callbackExecutor, newReconnectScheduler(streamName), true);
  }

  protected AbstractAlpacaStream(
      OkHttpClient httpClient,
      AlpacaStreamReconnectPolicy reconnectPolicy,
      Executor callbackExecutor,
      ScheduledExecutorService scheduler) {
    this(httpClient, reconnectPolicy, callbackExecutor, scheduler, false);
  }

  private AbstractAlpacaStream(
      OkHttpClient httpClient,
      AlpacaStreamReconnectPolicy reconnectPolicy,
      Executor callbackExecutor,
      ScheduledExecutorService scheduler,
      boolean ownsScheduler) {
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
    this.reconnectPolicy =
        Objects.requireNonNull(reconnectPolicy, "reconnectPolicy must not be null");
    this.callbackExecutor =
        Objects.requireNonNull(callbackExecutor, "callbackExecutor must not be null");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
    this.ownsScheduler = ownsScheduler;
  }

  // -------------------------------------------------------------------------
  // Template methods — subclasses must implement
  // -------------------------------------------------------------------------

  /** Builds the WebSocket upgrade request (URL + any auth headers). */
  protected abstract Request buildRequest();

  /** Called when the WebSocket connection is open. */
  protected abstract void handleOpen(WebSocket ws);

  /** Called for each text frame received from the server. */
  protected abstract void handleText(WebSocket ws, String text);

  /**
   * Called for each binary frame received from the server.
   *
   * <p>Most streams use text frames only. The paper trading endpoint sends binary {@code
   * trade_updates} frames; the default implementation decodes them as UTF-8 and delegates to {@link
   * #handleText}.
   */
  protected void handleBytes(WebSocket ws, ByteString bytes) {
    handleText(ws, bytes.utf8());
  }

  /**
   * Called when the connection closes, either cleanly or due to an error.
   *
   * @param code WebSocket close code, or {@code -1} for transport failures
   * @param reason human-readable close reason
   */
  protected abstract void handleClose(int code, String reason, boolean willReconnect);

  // -------------------------------------------------------------------------
  // Connection management
  // -------------------------------------------------------------------------

  /**
   * Opens the WebSocket connection. Safe to call once; subsequent calls are ignored if the stream
   * is already open or has been closed.
   */
  public final void connect() {
    if (closed.get() || !connectStarted.compareAndSet(false, true)) return;
    Request req = buildRequest();
    webSocket = httpClient.newWebSocket(req, new Listener());
  }

  /**
   * Sends a text frame on the current connection.
   *
   * @return {@code true} if the message was enqueued successfully
   */
  protected final boolean send(String json) {
    WebSocket ws = webSocket;
    if (ws == null) return false;
    return ws.send(json);
  }

  /**
   * Closes the stream permanently. No reconnect will be attempted after this call. Idempotent —
   * safe to call multiple times.
   */
  @Override
  public final void close() {
    if (!closed.compareAndSet(false, true)) return;
    completeAuthentication(AlpacaStreamAuthResult.closed("client closed"));
    shutdownScheduler();
    WebSocket ws = webSocket;
    if (ws != null) ws.close(1000, "client closed");
  }

  /** Returns {@code true} if {@link #close()} has been called. */
  public final boolean isClosed() {
    return closed.get();
  }

  /** Throws if a user command attempts to mutate a terminal stream. */
  protected final void requireOpenForCommand(String commandName) {
    Objects.requireNonNull(commandName, "commandName must not be null");
    if (closed.get()) {
      throw new IllegalStateException(commandName + " cannot be used after the stream is closed");
    }
  }

  /**
   * Returns a future that completes when the stream's first authentication attempt succeeds or
   * fails. A clean close before authentication completes the future with {@code false}. Prefer
   * {@link #authenticationResultFuture()} when callers need the failure status or server error.
   */
  public final CompletableFuture<Boolean> authenticationFuture() {
    return authenticationFuture;
  }

  /**
   * Returns a future that completes with the stream's first authentication outcome.
   *
   * <p>The future completes with server rejection details, terminal close reasons, or success. It
   * is not completed by a caller-side timeout from {@link #waitForAuthenticationResult(Duration)}.
   */
  public final CompletableFuture<AlpacaStreamAuthResult> authenticationResultFuture() {
    return authenticationResultFuture.copy();
  }

  /**
   * Blocks until the stream authenticates, authentication fails, or the timeout elapses.
   *
   * @param timeout maximum time to wait
   * @return {@code true} when authentication succeeded; {@code false} on timeout, failure,
   *     interruption, or close before authentication
   */
  public final boolean waitForAuthentication(Duration timeout) {
    return waitForAuthenticationResult(timeout).isAuthenticated();
  }

  /**
   * Blocks until the stream authenticates, authentication fails, or the timeout elapses.
   *
   * <p>A timeout returns {@link AlpacaStreamAuthResult.Status#TIMEOUT} without completing {@link
   * #authenticationResultFuture()}, allowing other callers to continue waiting for the actual
   * stream outcome.
   *
   * @param timeout maximum time to wait
   * @return typed authentication outcome
   */
  public final AlpacaStreamAuthResult waitForAuthenticationResult(Duration timeout) {
    Objects.requireNonNull(timeout, "timeout must not be null");
    try {
      return authenticationResultFuture.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return AlpacaStreamAuthResult.interrupted();
    } catch (TimeoutException e) {
      return AlpacaStreamAuthResult.timeout(timeout);
    } catch (ExecutionException | CompletionException e) {
      return AlpacaStreamAuthResult.failed("authentication wait failed", e.getCause());
    }
  }

  /** Marks the first authentication attempt as completed. */
  protected final void completeAuthentication(boolean authenticated) {
    completeAuthentication(
        authenticated
            ? AlpacaStreamAuthResult.authenticated()
            : AlpacaStreamAuthResult.failed("authentication failed", null));
  }

  /** Marks the first authentication attempt as completed with diagnostic details. */
  protected final void completeAuthentication(AlpacaStreamAuthResult result) {
    Objects.requireNonNull(result, "result must not be null");
    authenticationResultFuture.complete(result);
  }

  /** Closes the stream permanently after a terminal protocol failure such as failed auth. */
  protected final void closeTerminal(String reason) {
    if (!closed.compareAndSet(false, true)) return;
    completeAuthentication(AlpacaStreamAuthResult.closed(reason));
    shutdownScheduler();
    WebSocket ws = webSocket;
    if (ws != null) ws.close(1000, reason);
  }

  /**
   * Runs user-supplied listener code without allowing callback failures to interrupt protocol state
   * transitions such as authentication, re-subscription, or reconnect.
   */
  protected final void invokeCallback(String callbackName, Runnable callback) {
    try {
      callbackExecutor.execute(
          () -> {
            try {
              callback.run();
            } catch (RuntimeException e) {
              LOG.log(Level.WARNING, "WebSocket listener callback failed: " + callbackName, e);
            }
          });
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "WebSocket listener callback executor failed: " + callbackName, e);
    }
  }

  // -------------------------------------------------------------------------
  // Reconnect logic
  // -------------------------------------------------------------------------

  private void scheduleReconnect() {
    if (closed.get()) return;
    int attempt = reconnectAttempt.incrementAndGet();
    long delayMs = reconnectPolicy.jitteredDelayMillisForAttempt(attempt);
    LOG.log(
        Level.INFO, "Scheduling reconnect attempt {0} in {1} ms", new Object[] {attempt, delayMs});
    scheduler.schedule(this::reconnect, delayMs, TimeUnit.MILLISECONDS);
  }

  private void reconnect() {
    if (closed.get()) return;
    invokeCallback("onReconnecting", () -> notifyReconnecting(reconnectAttempt.get()));
    Request req = buildRequest();
    webSocket = httpClient.newWebSocket(req, new Listener());
  }

  /** Resets the reconnect attempt counter after a successful connection. */
  protected final void resetReconnectAttempts() {
    reconnectAttempt.set(0);
  }

  /**
   * Returns {@code true} if the current connection is a reconnect (i.e. a previous connection was
   * made and then lost). Check this <em>before</em> calling {@link #resetReconnectAttempts()}.
   */
  protected final boolean isReconnecting() {
    return reconnectAttempt.get() > 0;
  }

  private boolean canReconnect() {
    return !closed.get() && reconnectPolicy.allowsAttempt(reconnectAttempt.get() + 1);
  }

  private void markTerminal(AlpacaStreamAuthResult result) {
    if (!closed.compareAndSet(false, true)) return;
    completeAuthentication(result);
    shutdownScheduler();
  }

  private void shutdownScheduler() {
    if (ownsScheduler) scheduler.shutdownNow();
  }

  private static ScheduledExecutorService newReconnectScheduler(String streamName) {
    String checkedStreamName = Objects.requireNonNull(streamName, "streamName must not be null");
    return Executors.newSingleThreadScheduledExecutor(
        r -> {
          Thread t =
              new Thread(
                  r,
                  "alpaca-ws-"
                      + checkedStreamName
                      + "-reconnect-"
                      + SCHEDULER_IDS.incrementAndGet());
          t.setDaemon(true);
          return t;
        });
  }

  // -------------------------------------------------------------------------
  // Subclass hooks for reconnect notifications
  // -------------------------------------------------------------------------

  /**
   * Called just before a reconnect attempt is initiated.
   *
   * @param attempt 1-based attempt counter
   */
  protected void notifyReconnecting(int attempt) {}

  /**
   * Called after a reconnect attempt succeeds and the stream is fully re-authenticated (and
   * re-subscribed, if applicable). Default implementation is a no-op.
   */
  protected void notifyReconnected() {}

  // -------------------------------------------------------------------------
  // OkHttp WebSocketListener
  // -------------------------------------------------------------------------

  private final class Listener extends WebSocketListener {

    @Override
    public void onOpen(WebSocket ws, Response response) {
      try {
        handleOpen(ws);
      } catch (RuntimeException e) {
        LOG.log(Level.WARNING, "WebSocket open handler failed", e);
      }
    }

    @Override
    public void onMessage(WebSocket ws, String text) {
      try {
        handleText(ws, text);
      } catch (RuntimeException e) {
        LOG.log(Level.WARNING, "WebSocket message handler failed", e);
      }
    }

    @Override
    public void onMessage(WebSocket ws, ByteString bytes) {
      try {
        handleBytes(ws, bytes);
      } catch (RuntimeException e) {
        LOG.log(Level.WARNING, "WebSocket binary message handler failed", e);
      }
    }

    @Override
    public void onClosing(WebSocket ws, int code, String reason) {
      ws.close(code, reason);
    }

    @Override
    public void onClosed(WebSocket ws, int code, String reason) {
      boolean willReconnect = canReconnect();
      if (!willReconnect) markTerminal(AlpacaStreamAuthResult.closed(reason));
      try {
        handleClose(code, reason, willReconnect);
      } catch (RuntimeException e) {
        LOG.log(Level.WARNING, "WebSocket close handler failed", e);
      }
      if (willReconnect) scheduleReconnect();
    }

    @Override
    public void onFailure(WebSocket ws, Throwable t, Response response) {
      LOG.log(Level.WARNING, "WebSocket failure", t);
      boolean willReconnect = canReconnect();
      String reason = t.getMessage() != null ? t.getMessage() : "transport failure";
      if (!willReconnect) markTerminal(AlpacaStreamAuthResult.failed(reason, t));
      try {
        handleClose(-1, reason, willReconnect);
      } catch (RuntimeException e) {
        LOG.log(Level.WARNING, "WebSocket failure handler failed", e);
      }
      if (willReconnect) scheduleReconnect();
    }
  }
}
