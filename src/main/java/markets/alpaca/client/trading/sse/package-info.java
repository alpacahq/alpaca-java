/**
 * Handwritten Trading account-activity Server-Sent Events client.
 *
 * <p>Use this package instead of the generated buffered SSE operation. Subscriptions support
 * awaitable opening, reconnect-aware connection metadata, and named activity cursors. Reconnects
 * send the committed event ID through the documented {@code since_id} query and the standard {@code
 * Last-Event-ID} header.
 */
package markets.alpaca.client.trading.sse;
