package markets.alpaca.client.http;

/** Callback hooks for observing {@link AlpacaRetryInterceptor} retry behavior. */
public interface AlpacaRetryListener {

  AlpacaRetryListener NONE = new AlpacaRetryListener() {};

  /** Called before a retry attempt is delayed and executed. */
  default void onRetry(AlpacaRetryEvent event) {}

  /** Called when a retryable response is returned but no retry attempts remain. */
  default void onGiveUp(AlpacaRetryEvent event) {}
}
