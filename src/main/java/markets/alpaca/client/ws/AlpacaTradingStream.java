package markets.alpaca.client.ws;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;
import markets.alpaca.client.AlpacaCredentials;
import markets.alpaca.client.ws.internal.AbstractAlpacaStream;
import markets.alpaca.client.ws.model.TradeUpdate;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.WebSocket;

/**
 * WebSocket client for the Alpaca real-time trading stream ({@code /stream}).
 *
 * <p>Provides real-time order lifecycle events ({@code trade_updates}) for the authenticated
 * brokerage account. The trading stream uses a different wire protocol than the market data
 * streams: authentication uses {@code action: authenticate} with credentials nested under {@code
 * data}, and subscriptions use {@code action: listen}.
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * var stream = AlpacaClientFactory.tradingStream(
 *     creds,
 *     TradingEnvironment.PAPER,
 *     new TradingStreamListener() {
 *         @Override public void onAuthenticated() {
 *             // subscribe to trade updates right after auth
 *         }
 *         @Override public void onTradeUpdate(TradeUpdate u) { System.out.println(u); }
 *     });
 *
 * stream.connect(TradingSubscription.TRADE_UPDATES);
 * }</pre>
 *
 * <p>The client reconnects with jittered exponential backoff and re-listens to the previously
 * active streams after re-authentication.
 *
 * <p>Callbacks are dispatched on OkHttp's reader thread by default. Use the overloads that accept
 * an {@link Executor} to offload listener work. The paper endpoint sends binary frames, which are
 * transparently decoded as UTF-8 text.
 */
public final class AlpacaTradingStream extends AbstractAlpacaStream {

  private static final Logger LOG = Logger.getLogger(AlpacaTradingStream.class.getName());
  private static final Gson GSON = new Gson();

  private final AlpacaCredentials credentials;
  private final String url;
  private final TradingStreamListener listener;

  /** Last streams requested via {@link #listen}; replayed on reconnect. */
  private volatile Set<String> pendingStreams = Collections.emptySet();

  private volatile boolean authenticated;

  /**
   * Creates a new trading stream client. Use {@link
   * markets.alpaca.client.AlpacaClientFactory#tradingStream} rather than calling this constructor
   * directly.
   */
  public AlpacaTradingStream(
      OkHttpClient httpClient,
      AlpacaCredentials credentials,
      TradingEnvironment environment,
      TradingStreamListener listener) {
    this(
        httpClient,
        credentials,
        environment,
        listener,
        AlpacaStreamReconnectPolicy.defaultPolicy());
  }

  /**
   * Creates a new trading stream client with a custom reconnect policy. Use {@link
   * markets.alpaca.client.AlpacaClientFactory#tradingStream} rather than calling this constructor
   * directly.
   */
  public AlpacaTradingStream(
      OkHttpClient httpClient,
      AlpacaCredentials credentials,
      TradingEnvironment environment,
      TradingStreamListener listener,
      AlpacaStreamReconnectPolicy reconnectPolicy) {
    this(httpClient, credentials, environment, listener, reconnectPolicy, Runnable::run);
  }

  /**
   * Creates a new trading stream client with a custom reconnect policy and listener executor. Use
   * {@link markets.alpaca.client.AlpacaClientFactory#tradingStream} rather than calling this
   * constructor directly.
   */
  public AlpacaTradingStream(
      OkHttpClient httpClient,
      AlpacaCredentials credentials,
      TradingEnvironment environment,
      TradingStreamListener listener,
      AlpacaStreamReconnectPolicy reconnectPolicy,
      Executor callbackExecutor) {
    super(httpClient, reconnectPolicy, callbackExecutor, "trading");
    this.credentials = Objects.requireNonNull(credentials, "credentials must not be null");
    this.url = Objects.requireNonNull(environment, "environment must not be null").url();
    this.listener = Objects.requireNonNull(listener, "listener must not be null");
  }

  /** Creates a new trading stream client for a custom WebSocket URL. */
  public AlpacaTradingStream(
      OkHttpClient httpClient,
      AlpacaCredentials credentials,
      String url,
      TradingStreamListener listener) {
    this(httpClient, credentials, url, listener, AlpacaStreamReconnectPolicy.defaultPolicy());
  }

  /** Package-private — injects the WebSocket URL and reconnect policy for unit tests. */
  AlpacaTradingStream(
      OkHttpClient httpClient,
      AlpacaCredentials credentials,
      String url,
      TradingStreamListener listener,
      AlpacaStreamReconnectPolicy reconnectPolicy) {
    this(httpClient, credentials, url, listener, reconnectPolicy, Runnable::run);
  }

  /** Package-private — injects the WebSocket URL, reconnect policy, and executor for unit tests. */
  AlpacaTradingStream(
      OkHttpClient httpClient,
      AlpacaCredentials credentials,
      String url,
      TradingStreamListener listener,
      AlpacaStreamReconnectPolicy reconnectPolicy,
      Executor callbackExecutor) {
    super(httpClient, reconnectPolicy, callbackExecutor, "trading");
    this.credentials = Objects.requireNonNull(credentials, "credentials must not be null");
    this.url = Objects.requireNonNull(url, "url must not be null");
    this.listener = Objects.requireNonNull(listener, "listener must not be null");
  }

  // -------------------------------------------------------------------------
  // AbstractAlpacaStream
  // -------------------------------------------------------------------------

  @Override
  protected Request buildRequest() {
    return new Request.Builder().url(url).build();
  }

  @Override
  protected void handleOpen(WebSocket ws) {
    // The trading stream has no "connected" message — send auth immediately on open.
    sendAuth(ws);
  }

  @Override
  protected void handleText(WebSocket ws, String text) {
    try {
      JsonObject msg = GSON.fromJson(text, JsonObject.class);
      String stream = msg.has("stream") ? msg.get("stream").getAsString() : "";
      String action = msg.has("action") ? msg.get("action").getAsString() : "";

      if ("authorization".equals(stream)) {
        handleAuthorization(msg);
      } else if ("listening".equals(stream)) {
        handleListening(msg);
      } else if ("trade_updates".equals(stream)) {
        handleTradeUpdate(msg);
      } else if ("error".equals(action)) {
        handleError(msg);
      } else {
        LOG.log(Level.FINE, "Unknown trading stream message: {0}", text);
      }
    } catch (Exception e) {
      LOG.log(Level.WARNING, "Failed to parse trading stream message: " + text, e);
    }
  }

  @Override
  protected void handleClose(int code, String reason, boolean willReconnect) {
    authenticated = false;
    invokeCallback("onDisconnected", () -> listener.onDisconnected(code, reason, willReconnect));
  }

  @Override
  protected void notifyReconnecting(int attempt) {
    listener.onReconnecting(attempt);
  }

  // -------------------------------------------------------------------------
  // Protocol handlers
  // -------------------------------------------------------------------------

  private void sendAuth(WebSocket ws) {
    JsonObject data = new JsonObject();
    data.addProperty("key_id", credentials.apiKeyId());
    data.addProperty("secret_key", credentials.apiSecretKey());

    JsonObject payload = new JsonObject();
    payload.addProperty("action", "authenticate");
    payload.add("data", data);
    ws.send(GSON.toJson(payload));
  }

  private void handleAuthorization(JsonObject msg) {
    JsonObject data = msg.has("data") ? msg.getAsJsonObject("data") : null;
    if (data == null) return;
    String status = data.has("status") ? data.get("status").getAsString() : "";
    if ("authorized".equals(status)) {
      boolean wasReconnecting = isReconnecting();
      resetReconnectAttempts();
      authenticated = true;
      completeAuthentication(AlpacaStreamAuthResult.authenticated());
      invokeCallback("onAuthenticated", listener::onAuthenticated);
      Set<String> streams = pendingStreams;
      if (!streams.isEmpty()) {
        sendListen(streams);
      }
      if (wasReconnecting) invokeCallback("onReconnected", listener::onReconnected);
    } else {
      // unauthorized — report as error; the server will close the connection
      String message = "authentication failed: " + status;
      completeAuthentication(AlpacaStreamAuthResult.serverRejected(null, message));
      invokeCallback("onError", () -> listener.onError(message));
      closeTerminal("authentication failed");
    }
  }

  private void handleListening(JsonObject msg) {
    JsonObject data = msg.has("data") ? msg.getAsJsonObject("data") : null;
    List<String> streams = new ArrayList<>();
    if (data != null && data.has("streams")) {
      for (JsonElement el : data.getAsJsonArray("streams")) {
        streams.add(el.getAsString());
      }
    }
    invokeCallback(
        "onListening", () -> listener.onListening(Collections.unmodifiableList(streams)));
  }

  private void handleTradeUpdate(JsonObject msg) {
    JsonObject data = msg.has("data") ? msg.getAsJsonObject("data") : null;
    if (data != null) {
      TradeUpdate update = GSON.fromJson(data, TradeUpdate.class);
      invokeCallback("onTradeUpdate", () -> listener.onTradeUpdate(update));
    }
  }

  private void handleError(JsonObject msg) {
    JsonObject data = msg.has("data") ? msg.getAsJsonObject("data") : null;
    String errorMessage =
        (data != null && data.has("error_message"))
            ? data.get("error_message").getAsString()
            : "unknown error";
    invokeCallback("onError", () -> listener.onError(errorMessage));
  }

  // -------------------------------------------------------------------------
  // Listen / subscribe
  // -------------------------------------------------------------------------

  /**
   * Subscribes to (or replaces) the active set of trading streams.
   *
   * <p>The {@code streams} list is authoritative: to stop a stream, send the list without it. Can
   * be called before authentication — the request is queued and sent after auth completes.
   */
  public void listen(TradingSubscription subscription) {
    Objects.requireNonNull(subscription, "subscription must not be null");
    requireOpenForCommand("listen");
    pendingStreams = Set.copyOf(subscription.streams());
    if (authenticated) {
      sendListen(pendingStreams);
    }
  }

  /**
   * Opens the stream and starts listening after authentication completes.
   *
   * <p>This is equivalent to calling {@link #listen(TradingSubscription)} before {@link
   * #connect()}; the requested streams are replayed when the server authorizes the connection.
   */
  public void connect(TradingSubscription subscription) {
    listen(subscription);
    connect();
  }

  private void sendListen(Set<String> streams) {
    JsonArray streamNames = new JsonArray();
    for (String s : streams) {
      streamNames.add(s);
    }

    JsonObject data = new JsonObject();
    data.add("streams", streamNames);

    JsonObject payload = new JsonObject();
    payload.addProperty("action", "listen");
    payload.add("data", data);
    send(GSON.toJson(payload));
  }
}
