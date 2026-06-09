package markets.alpaca.client;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TradingApiEnvironmentTest {

  @Test
  void paperBaseUrl_isPaperTradingEndpoint() {
    assertEquals("https://paper-api.alpaca.markets", TradingApiEnvironment.PAPER.baseUrl());
  }

  @Test
  void productionBaseUrl_isLiveTradingEndpoint() {
    assertEquals("https://api.alpaca.markets", TradingApiEnvironment.PRODUCTION.baseUrl());
  }

  @Test
  void from_acceptsPaper() {
    assertSame(TradingApiEnvironment.PAPER, TradingApiEnvironment.from(" paper "));
  }

  @Test
  void from_acceptsProductionAliases() {
    assertSame(TradingApiEnvironment.PRODUCTION, TradingApiEnvironment.from("production"));
    assertSame(TradingApiEnvironment.PRODUCTION, TradingApiEnvironment.from("prod"));
    assertSame(TradingApiEnvironment.PRODUCTION, TradingApiEnvironment.from("live"));
  }

  @Test
  void from_rejectsUnknownValue() {
    assertThrows(IllegalArgumentException.class, () -> TradingApiEnvironment.from("sandbox"));
  }
}
