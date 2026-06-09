package markets.alpaca.client.ws;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link CryptoSubscription} — builder DSL, list overloads, immutability, and {@link
 * CryptoSubscription#isEmpty()}.
 */
class CryptoSubscriptionTest {

  @Test
  void builder_defaultProducesEmptySubscription() {
    var sub = CryptoSubscription.builder().build();

    assertTrue(sub.isEmpty());
    assertTrue(sub.trades().isEmpty());
    assertTrue(sub.quotes().isEmpty());
    assertTrue(sub.bars().isEmpty());
    assertTrue(sub.dailyBars().isEmpty());
    assertTrue(sub.updatedBars().isEmpty());
    assertTrue(sub.orderbooks().isEmpty());
  }

  @Test
  void builder_varargs_populatesAllChannels() {
    var sub =
        CryptoSubscription.builder()
            .trades("BTC/USD")
            .quotes("ETH/USD")
            .bars("*")
            .dailyBars("SOL/USD")
            .updatedBars("DOGE/USD")
            .orderbooks("AVAX/USD")
            .build();

    assertFalse(sub.isEmpty());
    assertEquals(Set.of("BTC/USD"), sub.trades());
    assertEquals(Set.of("ETH/USD"), sub.quotes());
    assertEquals(Set.of("*"), sub.bars());
    assertEquals(Set.of("SOL/USD"), sub.dailyBars());
    assertEquals(Set.of("DOGE/USD"), sub.updatedBars());
    assertEquals(Set.of("AVAX/USD"), sub.orderbooks());
  }

  @Test
  void isEmpty_falseForEachIndividualChannel() {
    assertFalse(CryptoSubscription.builder().trades("BTC/USD").build().isEmpty());
    assertFalse(CryptoSubscription.builder().quotes("BTC/USD").build().isEmpty());
    assertFalse(CryptoSubscription.builder().bars("BTC/USD").build().isEmpty());
    assertFalse(CryptoSubscription.builder().dailyBars("BTC/USD").build().isEmpty());
    assertFalse(CryptoSubscription.builder().updatedBars("BTC/USD").build().isEmpty());
    assertFalse(CryptoSubscription.builder().orderbooks("BTC/USD").build().isEmpty());
  }

  @Test
  void builder_listOverloads_populateAllChannels() {
    var sub =
        CryptoSubscription.builder()
            .trades(List.of("BTC/USD", "ETH/USD"))
            .quotes(List.of("SOL/USD"))
            .bars(List.of("*"))
            .dailyBars(List.of("DOGE/USD"))
            .updatedBars(List.of("AVAX/USD"))
            .orderbooks(List.of("UNI/USD"))
            .build();

    assertEquals(Set.of("BTC/USD", "ETH/USD"), sub.trades());
    assertEquals(Set.of("SOL/USD"), sub.quotes());
    assertEquals(Set.of("*"), sub.bars());
    assertEquals(Set.of("DOGE/USD"), sub.dailyBars());
    assertEquals(Set.of("AVAX/USD"), sub.updatedBars());
    assertEquals(Set.of("UNI/USD"), sub.orderbooks());
  }

  @Test
  void builder_deduplicatesSymbolsAndPreservesInsertionOrder() {
    var sub = CryptoSubscription.builder().trades("BTC/USD", "ETH/USD", "BTC/USD").build();

    assertEquals(List.of("BTC/USD", "ETH/USD"), List.copyOf(sub.trades()));
  }

  @Test
  void returnedSets_areUnmodifiable() {
    var sub = CryptoSubscription.builder().orderbooks("BTC/USD").build();

    assertThrows(UnsupportedOperationException.class, () -> sub.orderbooks().add("ETH/USD"));
  }

  @Test
  void builder_rejectsNullSymbols() {
    assertThrows(
        IllegalArgumentException.class, () -> CryptoSubscription.builder().trades("BTC/USD", null));
    assertThrows(
        IllegalArgumentException.class,
        () -> CryptoSubscription.builder().quotes(java.util.Arrays.asList("BTC/USD", null)));
  }

  @Test
  void builder_rejectsBlankSymbols() {
    assertThrows(IllegalArgumentException.class, () -> CryptoSubscription.builder().bars(""));
    assertThrows(
        IllegalArgumentException.class,
        () -> CryptoSubscription.builder().orderbooks(List.of("BTC/USD", "   ")));
  }
}
