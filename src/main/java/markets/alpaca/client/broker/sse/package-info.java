/**
 * Handwritten Server-Sent Events clients for Alpaca Broker event streams.
 *
 * <p>The generated Broker OpenAPI client exposes SSE endpoints as blocking REST calls. This package
 * reuses the generated request builders for authentication, path, and query construction, then
 * opens them through the SDK's incremental OkHttp transport so applications can receive live Broker
 * events, await connection metadata, distinguish per-event decoding failures, opt into reconnects,
 * and close subscriptions explicitly. Gap replay uses each endpoint's documented {@code since_id}
 * or {@code since_ulid} request option. Rich default listener methods preserve legacy failure/close
 * callback shapes without duplicate delivery. The exposed OkHttp {@code EventSource} is a
 * request/cancel compatibility facade; new code should close the subscription directly.
 *
 * @see markets.alpaca.client.broker.sse.BrokerEventsSseClient
 */
package markets.alpaca.client.broker.sse;
