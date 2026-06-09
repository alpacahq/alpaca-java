/**
 * Immutable payload models for Alpaca WebSocket stream events.
 *
 * <p>These records are deserialized from stream messages delivered by the handwritten WebSocket
 * clients. Price, size, and quantity fields that can require decimal precision use {@link
 * java.math.BigDecimal} rather than floating-point types.
 */
package markets.alpaca.client.ws.model;
