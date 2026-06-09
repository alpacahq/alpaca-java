package markets.alpaca.client.rest;

import java.util.Objects;
import java.util.OptionalInt;

/** Options for bounded and defensive pagination helpers. */
public final class AlpacaPaginationOptions {

  private static final AlpacaPaginationOptions DEFAULTS =
      new AlpacaPaginationOptions(null, null, RepeatedTokenAction.THROW);

  private final Integer maxPages;
  private final Integer maxItems;
  private final RepeatedTokenAction repeatedTokenAction;

  private AlpacaPaginationOptions(
      Integer maxPages, Integer maxItems, RepeatedTokenAction repeatedTokenAction) {
    this.maxPages = maxPages;
    this.maxItems = maxItems;
    this.repeatedTokenAction = Objects.requireNonNull(repeatedTokenAction);
  }

  /** Returns the default options: unbounded page/item counts with repeated-token detection. */
  public static AlpacaPaginationOptions defaults() {
    return DEFAULTS;
  }

  /** Returns a builder for pagination options. */
  public static Builder builder() {
    return new Builder();
  }

  /** Maximum pages to fetch, or empty when page count is unbounded. */
  public OptionalInt maxPages() {
    return maxPages == null ? OptionalInt.empty() : OptionalInt.of(maxPages);
  }

  /** Maximum items to collect, or empty when item count is unbounded. */
  public OptionalInt maxItems() {
    return maxItems == null ? OptionalInt.empty() : OptionalInt.of(maxItems);
  }

  /** Behavior when an endpoint returns the same {@code next_page_token} more than once. */
  public RepeatedTokenAction repeatedTokenAction() {
    return repeatedTokenAction;
  }

  Integer maxPagesLimit() {
    return maxPages;
  }

  Integer maxItemsLimit() {
    return maxItems;
  }

  /** Behavior when a pagination sequence repeats a returned next-page token. */
  public enum RepeatedTokenAction {
    /** Throw an {@link IllegalStateException}. */
    THROW,

    /** Stop pagination and return the pages/items collected so far. */
    STOP
  }

  /** Builder for {@link AlpacaPaginationOptions}. */
  public static final class Builder {
    private Integer maxPages;
    private Integer maxItems;
    private RepeatedTokenAction repeatedTokenAction = RepeatedTokenAction.THROW;

    private Builder() {}

    /** Sets the maximum number of pages to fetch. */
    public Builder maxPages(int maxPages) {
      this.maxPages = requirePositive(maxPages, "maxPages");
      return this;
    }

    /** Removes the maximum page limit. */
    public Builder unlimitedPages() {
      this.maxPages = null;
      return this;
    }

    /** Sets the maximum number of items to collect. */
    public Builder maxItems(int maxItems) {
      this.maxItems = requirePositive(maxItems, "maxItems");
      return this;
    }

    /** Removes the maximum item limit. */
    public Builder unlimitedItems() {
      this.maxItems = null;
      return this;
    }

    /** Sets how repeated next-page tokens are handled. */
    public Builder repeatedTokenAction(RepeatedTokenAction repeatedTokenAction) {
      this.repeatedTokenAction =
          Objects.requireNonNull(repeatedTokenAction, "repeatedTokenAction must not be null");
      return this;
    }

    /** Builds immutable pagination options. */
    public AlpacaPaginationOptions build() {
      return new AlpacaPaginationOptions(maxPages, maxItems, repeatedTokenAction);
    }

    private static int requirePositive(int value, String name) {
      if (value < 1) {
        throw new IllegalArgumentException(name + " must be greater than zero");
      }
      return value;
    }
  }
}
