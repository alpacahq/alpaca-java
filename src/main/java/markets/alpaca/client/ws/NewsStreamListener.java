package markets.alpaca.client.ws;

import markets.alpaca.client.ws.model.NewsArticle;

/**
 * Receives events from the real-time news WebSocket stream.
 *
 * <p>All callback methods have empty default implementations. By default, callbacks are invoked on
 * OkHttp's internal reader thread; do not perform blocking I/O inside them. Use {@code
 * AlpacaClientFactory.newsStream(..., Executor)} overloads to dispatch listener work to an
 * application-owned executor.
 *
 * <p>See {@link StockStreamListener} for lifecycle callback documentation.
 */
public interface NewsStreamListener {

  // -------------------------------------------------------------------------
  // Lifecycle
  // -------------------------------------------------------------------------

  default void onConnected() {}

  default void onAuthenticated() {}

  default void onSubscriptionConfirmed(
      java.util.Map<String, java.util.List<String>> subscriptions) {}

  default void onDisconnected(int code, String reason, boolean willReconnect) {}

  default void onReconnecting(int attempt) {}

  default void onReconnected() {}

  default void onError(int code, String message) {}

  // -------------------------------------------------------------------------
  // Data events
  // -------------------------------------------------------------------------

  /** A real-time news article event. */
  default void onArticle(NewsArticle article) {}
}
