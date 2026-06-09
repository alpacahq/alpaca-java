package markets.alpaca.client.ws;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Describes which stock data channels and symbols to subscribe to (or unsubscribe from).
 *
 * <p>Use {@link #builder()} to construct an instance. Each field is a set of symbols; an empty set
 * means "no change for this channel". Use {@code "*"} to subscribe to all available symbols.
 * Subscribing to {@code trades} automatically subscribes to corrections and cancel-errors for the
 * same symbols.
 *
 * <pre>{@code
 * var sub = StockSubscription.builder()
 *     .trades("AAPL", "TSLA")
 *     .quotes("AAPL")
 *     .bars("*")
 *     .build();
 * stream.subscribe(sub);
 * }</pre>
 */
public final class StockSubscription {

  private final Set<String> trades;
  private final Set<String> quotes;
  private final Set<String> bars;
  private final Set<String> dailyBars;
  private final Set<String> updatedBars;
  private final Set<String> statuses;
  private final Set<String> lulds;

  private StockSubscription(Builder b) {
    this.trades = Collections.unmodifiableSet(new LinkedHashSet<>(b.trades));
    this.quotes = Collections.unmodifiableSet(new LinkedHashSet<>(b.quotes));
    this.bars = Collections.unmodifiableSet(new LinkedHashSet<>(b.bars));
    this.dailyBars = Collections.unmodifiableSet(new LinkedHashSet<>(b.dailyBars));
    this.updatedBars = Collections.unmodifiableSet(new LinkedHashSet<>(b.updatedBars));
    this.statuses = Collections.unmodifiableSet(new LinkedHashSet<>(b.statuses));
    this.lulds = Collections.unmodifiableSet(new LinkedHashSet<>(b.lulds));
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

  public Set<String> statuses() {
    return statuses;
  }

  public Set<String> lulds() {
    return lulds;
  }

  /** Returns {@code true} if this subscription contains at least one symbol in any channel. */
  public boolean isEmpty() {
    return trades.isEmpty()
        && quotes.isEmpty()
        && bars.isEmpty()
        && dailyBars.isEmpty()
        && updatedBars.isEmpty()
        && statuses.isEmpty()
        && lulds.isEmpty();
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
    private final Set<String> statuses = new LinkedHashSet<>();
    private final Set<String> lulds = new LinkedHashSet<>();

    private Builder() {}

    /** Subscribe to trades (and automatically corrections + cancel-errors) for these symbols. */
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

    public Builder statuses(String... symbols) {
      SubscriptionValidation.addAll(statuses, "symbols", symbols);
      return this;
    }

    public Builder statuses(List<String> symbols) {
      SubscriptionValidation.addAll(statuses, "symbols", symbols);
      return this;
    }

    public Builder lulds(String... symbols) {
      SubscriptionValidation.addAll(lulds, "symbols", symbols);
      return this;
    }

    public Builder lulds(List<String> symbols) {
      SubscriptionValidation.addAll(lulds, "symbols", symbols);
      return this;
    }

    public StockSubscription build() {
      return new StockSubscription(this);
    }
  }
}
