package markets.alpaca.client.trading;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import markets.alpaca.client.openapi.trading.model.AssetClass;
import markets.alpaca.client.openapi.trading.model.OrderSide;

/**
 * Named parameters for the Trading API {@code GET /v2/orders} endpoint.
 *
 * <p>Unset fields are sent as {@code null}, allowing Alpaca's API defaults to apply. The SDK
 * validates local constraints that are easy to violate accidentally: {@code limit} must be between
 * 1 and 500, {@code beforeOrderId} and {@code afterOrderId} are mutually exclusive, and order-id
 * pagination cannot be combined with {@code after}/{@code until} time filters.
 *
 * @see <a href="https://docs.alpaca.markets/openapi/trading-api.json">Trading OpenAPI spec</a>
 */
public record ListOrdersRequest(
    String status,
    Integer limit,
    String after,
    String until,
    String direction,
    Boolean nested,
    String symbols,
    String side,
    List<String> assetClasses,
    String beforeOrderId,
    String afterOrderId) {

  public ListOrdersRequest {
    status = nonBlank(status, "status");
    after = nonBlank(after, "after");
    until = nonBlank(until, "until");
    direction = nonBlank(direction, "direction");
    symbols = nonBlank(symbols, "symbols");
    side = nonBlank(side, "side");
    beforeOrderId = nonBlank(beforeOrderId, "beforeOrderId");
    afterOrderId = nonBlank(afterOrderId, "afterOrderId");
    assetClasses = immutableStringList(assetClasses, "assetClasses");

    if (limit != null && (limit < 1 || limit > 500)) {
      throw new IllegalArgumentException("limit must be between 1 and 500");
    }
    if (beforeOrderId != null && afterOrderId != null) {
      throw new IllegalArgumentException("beforeOrderId and afterOrderId are mutually exclusive");
    }
    if ((beforeOrderId != null || afterOrderId != null) && (after != null || until != null)) {
      throw new IllegalArgumentException(
          "order-id pagination cannot be combined with after/until time filters");
    }
  }

  /** Returns an empty request that uses the API defaults. */
  public static ListOrdersRequest empty() {
    return builder().build();
  }

  /** Returns a builder for named list-orders parameters. */
  public static Builder builder() {
    return new Builder();
  }

  /** Returns the asset-class filter as an immutable snapshot. */
  @Override
  public List<String> assetClasses() {
    return List.copyOf(assetClasses);
  }

  /** Order status filter accepted by the list-orders endpoint. */
  public enum Status {
    OPEN("open"),
    CLOSED("closed"),
    ALL("all");

    private final String value;

    Status(String value) {
      this.value = value;
    }

    /** Returns the wire value accepted by Alpaca's Trading API. */
    public String value() {
      return value;
    }
  }

  /** Sort direction for list-orders results. */
  public enum Direction {
    ASC("asc"),
    DESC("desc");

    private final String value;

    Direction(String value) {
      this.value = value;
    }

    /** Returns the wire value accepted by Alpaca's Trading API. */
    public String value() {
      return value;
    }
  }

  /** Builder for immutable {@link ListOrdersRequest} instances. */
  public static final class Builder {
    private String status;
    private Integer limit;
    private String after;
    private String until;
    private String direction;
    private Boolean nested;
    private String symbols;
    private String side;
    private final List<String> assetClasses = new ArrayList<>();
    private String beforeOrderId;
    private String afterOrderId;

    private Builder() {}

    /**
     * Filters by the documented order status values: {@code open}, {@code closed}, or {@code all}.
     */
    public Builder status(Status status) {
      this.status = status == null ? null : status.value();
      return this;
    }

    /** Filters by a raw status value supported by the Trading API. */
    public Builder status(String status) {
      this.status = status;
      return this;
    }

    /** Limits the number of returned orders. The Trading API accepts values from 1 through 500. */
    public Builder limit(Integer limit) {
      this.limit = limit;
      return this;
    }

    /**
     * Returns orders submitted after this timestamp. Cannot be combined with order-id pagination.
     */
    public Builder after(OffsetDateTime after) {
      this.after = after == null ? null : after.toString();
      return this;
    }

    /** Returns orders submitted after this raw RFC 3339 timestamp string. */
    public Builder after(String after) {
      this.after = after;
      return this;
    }

    /**
     * Returns orders submitted until this timestamp. Cannot be combined with order-id pagination.
     */
    public Builder until(OffsetDateTime until) {
      this.until = until == null ? null : until.toString();
      return this;
    }

    /** Returns orders submitted until this raw RFC 3339 timestamp string. */
    public Builder until(String until) {
      this.until = until;
      return this;
    }

    /** Sets the result sort direction. */
    public Builder direction(Direction direction) {
      this.direction = direction == null ? null : direction.value();
      return this;
    }

    /** Sets the raw result sort direction accepted by the Trading API. */
    public Builder direction(String direction) {
      this.direction = direction;
      return this;
    }

    /** Includes nested multi-leg order details when supported by the endpoint. */
    public Builder nested(Boolean nested) {
      this.nested = nested;
      return this;
    }

    /**
     * Filters to one or more symbols, encoded as the comma-separated parameter expected by the API.
     */
    public Builder symbols(String... symbols) {
      this.symbols = commaSeparated("symbols", immutableStringList(symbols, "symbols"));
      return this;
    }

    /**
     * Filters to one or more symbols, encoded as the comma-separated parameter expected by the API.
     */
    public Builder symbols(Collection<String> symbols) {
      this.symbols = commaSeparated("symbols", symbols);
      return this;
    }

    /** Sets a raw comma-separated symbol filter string. */
    public Builder symbolsCsv(String symbols) {
      this.symbols = symbols;
      return this;
    }

    /** Filters by order side using the generated Trading model enum. */
    public Builder side(OrderSide side) {
      this.side = side == null ? null : side.getValue();
      return this;
    }

    /** Filters by a raw order side value supported by the Trading API. */
    public Builder side(String side) {
      this.side = side;
      return this;
    }

    /** Filters by asset class using generated Trading model enum values. */
    public Builder assetClasses(AssetClass... assetClasses) {
      this.assetClasses.clear();
      if (assetClasses != null) {
        for (AssetClass assetClass : assetClasses) {
          if (assetClass == null) {
            throw new IllegalArgumentException("assetClasses must not contain null values");
          }
          this.assetClasses.add(assetClass.getValue());
        }
      }
      return this;
    }

    /** Filters by raw asset class values supported by the Trading API. */
    public Builder assetClasses(String... assetClasses) {
      this.assetClasses.clear();
      this.assetClasses.addAll(immutableStringList(assetClasses, "assetClasses"));
      return this;
    }

    /** Filters by raw asset class values supported by the Trading API. */
    public Builder assetClasses(Collection<String> assetClasses) {
      this.assetClasses.clear();
      this.assetClasses.addAll(immutableStringList(assetClasses, "assetClasses"));
      return this;
    }

    /** Starts order-id pagination before the supplied order id. */
    public Builder beforeOrderId(String beforeOrderId) {
      this.beforeOrderId = beforeOrderId;
      return this;
    }

    /** Starts order-id pagination after the supplied order id. */
    public Builder afterOrderId(String afterOrderId) {
      this.afterOrderId = afterOrderId;
      return this;
    }

    /** Builds and validates the immutable request. */
    public ListOrdersRequest build() {
      return new ListOrdersRequest(
          status,
          limit,
          after,
          until,
          direction,
          nested,
          symbols,
          side,
          assetClasses,
          beforeOrderId,
          afterOrderId);
    }
  }

  private static String commaSeparated(String name, Collection<String> values) {
    List<String> copy = immutableStringList(values, name);
    return copy.isEmpty() ? null : String.join(",", copy);
  }

  private static String nonBlank(String value, String name) {
    if (value == null) return null;
    if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    return value;
  }

  private static List<String> immutableStringList(Collection<String> values, String name) {
    if (values == null || values.isEmpty()) return List.of();
    List<String> copy = new ArrayList<>();
    for (String value : values) {
      copy.add(nonBlankElement(value, name));
    }
    return Collections.unmodifiableList(copy);
  }

  private static List<String> immutableStringList(String[] values, String name) {
    Objects.requireNonNull(values, name + " must not be null");
    List<String> copy = new ArrayList<>();
    for (String value : values) {
      copy.add(nonBlankElement(value, name));
    }
    return Collections.unmodifiableList(copy);
  }

  private static String nonBlankElement(String value, String name) {
    if (value == null) throw new IllegalArgumentException(name + " must not contain null values");
    return nonBlank(value, name);
  }
}
