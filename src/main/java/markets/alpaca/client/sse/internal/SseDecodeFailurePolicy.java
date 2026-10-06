package markets.alpaca.client.sse.internal;

/** Internal policy for handling a malformed event on an otherwise healthy SSE connection. */
public enum SseDecodeFailurePolicy {
  TERMINATE,
  REPORT_AND_CONTINUE
}
