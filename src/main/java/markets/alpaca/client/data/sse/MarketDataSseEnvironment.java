package markets.alpaca.client.data.sse;

import java.net.URI;
import java.util.Objects;

/** Endpoint selection for Market Data Server-Sent Events streams. */
public record MarketDataSseEnvironment(String baseUrl) {

  public static final MarketDataSseEnvironment PRODUCTION =
      new MarketDataSseEnvironment("https://stream.data.alpaca.markets");

  public static final MarketDataSseEnvironment SANDBOX =
      new MarketDataSseEnvironment("https://stream.data.sandbox.alpaca.markets");

  public MarketDataSseEnvironment {
    baseUrl = normalize(baseUrl);
  }

  /** Creates an environment for an HTTP(S)-compatible proxy or test endpoint. */
  public static MarketDataSseEnvironment custom(String baseUrl) {
    return new MarketDataSseEnvironment(baseUrl);
  }

  private static String normalize(String value) {
    Objects.requireNonNull(value, "baseUrl must not be null");
    String normalized = value.trim();
    while (normalized.endsWith("/")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    if (normalized.isEmpty()) throw new IllegalArgumentException("baseUrl must not be blank");
    URI uri;
    try {
      uri = URI.create(normalized);
    } catch (IllegalArgumentException failure) {
      throw new IllegalArgumentException("baseUrl must be an absolute HTTP(S) URI", failure);
    }
    String scheme = uri.getScheme();
    if (scheme == null
        || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null) {
      throw new IllegalArgumentException(
          "baseUrl must be an absolute HTTP(S) URI without credentials, query, or fragment");
    }
    return normalized;
  }
}
