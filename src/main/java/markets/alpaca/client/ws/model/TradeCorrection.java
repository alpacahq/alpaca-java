package markets.alpaca.client.ws.model;

import com.google.gson.annotations.SerializedName;
import java.math.BigDecimal;
import java.util.List;

/**
 * A trade correction event ({@code T: "c"}).
 *
 * <p>Received automatically for symbols subscribed to {@code trades}. Contains both the original
 * erroneous trade data and the corrected replacement data.
 *
 * @param symbol ticker symbol
 * @param exchange exchange code
 * @param origTradeId original trade ID
 * @param origPrice original trade price
 * @param origSize original trade size
 * @param origConditions original trade condition codes
 * @param correctedTradeId corrected trade ID
 * @param correctedPrice corrected trade price
 * @param correctedSize corrected trade size
 * @param correctedConditions corrected trade condition codes
 * @param timestamp RFC-3339 timestamp
 * @param tape consolidated tape identifier
 */
public record TradeCorrection(
    @SerializedName("S") String symbol,
    @SerializedName("x") String exchange,
    @SerializedName("oi") long origTradeId,
    @SerializedName("op") BigDecimal origPrice,
    @SerializedName("os") String origSize,
    @SerializedName("oc") List<String> origConditions,
    @SerializedName("ci") long correctedTradeId,
    @SerializedName("cp") BigDecimal correctedPrice,
    @SerializedName("cs") long correctedSize,
    @SerializedName("cc") List<String> correctedConditions,
    @SerializedName("t") String timestamp,
    @SerializedName("z") String tape) {}
