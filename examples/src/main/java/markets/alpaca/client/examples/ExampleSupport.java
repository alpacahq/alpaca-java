package markets.alpaca.client.examples;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import markets.alpaca.client.AlpacaCredentials;
import markets.alpaca.client.BrokerApiEnvironment;
import markets.alpaca.client.TradingApiEnvironment;

final class ExampleSupport {
  private static final Properties LOCAL_PROPERTIES = loadLocalProperties();

  private ExampleSupport() {}

  static AlpacaCredentials tradingCredentials() {
    return credentials(
        firstCredential("tradingApiKeyId", AlpacaCredentials.TRADING_API_KEY_ID_ENV),
        firstCredential("tradingApiSecretKey", AlpacaCredentials.TRADING_API_SECRET_KEY_ENV),
        "Set tradingApiKeyId/tradingApiSecretKey in local.properties, "
            + "or APCA_TRADING_KEY_ID/APCA_TRADING_SECRET_KEY.");
  }

  static AlpacaCredentials brokerCredentials() {
    return credentials(
        firstCredential("brokerApiKeyId", AlpacaCredentials.BROKER_API_KEY_ID_ENV),
        firstCredential("brokerApiSecretKey", AlpacaCredentials.BROKER_API_SECRET_KEY_ENV),
        "Set brokerApiKeyId/brokerApiSecretKey in local.properties, "
            + "or APCA_BROKER_KEY_ID/APCA_BROKER_SECRET_KEY.");
  }

  static BrokerApiEnvironment brokerApiEnvironment() {
    return BrokerApiEnvironment.from(
        setting(
            "brokerApiEnvironment",
            "APCA_BROKER_ENVIRONMENT",
            BrokerApiEnvironment.SANDBOX.name()));
  }

  static TradingApiEnvironment tradingApiEnvironment() {
    return TradingApiEnvironment.from(
        setting(
            "tradingApiEnvironment",
            "APCA_TRADING_ENVIRONMENT",
            TradingApiEnvironment.PAPER.name()));
  }

  static String env(String name) {
    String value = System.getProperty(name);
    if (value == null || value.isBlank()) {
      value = System.getenv(name);
    }
    return value == null || value.isBlank() ? null : value;
  }

  static String env(String name, String defaultValue) {
    String value = env(name);
    return value == null ? defaultValue : value;
  }

  static String setting(String localPropertyName, String envName, String defaultValue) {
    String value = systemProperty(envName);
    if (value != null) return value;

    value = systemProperty(localPropertyName);
    if (value != null) return value;

    value = localProperty(localPropertyName);
    if (value != null) return value;

    value = environment(envName);
    return value == null ? defaultValue : value;
  }

  static boolean enabled(String name) {
    return "true".equalsIgnoreCase(env(name, "false"));
  }

  static void printSection(String title) {
    System.out.println();
    System.out.println("== " + title + " ==");
  }

  private static AlpacaCredentials credentials(String keyId, String secretKey, String message) {
    if (keyId == null || secretKey == null) {
      throw new IllegalStateException(message);
    }
    return new AlpacaCredentials(keyId, secretKey);
  }

  private static String firstCredential(String localPropertyName, String envName) {
    String value = systemProperty(envName);
    if (value != null) return value;

    value = localProperty(localPropertyName);
    if (value != null) return value;

    return environment(envName);
  }

  private static String localProperty(String name) {
    String value = LOCAL_PROPERTIES.getProperty(name);
    return value == null || value.isBlank() ? null : value;
  }

  private static String systemProperty(String name) {
    String value = System.getProperty(name);
    return value == null || value.isBlank() ? null : value;
  }

  private static String environment(String name) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? null : value;
  }

  private static Properties loadLocalProperties() {
    var properties = new Properties();
    for (Path candidate : localPropertiesCandidates()) {
      if (!Files.isRegularFile(candidate)) continue;
      try (InputStream in = Files.newInputStream(candidate)) {
        properties.load(in);
        break;
      } catch (IOException e) {
        throw new IllegalStateException("Failed to read " + candidate.toAbsolutePath(), e);
      }
    }
    return properties;
  }

  private static List<Path> localPropertiesCandidates() {
    String configuredPath = System.getProperty("alpaca.examples.localProperties");
    if (configuredPath != null && !configuredPath.isBlank()) {
      return List.of(Path.of(configuredPath));
    }
    return List.of(Path.of("local.properties"), Path.of("../local.properties"));
  }
}
