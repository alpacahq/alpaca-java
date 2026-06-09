package markets.alpaca.client.rest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** A single paginated REST response page with HTTP metadata. */
public final class AlpacaPage<T> {

  private final T body;
  private final int statusCode;
  private final Map<String, List<String>> headers;
  private final String nextPageToken;
  private final AlpacaRateLimit rateLimit;

  public AlpacaPage(
      T body, int statusCode, Map<String, List<String>> headers, String nextPageToken) {
    this.body = body;
    this.statusCode = statusCode;
    this.headers = copyHeaders(headers);
    this.nextPageToken = normalizePageToken(nextPageToken);
    this.rateLimit = AlpacaRateLimit.from(this.headers);
  }

  /** Deserialized response body for this page. */
  public T body() {
    return body;
  }

  /** HTTP status code returned with this page. */
  public int statusCode() {
    return statusCode;
  }

  /** HTTP response headers for this page. */
  public Map<String, List<String>> headers() {
    return copyHeaders(headers);
  }

  /** First value for a header name, matched case-insensitively. */
  public Optional<String> header(String name) {
    return headers(name).stream().findFirst();
  }

  /** Values for a header name, matched case-insensitively. */
  public List<String> headers(String name) {
    if (name == null || headers.isEmpty()) return List.of();

    String normalizedName = name.toLowerCase(Locale.ROOT);
    List<String> values = new ArrayList<>();
    headers.forEach(
        (headerName, headerValues) -> {
          if (headerName != null && headerName.toLowerCase(Locale.ROOT).equals(normalizedName)) {
            values.addAll(headerValues);
          }
        });
    return Collections.unmodifiableList(values);
  }

  /** Next page token, or {@code null} when this is the final page. */
  public String nextPageToken() {
    return nextPageToken;
  }

  /** Next page token as an {@link Optional}. */
  public Optional<String> nextPageTokenOptional() {
    return Optional.ofNullable(nextPageToken);
  }

  /** Returns {@code true} when another page is available. */
  public boolean hasNextPage() {
    return nextPageToken != null;
  }

  /** Parsed rate-limit metadata from this page's HTTP headers. */
  public AlpacaRateLimit rateLimit() {
    return rateLimit;
  }

  static String normalizePageToken(String pageToken) {
    return pageToken == null || pageToken.isBlank() ? null : pageToken;
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
