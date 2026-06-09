package markets.alpaca.client.broker.sse;

import java.time.LocalDate;

/** Optional legacy LocalDate/numeric-ID/ULID filters plus an event/account identifier. */
public record BrokerSseIdentifiedLegacyDateOptions(
    LocalDate since,
    LocalDate until,
    Integer sinceId,
    Integer untilId,
    String sinceUlid,
    String untilUlid,
    String id) {

  public static BrokerSseIdentifiedLegacyDateOptions empty() {
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
    private String id;

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

    public Builder id(String id) {
      this.id = id;
      return this;
    }

    public BrokerSseIdentifiedLegacyDateOptions build() {
      return new BrokerSseIdentifiedLegacyDateOptions(
          since, until, sinceId, untilId, sinceUlid, untilUlid, id);
    }
  }
}
