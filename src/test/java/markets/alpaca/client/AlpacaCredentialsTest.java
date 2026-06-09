package markets.alpaca.client;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class AlpacaCredentialsTest {

  // -------------------------------------------------------------------------
  // Construction — null checks
  // -------------------------------------------------------------------------

  @Test
  void constructor_throwsOnNullKeyId() {
    assertThrows(NullPointerException.class, () -> new AlpacaCredentials(null, "secret"));
  }

  @Test
  void constructor_throwsOnNullSecret() {
    assertThrows(NullPointerException.class, () -> new AlpacaCredentials("key", null));
  }

  // -------------------------------------------------------------------------
  // Construction — blank checks
  // -------------------------------------------------------------------------

  @Test
  void constructor_throwsOnEmptyKeyId() {
    assertThrows(IllegalArgumentException.class, () -> new AlpacaCredentials("", "secret"));
  }

  @Test
  void constructor_throwsOnBlankKeyId() {
    assertThrows(IllegalArgumentException.class, () -> new AlpacaCredentials("   ", "secret"));
  }

  @Test
  void constructor_throwsOnEmptySecret() {
    assertThrows(IllegalArgumentException.class, () -> new AlpacaCredentials("key", ""));
  }

  @Test
  void constructor_throwsOnBlankSecret() {
    assertThrows(IllegalArgumentException.class, () -> new AlpacaCredentials("key", "   "));
  }

  // -------------------------------------------------------------------------
  // Accessors
  // -------------------------------------------------------------------------

  @Test
  void accessors_returnConstructorValues() {
    var creds = new AlpacaCredentials("my-key-id", "my-secret-key");
    assertEquals("my-key-id", creds.apiKeyId());
    assertEquals("my-secret-key", creds.apiSecretKey());
  }

  // -------------------------------------------------------------------------
  // Environment factories
  // -------------------------------------------------------------------------

  @Test
  void fromTradingApiEnvironmentVariables_readsTradingCredentialsFromEnvironment() {
    var creds =
        AlpacaCredentials.fromTradingApiEnvironmentVariables(
            env(
                Map.of(
                    AlpacaCredentials.TRADING_API_KEY_ID_ENV,
                    "trading-key",
                    AlpacaCredentials.TRADING_API_SECRET_KEY_ENV,
                    "trading-secret")));

    assertEquals("trading-key", creds.apiKeyId());
    assertEquals("trading-secret", creds.apiSecretKey());
  }

  @Test
  void fromBrokerApiEnvironmentVariables_readsBrokerCredentialsFromEnvironment() {
    var creds =
        AlpacaCredentials.fromBrokerApiEnvironmentVariables(
            env(
                Map.of(
                    AlpacaCredentials.BROKER_API_KEY_ID_ENV,
                    "broker-key",
                    AlpacaCredentials.BROKER_API_SECRET_KEY_ENV,
                    "broker-secret")));

    assertEquals("broker-key", creds.apiKeyId());
    assertEquals("broker-secret", creds.apiSecretKey());
  }

  @Test
  void fromEnvironmentVariables_readsCredentialsFromCustomEnvironmentVariables() {
    var creds =
        AlpacaCredentials.fromEnvironmentVariables(
            "CUSTOM_KEY_ID",
            "CUSTOM_SECRET_KEY",
            env(Map.of("CUSTOM_KEY_ID", "key", "CUSTOM_SECRET_KEY", "secret")));

    assertEquals("key", creds.apiKeyId());
    assertEquals("secret", creds.apiSecretKey());
  }

  @Test
  void fromEnvironmentVariables_throwsWhenCredentialVariablesAreMissing() {
    var thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                AlpacaCredentials.fromEnvironmentVariables("KEY_ID", "SECRET_KEY", env(Map.of())));

    assertTrue(thrown.getMessage().contains("KEY_ID"));
    assertTrue(thrown.getMessage().contains("SECRET_KEY"));
  }

  @Test
  void fromEnvironmentVariables_treatsBlankEnvironmentValuesAsMissing() {
    assertThrows(
        IllegalStateException.class,
        () ->
            AlpacaCredentials.fromEnvironmentVariables(
                "KEY_ID", "SECRET_KEY", env(Map.of("KEY_ID", "   "))));
  }

  @Test
  void fromEnvironmentVariables_rejectsInvalidVariableNames() {
    assertThrows(
        NullPointerException.class,
        () -> AlpacaCredentials.fromEnvironmentVariables(null, "SECRET_KEY", env(Map.of())));
    assertThrows(
        IllegalArgumentException.class,
        () -> AlpacaCredentials.fromEnvironmentVariables("   ", "SECRET_KEY", env(Map.of())));
    assertThrows(
        NullPointerException.class,
        () -> AlpacaCredentials.fromEnvironmentVariables("KEY_ID", null, env(Map.of())));
    assertThrows(
        IllegalArgumentException.class,
        () -> AlpacaCredentials.fromEnvironmentVariables("KEY_ID", "", env(Map.of())));
  }

  @Test
  void fromEnvironmentVariables_rejectsNullEnvironmentResolver() {
    assertThrows(
        NullPointerException.class,
        () -> AlpacaCredentials.fromEnvironmentVariables("KEY_ID", "SECRET_KEY", null));
  }

  // -------------------------------------------------------------------------
  // toString — must not leak the secret
  // -------------------------------------------------------------------------

  @Test
  void toString_doesNotContainSecretKey() {
    var creds = new AlpacaCredentials("my-key-id", "super-secret-value");
    assertFalse(
        creds.toString().contains("super-secret-value"),
        "toString() must not expose the secret key");
  }

  @Test
  void toString_containsKeyId() {
    var creds = new AlpacaCredentials("my-key-id", "super-secret-value");
    assertTrue(
        creds.toString().contains("my-key-id"),
        "toString() should include the key ID for identification");
  }

  @Test
  void toString_containsRedactionMarker() {
    var creds = new AlpacaCredentials("my-key-id", "super-secret-value");
    assertTrue(
        creds.toString().contains("***"), "toString() should use *** as the redaction marker");
  }

  private static Function<String, String> env(Map<String, String> values) {
    return values::get;
  }
}
