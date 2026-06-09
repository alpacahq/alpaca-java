package markets.alpaca.client.ws;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import markets.alpaca.client.AlpacaCredentials;
import markets.alpaca.client.ws.internal.AbstractMarketDataStream;
import markets.alpaca.client.ws.model.NewsArticle;
import okhttp3.OkHttpClient;
import okhttp3.Request;

/**
 * WebSocket client for the Alpaca real-time news stream ({@code /v1beta1/news}).
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * var stream = AlpacaClientFactory.newsStream(
 *     creds,
 *     AlpacaStreamEnvironment.PRODUCTION,
 *     new NewsStreamListener() {
 *         @Override public void onArticle(NewsArticle a) { System.out.println(a.headline()); }
 *     });
 *
 * stream.connect(NewsSubscription.builder().symbols("AAPL", "TSLA").build());
 * }</pre>
 *
 * <p>Only available on the production endpoint. Use {@code "*"} as a symbol to receive news for all
 * available symbols.
 */
public final class AlpacaNewsStream extends AbstractMarketDataStream {

  private final String url;
  private final NewsStreamListener listener;

  /**
   * Creates a new news stream client. Use {@link
   * markets.alpaca.client.AlpacaClientFactory#newsStream} rather than calling this constructor
   * directly.
   */
  public AlpacaNewsStream(
      OkHttpClient httpClient,
      AlpacaCredentials credentials,
      AlpacaStreamEnvironment environment,
      NewsStreamListener listener) {
    this(
        httpClient,
        credentials,
        environment,
        listener,
        AlpacaStreamReconnectPolicy.defaultPolicy());
  }

  /**
   * Creates a new news stream client with a custom reconnect policy. Use {@link
   * markets.alpaca.client.AlpacaClientFactory#newsStream} rather than calling this constructor
   * directly.
   */
  public AlpacaNewsStream(
      OkHttpClient httpClient,
      AlpacaCredentials credentials,
      AlpacaStreamEnvironment environment,
      NewsStreamListener listener,
      AlpacaStreamReconnectPolicy reconnectPolicy) {
    this(httpClient, credentials, environment, listener, reconnectPolicy, Runnable::run);
  }

  /**
   * Creates a new news stream client with a custom reconnect policy and listener executor. Use
   * {@link markets.alpaca.client.AlpacaClientFactory#newsStream} rather than calling this
   * constructor directly.
   */
  public AlpacaNewsStream(
      OkHttpClient httpClient,
      AlpacaCredentials credentials,
      AlpacaStreamEnvironment environment,
      NewsStreamListener listener,
      AlpacaStreamReconnectPolicy reconnectPolicy,
      Executor callbackExecutor) {
    super(httpClient, credentials, reconnectPolicy, callbackExecutor, "news");
    AlpacaStreamEnvironment checkedEnvironment =
        AlpacaStreamEnvironment.requireProductionOnly("News", environment);
    Objects.requireNonNull(listener, "listener must not be null");
    this.url = checkedEnvironment.baseUrl() + "/v1beta1/news";
    this.listener = listener;
  }

  /** Creates a new news stream client for a custom WebSocket URL. */
  public AlpacaNewsStream(
      OkHttpClient httpClient,
      AlpacaCredentials credentials,
      String url,
      NewsStreamListener listener) {
    this(httpClient, credentials, url, listener, AlpacaStreamReconnectPolicy.defaultPolicy());
  }

  /** Package-private — injects the WebSocket URL and reconnect policy for unit tests. */
  AlpacaNewsStream(
      OkHttpClient httpClient,
      AlpacaCredentials credentials,
      String url,
      NewsStreamListener listener,
      AlpacaStreamReconnectPolicy reconnectPolicy) {
    this(httpClient, credentials, url, listener, reconnectPolicy, Runnable::run);
  }

  /** Package-private — injects the WebSocket URL, reconnect policy, and executor for unit tests. */
  AlpacaNewsStream(
      OkHttpClient httpClient,
      AlpacaCredentials credentials,
      String url,
      NewsStreamListener listener,
      AlpacaStreamReconnectPolicy reconnectPolicy,
      Executor callbackExecutor) {
    super(httpClient, credentials, reconnectPolicy, callbackExecutor, "news");
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
  protected void handleMarketDataClose(int code, String reason, boolean willReconnect) {
    listener.onDisconnected(code, reason, willReconnect);
  }

  @Override
  protected void notifyReconnecting(int attempt) {
    listener.onReconnecting(attempt);
  }

  // -------------------------------------------------------------------------
  // AbstractMarketDataStream
  // -------------------------------------------------------------------------

  @Override
  protected void notifyConnected() {
    listener.onConnected();
  }

  @Override
  protected void notifyAuthenticated() {
    listener.onAuthenticated();
  }

  @Override
  protected void notifySubscriptionConfirmed(Map<String, List<String>> subscriptions) {
    listener.onSubscriptionConfirmed(subscriptions);
  }

  @Override
  protected void notifyError(int code, String message) {
    listener.onError(code, message);
  }

  @Override
  protected void notifyReconnected() {
    listener.onReconnected();
  }

  @Override
  protected void dispatchDataMessage(JsonObject msg, String type) {
    if ("n".equals(type)) {
      listener.onArticle(GSON.fromJson(msg, NewsArticle.class));
    }
    // unknown types are silently ignored
  }

  // -------------------------------------------------------------------------
  // Subscribe / unsubscribe
  // -------------------------------------------------------------------------

  /**
   * Subscribes to news for the given symbols. Additive. Use {@code
   * NewsSubscription.builder().symbols("*").build()} for all symbols.
   */
  public void subscribe(NewsSubscription sub) {
    sendSubscriptionMessage("subscribe", toPayload(sub));
  }

  /**
   * Opens the stream and subscribes after authentication completes.
   *
   * <p>This is equivalent to calling {@link #subscribe(NewsSubscription)} before {@link
   * #connect()}; the subscription is buffered until the server accepts the credentials.
   */
  public void connect(NewsSubscription sub) {
    subscribe(sub);
    connect();
  }

  /** Unsubscribes from news for the given symbols. */
  public void unsubscribe(NewsSubscription sub) {
    sendSubscriptionMessage("unsubscribe", toPayload(sub));
  }

  private static Map<String, Set<String>> toPayload(NewsSubscription sub) {
    Map<String, Set<String>> map = new LinkedHashMap<>();
    if (!sub.symbols().isEmpty()) map.put("news", sub.symbols());
    return map;
  }
}
