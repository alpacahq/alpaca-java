package markets.alpaca.client.ws.model;

import com.google.gson.annotations.SerializedName;
import java.math.BigDecimal;

/**
 * A top-of-book quote from the Alpaca crypto exchange order book ({@code T: "q"}).
 *
 * @param symbol crypto pair symbol (e.g. {@code "BTC/USD"})
 * @param bidPrice best bid price
 * @param bidSize best bid size
 * @param askPrice best ask price
 * @param askSize best ask size
 * @param timestamp RFC-3339 timestamp with nanosecond precision
 */
public record CryptoQuote(
    @SerializedName("S") String symbol,
    @SerializedName("bp") BigDecimal bidPrice,
    @SerializedName("bs") BigDecimal bidSize,
    @SerializedName("ap") BigDecimal askPrice,
    @SerializedName("as") BigDecimal askSize,
    @SerializedName("t") String timestamp) {}
