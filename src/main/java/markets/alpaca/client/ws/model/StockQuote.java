package markets.alpaca.client.ws.model;

import com.google.gson.annotations.SerializedName;
import java.math.BigDecimal;
import java.util.List;

/**
 * A National Best Bid and Offer (NBBO) quote update ({@code T: "q"}).
 *
 * @param symbol ticker symbol
 * @param askExchange ask exchange code
 * @param askPrice ask price
 * @param askSize ask size in shares
 * @param bidExchange bid exchange code
 * @param bidPrice bid price
 * @param bidSize bid size in shares
 * @param tradeSize trade size (if applicable)
 * @param conditions quote condition codes (may be null or empty)
 * @param timestamp RFC-3339 timestamp with nanosecond precision
 * @param tape consolidated tape identifier
 */
public record StockQuote(
    @SerializedName("S") String symbol,
    @SerializedName("ax") String askExchange,
    @SerializedName("ap") BigDecimal askPrice,
    @SerializedName("as") long askSize,
    @SerializedName("bx") String bidExchange,
    @SerializedName("bp") BigDecimal bidPrice,
    @SerializedName("bs") long bidSize,
    @SerializedName("s") long tradeSize,
    @SerializedName("c") List<String> conditions,
    @SerializedName("t") String timestamp,
    @SerializedName("z") String tape) {}
