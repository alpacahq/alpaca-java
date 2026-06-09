package markets.alpaca.client.broker.sse;

import java.time.OffsetDateTime;

/** Optional OffsetDateTime-based filters for Broker SSE endpoints. */
public record BrokerSseDateTimeOptions(
    OffsetDateTime since, OffsetDateTime until, String sinceId, String untilId) {

  public static BrokerSseDateTimeOptions empty() {
    return builder().build();
  }

  public static Builder builder() {
    return new Builder();
  }

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

    public BrokerSseDateTimeOptions build() {
      return new BrokerSseDateTimeOptions(since, until, sinceId, untilId);
    }
  }
}
