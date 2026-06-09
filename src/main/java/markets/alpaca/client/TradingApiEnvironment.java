package markets.alpaca.client;

import java.util.Locale;
import java.util.Objects;

/** Selects between the Alpaca paper and production Trading REST API endpoints. */
public enum TradingApiEnvironment {

  /** {@code paper-api.alpaca.markets} — paper trading account endpoint. */
  PAPER("https://paper-api.alpaca.markets"),

  /** {@code api.alpaca.markets} — live trading account endpoint. */
  PRODUCTION("https://api.alpaca.markets");

  private final String baseUrl;

  TradingApiEnvironment(String baseUrl) {
    this.baseUrl = baseUrl;
  }

  /** Returns the REST API base URL for this environment. */
  public String baseUrl() {
    return baseUrl;
  }

  /**
   * Parses a user-provided environment value.
   *
   * <p>Accepted values are {@code paper}, {@code production}, {@code prod}, and {@code live}.
   */
  public static TradingApiEnvironment from(String value) {
    String normalized = Objects.requireNonNull(value, "value").trim().toLowerCase(Locale.ROOT);
    return switch (normalized) {
      case "paper" -> PAPER;
      case "production", "prod", "live" -> PRODUCTION;
      default ->
          throw new IllegalArgumentException(
              "Unsupported trading API environment '" + value + "'. Expected paper or production.");
    };
  }
}
