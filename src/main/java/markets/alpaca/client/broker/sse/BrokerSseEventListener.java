package markets.alpaca.client.broker.sse;

import okhttp3.Response;

/**
 * Receives lifecycle callbacks and typed events from a Broker Server-Sent Events stream.
 *
 * @param <T> generated Broker event model type delivered by the stream
 */
public interface BrokerSseEventListener<T> {

  /** The SSE connection opened successfully. */
  default void onOpen() {}

  /**
   * A typed event payload was received.
   *
   * @param event generated Broker event model
   */
  default void onEvent(T event) {}

  /** The SSE stream closed normally. */
  default void onClosed() {}

  /**
   * The SSE stream failed.
   *
   * @param throwable failure cause, when OkHttp provided one
   * @param response HTTP response associated with the failure, when available
   */
  default void onFailure(Throwable throwable, Response response) {}
}
