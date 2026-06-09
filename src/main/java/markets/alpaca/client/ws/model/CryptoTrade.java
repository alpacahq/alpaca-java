package markets.alpaca.client.ws.model;

import com.google.gson.annotations.SerializedName;
import java.math.BigDecimal;

/**
 * A cryptocurrency trade executed on the Alpaca exchange ({@code T: "t"}).
 *
 * @param symbol crypto pair symbol (e.g. {@code "BTC/USD"})
 * @param price trade price
 * @param size trade size
 * @param timestamp RFC-3339 timestamp
 * @param tradeId exchange-assigned trade ID
 * @param takerSide taker side — {@code "B"} for buy, {@code "S"} for sell
 */
public record CryptoTrade(
    @SerializedName("S") String symbol,
    @SerializedName("p") BigDecimal price,
    @SerializedName("s") BigDecimal size,
    @SerializedName("t") String timestamp,
    @SerializedName("i") long tradeId,
    @SerializedName("tks") String takerSide) {}
