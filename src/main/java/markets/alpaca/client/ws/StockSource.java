package markets.alpaca.client.ws;

/**
 * Data source for the stock pricing stream.
 *
 * <ul>
 *   <li>{@link #IEX} — IEX Exchange data; limited to 30 concurrent trade/quote channels and one
 *       connection per account.
 *   <li>{@link #SIP} — CTA (NYSE) and UTP (Nasdaq) SIP direct feeds; unlimited channels and one
 *       connection per account.
 * </ul>
 */
public enum StockSource {
  IEX("iex"),
  SIP("sip");

  private final String pathSegment;

  StockSource(String pathSegment) {
    this.pathSegment = pathSegment;
  }

  /** Returns the URL path segment used to address this source (e.g. {@code "iex"}). */
  public String pathSegment() {
    return pathSegment;
  }
}
