package markets.alpaca.client.ws.model;

import com.google.gson.annotations.SerializedName;
import java.math.BigDecimal;

/**
 * A single price level in a crypto order book.
 *
 * @param price price at this level
 * @param size size available at this price level
 */
public record CryptoOrderbookLevel(
    @SerializedName("p") BigDecimal price, @SerializedName("s") BigDecimal size) {}
