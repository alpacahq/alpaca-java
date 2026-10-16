package markets.alpaca.client.sse;

/** Base class for terminal SSE transport, protocol, and decoding failures. */
public class AlpacaSseException extends RuntimeException {

  public AlpacaSseException(String message) {
    super(message);
  }

  public AlpacaSseException(String message, Throwable cause) {
    super(message, cause);
  }
}
