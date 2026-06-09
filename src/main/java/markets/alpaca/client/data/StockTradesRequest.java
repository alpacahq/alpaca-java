package markets.alpaca.client.data;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import markets.alpaca.client.openapi.data.model.Sort;
import markets.alpaca.client.openapi.data.model.StockHistoricalFeed;

/**
 * Named parameters for Market Data historical stock-trades endpoints.
 *
 * <p>At least one symbol is required. Unset optional fields are sent as {@code null}, allowing the
 * generated client and API defaults to apply. {@link AlpacaStocks#trades(StockTradesRequest)}
 * supports multiple symbols; {@link AlpacaStocks#tradesForSymbol(StockTradesRequest)} requires
 * exactly one symbol because it calls the generated single-symbol endpoint.
 *
 * @see <a href="https://docs.alpaca.markets/openapi/market-data-api.json">Market Data OpenAPI
 *     spec</a>
 */
public record StockTradesRequest(
    List<String> symbols,
    OffsetDateTime start,
    OffsetDateTime end,
    Integer limit,
    String asof,
    StockHistoricalFeed feed,
    String currency,
    String pageToken,
    Sort sort) {

  public StockTradesRequest {
    symbols = immutableRequiredSymbols(symbols);
    asof = nonBlank(asof, "asof");
    currency = nonBlank(currency, "currency");
    pageToken = nonBlank(pageToken, "pageToken");
    if (limit != null && limit < 1) {
      throw new IllegalArgumentException("limit must be positive");
    }
  }

  /** Returns a builder for historical stock-trades parameters. */
  public static Builder builder() {
    return new Builder();
  }

  /** Returns the requested symbols as an immutable snapshot. */
  @Override
  public List<String> symbols() {
    return List.copyOf(symbols);
  }

  /** Returns the comma-separated symbol parameter expected by the generated API. */
  String symbolsParameter() {
    return String.join(",", symbols);
  }

  /** Returns the single symbol for single-symbol endpoints. */
  String singleSymbol() {
    if (symbols.size() != 1) {
      throw new IllegalArgumentException("request must contain exactly one symbol");
    }
    return symbols.get(0);
  }

  /** Builder for immutable {@link StockTradesRequest} instances. */
  public static final class Builder {
    private final List<String> symbols = new ArrayList<>();
    private OffsetDateTime start;
    private OffsetDateTime end;
    private Integer limit;
    private String asof;
    private StockHistoricalFeed feed;
    private String currency;
    private String pageToken;
    private Sort sort;

    private Builder() {}

    /** Sets the required symbols for the request. */
    public Builder symbols(String... symbols) {
      this.symbols.clear();
      this.symbols.addAll(immutableRequiredSymbols(symbols));
      return this;
    }

    /** Sets the required symbols for the request. */
    public Builder symbols(Collection<String> symbols) {
      this.symbols.clear();
      this.symbols.addAll(immutableRequiredSymbols(symbols));
      return this;
    }

    /** Sets the inclusive start timestamp for historical trades. */
    public Builder start(OffsetDateTime start) {
      this.start = start;
      return this;
    }

    /** Sets the exclusive end timestamp for historical trades. */
    public Builder end(OffsetDateTime end) {
      this.end = end;
      return this;
    }

    /** Limits the number of returned trades. Must be positive when set. */
    public Builder limit(Integer limit) {
      this.limit = limit;
      return this;
    }

    /** Applies symbol mapping as of the given date. */
    public Builder asof(LocalDate asof) {
      this.asof = asof == null ? null : asof.toString();
      return this;
    }

    /** Applies symbol mapping as of a raw API date string. */
    public Builder asof(String asof) {
      this.asof = asof;
      return this;
    }

    /** Requests no symbol mapping by setting the API's {@code asof=-} sentinel. */
    public Builder skipSymbolMapping() {
      this.asof = "-";
      return this;
    }

    /** Selects the historical stock data feed. */
    public Builder feed(StockHistoricalFeed feed) {
      this.feed = feed;
      return this;
    }

    /** Requests prices in a supported local currency when enabled for the account. */
    public Builder currency(String currency) {
      this.currency = currency;
      return this;
    }

    /** Continues from a {@code next_page_token} returned by a previous response. */
    public Builder pageToken(String pageToken) {
      this.pageToken = pageToken;
      return this;
    }

    /** Sets chronological sort order for returned trades. */
    public Builder sort(Sort sort) {
      this.sort = sort;
      return this;
    }

    /** Builds and validates the immutable request. */
    public StockTradesRequest build() {
      return new StockTradesRequest(
          symbols, start, end, limit, asof, feed, currency, pageToken, sort);
    }
  }

  private static String nonBlank(String value, String name) {
    if (value == null) return null;
    if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    return value;
  }

  private static List<String> immutableRequiredSymbols(Collection<String> values) {
    if (values == null || values.isEmpty()) {
      throw new IllegalArgumentException("symbols must contain at least one value");
    }

    List<String> copy = new ArrayList<>();
    for (String value : values) {
      copy.add(nonBlankElement(value, "symbols"));
    }
    return Collections.unmodifiableList(copy);
  }

  private static List<String> immutableRequiredSymbols(String[] values) {
    if (values == null || values.length == 0) {
      throw new IllegalArgumentException("symbols must contain at least one value");
    }

    List<String> copy = new ArrayList<>();
    for (String value : values) {
      copy.add(nonBlankElement(value, "symbols"));
    }
    return Collections.unmodifiableList(copy);
  }

  private static String nonBlankElement(String value, String name) {
    if (value == null) throw new IllegalArgumentException(name + " must not contain null values");
    return nonBlank(value, name);
  }
}
