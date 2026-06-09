package markets.alpaca.client.ws.model;

import com.google.gson.annotations.SerializedName;
import java.math.BigDecimal;

/**
 * An aggregated stock price bar (minute, daily, or updated).
 *
 * <p>All three bar types share the same wire format; the listener method that receives this record
 * already indicates which type it is ({@code onMinuteBar}, {@code onDailyBar}, or {@code
 * onUpdatedBar}).
 *
 * @param symbol ticker symbol
 * @param open opening price
 * @param high highest price
 * @param low lowest price
 * @param close closing price
 * @param volume volume in shares
 * @param timestamp RFC-3339 bar start timestamp
 */
public record StockBar(
    @SerializedName("S") String symbol,
    @SerializedName("o") BigDecimal open,
    @SerializedName("h") BigDecimal high,
    @SerializedName("l") BigDecimal low,
    @SerializedName("c") BigDecimal close,
    @SerializedName("v") long volume,
    @SerializedName("t") String timestamp) {}
