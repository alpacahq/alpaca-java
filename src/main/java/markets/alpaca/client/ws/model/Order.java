package markets.alpaca.client.ws.model;

import com.google.gson.annotations.SerializedName;
import java.util.List;

/**
 * An order entity as returned within trade update events.
 *
 * <p>All monetary values are represented as strings by the Alpaca API to avoid floating-point
 * precision issues. Parse them with {@link java.math.BigDecimal}.
 */
public record Order(
    @SerializedName("id") String id,
    @SerializedName("client_order_id") String clientOrderId,
    @SerializedName("asset_id") String assetId,
    @SerializedName("asset_class") String assetClass,
    @SerializedName("symbol") String symbol,
    @SerializedName("side") String side,
    @SerializedName("type") String type,
    @SerializedName("order_type") String orderType,
    @SerializedName("order_class") String orderClass,
    @SerializedName("qty") String qty,
    @SerializedName("notional") String notional,
    @SerializedName("filled_qty") String filledQty,
    @SerializedName("filled_avg_price") String filledAvgPrice,
    @SerializedName("status") String status,
    @SerializedName("time_in_force") String timeInForce,
    @SerializedName("limit_price") String limitPrice,
    @SerializedName("stop_price") String stopPrice,
    @SerializedName("trail_price") String trailPrice,
    @SerializedName("trail_percent") String trailPercent,
    @SerializedName("hwm") String hwm,
    @SerializedName("extended_hours") boolean extendedHours,
    @SerializedName("legs") List<Order> legs,
    @SerializedName("replaces") String replaces,
    @SerializedName("replaced_by") String replacedBy,
    @SerializedName("created_at") String createdAt,
    @SerializedName("submitted_at") String submittedAt,
    @SerializedName("filled_at") String filledAt,
    @SerializedName("canceled_at") String canceledAt,
    @SerializedName("expired_at") String expiredAt,
    @SerializedName("replaced_at") String replacedAt,
    @SerializedName("failed_at") String failedAt,
    @SerializedName("cancel_requested_at") String cancelRequestedAt,
    @SerializedName("updated_at") String updatedAt) {}
