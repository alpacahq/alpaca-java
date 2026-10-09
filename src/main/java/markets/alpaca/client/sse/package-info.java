/**
 * Shared Server-Sent Events lifecycle, reconnect, callback, and subscription types.
 *
 * <p>Create API-specific streams through {@code AlpacaClientFactory}; generated OpenAPI SSE methods
 * are buffered REST methods and are not suitable for live streams. Subscription handles provide
 * awaitable opening, current connection metadata, cancellation, resume IDs, and structured
 * completion. Callbacks are serialized per subscription, while lifecycle completion, cancellation,
 * and timers never wait for user callbacks. Event callbacks backpressure response parsing, so a
 * blocked callback can delay detection of remote EOF and the following reconnect. A listener
 * runtime exception is logged and leaves a delivered event's cursor committed; callback-executor
 * rejection is terminal.
 */
package markets.alpaca.client.sse;
