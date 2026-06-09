package markets.alpaca.client.ws.model;

import com.google.gson.annotations.SerializedName;
import java.math.BigDecimal;
import java.util.List;

/**
 * A stock trade execution event ({@code T: "t"}).
 *
 * @param symbol ticker symbol
 * @param tradeId exchange-assigned trade ID
 * @param exchange exchange code where the trade occurred
 * @param price trade price
 * @param size trade size in shares
 * @param conditions trade condition codes (may be null or empty)
 * @param timestamp RFC-3339 timestamp with nanosecond precision
 * @param tape consolidated tape identifier ({@code A}, {@code B}, or {@code C})
 */
public record StockTrade(
    @SerializedName("S") String symbol,
    @SerializedName("i") long tradeId,
    @SerializedName("x") String exchange,
    @SerializedName("p") BigDecimal price,
    @SerializedName("s") long size,
    @SerializedName("c") List<String> conditions,
    @SerializedName("t") String timestamp,
    @SerializedName("z") String tape) {}
