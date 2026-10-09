package markets.alpaca.client.sse;

/** Current lifecycle state of an SSE subscription. */
public enum AlpacaSseState {
  CONNECTING,
  OPEN,
  RECONNECTING,
  CLOSED,
  FAILED
}
