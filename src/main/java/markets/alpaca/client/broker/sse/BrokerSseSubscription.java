package markets.alpaca.client.broker.sse;

import java.util.Objects;
import okhttp3.sse.EventSource;

/**
 * Handle for a Broker SSE subscription.
 *
 * <p>Closing the handle cancels the underlying OkHttp {@link EventSource}.
 */
public final class BrokerSseSubscription implements AutoCloseable {

  private final EventSource eventSource;

  BrokerSseSubscription(EventSource eventSource) {
    this.eventSource = Objects.requireNonNull(eventSource, "eventSource must not be null");
  }

  /** Returns the underlying OkHttp SSE event source. */
  public EventSource eventSource() {
    return eventSource;
  }

  /** Cancels the SSE stream. */
  @Override
  public void close() {
    eventSource.cancel();
  }
}
