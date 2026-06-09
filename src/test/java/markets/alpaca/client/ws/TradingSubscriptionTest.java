package markets.alpaca.client.ws;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TradingSubscriptionTest {

  @Test
  void tradeUpdatesConstant_containsTradeUpdatesStream() {
    assertEquals(Set.of("trade_updates"), TradingSubscription.TRADE_UPDATES.streams());
    assertFalse(TradingSubscription.TRADE_UPDATES.isEmpty());
  }

  @Test
  void setConstructor_deduplicatesAndPreservesIterationOrder() {
    var sub =
        new TradingSubscription(
            new java.util.LinkedHashSet<>(List.of("trade_updates", "trade_updates")));

    assertEquals(List.of("trade_updates"), List.copyOf(sub.streams()));
  }

  @Test
  void listConstructor_populatesStreams() {
    var sub = new TradingSubscription(List.of("trade_updates"));

    assertEquals(Set.of("trade_updates"), sub.streams());
  }

  @Test
  void emptySet_isEmpty() {
    assertTrue(new TradingSubscription(Set.of()).isEmpty());
  }

  @Test
  void returnedStreams_areUnmodifiable() {
    var sub = new TradingSubscription(Set.of("trade_updates"));

    assertThrows(UnsupportedOperationException.class, () -> sub.streams().add("other"));
  }

  @Test
  void constructors_rejectNullStreams() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TradingSubscription(
                new java.util.LinkedHashSet<>(java.util.Arrays.asList("trade_updates", null))));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TradingSubscription(java.util.Arrays.asList("trade_updates", null)));
  }

  @Test
  void constructors_rejectBlankStreams() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new TradingSubscription(new java.util.LinkedHashSet<>(List.of("trade_updates", ""))));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TradingSubscription(List.of("trade_updates", "   ")));
  }
}
