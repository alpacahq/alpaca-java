package markets.alpaca.client.sse;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Terminal or retryable non-SSE HTTP response, captured before its body was closed. */
public final class AlpacaSseHttpException extends AlpacaSseException {

  private final int statusCode;
  private final String requestId;
  private final Map<String, List<String>> headers;
  private final Duration retryAfter;
  private final String responseBody;

  public AlpacaSseHttpException(
      int statusCode,
      String requestId,
      Map<String, List<String>> headers,
      Duration retryAfter,
      String responseBody) {
    super("SSE request failed with HTTP " + statusCode);
    this.statusCode = statusCode;
    this.requestId = requestId;
    this.headers = Map.copyOf(headers);
    this.retryAfter = retryAfter;
    this.responseBody = responseBody;
  }

  public int statusCode() {
    return statusCode;
  }

  public String requestId() {
    return requestId;
  }

  public Map<String, List<String>> headers() {
    return headers;
  }

  public Duration retryAfter() {
    return retryAfter;
  }

  public String responseBody() {
    return responseBody;
  }
}
