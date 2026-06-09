package markets.alpaca.client.ws;

import java.util.Objects;

/**
 * Selects between the Alpaca production and sandbox WebSocket endpoints.
 *
 * <h2>Which environment should I use?</h2>
 *
 * <ul>
 *   <li><b>Trading/data credentials</b> ({@code APCA_TRADING_KEY_ID} / {@code
 *       APCA_TRADING_SECRET_KEY}): authenticate against {@link #PRODUCTION}. Trading API keys are
 *       scoped to the live data stream and work for all four stream types.
 *   <li><b>Broker-sandbox credentials</b>: use {@link #SANDBOX} for stock streams. Crypto and news
 *       streams are production-only and do not have a sandbox endpoint.
 * </ul>
 *
 * <p>The trading stream uses its own production/paper distinction via {@link TradingEnvironment}.
 */
public enum AlpacaStreamEnvironment {

  /** {@code stream.data.alpaca.markets} — live market data (all stream types). */
  PRODUCTION("wss://stream.data.alpaca.markets"),

  /**
   * {@code stream.data.sandbox.alpaca.markets} — stock streams only.
   *
   * <p>Requires broker-sandbox credentials, not paper-trading credentials. Crypto and news streams
   * have no sandbox endpoint; use {@link #PRODUCTION} for those.
   */
  SANDBOX("wss://stream.data.sandbox.alpaca.markets");

  private final String baseUrl;

  AlpacaStreamEnvironment(String baseUrl) {
    this.baseUrl = baseUrl;
  }

  /** Returns the base WebSocket URL for this environment (no trailing slash). */
  public String baseUrl() {
    return baseUrl;
  }

  static AlpacaStreamEnvironment requireProductionOnly(
      String streamName, AlpacaStreamEnvironment environment) {
    AlpacaStreamEnvironment checkedEnvironment =
        Objects.requireNonNull(environment, "environment must not be null");
    if (checkedEnvironment != PRODUCTION) {
      throw new IllegalArgumentException(
          streamName + " stream is only available on AlpacaStreamEnvironment.PRODUCTION");
    }
    return checkedEnvironment;
  }
}
