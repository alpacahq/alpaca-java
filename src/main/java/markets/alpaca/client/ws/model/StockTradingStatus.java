package markets.alpaca.client.ws.model;

import com.google.gson.annotations.SerializedName;

/**
 * A trading status update for a security ({@code T: "s"}).
 *
 * <p>Available from any {@code {source}} regardless of subscription plan.
 *
 * <p>Status codes by tape:
 *
 * <ul>
 *   <li>Tape A &amp; B (CTA): {@code 2} Halt, {@code 3} Resume, {@code 5} Price Indication, {@code
 *       6} Range Indication, {@code 7} Market Imbalance Buy, {@code 8} Market Imbalance Sell,
 *       {@code E} Short Sale Restriction, {@code F} LULD, etc.
 *   <li>Tape C &amp; O (UTP): {@code H} Halt, {@code Q} Quotation Resumption, {@code T} Trading
 *       Resumption, {@code P} Volatility Trading Pause.
 * </ul>
 *
 * @param symbol ticker symbol
 * @param statusCode status code (tape-dependent; see above)
 * @param statusMessage human-readable status description
 * @param reasonCode halt/resume reason code (may be null)
 * @param reasonMessage human-readable reason (may be null)
 * @param timestamp RFC-3339 timestamp
 * @param tape consolidated tape identifier
 */
public record StockTradingStatus(
    @SerializedName("S") String symbol,
    @SerializedName("sc") String statusCode,
    @SerializedName("sm") String statusMessage,
    @SerializedName("rc") String reasonCode,
    @SerializedName("rm") String reasonMessage,
    @SerializedName("t") String timestamp,
    @SerializedName("z") String tape) {}
