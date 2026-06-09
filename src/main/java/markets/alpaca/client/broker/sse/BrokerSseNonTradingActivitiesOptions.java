package markets.alpaca.client.broker.sse;

import java.time.LocalDate;
import java.util.UUID;

/** Optional filters for Broker non-trading-activity SSE events. */
public record BrokerSseNonTradingActivitiesOptions(
    String id,
    LocalDate since,
    LocalDate until,
    Integer sinceId,
    Integer untilId,
    String sinceUlid,
    String untilUlid,
    Boolean includePreprocessing,
    UUID groupId) {

  public static BrokerSseNonTradingActivitiesOptions empty() {
    return builder().build();
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private String id;
    private LocalDate since;
    private LocalDate until;
    private Integer sinceId;
    private Integer untilId;
    private String sinceUlid;
    private String untilUlid;
    private Boolean includePreprocessing;
    private UUID groupId;

    private Builder() {}

    public Builder id(String id) {
      this.id = id;
      return this;
    }

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

    public Builder includePreprocessing(Boolean includePreprocessing) {
      this.includePreprocessing = includePreprocessing;
      return this;
    }

    public Builder groupId(UUID groupId) {
      this.groupId = groupId;
      return this;
    }

    public BrokerSseNonTradingActivitiesOptions build() {
      return new BrokerSseNonTradingActivitiesOptions(
          id, since, until, sinceId, untilId, sinceUlid, untilUlid, includePreprocessing, groupId);
    }
  }
}
