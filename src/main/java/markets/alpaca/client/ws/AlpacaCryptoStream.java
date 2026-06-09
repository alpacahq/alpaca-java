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
import markets.alpaca.client.ws.model.CryptoBar;
import markets.alpaca.client.ws.model.CryptoOrderbook;
import markets.alpaca.client.ws.model.CryptoQuote;
import markets.alpaca.client.ws.model.CryptoTrade;
import okhttp3.OkHttpClient;
import okhttp3.Request;

/**
 * WebSocket client for the Alpaca real-time crypto pricing stream ({@code /v1beta3/crypto/us}).
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * var stream = AlpacaClientFactory.cryptoStream(
 *     creds,
 *     AlpacaStreamEnvironment.PRODUCTION,
 *     new CryptoStreamListener() {
 *         @Override public void onTrade(CryptoTrade t) { System.out.println(t); }
 *     });
 *
 * stream.connect(CryptoSubscription.builder().trades("BTC/USD").bars("*").build());
 * }</pre>
 *
 * <p>Only available on the production endpoint. The crypto stream is sourced from the Alpaca
 * exchange. The free plan is limited to 10 symbols for orderbooks.
 */
public final class AlpacaCryptoStream extends AbstractMarketDataStream {

  private final String url;
  private final CryptoStreamListener listener;

  /**
   * Creates a new crypto stream client. Use {@link
   * markets.alpaca.client.AlpacaClientFactory#cryptoStream} rather than calling this constructor
   * directly.
   */
  public AlpacaCryptoStream(
      OkHttpClient httpClient,
      AlpacaCredentials credentials,
      AlpacaStreamEnvironment environment,
      CryptoStreamListener listener) {
    this(
        httpClient,
        credentials,
        environment,
        listener,
        AlpacaStreamReconnectPolicy.defaultPolicy());
  }

  /**
   * Creates a new crypto stream client with a custom reconnect policy. Use {@link
   * markets.alpaca.client.AlpacaClientFactory#cryptoStream} rather than calling this constructor
   * directly.
   */
  public AlpacaCryptoStream(
      OkHttpClient httpClient,
      AlpacaCredentials credentials,
      AlpacaStreamEnvironment environment,
      CryptoStreamListener listener,
      AlpacaStreamReconnectPolicy reconnectPolicy) {
    this(httpClient, credentials, environment, listener, reconnectPolicy, Runnable::run);
  }

  /**
   * Creates a new crypto stream client with a custom reconnect policy and listener executor. Use
   * {@link markets.alpaca.client.AlpacaClientFactory#cryptoStream} rather than calling this
   * constructor directly.
   */
  public AlpacaCryptoStream(
      OkHttpClient httpClient,
      AlpacaCredentials credentials,
      AlpacaStreamEnvironment environment,
      CryptoStreamListener listener,
      AlpacaStreamReconnectPolicy reconnectPolicy,
      Executor callbackExecutor) {
    super(httpClient, credentials, reconnectPolicy, callbackExecutor, "crypto");
    AlpacaStreamEnvironment checkedEnvironment =
        AlpacaStreamEnvironment.requireProductionOnly("Crypto", environment);
    Objects.requireNonNull(listener, "listener must not be null");
    this.url = checkedEnvironment.baseUrl() + "/v1beta3/crypto/us";
    this.listener = listener;
  }

  /** Creates a new crypto stream client for a custom WebSocket URL. */
  public AlpacaCryptoStream(
      OkHttpClient httpClient,
      AlpacaCredentials credentials,
      String url,
      CryptoStreamListener listener) {
    this(httpClient, credentials, url, listener, AlpacaStreamReconnectPolicy.defaultPolicy());
  }

  /** Package-private — injects the WebSocket URL and reconnect policy for unit tests. */
  AlpacaCryptoStream(
      OkHttpClient httpClient,
      AlpacaCredentials credentials,
      String url,
      CryptoStreamListener listener,
      AlpacaStreamReconnectPolicy reconnectPolicy) {
    this(httpClient, credentials, url, listener, reconnectPolicy, Runnable::run);
  }

  /** Package-private — injects the WebSocket URL, reconnect policy, and executor for unit tests. */
  AlpacaCryptoStream(
      OkHttpClient httpClient,
      AlpacaCredentials credentials,
      String url,
      CryptoStreamListener listener,
      AlpacaStreamReconnectPolicy reconnectPolicy,
      Executor callbackExecutor) {
    super(httpClient, credentials, reconnectPolicy, callbackExecutor, "crypto");
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
    switch (type) {
      case "t" -> listener.onTrade(GSON.fromJson(msg, CryptoTrade.class));
      case "q" -> listener.onQuote(GSON.fromJson(msg, CryptoQuote.class));
      case "b" -> listener.onMinuteBar(GSON.fromJson(msg, CryptoBar.class));
      case "d" -> listener.onDailyBar(GSON.fromJson(msg, CryptoBar.class));
      case "u" -> listener.onUpdatedBar(GSON.fromJson(msg, CryptoBar.class));
      case "o" -> listener.onOrderbook(GSON.fromJson(msg, CryptoOrderbook.class));
      default -> {
        /* unknown type — ignore */
      }
    }
  }

  // -------------------------------------------------------------------------
  // Subscribe / unsubscribe
  // -------------------------------------------------------------------------

  /**
   * Subscribes to the given crypto channels and pairs. Additive — existing subscriptions for
   * channels not mentioned here are preserved.
   */
  public void subscribe(CryptoSubscription sub) {
    sendSubscriptionMessage("subscribe", toPayload(sub));
  }

  /**
   * Opens the stream and subscribes after authentication completes.
   *
   * <p>This is equivalent to calling {@link #subscribe(CryptoSubscription)} before {@link
   * #connect()}; the subscription is buffered until the server accepts the credentials.
   */
  public void connect(CryptoSubscription sub) {
    subscribe(sub);
    connect();
  }

  /** Unsubscribes from the given crypto channels and pairs. */
  public void unsubscribe(CryptoSubscription sub) {
    sendSubscriptionMessage("unsubscribe", toPayload(sub));
  }

  private static Map<String, Set<String>> toPayload(CryptoSubscription sub) {
    Map<String, Set<String>> map = new LinkedHashMap<>();
    if (!sub.trades().isEmpty()) map.put("trades", sub.trades());
    if (!sub.quotes().isEmpty()) map.put("quotes", sub.quotes());
    if (!sub.bars().isEmpty()) map.put("bars", sub.bars());
    if (!sub.dailyBars().isEmpty()) map.put("dailyBars", sub.dailyBars());
    if (!sub.updatedBars().isEmpty()) map.put("updatedBars", sub.updatedBars());
    if (!sub.orderbooks().isEmpty()) map.put("orderbooks", sub.orderbooks());
    return map;
  }
}
