package markets.alpaca.client.ws;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link StockSubscription} — builder DSL, field accessors, and {@link
 * StockSubscription#isEmpty()}.
 */
class StockSubscriptionTest {

  @Test
  void builder_defaultProducesEmptySubscription() {
    var sub = StockSubscription.builder().build();
    assertTrue(sub.isEmpty(), "fresh builder with no calls must be empty");
  }

  @Test
  void isEmpty_falseAfterAddingTrades() {
    var sub = StockSubscription.builder().trades("AAPL").build();
    assertFalse(sub.isEmpty());
  }

  @Test
  void isEmpty_falseAfterAddingQuotes() {
    assertFalse(StockSubscription.builder().quotes("MSFT").build().isEmpty());
  }

  @Test
  void isEmpty_falseAfterAddingBars() {
    assertFalse(StockSubscription.builder().bars("*").build().isEmpty());
  }

  @Test
  void isEmpty_falseAfterAddingStatuses() {
    assertFalse(StockSubscription.builder().statuses("TSLA").build().isEmpty());
  }

  @Test
  void isEmpty_falseAfterAddingDailyBarsUpdatedBarsOrLulds() {
    assertFalse(StockSubscription.builder().dailyBars("AAPL").build().isEmpty());
    assertFalse(StockSubscription.builder().updatedBars("AAPL").build().isEmpty());
    assertFalse(StockSubscription.builder().lulds("AAPL").build().isEmpty());
  }

  @Test
  void trades_varargs_populatesTradesSet() {
    var sub = StockSubscription.builder().trades("AAPL", "TSLA").build();
    assertEquals(java.util.Set.of("AAPL", "TSLA"), sub.trades());
    assertTrue(sub.quotes().isEmpty());
  }

  @Test
  void trades_listOverload_populatesTradesSet() {
    var sub = StockSubscription.builder().trades(List.of("GOOG", "AMZN")).build();
    assertTrue(sub.trades().contains("GOOG"));
    assertTrue(sub.trades().contains("AMZN"));
  }

  @Test
  void listOverloads_populateAllChannels() {
    var sub =
        StockSubscription.builder()
            .quotes(List.of("AAPL"))
            .bars(List.of("MSFT"))
            .dailyBars(List.of("SPY"))
            .updatedBars(List.of("QQQ"))
            .statuses(List.of("TSLA"))
            .lulds(List.of("NVDA"))
            .build();

    assertEquals(java.util.Set.of("AAPL"), sub.quotes());
    assertEquals(java.util.Set.of("MSFT"), sub.bars());
    assertEquals(java.util.Set.of("SPY"), sub.dailyBars());
    assertEquals(java.util.Set.of("QQQ"), sub.updatedBars());
    assertEquals(java.util.Set.of("TSLA"), sub.statuses());
    assertEquals(java.util.Set.of("NVDA"), sub.lulds());
  }

  @Test
  void bars_wildcard_storesAsterisk() {
    var sub = StockSubscription.builder().bars("*").build();
    assertEquals(java.util.Set.of("*"), sub.bars());
  }

  @Test
  void dailyBars_and_updatedBars_stored() {
    var sub = StockSubscription.builder().dailyBars("AAPL").updatedBars("MSFT").build();
    assertEquals(java.util.Set.of("AAPL"), sub.dailyBars());
    assertEquals(java.util.Set.of("MSFT"), sub.updatedBars());
  }

  @Test
  void lulds_stored() {
    var sub = StockSubscription.builder().lulds("AAPL").build();
    assertEquals(java.util.Set.of("AAPL"), sub.lulds());
  }

  @Test
  void builder_chained_accumulatesAllChannels() {
    var sub =
        StockSubscription.builder()
            .trades("AAPL")
            .quotes("AAPL", "TSLA")
            .bars("*")
            .statuses("AAPL")
            .build();

    assertFalse(sub.isEmpty());
    assertTrue(sub.trades().contains("AAPL"));
    assertEquals(2, sub.quotes().size());
    assertEquals(java.util.Set.of("*"), sub.bars());
    assertTrue(sub.statuses().contains("AAPL"));
  }

  @Test
  void builder_calledTwice_doesNotDeduplicate_butSetsAreLinkedHashSets() {
    // Adding the same symbol twice must result in one entry (Set semantics).
    var sub = StockSubscription.builder().trades("AAPL").trades("AAPL").build();
    assertEquals(1, sub.trades().size());
  }

  @Test
  void returnedSets_areUnmodifiable() {
    var sub = StockSubscription.builder().trades("AAPL").build();
    assertThrows(UnsupportedOperationException.class, () -> sub.trades().add("TSLA"));
  }

  @Test
  void builder_rejectsNullSymbols() {
    assertThrows(
        IllegalArgumentException.class, () -> StockSubscription.builder().trades("AAPL", null));
    assertThrows(
        IllegalArgumentException.class,
        () -> StockSubscription.builder().quotes(java.util.Arrays.asList("AAPL", null)));
  }

  @Test
  void builder_rejectsBlankSymbols() {
    assertThrows(IllegalArgumentException.class, () -> StockSubscription.builder().bars(""));
    assertThrows(
        IllegalArgumentException.class,
        () -> StockSubscription.builder().statuses(List.of("AAPL", "   ")));
  }
}
