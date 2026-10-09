package markets.alpaca.client.broker.sse;

import java.io.IOException;
import java.time.Duration;
import markets.alpaca.client.sse.AlpacaSseCloseResult;
import markets.alpaca.client.sse.AlpacaSseDeserializationException;
import markets.alpaca.client.sse.AlpacaSseHttpException;
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

  /**
   * A typed event and its SSE protocol metadata were received.
   *
   * <p>The default preserves the original listener contract by delegating to {@link #onEvent}.
   */
  default void onEvent(T event, String id, String type) {
    onEvent(event);
  }

  /** A server diagnostic comment was received. */
  default void onComment(String comment) {}

  /** The client is waiting before another connection attempt. */
  default void onReconnecting(int attempt, Duration delay) {}

  /** A replacement connection opened successfully. */
  default void onReconnected() {}

  /** The server changed the preferred reconnect delay through an SSE {@code retry:} field. */
  default void onRetryChanged(Duration delay) {}

  /**
   * The SSE stream closed normally with a structured reason.
   *
   * <p>For a user-initiated close, the default preserves OkHttp EventSource's legacy cancellation
   * shape by delegating to {@link #onFailure(Throwable, Response)} with an {@link IOException}
   * whose message is {@code canceled}. The compatibility response is {@code null}; override this
   * method to consume structured close reasons directly. Other normal reasons delegate to {@link
   * #onClosed()}.
   */
  default void onClosed(AlpacaSseCloseResult result) {
    if (result.reason() == AlpacaSseCloseResult.Reason.USER_CLOSED) {
      onFailure(new IOException("canceled"), null);
    } else {
      onClosed();
    }
  }

  /** The SSE stream closed normally. */
  default void onClosed() {}

  /**
   * One malformed event could not be converted to its generated model.
   *
   * <p>The healthy Broker stream continues. The default preserves the original listener contract by
   * delegating the original deserialization cause to {@link #onFailure(Throwable, Response)} with
   * no HTTP response.
   */
  default void onEventFailure(AlpacaSseDeserializationException failure) {
    onFailure(failure.getCause() == null ? failure : failure.getCause(), null);
  }

  /**
   * The SSE request failed with a non-successful HTTP response.
   *
   * <p>The default preserves OkHttp EventSource's legacy callback shape: no throwable and a bounded
   * compatibility response. Override this method to consume the structured HTTP exception.
   */
  default void onHttpFailure(AlpacaSseHttpException failure, Response response) {
    onFailure(null, response);
  }

  /**
   * The SSE stream or one event failed.
   *
   * <p>A malformed event is reported without closing an otherwise healthy Broker stream. HTTP,
   * protocol, and transport failures are reported terminally after any configured retries are
   * exhausted.
   *
   * @param throwable failure cause
   * @param response bounded compatibility response reconstructed for HTTP failures, otherwise
   *     {@code null}
   */
  default void onFailure(Throwable throwable, Response response) {}
}
