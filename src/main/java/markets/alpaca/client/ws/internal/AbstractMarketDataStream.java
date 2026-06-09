package markets.alpaca.client.ws.internal;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;
import markets.alpaca.client.AlpacaCredentials;
import markets.alpaca.client.ws.AlpacaStreamAuthResult;
import markets.alpaca.client.ws.AlpacaStreamReconnectPolicy;
import okhttp3.OkHttpClient;
import okhttp3.WebSocket;

/**
 * Shared protocol logic for the three market-data WebSocket streams (stock, crypto, and news).
 *
 * <p>All three streams follow the same message envelope:
 *
 * <ol>
 *   <li>Server sends {@code [{"T":"success","msg":"connected"}]}.
 *   <li>Client sends {@code {"action":"auth","key":"...","secret":"..."}}.
 *   <li>Server sends {@code [{"T":"success","msg":"authenticated"}]}.
 *   <li>Client sends subscribe/unsubscribe messages.
 *   <li>Server streams data as JSON arrays; each element has a {@code T} discriminator.
 * </ol>
 *
 * <p>This class handles the auth/connected handshake, subscription state tracking (updated from
 * confirmed subscription messages), and reconnect-aware re-subscription. Subclasses implement
 * data-message dispatch via {@link #dispatchDataMessage}.
 */
public abstract class AbstractMarketDataStream extends AbstractAlpacaStream {

  private static final Logger LOG = Logger.getLogger(AbstractMarketDataStream.class.getName());

  protected static final Gson GSON = new Gson();

  private final AlpacaCredentials credentials;

  /**
   * Subscription state confirmed by the server. Key = channel name (e.g. "trades"), value =
   * confirmed symbol set. Guarded by {@code this}.
   */
  private final Map<String, Set<String>> confirmedSubscriptions = new LinkedHashMap<>();

  private final List<PendingSubscriptionMessage> pendingSubscriptionMessages = new ArrayList<>();
  private boolean authenticated;

  protected AbstractMarketDataStream(OkHttpClient httpClient, AlpacaCredentials credentials) {
    this(httpClient, credentials, AlpacaStreamReconnectPolicy.defaultPolicy());
  }

  protected AbstractMarketDataStream(
      OkHttpClient httpClient,
      AlpacaCredentials credentials,
      AlpacaStreamReconnectPolicy reconnectPolicy) {
    this(httpClient, credentials, reconnectPolicy, Runnable::run);
  }

  protected AbstractMarketDataStream(
      OkHttpClient httpClient,
      AlpacaCredentials credentials,
      AlpacaStreamReconnectPolicy reconnectPolicy,
      Executor callbackExecutor) {
    super(httpClient, reconnectPolicy, callbackExecutor);
    this.credentials = Objects.requireNonNull(credentials, "credentials must not be null");
  }

  protected AbstractMarketDataStream(
      OkHttpClient httpClient,
      AlpacaCredentials credentials,
      AlpacaStreamReconnectPolicy reconnectPolicy,
      Executor callbackExecutor,
      String streamName) {
    super(httpClient, reconnectPolicy, callbackExecutor, streamName);
    this.credentials = Objects.requireNonNull(credentials, "credentials must not be null");
  }

  protected AbstractMarketDataStream(
      OkHttpClient httpClient,
      AlpacaCredentials credentials,
      AlpacaStreamReconnectPolicy reconnectPolicy,
      Executor callbackExecutor,
      ScheduledExecutorService scheduler) {
    super(httpClient, reconnectPolicy, callbackExecutor, scheduler);
    this.credentials = Objects.requireNonNull(credentials, "credentials must not be null");
  }

  // -------------------------------------------------------------------------
  // AbstractAlpacaStream template methods
  // -------------------------------------------------------------------------

  @Override
  protected void handleOpen(WebSocket ws) {
    // Wait for the server's "connected" control message before sending auth.
    invokeCallback("onConnected", this::notifyConnected);
  }

  @Override
  protected final void handleText(WebSocket ws, String text) {
    try {
      JsonArray array = GSON.fromJson(text, JsonArray.class);
      for (JsonElement element : array) {
        if (!element.isJsonObject()) continue;
        JsonObject obj = element.getAsJsonObject();
        String type = obj.has("T") ? obj.get("T").getAsString() : "";
        processMessage(ws, obj, type);
      }
    } catch (Exception e) {
      LOG.log(Level.WARNING, "Failed to parse WebSocket message: " + text, e);
    }
  }

  // -------------------------------------------------------------------------
  // Message dispatch
  // -------------------------------------------------------------------------

  private void processMessage(WebSocket ws, JsonObject msg, String type) {
    switch (type) {
      case "success" -> {
        String body = msg.has("msg") ? msg.get("msg").getAsString() : "";
        if ("connected".equals(body)) {
          sendAuth(ws);
        } else if ("authenticated".equals(body)) {
          boolean wasReconnecting = isReconnecting();
          resetReconnectAttempts();
          synchronized (this) {
            authenticated = true;
          }
          completeAuthentication(AlpacaStreamAuthResult.authenticated());
          invokeCallback("onAuthenticated", this::notifyAuthenticated);
          resubscribeAfterAuth(ws);
          flushPendingSubscriptionMessages();
          if (wasReconnecting) invokeCallback("onReconnected", this::notifyReconnected);
        }
      }
      case "error" -> {
        int code = msg.has("code") ? msg.get("code").getAsInt() : 0;
        String message = msg.has("msg") ? msg.get("msg").getAsString() : "";
        boolean authFailure = isAuthFailure(message);
        if (authFailure) {
          completeAuthentication(AlpacaStreamAuthResult.serverRejected(code, message));
        }
        invokeCallback("onError", () -> notifyError(code, message));
        if (authFailure) closeTerminal("authentication failed");
      }
      case "subscription" -> {
        Map<String, List<String>> confirmed = parseSubscriptionConfirmation(msg);
        updateConfirmedSubscriptions(confirmed);
        invokeCallback("onSubscriptionConfirmed", () -> notifySubscriptionConfirmed(confirmed));
      }
      default -> {
        if (!type.isEmpty())
          invokeCallback("onDataMessage(" + type + ")", () -> dispatchDataMessage(msg, type));
      }
    }
  }

  // -------------------------------------------------------------------------
  // Auth
  // -------------------------------------------------------------------------

  private void sendAuth(WebSocket ws) {
    JsonObject payload = new JsonObject();
    payload.addProperty("action", "auth");
    payload.addProperty("key", credentials.apiKeyId());
    payload.addProperty("secret", credentials.apiSecretKey());
    ws.send(GSON.toJson(payload));
  }

  private static boolean isAuthFailure(String message) {
    return "auth failed".equals(message)
        || "auth timeout".equals(message)
        || "not authenticated".equals(message);
  }

  @Override
  protected final void handleClose(int code, String reason, boolean willReconnect) {
    synchronized (this) {
      authenticated = false;
    }
    invokeCallback("onDisconnected", () -> handleMarketDataClose(code, reason, willReconnect));
  }

  // -------------------------------------------------------------------------
  // Subscription state
  // -------------------------------------------------------------------------

  private Map<String, List<String>> parseSubscriptionConfirmation(JsonObject msg) {
    Map<String, List<String>> result = new LinkedHashMap<>();
    for (Map.Entry<String, JsonElement> entry : msg.entrySet()) {
      if ("T".equals(entry.getKey())) continue;
      if (entry.getValue().isJsonArray()) {
        List<String> symbols = new ArrayList<>();
        for (JsonElement el : entry.getValue().getAsJsonArray()) {
          symbols.add(el.getAsString());
        }
        result.put(entry.getKey(), Collections.unmodifiableList(symbols));
      }
    }
    return Collections.unmodifiableMap(result);
  }

  private synchronized void updateConfirmedSubscriptions(Map<String, List<String>> confirmed) {
    confirmedSubscriptions.clear();
    confirmed.forEach(
        (channel, symbols) -> confirmedSubscriptions.put(channel, new LinkedHashSet<>(symbols)));
  }

  /** Returns the current server-confirmed subscriptions, keyed by channel name. */
  protected synchronized Map<String, Set<String>> getConfirmedSubscriptions() {
    Map<String, Set<String>> copy = new LinkedHashMap<>();
    confirmedSubscriptions.forEach((k, v) -> copy.put(k, Collections.unmodifiableSet(v)));
    return Collections.unmodifiableMap(copy);
  }

  /**
   * Sends a subscribe or unsubscribe message and returns immediately. The confirmed state is
   * updated when the server responds with a subscription message.
   *
   * @param action {@code "subscribe"} or {@code "unsubscribe"}
   * @param payload channel-to-symbols map (channels with empty sets are omitted)
   */
  protected void sendSubscriptionMessage(String action, Map<String, Set<String>> payload) {
    requireOpenForCommand(action);
    if (payload.isEmpty()) return;
    Map<String, Set<String>> copy = copyPayload(payload);
    synchronized (this) {
      if (!authenticated) {
        bufferSubscriptionMessage(action, copy);
        return;
      }
    }
    if (!send(buildSubscriptionMessage(action, copy))) {
      bufferSubscriptionMessage(action, copy);
    }
  }

  /**
   * Called after (re-)authentication to re-subscribe with the previously confirmed state.
   * Subclasses may override to add their own reconnect-subscribe payload building.
   */
  protected void resubscribeAfterAuth(WebSocket ws) {
    Map<String, Set<String>> subs = getConfirmedSubscriptions();
    if (subs.isEmpty()) return;

    Map<String, Set<String>> payload = new LinkedHashMap<>();
    subs.forEach(
        (channel, symbols) -> {
          // Skip server-managed channels that are implicit (corrections, cancelErrors)
          if ("corrections".equals(channel) || "cancelErrors".equals(channel)) return;
          if (!symbols.isEmpty()) payload.put(channel, symbols);
        });
    if (!payload.isEmpty()) sendSubscriptionMessage("subscribe", payload);
  }

  // -------------------------------------------------------------------------
  // Subclass data dispatch
  // -------------------------------------------------------------------------

  /**
   * Called for each data message element with a non-control {@code T} value.
   *
   * @param msg the JSON object for this message
   * @param type the {@code T} field value (e.g. {@code "t"}, {@code "q"}, {@code "b"})
   */
  protected abstract void dispatchDataMessage(JsonObject msg, String type);

  // -------------------------------------------------------------------------
  // Subclass notification hooks
  // -------------------------------------------------------------------------

  protected abstract void notifyConnected();

  protected abstract void notifyAuthenticated();

  protected abstract void notifySubscriptionConfirmed(Map<String, List<String>> subscriptions);

  protected abstract void notifyError(int code, String message);

  protected abstract void handleMarketDataClose(int code, String reason, boolean willReconnect);

  // -------------------------------------------------------------------------
  // Utility
  // -------------------------------------------------------------------------

  private void flushPendingSubscriptionMessages() {
    List<PendingSubscriptionMessage> pending;
    synchronized (this) {
      if (pendingSubscriptionMessages.isEmpty()) return;
      pending = List.copyOf(pendingSubscriptionMessages);
      pendingSubscriptionMessages.clear();
    }
    pending.forEach(
        msg -> {
          if (!send(buildSubscriptionMessage(msg.action(), msg.payload()))) {
            bufferSubscriptionMessage(msg.action(), msg.payload());
          }
        });
  }

  private synchronized void bufferSubscriptionMessage(
      String action, Map<String, Set<String>> payload) {
    pendingSubscriptionMessages.add(new PendingSubscriptionMessage(action, payload));
  }

  private static Map<String, Set<String>> copyPayload(Map<String, Set<String>> payload) {
    Map<String, Set<String>> copy = new LinkedHashMap<>();
    payload.forEach(
        (channel, symbols) -> {
          if (!symbols.isEmpty())
            copy.put(channel, Collections.unmodifiableSet(new LinkedHashSet<>(symbols)));
        });
    return Collections.unmodifiableMap(copy);
  }

  private static String buildSubscriptionMessage(String action, Map<String, Set<String>> payload) {
    JsonObject root = new JsonObject();
    root.addProperty("action", action);
    payload.forEach(
        (channel, symbols) -> {
          if (symbols.isEmpty()) return;
          JsonArray values = new JsonArray();
          for (String s : symbols) {
            values.add(s);
          }
          root.add(channel, values);
        });
    return GSON.toJson(root);
  }

  private record PendingSubscriptionMessage(String action, Map<String, Set<String>> payload) {}
}
