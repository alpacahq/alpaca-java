package markets.alpaca.client.ws.model;

import com.google.gson.annotations.SerializedName;
import java.math.BigDecimal;

/**
 * A trade cancellation or error event ({@code T: "x"}).
 *
 * <p>Received automatically for symbols subscribed to {@code trades}.
 *
 * @param symbol ticker symbol
 * @param tradeId trade ID
 * @param exchange exchange code
 * @param price trade price
 * @param size trade size
 * @param action {@code "C"} for cancel, {@code "E"} for error
 * @param timestamp RFC-3339 timestamp
 * @param tape consolidated tape identifier
 */
public record TradeCancelError(
    @SerializedName("S") String symbol,
    @SerializedName("i") long tradeId,
    @SerializedName("x") String exchange,
    @SerializedName("p") BigDecimal price,
    @SerializedName("s") long size,
    @SerializedName("a") String action,
    @SerializedName("t") String timestamp,
    @SerializedName("z") String tape) {}
