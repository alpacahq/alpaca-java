package markets.alpaca.client.sse;

/**
 * A complete SSE message could not be converted to its generated event model.
 *
 * <p>Domain SSE clients report this recoverable per-event failure through {@link
 * AlpacaSseListener#onEventFailure(AlpacaSseDeserializationException)}.
 */
public final class AlpacaSseDeserializationException extends AlpacaSseException {

  private final String eventId;
  private final String eventType;

  public AlpacaSseDeserializationException(String message, Throwable cause) {
    this(message, cause, null, null);
  }

  public AlpacaSseDeserializationException(
      String message, Throwable cause, String eventId, String eventType) {
    super(message, cause);
    this.eventId = eventId;
    this.eventType = eventType;
  }

  /** Returns the effective SSE event ID, or {@code null} when none was present. */
  public String eventId() {
    return eventId;
  }

  /** Returns the effective SSE event type, or {@code null} when none was available. */
  public String eventType() {
    return eventType;
  }
}
