package markets.alpaca.client;

import java.util.Locale;
import java.util.Objects;

/** Selects between the Alpaca sandbox and production Broker REST API endpoints. */
public enum BrokerApiEnvironment {

  /** {@code broker-api.sandbox.alpaca.markets} — Broker sandbox endpoint. */
  SANDBOX("https://broker-api.sandbox.alpaca.markets"),

  /** {@code broker-api.alpaca.markets} — production Broker endpoint. */
  PRODUCTION("https://broker-api.alpaca.markets");

  private final String baseUrl;

  BrokerApiEnvironment(String baseUrl) {
    this.baseUrl = baseUrl;
  }

  /** Returns the REST API base URL for this environment. */
  public String baseUrl() {
    return baseUrl;
  }

  /**
   * Parses a user-provided environment value.
   *
   * <p>Accepted values are {@code sandbox}, {@code production}, {@code prod}, and {@code live}.
   */
  public static BrokerApiEnvironment from(String value) {
    String normalized = Objects.requireNonNull(value, "value").trim().toLowerCase(Locale.ROOT);
    return switch (normalized) {
      case "sandbox" -> SANDBOX;
      case "production", "prod", "live" -> PRODUCTION;
      default ->
          throw new IllegalArgumentException(
              "Unsupported broker API environment '"
                  + value
                  + "'. Expected sandbox or production.");
    };
  }
}
