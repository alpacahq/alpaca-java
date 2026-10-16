package markets.alpaca.client.data.sse;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import markets.alpaca.client.openapi.data.model.CorporateActionEventType;

/**
 * Filters and history cursors for the Market Data corporate-actions SSE endpoint.
 *
 * <p>ID cursors are uppercase ULIDs. When {@link
 * markets.alpaca.client.sse.AlpacaSseOptions#initialLastEventId()} is configured, the transport
 * sends it in {@code Last-Event-ID}; the endpoint gives that header precedence over {@link
 * #sinceId()}.
 */
public record CorporateActionsSseRequest(
    List<CorporateActionEventType> eventTypes,
    Region region,
    OffsetDateTime since,
    OffsetDateTime until,
    String sinceId,
    String untilId) {

  private static final Pattern EVENT_ID = Pattern.compile("[0-7][0-9A-HJKMNP-TV-Z]{25}");

  public CorporateActionsSseRequest {
    eventTypes = List.copyOf(Objects.requireNonNull(eventTypes, "eventTypes must not be null"));
    if (eventTypes.stream().anyMatch(Objects::isNull)) {
      throw new IllegalArgumentException("eventTypes must not contain null");
    }
    region = Objects.requireNonNull(region, "region must not be null");
    if (until != null && since == null) throw new IllegalArgumentException("until requires since");
    if (untilId != null && sinceId == null) {
      throw new IllegalArgumentException("untilId requires sinceId");
    }
    if ((since != null || until != null) && (sinceId != null || untilId != null)) {
      throw new IllegalArgumentException("date/time and ID cursor families cannot be mixed");
    }
    sinceId = optionalEventId(sinceId, "sinceId");
    untilId = optionalEventId(untilId, "untilId");
    if (since != null && until != null && until.isBefore(since)) {
      throw new IllegalArgumentException("until must not be before since");
    }
    if (sinceId != null && untilId != null && untilId.compareTo(sinceId) < 0) {
      throw new IllegalArgumentException("untilId must not be before sinceId");
    }
  }

  /** Returns a live, unfiltered subscription request. */
  public static CorporateActionsSseRequest live() {
    return builder().build();
  }

  /** Returns an unbounded request starting from an event ID. */
  public static CorporateActionsSseRequest fromEventId(String sinceId) {
    return builder().sinceId(validateEventId(sinceId, "sinceId")).build();
  }

  /** Returns a bounded request spanning two event IDs. */
  public static CorporateActionsSseRequest throughEventId(String sinceId, String untilId) {
    return builder()
        .sinceId(validateEventId(sinceId, "sinceId"))
        .untilId(validateEventId(untilId, "untilId"))
        .build();
  }

  public static Builder builder() {
    return new Builder();
  }

  boolean bounded() {
    return until != null || untilId != null;
  }

  String regionParameter() {
    return region == Region.ALL ? null : region.value;
  }

  private static String optionalEventId(String value, String name) {
    return value == null ? null : validateEventId(value, name);
  }

  static String validateEventId(String value, String name) {
    Objects.requireNonNull(value, name + " must not be null");
    if (!EVENT_ID.matcher(value).matches()) {
      throw new IllegalArgumentException(name + " must be a 26-character uppercase ULID");
    }
    return value;
  }

  /** Region filter accepted by the corporate-actions stream. */
  public enum Region {
    ALL("all"),
    US("us"),
    NON_US("non_us");

    private final String value;

    Region(String value) {
      this.value = value;
    }

    public String value() {
      return value;
    }
  }

  /** Builder for optional corporate-action event filters and cursors. */
  public static final class Builder {
    private List<CorporateActionEventType> eventTypes = List.of();
    private Region region = Region.ALL;
    private OffsetDateTime since;
    private OffsetDateTime until;
    private String sinceId;
    private String untilId;

    private Builder() {}

    public Builder eventTypes(CorporateActionEventType... eventTypes) {
      Objects.requireNonNull(eventTypes, "eventTypes must not be null");
      return eventTypes(Arrays.asList(eventTypes));
    }

    public Builder eventTypes(Collection<CorporateActionEventType> eventTypes) {
      this.eventTypes =
          List.copyOf(Objects.requireNonNull(eventTypes, "eventTypes must not be null"));
      return this;
    }

    public Builder region(Region region) {
      this.region = Objects.requireNonNull(region, "region must not be null");
      return this;
    }

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

    public CorporateActionsSseRequest build() {
      return new CorporateActionsSseRequest(eventTypes, region, since, until, sinceId, untilId);
    }
  }
}
