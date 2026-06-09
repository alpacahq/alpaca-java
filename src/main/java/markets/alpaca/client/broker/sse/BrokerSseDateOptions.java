package markets.alpaca.client.broker.sse;

import java.time.LocalDate;

/** Optional LocalDate-based filters for Broker SSE endpoints. */
public record BrokerSseDateOptions(
    LocalDate since, LocalDate until, String sinceId, String untilId) {

  public static BrokerSseDateOptions empty() {
    return builder().build();
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private LocalDate since;
    private LocalDate until;
    private String sinceId;
    private String untilId;

    private Builder() {}

    public Builder since(LocalDate since) {
      this.since = since;
      return this;
    }

    public Builder until(LocalDate until) {
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

    public BrokerSseDateOptions build() {
      return new BrokerSseDateOptions(since, until, sinceId, untilId);
    }
  }
}
