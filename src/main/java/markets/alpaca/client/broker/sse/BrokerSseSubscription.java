package markets.alpaca.client.broker.sse;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import markets.alpaca.client.sse.AlpacaSseCloseResult;
import markets.alpaca.client.sse.AlpacaSseConnectionInfo;
import markets.alpaca.client.sse.AlpacaSseState;
import markets.alpaca.client.sse.AlpacaSseSubscription;
import okhttp3.Request;
import okhttp3.sse.EventSource;

/**
 * Handle for a Broker SSE subscription.
 *
 * <p>Closing the handle cancels the underlying shared SSE subscription.
 */
public final class BrokerSseSubscription implements AlpacaSseSubscription {

  private final AlpacaSseSubscription subscription;
  private final EventSource eventSource;

  BrokerSseSubscription(AlpacaSseSubscription subscription) {
    this.subscription = Objects.requireNonNull(subscription, "subscription must not be null");
    this.eventSource =
        new EventSource() {
          @Override
          public Request request() {
            return subscription.request();
          }

          @Override
          public void cancel() {
            subscription.close();
          }
        };
  }

  /**
   * Returns a stable OkHttp-compatible facade for this logical subscription.
   *
   * <p>This compatibility facade delegates {@link EventSource#request()} and {@link
   * EventSource#cancel()} to the shared SSE subscription; it is not the transport's underlying
   * OkHttp event source. Identity, implementation casts, and access to the live OkHttp response are
   * not supported. New code should call {@link #close()}.
   */
  public EventSource eventSource() {
    return eventSource;
  }

  @Override
  public Request request() {
    return subscription.request();
  }

  @Override
  public AlpacaSseState state() {
    return subscription.state();
  }

  @Override
  public Optional<String> lastEventId() {
    return subscription.lastEventId();
  }

  @Override
  public Optional<AlpacaSseConnectionInfo> connection() {
    return subscription.connection();
  }

  @Override
  public CompletableFuture<AlpacaSseConnectionInfo> opened() {
    return subscription.opened();
  }

  @Override
  public CompletableFuture<AlpacaSseCloseResult> completion() {
    return subscription.completion();
  }

  /** Cancels the SSE stream. */
  @Override
  public void close() {
    eventSource.cancel();
  }
}
