/**
 * Handwritten Server-Sent Events clients for Alpaca Broker event streams.
 *
 * <p>The generated Broker OpenAPI client exposes SSE endpoints as blocking REST calls. This package
 * reuses the generated request builders for authentication, path, and query construction, then
 * opens the stream through OkHttp SSE so applications can receive live Broker events and close the
 * subscription explicitly.
 *
 * @see markets.alpaca.client.broker.sse.BrokerEventsSseClient
 */
package markets.alpaca.client.broker.sse;
