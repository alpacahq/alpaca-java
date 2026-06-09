package markets.alpaca.client;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class BrokerApiEnvironmentTest {

  @Test
  void sandboxBaseUrl_isBrokerSandboxEndpoint() {
    assertEquals(
        "https://broker-api.sandbox.alpaca.markets", BrokerApiEnvironment.SANDBOX.baseUrl());
  }

  @Test
  void productionBaseUrl_isBrokerProductionEndpoint() {
    assertEquals("https://broker-api.alpaca.markets", BrokerApiEnvironment.PRODUCTION.baseUrl());
  }

  @Test
  void from_acceptsSandbox() {
    assertSame(BrokerApiEnvironment.SANDBOX, BrokerApiEnvironment.from(" sandbox "));
  }

  @Test
  void from_acceptsProductionAliases() {
    assertSame(BrokerApiEnvironment.PRODUCTION, BrokerApiEnvironment.from("production"));
    assertSame(BrokerApiEnvironment.PRODUCTION, BrokerApiEnvironment.from("prod"));
    assertSame(BrokerApiEnvironment.PRODUCTION, BrokerApiEnvironment.from("live"));
  }

  @Test
  void from_rejectsUnknownValue() {
    assertThrows(IllegalArgumentException.class, () -> BrokerApiEnvironment.from("paper"));
  }
}
