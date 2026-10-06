package markets.alpaca.client.sse.internal;

/** Converts one complete SSE data field into an API-specific generated model. */
@FunctionalInterface
public interface SseDecoder<T> {

  T decode(String data) throws Exception;
}
