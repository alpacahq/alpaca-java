package markets.alpaca.client.ws.model;

import com.google.gson.annotations.SerializedName;
import java.util.List;

/**
 * A full or incremental order book snapshot for a crypto pair ({@code T: "o"}).
 *
 * <p>When {@link #reset} is {@code true} this is a full order-book snapshot (all levels). When
 * {@code false} only the changed levels are included and should be merged into the locally
 * maintained book.
 *
 * @param symbol crypto pair symbol (e.g. {@code "BTC/USD"})
 * @param timestamp RFC-3339 timestamp with nanosecond precision
 * @param bids bid price levels
 * @param asks ask price levels
 * @param reset {@code true} for a full snapshot, {@code false} for an incremental update
 */
public record CryptoOrderbook(
    @SerializedName("S") String symbol,
    @SerializedName("t") String timestamp,
    @SerializedName("b") List<CryptoOrderbookLevel> bids,
    @SerializedName("a") List<CryptoOrderbookLevel> asks,
    @SerializedName("r") boolean reset) {}
