package markets.alpaca.client.ws;

import markets.alpaca.client.ws.model.LuldBand;
import markets.alpaca.client.ws.model.StockBar;
import markets.alpaca.client.ws.model.StockQuote;
import markets.alpaca.client.ws.model.StockTrade;
import markets.alpaca.client.ws.model.StockTradingStatus;
import markets.alpaca.client.ws.model.TradeCancelError;
import markets.alpaca.client.ws.model.TradeCorrection;

/**
 * Receives events from a stock pricing WebSocket stream.
 *
 * <p>All callback methods have empty default implementations so implementations only need to
 * override the events they care about. By default, callbacks are invoked on OkHttp's internal
 * reader thread; do not perform blocking I/O inside them. Use {@code
 * AlpacaClientFactory.stockStream(..., Executor)} overloads to dispatch listener work to an
 * application-owned executor.
 *
 * <h2>Connection lifecycle callbacks</h2>
 *
 * <ul>
 *   <li>{@link #onConnected()} — WebSocket connection is open, auth not yet completed.
 *   <li>{@link #onAuthenticated()} — credentials accepted; subscriptions can now be placed.
 *   <li>{@link #onSubscriptionConfirmed(java.util.Map)} — server confirmed the current subscription
 *       state after a subscribe or unsubscribe.
 *   <li>{@link #onDisconnected(int, String, boolean)} — stream closed; the {@code willReconnect}
 *       flag is {@code true} when the client will attempt to reconnect automatically.
 *   <li>{@link #onReconnecting(int)} — about to attempt reconnect; {@code attempt} starts at 1.
 *   <li>{@link #onReconnected()} — reconnect succeeded and subscriptions were restored.
 *   <li>{@link #onError(int, String)} — server sent an error frame.
 * </ul>
 */
public interface StockStreamListener {

  // -------------------------------------------------------------------------
  // Lifecycle
  // -------------------------------------------------------------------------

  /** WebSocket connection established; authentication has not yet completed. */
  default void onConnected() {}

  /** Server accepted the API credentials; the stream is ready for subscriptions. */
  default void onAuthenticated() {}

  /**
   * Server confirmed the current subscription state.
   *
   * @param subscriptions map of channel name → list of active symbols (keys: {@code trades}, {@code
   *     quotes}, {@code bars}, {@code dailyBars}, {@code updatedBars}, {@code statuses}, {@code
   *     lulds}, {@code corrections}, {@code cancelErrors})
   */
  default void onSubscriptionConfirmed(
      java.util.Map<String, java.util.List<String>> subscriptions) {}

  /**
   * The WebSocket connection closed.
   *
   * @param code WebSocket close code, or {@code -1} for transport errors
   * @param reason human-readable close reason (may be empty)
   * @param willReconnect {@code true} if the client will automatically attempt reconnection
   */
  default void onDisconnected(int code, String reason, boolean willReconnect) {}

  /**
   * About to attempt a reconnect after an unexpected disconnect.
   *
   * @param attempt 1-based reconnect attempt count
   */
  default void onReconnecting(int attempt) {}

  /** A reconnect attempt succeeded and subscriptions have been restored. */
  default void onReconnected() {}

  /**
   * Server sent an error message.
   *
   * @param code numeric error code (400–500 range)
   * @param message human-readable error description
   */
  default void onError(int code, String message) {}

  // -------------------------------------------------------------------------
  // Market data events
  // -------------------------------------------------------------------------

  /** A trade execution event. */
  default void onTrade(StockTrade trade) {}

  /** An NBBO quote update. */
  default void onQuote(StockQuote quote) {}

  /** An aggregated minute bar, emitted right after each minute mark. */
  default void onMinuteBar(StockBar bar) {}

  /**
   * A running daily bar, emitted each minute after market open. Contains all trades up to the
   * emission time.
   */
  default void onDailyBar(StockBar bar) {}

  /**
   * A corrected minute bar emitted at the half-minute mark when a late trade arrived after the
   * previous minute mark.
   */
  default void onUpdatedBar(StockBar bar) {}

  /**
   * A trade correction — a previously reported trade was incorrect. Received automatically when
   * subscribed to {@code trades} for the same symbols.
   */
  default void onTradeCorrection(TradeCorrection correction) {}

  /**
   * A trade cancel or error — a previously reported trade was canceled or had an error. Received
   * automatically when subscribed to {@code trades} for the same symbols.
   */
  default void onTradeCancelError(TradeCancelError cancelError) {}

  /** A Limit Up – Limit Down price band update. */
  default void onLuld(LuldBand band) {}

  /** A trading status update (halt, resume, etc.). */
  default void onTradingStatus(StockTradingStatus status) {}
}
