package markets.alpaca.client.sse;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable metadata for one accepted SSE connection. */
public record AlpacaSseConnectionInfo(URI uri, int statusCode, Map<String, List<String>> headers) {

  public AlpacaSseConnectionInfo {
    Objects.requireNonNull(uri, "uri must not be null");
    Objects.requireNonNull(headers, "headers must not be null");
    var copiedHeaders = new LinkedHashMap<String, List<String>>();
    headers.forEach(
        (name, values) ->
            copiedHeaders.put(
                Objects.requireNonNull(name, "header name must not be null"),
                List.copyOf(Objects.requireNonNull(values, "header values must not be null"))));
    headers = Collections.unmodifiableMap(copiedHeaders);
  }

  /** Returns the last value for a response header using case-insensitive name matching. */
  public Optional<String> header(String name) {
    Objects.requireNonNull(name, "name must not be null");
    return headers.entrySet().stream()
        .filter(entry -> entry.getKey().equalsIgnoreCase(name))
        .map(Map.Entry::getValue)
        .filter(values -> !values.isEmpty())
        .map(values -> values.get(values.size() - 1))
        .findFirst();
  }
}
