package markets.alpaca.client.ws;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The complete set of trading streams to subscribe to.
 *
 * <p>The {@code streams} list is authoritative — sending a new listen request replaces the current
 * subscription entirely. To add a stream, include all currently active streams plus the new one.
 *
 * <p>Currently the only supported stream is {@code trade_updates}.
 */
public record TradingSubscription(Set<String> streams) {

  /** Pre-built subscription for trade updates only (the most common case). */
  public static final TradingSubscription TRADE_UPDATES =
      new TradingSubscription(Set.of("trade_updates"));

  public TradingSubscription {
    streams = SubscriptionValidation.immutableSet("streams", streams);
  }

  public TradingSubscription(List<String> streams) {
    this(SubscriptionValidation.immutableSet("streams", streams));
  }

  /** The set of stream names to subscribe to (e.g. {@code "trade_updates"}). */
  public Set<String> streams() {
    return Collections.unmodifiableSet(new LinkedHashSet<>(streams));
  }

  public boolean isEmpty() {
    return streams.isEmpty();
  }
}
