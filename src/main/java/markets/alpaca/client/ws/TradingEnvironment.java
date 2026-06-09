package markets.alpaca.client.ws;

/**
 * Selects between the Alpaca live and paper trading WebSocket endpoints.
 *
 * <p>Note: on the paper endpoint, {@code trade_updates} messages are sent as binary frames. The
 * client handles this transparently by decoding them as UTF-8 text.
 */
public enum TradingEnvironment {

  /** {@code api.alpaca.markets} — live brokerage account updates. */
  PRODUCTION("wss://api.alpaca.markets/stream"),

  /** {@code paper-api.alpaca.markets} — paper trading account updates. */
  PAPER("wss://paper-api.alpaca.markets/stream");

  private final String url;

  TradingEnvironment(String url) {
    this.url = url;
  }

  /** Returns the full WebSocket URL for this environment. */
  public String url() {
    return url;
  }
}
