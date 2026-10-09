package markets.alpaca.client.sse;

/** The configured callback executor rejected a lifecycle or event callback. */
public final class AlpacaSseCallbackException extends AlpacaSseException {

  public AlpacaSseCallbackException(String message, Throwable cause) {
    super(message, cause);
  }
}
