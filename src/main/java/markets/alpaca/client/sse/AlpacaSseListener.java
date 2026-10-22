package markets.alpaca.client.sse;

import java.time.Duration;

/**
 * Receives ordered lifecycle and data callbacks from an SSE subscription.
 *
 * <p>Callbacks are serialized per subscription. A callback {@link RuntimeException} is logged and
 * does not fail the transport; an event callback is still considered delivered and its resume ID
 * advances.
 */
public interface AlpacaSseListener<T> {

  default void onOpen() {}

  default void onEvent(AlpacaSseEvent<T> event) {}

  default void onComment(String comment) {}

  default void onRetryChanged(Duration delay) {}

  default void onReconnecting(int attempt, Duration delay) {}

  default void onReconnected() {}

  default void onClosed(AlpacaSseCloseResult result) {}

  /** The subscription failed terminally. */
  default void onFailure(Throwable failure) {}
}
