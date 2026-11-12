package markets.alpaca.client.rest;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/** Parsed rate-limit metadata from Alpaca REST response headers. */
public final class AlpacaRateLimit {

  private static final String LIMIT_HEADER = "x-ratelimit-limit";
  private static final String REMAINING_HEADER = "x-ratelimit-remaining";
  private static final String RESET_HEADER = "x-ratelimit-reset";

  private final Long limit;
  private final Long remaining;
  private final Long resetEpochSeconds;

  private AlpacaRateLimit(Long limit, Long remaining, Long resetEpochSeconds) {
    this.limit = limit;
    this.remaining = remaining;
    this.resetEpochSeconds = resetEpochSeconds;
  }

  /** Parses Alpaca's common rate-limit response headers, ignoring missing or invalid values. */
  public static AlpacaRateLimit from(Map<String, List<String>> headers) {
    return new AlpacaRateLimit(
        firstLong(headers, LIMIT_HEADER),
        firstLong(headers, REMAINING_HEADER),
        firstLong(headers, RESET_HEADER));
  }

  /** Maximum number of requests allowed in the current rate-limit window, when reported. */
  public OptionalLong limit() {
    return optionalLong(limit);
  }

  /** Remaining requests in the current rate-limit window, when reported. */
  public OptionalLong remaining() {
    return optionalLong(remaining);
  }

  /** Reset time as epoch seconds, when reported. */
  public OptionalLong resetEpochSeconds() {
    return optionalLong(resetEpochSeconds);
  }

  /** Reset time as an {@link Instant}, when reported. */
  public Optional<Instant> resetAt() {
    return resetEpochSeconds == null
        ? Optional.empty()
        : Optional.of(Instant.ofEpochSecond(resetEpochSeconds));
  }

  private static OptionalLong optionalLong(Long value) {
    return value == null ? OptionalLong.empty() : OptionalLong.of(value);
  }

  private static Long firstLong(Map<String, List<String>> headers, String headerName) {
    return firstHeader(headers, headerName).map(AlpacaRateLimit::parseLongOrNull).orElse(null);
  }

  private static Optional<String> firstHeader(
      Map<String, List<String>> headers, String headerName) {
    if (headers == null || headers.isEmpty()) return Optional.empty();

    String normalizedName = headerName.toLowerCase(Locale.ROOT);
    return headers.entrySet().stream()
        .filter(entry -> entry.getKey() != null)
        .filter(entry -> entry.getKey().toLowerCase(Locale.ROOT).equals(normalizedName))
        .flatMap(
            entry ->
                entry.getValue() == null
                    ? java.util.stream.Stream.empty()
                    : entry.getValue().stream())
        .filter(value -> value != null && !value.isBlank())
        .findFirst();
  }

  private static Long parseLongOrNull(String value) {
    try {
      return Long.parseLong(value.trim());
    } catch (NumberFormatException ignored) {
      return null;
    }
  }
}
