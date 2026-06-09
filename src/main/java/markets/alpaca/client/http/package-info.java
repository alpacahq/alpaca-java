/**
 * OkHttp configuration and retry helpers used by Alpaca REST, WebSocket, and SSE clients.
 *
 * <p>The default client is shared and conservative. Add retry interceptors deliberately, especially
 * around non-idempotent operations such as order placement, transfers, account creation, or other
 * state-changing Broker and Trading API calls.
 */
package markets.alpaca.client.http;
