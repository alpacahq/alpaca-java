package markets.alpaca.client.rest;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Result metadata for a generated REST API call completed through {@link AlpacaFutures}.
 *
 * @param body deserialized response body; may be {@code null} for endpoints with no response body
 * @param statusCode HTTP status code returned by the server
 * @param headers HTTP response headers
 */
public record AlpacaApiResponse<T>(T body, int statusCode, Map<String, List<String>> headers) {

  public AlpacaApiResponse {
    headers = copyHeaders(headers);
  }

  /** HTTP response headers. */
  @Override
  public Map<String, List<String>> headers() {
    return copyHeaders(headers);
  }

  private static Map<String, List<String>> copyHeaders(Map<String, List<String>> headers) {
    if (headers == null || headers.isEmpty()) return Map.of();

    Map<String, List<String>> copy = new LinkedHashMap<>();
    headers.forEach(
        (name, values) -> {
          List<String> valueCopy = values == null ? List.of() : List.copyOf(values);
          copy.put(name, valueCopy);
        });
    return Collections.unmodifiableMap(copy);
  }
}
