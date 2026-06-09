package markets.alpaca.client.broker.sse;

import java.time.LocalDate;

/** Optional LocalDate, numeric ID, and ULID filters for legacy Broker SSE endpoints. */
public record BrokerSseLegacyDateOptions(
    LocalDate since,
    LocalDate until,
    Integer sinceId,
    Integer untilId,
    String sinceUlid,
    String untilUlid) {

  public static BrokerSseLegacyDateOptions empty() {
    return builder().build();
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private LocalDate since;
    private LocalDate until;
    private Integer sinceId;
    private Integer untilId;
    private String sinceUlid;
    private String untilUlid;

    private Builder() {}

    public Builder since(LocalDate since) {
      this.since = since;
      return this;
    }

    public Builder until(LocalDate until) {
      this.until = until;
      return this;
    }

    public Builder sinceId(Integer sinceId) {
      this.sinceId = sinceId;
      return this;
    }

    public Builder untilId(Integer untilId) {
      this.untilId = untilId;
      return this;
    }

    public Builder sinceUlid(String sinceUlid) {
      this.sinceUlid = sinceUlid;
      return this;
    }

    public Builder untilUlid(String untilUlid) {
      this.untilUlid = untilUlid;
      return this;
    }

    public BrokerSseLegacyDateOptions build() {
      return new BrokerSseLegacyDateOptions(since, until, sinceId, untilId, sinceUlid, untilUlid);
    }
  }
}
