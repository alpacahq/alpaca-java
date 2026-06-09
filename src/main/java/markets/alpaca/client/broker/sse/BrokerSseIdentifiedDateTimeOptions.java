package markets.alpaca.client.broker.sse;

import java.time.OffsetDateTime;

/** Optional OffsetDateTime filters plus an event/account identifier. */
public record BrokerSseIdentifiedDateTimeOptions(
    OffsetDateTime since, OffsetDateTime until, String sinceId, String untilId, String id) {

  public static BrokerSseIdentifiedDateTimeOptions empty() {
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
    private String id;

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

    public Builder id(String id) {
      this.id = id;
      return this;
    }

    public BrokerSseIdentifiedDateTimeOptions build() {
      return new BrokerSseIdentifiedDateTimeOptions(since, until, sinceId, untilId, id);
    }
  }
}
