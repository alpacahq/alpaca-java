package markets.alpaca.client.ws.model;

import com.google.gson.annotations.SerializedName;
import java.math.BigDecimal;

/**
 * A Limit Up – Limit Down (LULD) price band update ({@code T: "l"}).
 *
 * @param symbol ticker symbol
 * @param limitUp upper price band
 * @param limitDown lower price band
 * @param indicator LULD indicator code
 * @param timestamp RFC-3339 timestamp
 * @param tape consolidated tape identifier
 */
public record LuldBand(
    @SerializedName("S") String symbol,
    @SerializedName("u") BigDecimal limitUp,
    @SerializedName("d") BigDecimal limitDown,
    @SerializedName("i") String indicator,
    @SerializedName("t") String timestamp,
    @SerializedName("z") String tape) {}
