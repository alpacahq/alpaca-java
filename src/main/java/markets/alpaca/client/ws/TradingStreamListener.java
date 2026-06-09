package markets.alpaca.client.ws;

import markets.alpaca.client.ws.model.TradeUpdate;

/**
 * Receives events from the real-time trading WebSocket stream ({@code /stream}).
 *
 * <p>All callback methods have empty default implementations. By default, callbacks are invoked on
 * OkHttp's internal reader thread; do not perform blocking I/O inside them. Use {@code
 * AlpacaClientFactory.tradingStream(..., Executor)} overloads to dispatch listener work to an
 * application-owned executor.
 *
 * <p>The trading stream uses a different wire protocol than the market data streams:
 *
 * <ul>
 *   <li>Authentication uses {@code action: authenticate} with credentials under a {@code data} key.
 *   <li>Messages use a {@code stream} field as the envelope discriminator instead of {@code T}.
 *   <li>After authentication, call {@link AlpacaTradingStream#listen} to subscribe to {@code
 *       trade_updates}.
 * </ul>
 */
public interface TradingStreamListener {

  // -------------------------------------------------------------------------
  // Lifecycle
  // -------------------------------------------------------------------------

  /** Server accepted the API credentials; call {@code listen()} to subscribe to streams. */
  default void onAuthenticated() {}

  /**
   * Server confirmed the current set of active stream subscriptions.
   *
   * @param streams list of active stream names (e.g. {@code ["trade_updates"]})
   */
  default void onListening(java.util.List<String> streams) {}

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

  /** A reconnect attempt succeeded and stream subscriptions have been restored. */
  default void onReconnected() {}

  /**
   * Server sent an error message and closed the connection.
   *
   * @param errorMessage human-readable error description
   */
  default void onError(String errorMessage) {}

  // -------------------------------------------------------------------------
  // Data events
  // -------------------------------------------------------------------------

  /** An order lifecycle event for the authenticated account. */
  default void onTradeUpdate(TradeUpdate update) {}
}
