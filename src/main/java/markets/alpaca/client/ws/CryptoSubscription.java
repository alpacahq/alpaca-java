package markets.alpaca.client.ws;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Describes which crypto data channels and pairs to subscribe to (or unsubscribe from).
 *
 * <p>Use {@link #builder()} to construct an instance. Use {@code "*"} to subscribe to all available
 * pairs. The free plan is limited to 10 symbols for orderbooks.
 *
 * <pre>{@code
 * var sub = CryptoSubscription.builder()
 *     .trades("BTC/USD")
 *     .quotes("BTC/USD", "ETH/USD")
 *     .bars("*")
 *     .build();
 * stream.subscribe(sub);
 * }</pre>
 */
public final class CryptoSubscription {

  private final Set<String> trades;
  private final Set<String> quotes;
  private final Set<String> bars;
  private final Set<String> dailyBars;
  private final Set<String> updatedBars;
  private final Set<String> orderbooks;

  private CryptoSubscription(Builder b) {
    this.trades = Collections.unmodifiableSet(new LinkedHashSet<>(b.trades));
    this.quotes = Collections.unmodifiableSet(new LinkedHashSet<>(b.quotes));
    this.bars = Collections.unmodifiableSet(new LinkedHashSet<>(b.bars));
    this.dailyBars = Collections.unmodifiableSet(new LinkedHashSet<>(b.dailyBars));
    this.updatedBars = Collections.unmodifiableSet(new LinkedHashSet<>(b.updatedBars));
    this.orderbooks = Collections.unmodifiableSet(new LinkedHashSet<>(b.orderbooks));
  }

  public Set<String> trades() {
    return trades;
  }

  public Set<String> quotes() {
    return quotes;
  }

  public Set<String> bars() {
    return bars;
  }

  public Set<String> dailyBars() {
    return dailyBars;
  }

  public Set<String> updatedBars() {
    return updatedBars;
  }

  public Set<String> orderbooks() {
    return orderbooks;
  }

  public boolean isEmpty() {
    return trades.isEmpty()
        && quotes.isEmpty()
        && bars.isEmpty()
        && dailyBars.isEmpty()
        && updatedBars.isEmpty()
        && orderbooks.isEmpty();
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private final Set<String> trades = new LinkedHashSet<>();
    private final Set<String> quotes = new LinkedHashSet<>();
    private final Set<String> bars = new LinkedHashSet<>();
    private final Set<String> dailyBars = new LinkedHashSet<>();
    private final Set<String> updatedBars = new LinkedHashSet<>();
    private final Set<String> orderbooks = new LinkedHashSet<>();

    private Builder() {}

    public Builder trades(String... symbols) {
      SubscriptionValidation.addAll(trades, "symbols", symbols);
      return this;
    }

    public Builder trades(List<String> symbols) {
      SubscriptionValidation.addAll(trades, "symbols", symbols);
      return this;
    }

    public Builder quotes(String... symbols) {
      SubscriptionValidation.addAll(quotes, "symbols", symbols);
      return this;
    }

    public Builder quotes(List<String> symbols) {
      SubscriptionValidation.addAll(quotes, "symbols", symbols);
      return this;
    }

    public Builder bars(String... symbols) {
      SubscriptionValidation.addAll(bars, "symbols", symbols);
      return this;
    }

    public Builder bars(List<String> symbols) {
      SubscriptionValidation.addAll(bars, "symbols", symbols);
      return this;
    }

    public Builder dailyBars(String... symbols) {
      SubscriptionValidation.addAll(dailyBars, "symbols", symbols);
      return this;
    }

    public Builder dailyBars(List<String> symbols) {
      SubscriptionValidation.addAll(dailyBars, "symbols", symbols);
      return this;
    }

    public Builder updatedBars(String... symbols) {
      SubscriptionValidation.addAll(updatedBars, "symbols", symbols);
      return this;
    }

    public Builder updatedBars(List<String> symbols) {
      SubscriptionValidation.addAll(updatedBars, "symbols", symbols);
      return this;
    }

    /** Subscribe to full/incremental order book updates (free plan: max 10 symbols). */
    public Builder orderbooks(String... symbols) {
      SubscriptionValidation.addAll(orderbooks, "symbols", symbols);
      return this;
    }

    public Builder orderbooks(List<String> symbols) {
      SubscriptionValidation.addAll(orderbooks, "symbols", symbols);
      return this;
    }

    public CryptoSubscription build() {
      return new CryptoSubscription(this);
    }
  }
}
