package markets.alpaca.client.sse;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import okhttp3.Request;

/** Thread-safe, cancellable handle for one logical SSE subscription. */
public interface AlpacaSseSubscription extends AutoCloseable {

  /**
   * Returns the original logical request used to create this subscription.
   *
   * <p>Reconnect-specific resume headers and query parameters are applied to internal request
   * copies and are not reflected here.
   */
  Request request();

  AlpacaSseState state();

  /**
   * Returns the latest completed SSE event ID, including a completed data-less {@code id:} block.
   */
  Optional<String> lastEventId();

  /** Returns the most recently accepted connection, including after closure. */
  Optional<AlpacaSseConnectionInfo> connection();

  /**
   * Completes with the first accepted connection.
   *
   * <p>Cancelling the returned defensive copy does not close this subscription.
   */
  CompletableFuture<AlpacaSseConnectionInfo> opened();

  /**
   * Returns terminal lifecycle completion.
   *
   * <p>Normal and user closure complete successfully; terminal failures complete exceptionally.
   * Lifecycle completion is resolved before the serialized terminal listener callback and does not
   * wait for an active user callback.
   */
  CompletableFuture<AlpacaSseCloseResult> completion();

  /**
   * Selects {@link AlpacaSseCloseResult.Reason#USER_CLOSED}, cancels transport work, and completes
   * {@link #completion()} before returning.
   */
  @Override
  void close();
}
