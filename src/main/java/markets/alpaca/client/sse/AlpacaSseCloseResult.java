package markets.alpaca.client.sse;

/** Normal terminal outcome for an SSE subscription. */
public record AlpacaSseCloseResult(Reason reason, String message) {

  public enum Reason {
    USER_CLOSED,
    HTTP_NO_CONTENT,
    BOUNDED_END,
    REMOTE_END
  }
}
