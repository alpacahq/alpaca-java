package markets.alpaca.client.ws;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NewsSubscriptionTest {

  @Test
  void builder_populatesSymbols() {
    var sub = NewsSubscription.builder().symbols("AAPL", "TSLA").build();

    assertEquals(Set.of("AAPL", "TSLA"), sub.symbols());
    assertFalse(sub.isEmpty());
  }

  @Test
  void builder_rejectsNullSymbols() {
    assertThrows(
        IllegalArgumentException.class, () -> NewsSubscription.builder().symbols("AAPL", null));
    assertThrows(
        IllegalArgumentException.class,
        () -> NewsSubscription.builder().symbols(java.util.Arrays.asList("AAPL", null)));
  }

  @Test
  void builder_rejectsBlankSymbols() {
    assertThrows(IllegalArgumentException.class, () -> NewsSubscription.builder().symbols(""));
    assertThrows(
        IllegalArgumentException.class,
        () -> NewsSubscription.builder().symbols(List.of("AAPL", "   ")));
  }

  @Test
  void returnedSymbols_areUnmodifiable() {
    var sub = NewsSubscription.builder().symbols("AAPL").build();

    assertThrows(UnsupportedOperationException.class, () -> sub.symbols().add("TSLA"));
  }
}
