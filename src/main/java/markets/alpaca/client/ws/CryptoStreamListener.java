package markets.alpaca.client.ws;

import markets.alpaca.client.ws.model.CryptoBar;
import markets.alpaca.client.ws.model.CryptoOrderbook;
import markets.alpaca.client.ws.model.CryptoQuote;
import markets.alpaca.client.ws.model.CryptoTrade;

/**
 * Receives events from a crypto pricing WebSocket stream.
 *
 * <p>All callback methods have empty default implementations. By default, callbacks are invoked on
 * OkHttp's internal reader thread; do not perform blocking I/O inside them. Use {@code
 * AlpacaClientFactory.cryptoStream(..., Executor)} overloads to dispatch listener work to an
 * application-owned executor.
 *
 * <p>See {@link StockStreamListener} for lifecycle callback documentation.
 */
public interface CryptoStreamListener {

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
  // Market data events
  // -------------------------------------------------------------------------

  /** A crypto trade executed on the Alpaca exchange. */
  default void onTrade(CryptoTrade trade) {}

  /** A top-of-book quote from the Alpaca crypto exchange order book. */
  default void onQuote(CryptoQuote quote) {}

  /** An aggregated crypto minute bar. */
  default void onMinuteBar(CryptoBar bar) {}

  /** A running crypto daily bar. */
  default void onDailyBar(CryptoBar bar) {}

  /** A corrected crypto minute bar for a late-arriving trade. */
  default void onUpdatedBar(CryptoBar bar) {}

  /**
   * A full or incremental order book update.
   *
   * <p>When {@code orderbook.reset()} is {@code true}, replace your local book entirely. When
   * {@code false}, merge only the provided levels into your local book.
   */
  default void onOrderbook(CryptoOrderbook orderbook) {}
}
