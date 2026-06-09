package markets.alpaca.client.ws;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Describes which symbols to subscribe to news for.
 *
 * <p>Use {@code "*"} to subscribe to news for all symbols.
 *
 * <pre>{@code
 * var sub = NewsSubscription.builder()
 *     .symbols("AAPL", "TSLA")
 *     .build();
 * stream.subscribe(sub);
 *
 * // Subscribe to everything:
 * stream.subscribe(NewsSubscription.builder().symbols("*").build());
 * }</pre>
 */
public final class NewsSubscription {

  private final Set<String> symbols;

  private NewsSubscription(Builder b) {
    this.symbols = Collections.unmodifiableSet(new LinkedHashSet<>(b.symbols));
  }

  public Set<String> symbols() {
    return symbols;
  }

  public boolean isEmpty() {
    return symbols.isEmpty();
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private final Set<String> symbols = new LinkedHashSet<>();

    private Builder() {}

    /** Symbols (stock or crypto) to receive news for. Use {@code "*"} for all. */
    public Builder symbols(String... symbols) {
      SubscriptionValidation.addAll(this.symbols, "symbols", symbols);
      return this;
    }

    public Builder symbols(List<String> symbols) {
      SubscriptionValidation.addAll(this.symbols, "symbols", symbols);
      return this;
    }

    public NewsSubscription build() {
      return new NewsSubscription(this);
    }
  }
}
