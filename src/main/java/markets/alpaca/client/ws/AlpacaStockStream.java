package markets.alpaca.client.ws;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import markets.alpaca.client.AlpacaCredentials;
import markets.alpaca.client.ws.internal.AbstractMarketDataStream;
import markets.alpaca.client.ws.model.LuldBand;
import markets.alpaca.client.ws.model.StockBar;
import markets.alpaca.client.ws.model.StockQuote;
import markets.alpaca.client.ws.model.StockTrade;
import markets.alpaca.client.ws.model.StockTradingStatus;
import markets.alpaca.client.ws.model.TradeCancelError;
import markets.alpaca.client.ws.model.TradeCorrection;
import okhttp3.OkHttpClient;
import okhttp3.Request;

/**
 * WebSocket client for the Alpaca real-time stock pricing stream ({@code /v2/{source}}).
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * var creds  = new AlpacaCredentials(keyId, secretKey);
 * var stream = AlpacaClientFactory.stockStream(
 *     creds,
 *     StockSource.IEX,
 *     AlpacaStreamEnvironment.PRODUCTION,
 *     new StockStreamListener() {
 *         @Override public void onTrade(StockTrade t) { System.out.println(t); }
 *     });
 *
 * stream.connect(StockSubscription.builder().trades("AAPL").quotes("AAPL").build());
 *
 * // Later:
 * stream.close();
 * }</pre>
 *
 * <p>The client automatically reconnects with jittered exponential backoff on unexpected
 * disconnects and re-subscribes to the previously confirmed symbol set after re-authentication.
 *
 * <p>Callbacks are dispatched on OkHttp's reader thread by default. Use the overloads that accept
 * an {@link Executor} to offload listener work.
 */
public final class AlpacaStockStream extends AbstractMarketDataStream {

  private final String url;
  private final StockStreamListener listener;

  /**
   * Creates a new stock stream client. Use {@link
   * markets.alpaca.client.AlpacaClientFactory#stockStream} rather than calling this constructor
   * directly.
   */
  public AlpacaStockStream(
      OkHttpClient httpClient,
      AlpacaCredentials credentials,
      StockSource source,
      AlpacaStreamEnvironment environment,
      StockStreamListener listener) {
    this(
        httpClient,
        credentials,
        source,
        environment,
        listener,
        AlpacaStreamReconnectPolicy.defaultPolicy());
  }

  /**
   * Creates a new stock stream client with a custom reconnect policy. Use {@link
   * markets.alpaca.client.AlpacaClientFactory#stockStream} rather than calling this constructor
   * directly.
   */
  public AlpacaStockStream(
      OkHttpClient httpClient,
      AlpacaCredentials credentials,
      StockSource source,
      AlpacaStreamEnvironment environment,
      StockStreamListener listener,
      AlpacaStreamReconnectPolicy reconnectPolicy) {
    this(httpClient, credentials, source, environment, listener, reconnectPolicy, Runnable::run);
  }

  /**
   * Creates a new stock stream client with a custom reconnect policy and listener executor. Use
   * {@link markets.alpaca.client.AlpacaClientFactory#stockStream} rather than calling this
   * constructor directly.
   */
  public AlpacaStockStream(
      OkHttpClient httpClient,
      AlpacaCredentials credentials,
      StockSource source,
      AlpacaStreamEnvironment environment,
      StockStreamListener listener,
      AlpacaStreamReconnectPolicy reconnectPolicy,
      Executor callbackExecutor) {
    super(httpClient, credentials, reconnectPolicy, callbackExecutor, "stock");
    Objects.requireNonNull(source, "source must not be null");
    Objects.requireNonNull(environment, "environment must not be null");
    Objects.requireNonNull(listener, "listener must not be null");
    this.url = environment.baseUrl() + "/v2/" + source.pathSegment();
    this.listener = listener;
  }

  /** Creates a new stock stream client for a custom WebSocket URL. */
  public AlpacaStockStream(
      OkHttpClient httpClient,
      AlpacaCredentials credentials,
      String url,
      StockStreamListener listener) {
    this(httpClient, credentials, url, listener, AlpacaStreamReconnectPolicy.defaultPolicy());
  }

  /** Package-private — injects the WebSocket URL and reconnect policy for unit tests. */
  AlpacaStockStream(
      OkHttpClient httpClient,
      AlpacaCredentials credentials,
      String url,
      StockStreamListener listener,
      AlpacaStreamReconnectPolicy reconnectPolicy) {
    this(httpClient, credentials, url, listener, reconnectPolicy, Runnable::run);
  }

  /** Package-private — injects the WebSocket URL, reconnect policy, and executor for unit tests. */
  AlpacaStockStream(
      OkHttpClient httpClient,
      AlpacaCredentials credentials,
      String url,
      StockStreamListener listener,
      AlpacaStreamReconnectPolicy reconnectPolicy,
      Executor callbackExecutor) {
    super(httpClient, credentials, reconnectPolicy, callbackExecutor, "stock");
    this.url = Objects.requireNonNull(url, "url must not be null");
    this.listener = Objects.requireNonNull(listener, "listener must not be null");
  }

  /** Package-private — injects the WebSocket URL, reconnect policy, executor, and scheduler. */
  AlpacaStockStream(
      OkHttpClient httpClient,
      AlpacaCredentials credentials,
      String url,
      StockStreamListener listener,
      AlpacaStreamReconnectPolicy reconnectPolicy,
      Executor callbackExecutor,
      ScheduledExecutorService scheduler) {
    super(httpClient, credentials, reconnectPolicy, callbackExecutor, scheduler);
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
      case "t" -> listener.onTrade(GSON.fromJson(msg, StockTrade.class));
      case "q" -> listener.onQuote(GSON.fromJson(msg, StockQuote.class));
      case "b" -> listener.onMinuteBar(GSON.fromJson(msg, StockBar.class));
      case "d" -> listener.onDailyBar(GSON.fromJson(msg, StockBar.class));
      case "u" -> listener.onUpdatedBar(GSON.fromJson(msg, StockBar.class));
      case "c" -> listener.onTradeCorrection(GSON.fromJson(msg, TradeCorrection.class));
      case "x" -> listener.onTradeCancelError(GSON.fromJson(msg, TradeCancelError.class));
      case "l" -> listener.onLuld(GSON.fromJson(msg, LuldBand.class));
      case "s" -> listener.onTradingStatus(GSON.fromJson(msg, StockTradingStatus.class));
      default -> {
        /* unknown type — ignore */
      }
    }
  }

  // -------------------------------------------------------------------------
  // Subscribe / unsubscribe
  // -------------------------------------------------------------------------

  /**
   * Subscribes to the given stock channels and symbols. Additive — existing subscriptions for
   * channels not mentioned here are preserved. The server will reply with a subscription
   * confirmation; the listener's {@link StockStreamListener#onSubscriptionConfirmed} will fire.
   *
   * <p>Can be called at any time after {@link #connect()} — if not yet authenticated, the request
   * will be buffered and sent after authentication completes.
   */
  public void subscribe(StockSubscription sub) {
    sendSubscriptionMessage("subscribe", toPayload(sub));
  }

  /**
   * Opens the stream and subscribes after authentication completes.
   *
   * <p>This is equivalent to calling {@link #subscribe(StockSubscription)} before {@link
   * #connect()}; the subscription is buffered until the server accepts the credentials.
   */
  public void connect(StockSubscription sub) {
    subscribe(sub);
    connect();
  }

  /**
   * Unsubscribes from the given stock channels and symbols. Subtractive — only the specified
   * symbols are removed from the respective channels.
   */
  public void unsubscribe(StockSubscription sub) {
    sendSubscriptionMessage("unsubscribe", toPayload(sub));
  }

  private static Map<String, Set<String>> toPayload(StockSubscription sub) {
    Map<String, Set<String>> map = new LinkedHashMap<>();
    if (!sub.trades().isEmpty()) map.put("trades", sub.trades());
    if (!sub.quotes().isEmpty()) map.put("quotes", sub.quotes());
    if (!sub.bars().isEmpty()) map.put("bars", sub.bars());
    if (!sub.dailyBars().isEmpty()) map.put("dailyBars", sub.dailyBars());
    if (!sub.updatedBars().isEmpty()) map.put("updatedBars", sub.updatedBars());
    if (!sub.statuses().isEmpty()) map.put("statuses", sub.statuses());
    if (!sub.lulds().isEmpty()) map.put("lulds", sub.lulds());
    return map;
  }
}
