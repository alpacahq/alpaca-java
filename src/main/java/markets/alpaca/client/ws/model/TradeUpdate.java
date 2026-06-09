package markets.alpaca.client.ws.model;

import com.google.gson.annotations.SerializedName;

/**
 * An order lifecycle event for the authenticated account.
 *
 * <p>Emitted for every state change on an order. All events include an {@link #event} type and the
 * full {@link #order} object. Fill events also carry {@link #price}, {@link #qty}, and {@link
 * #positionQty}. Cancellation, expiry, and replacement events include a {@link #timestamp}.
 *
 * <p>Common event types: {@code new}, {@code fill}, {@code partial_fill}, {@code canceled}, {@code
 * expired}, {@code replaced}, {@code rejected}, {@code pending_new}, etc.
 *
 * @param event order lifecycle event type
 * @param executionId unique execution ID (present on fill events)
 * @param price average fill price (fill and partial_fill events)
 * @param qty filled quantity for this event (fill and partial_fill events)
 * @param positionQty total position size after this event in shares (fill and partial_fill)
 * @param timestamp event timestamp (fill, partial_fill, canceled, expired, replaced, rejected)
 * @param order the full order entity
 */
public record TradeUpdate(
    @SerializedName("event") String event,
    @SerializedName("execution_id") String executionId,
    @SerializedName("price") String price,
    @SerializedName("qty") String qty,
    @SerializedName("position_qty") String positionQty,
    @SerializedName("timestamp") String timestamp,
    @SerializedName("order") Order order) {}
