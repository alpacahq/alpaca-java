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
   * <p>Once the response is accepted, this future completes successfully with that connection even
   * if closure races with opening. The ordered {@code onOpen} callback remains ahead of the
   * terminal listener callback.
   *
   * <p>Keep synchronous continuations short. The lifecycle dependency above is supported, but use
   * an asynchronous continuation such as {@code thenApplyAsync} with an application-owned executor
   * for unrelated or potentially unbounded blocking work.
   *
   * <p>If the subscription terminates before opening, {@link #completion()} is settled before this
   * future is cancelled or completed exceptionally. A synchronous opening continuation may
   * therefore safely inspect terminal completion.
   *
   * <p>Each call returns an independently settled defensive copy. Cancelling or manually completing
   * one copy does not affect this subscription or another saved copy.
   */
  CompletableFuture<AlpacaSseConnectionInfo> opened();

  /**
   * Returns terminal lifecycle completion.
   *
   * <p>Normal and user closure complete successfully; terminal failures complete exceptionally.
   * Lifecycle completion is resolved before the serialized terminal listener callback and does not
   * wait for an active user callback. Settlement is handed off from the active callback path, so a
   * synchronous completion continuation may wait for the ordered terminal listener even when the
   * active callback initiated closure.
   *
   * <p>Each call returns an independently settled defensive copy. Cancelling or manually completing
   * one copy does not affect this subscription or another saved copy. Synchronous continuations run
   * on isolated SDK lifecycle workers and must remain short. The ordered-terminal wait above is
   * supported, and a blocked continuation does not delay another saved lifecycle copy or queue
   * another subscription's settlement. Use an asynchronous continuation such as {@code
   * thenApplyAsync} with an application-owned executor for unrelated or potentially unbounded
   * blocking work.
   */
  CompletableFuture<AlpacaSseCloseResult> completion();

  /**
   * Selects {@link AlpacaSseCloseResult.Reason#USER_CLOSED} when this call wins the terminal
   * transition, cancels transport work, and waits for {@link #completion()} to settle before
   * returning.
   *
   * <p>If another terminal transition wins concurrently, this method observes its normal or
   * exceptional completion without rethrowing the terminal failure.
   */
  @Override
  void close();
}
