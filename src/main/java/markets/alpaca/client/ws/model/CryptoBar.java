package markets.alpaca.client.ws.model;

import com.google.gson.annotations.SerializedName;
import java.math.BigDecimal;

/**
 * An aggregated crypto price bar (minute, daily, or updated).
 *
 * <p>The listener method that delivers this record already identifies the bar type ({@code
 * onMinuteBar}, {@code onDailyBar}, or {@code onUpdatedBar}).
 *
 * @param symbol crypto pair symbol (e.g. {@code "BTC/USD"})
 * @param open opening price
 * @param high highest price
 * @param low lowest price
 * @param close closing price
 * @param volume volume
 * @param timestamp RFC-3339 bar start timestamp
 * @param tradeCount number of trades aggregated in the bar
 * @param vwap volume-weighted average price
 */
public record CryptoBar(
    @SerializedName("S") String symbol,
    @SerializedName("o") BigDecimal open,
    @SerializedName("h") BigDecimal high,
    @SerializedName("l") BigDecimal low,
    @SerializedName("c") BigDecimal close,
    @SerializedName("v") BigDecimal volume,
    @SerializedName("t") String timestamp,
    @SerializedName("n") int tradeCount,
    @SerializedName("vw") BigDecimal vwap) {}
