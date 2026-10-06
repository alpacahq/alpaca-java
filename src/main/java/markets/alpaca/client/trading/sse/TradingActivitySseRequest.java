package markets.alpaca.client.trading.sse;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Validated date or uppercase-ULID cursor filters for the Trading account-activity SSE endpoint.
 */
public record TradingActivitySseRequest(
    OffsetDateTime since, OffsetDateTime until, String sinceId, String untilId) {

  private static final Pattern EVENT_ID = Pattern.compile("[0-7][0-9A-HJKMNP-TV-Z]{25}");

  public TradingActivitySseRequest {
    if (until != null && since == null) {
      throw new IllegalArgumentException("until requires since");
    }
    if (untilId != null && sinceId == null) {
      throw new IllegalArgumentException("untilId requires sinceId");
    }
    if ((since != null || until != null) && (sinceId != null || untilId != null)) {
      throw new IllegalArgumentException("date/time and ID cursor families cannot be mixed");
    }
    if (sinceId != null) validateEventId(sinceId, "sinceId");
    if (untilId != null) validateEventId(untilId, "untilId");
    if (since != null && until != null && until.isBefore(since)) {
      throw new IllegalArgumentException("until must not be before since");
    }
    if (sinceId != null && untilId != null && untilId.compareTo(sinceId) < 0) {
      throw new IllegalArgumentException("untilId must not be before sinceId");
    }
  }

  public static TradingActivitySseRequest live() {
    return builder().build();
  }

  /** Returns an unbounded request starting from an event ID. */
  public static TradingActivitySseRequest fromEventId(String sinceId) {
    return new TradingActivitySseRequest(null, null, validateEventId(sinceId, "sinceId"), null);
  }

  /** Returns a bounded request spanning two event IDs. */
  public static TradingActivitySseRequest throughEventId(String sinceId, String untilId) {
    return new TradingActivitySseRequest(
        null, null, validateEventId(sinceId, "sinceId"), validateEventId(untilId, "untilId"));
  }

  public static Builder builder() {
    return new Builder();
  }

  boolean bounded() {
    return until != null || untilId != null;
  }

  static String validateEventId(String value, String name) {
    Objects.requireNonNull(value, name + " must not be null");
    if (!EVENT_ID.matcher(value).matches()) {
      throw new IllegalArgumentException(name + " must be a 26-character uppercase ULID");
    }
    return value;
  }

  /** Builder for optional Trading activity cursors. */
  public static final class Builder {
    private OffsetDateTime since;
    private OffsetDateTime until;
    private String sinceId;
    private String untilId;

    private Builder() {}

    public Builder since(OffsetDateTime since) {
      this.since = since;
      return this;
    }

    public Builder until(OffsetDateTime until) {
      this.until = until;
      return this;
    }

    public Builder sinceId(String sinceId) {
      this.sinceId = sinceId;
      return this;
    }

    public Builder untilId(String untilId) {
      this.untilId = untilId;
      return this;
    }

    public TradingActivitySseRequest build() {
      return new TradingActivitySseRequest(since, until, sinceId, untilId);
    }
  }
}
