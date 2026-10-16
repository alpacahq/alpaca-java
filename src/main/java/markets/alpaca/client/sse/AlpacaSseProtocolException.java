package markets.alpaca.client.sse;

/** Malformed SSE framing, invalid response metadata, or a configured resource-limit violation. */
public final class AlpacaSseProtocolException extends AlpacaSseException {

  public AlpacaSseProtocolException(String message) {
    super(message);
  }

  public AlpacaSseProtocolException(String message, Throwable cause) {
    super(message, cause);
  }
}
