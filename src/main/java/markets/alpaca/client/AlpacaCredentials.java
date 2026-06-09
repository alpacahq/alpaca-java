package markets.alpaca.client;

import java.util.Objects;
import java.util.function.Function;

/**
 * Alpaca API credentials (key ID + secret key).
 *
 * <ul>
 *   <li><b>Broker API</b> — sent as HTTP Basic Auth (key ID = username, secret key = password).
 *   <li><b>Trading API</b> — sent as {@code APCA-API-KEY-ID} and {@code APCA-API-SECRET-KEY}
 *       headers.
 *   <li><b>Data API</b> — same header scheme as Trading API.
 * </ul>
 *
 * Use {@code AlpacaClientFactory} to obtain pre-configured API client instances.
 */
public record AlpacaCredentials(String apiKeyId, String apiSecretKey) {

  /**
   * Environment variable used by {@link #fromTradingApiEnvironmentVariables()} for trading/data API
   * key IDs.
   */
  public static final String TRADING_API_KEY_ID_ENV = "APCA_TRADING_KEY_ID";

  /**
   * Environment variable used by {@link #fromTradingApiEnvironmentVariables()} for trading/data API
   * secret keys.
   */
  public static final String TRADING_API_SECRET_KEY_ENV = "APCA_TRADING_SECRET_KEY";

  /**
   * Environment variable used by {@link #fromBrokerApiEnvironmentVariables()} for Broker API key
   * IDs.
   */
  public static final String BROKER_API_KEY_ID_ENV = "APCA_BROKER_KEY_ID";

  /**
   * Environment variable used by {@link #fromBrokerApiEnvironmentVariables()} for Broker API secret
   * keys.
   */
  public static final String BROKER_API_SECRET_KEY_ENV = "APCA_BROKER_SECRET_KEY";

  public AlpacaCredentials {
    Objects.requireNonNull(apiKeyId, "apiKeyId must not be null");
    Objects.requireNonNull(apiSecretKey, "apiSecretKey must not be null");
    if (apiKeyId.isBlank()) throw new IllegalArgumentException("apiKeyId must not be blank");
    if (apiSecretKey.isBlank())
      throw new IllegalArgumentException("apiSecretKey must not be blank");
  }

  /**
   * Reads trading/data API credentials from {@value #TRADING_API_KEY_ID_ENV} and {@value
   * #TRADING_API_SECRET_KEY_ENV}.
   *
   * <p>Use {@link #fromBrokerApiEnvironmentVariables()} for Broker API credentials.
   *
   * @throws IllegalStateException if either environment variable is missing or blank
   */
  public static AlpacaCredentials fromTradingApiEnvironmentVariables() {
    return fromTradingApiEnvironmentVariables(System::getenv);
  }

  /**
   * Reads Broker API credentials from {@value #BROKER_API_KEY_ID_ENV} and {@value
   * #BROKER_API_SECRET_KEY_ENV}.
   *
   * @throws IllegalStateException if either environment variable is missing or blank
   */
  public static AlpacaCredentials fromBrokerApiEnvironmentVariables() {
    return fromBrokerApiEnvironmentVariables(System::getenv);
  }

  /**
   * Reads credentials from caller-specified environment variables.
   *
   * @param apiKeyIdEnvironmentVariable environment variable that contains the API key ID
   * @param apiSecretKeyEnvironmentVariable environment variable that contains the API secret key
   * @throws IllegalArgumentException if an environment variable name is blank
   * @throws IllegalStateException if either environment variable is missing or blank
   */
  public static AlpacaCredentials fromEnvironmentVariables(
      String apiKeyIdEnvironmentVariable, String apiSecretKeyEnvironmentVariable) {
    return fromEnvironmentVariables(
        apiKeyIdEnvironmentVariable, apiSecretKeyEnvironmentVariable, System::getenv);
  }

  static AlpacaCredentials fromTradingApiEnvironmentVariables(
      Function<String, String> environment) {
    return fromEnvironmentVariables(
        TRADING_API_KEY_ID_ENV, TRADING_API_SECRET_KEY_ENV, environment);
  }

  static AlpacaCredentials fromBrokerApiEnvironmentVariables(Function<String, String> environment) {
    return fromEnvironmentVariables(BROKER_API_KEY_ID_ENV, BROKER_API_SECRET_KEY_ENV, environment);
  }

  static AlpacaCredentials fromEnvironmentVariables(
      String apiKeyIdEnvironmentVariable,
      String apiSecretKeyEnvironmentVariable,
      Function<String, String> environment) {
    validateEnvironmentVariableName(apiKeyIdEnvironmentVariable, "apiKeyIdEnvironmentVariable");
    validateEnvironmentVariableName(
        apiSecretKeyEnvironmentVariable, "apiSecretKeyEnvironmentVariable");
    Objects.requireNonNull(environment, "environment must not be null");

    String apiKeyId = nonBlank(environment.apply(apiKeyIdEnvironmentVariable));
    String apiSecretKey = nonBlank(environment.apply(apiSecretKeyEnvironmentVariable));
    if (apiKeyId == null || apiSecretKey == null) {
      throw new IllegalStateException(
          "Missing Alpaca credentials in environment. Set "
              + apiKeyIdEnvironmentVariable
              + " and "
              + apiSecretKeyEnvironmentVariable
              + ".");
    }
    return new AlpacaCredentials(apiKeyId, apiSecretKey);
  }

  private static void validateEnvironmentVariableName(String value, String name) {
    Objects.requireNonNull(value, name + " must not be null");
    if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
  }

  private static String nonBlank(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  @Override
  public String toString() {
    return "AlpacaCredentials[apiKeyId=" + apiKeyId + ", apiSecretKey=***]";
  }
}
