package markets.alpaca.client.broker.sse;

import java.time.OffsetDateTime;

/**
 * Optional filters for the Broker rebalancer SSE stream.
 *
 * <p>{@code lastEventId} is sent as the {@code Last-Event-Id} header. The Broker API uses that
 * header as {@code since_id} and rejects it together with {@code since}.
 */
public record BrokerSseRebalancerOptions(
    OffsetDateTime since,
    OffsetDateTime until,
    String sinceId,
    String untilId,
    String lastEventId) {

  public static BrokerSseRebalancerOptions empty() {
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
    private String lastEventId;

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

    public Builder lastEventId(String lastEventId) {
      this.lastEventId = lastEventId;
      return this;
    }

    public BrokerSseRebalancerOptions build() {
      return new BrokerSseRebalancerOptions(since, until, sinceId, untilId, lastEventId);
    }
  }
}
