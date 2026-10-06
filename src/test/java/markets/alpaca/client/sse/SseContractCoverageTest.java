package markets.alpaca.client.sse;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import markets.alpaca.client.broker.sse.BrokerEventsSseClient;
import markets.alpaca.client.data.sse.CorporateActionsSseClient;
import markets.alpaca.client.trading.sse.TradingEventsSseClient;
import org.junit.jupiter.api.Test;

class SseContractCoverageTest {

  @Test
  void brokerSupportedOperationsHavePublicHandwrittenBindings() {
    assertPublicMethods(
        BrokerEventsSseClient.class,
        Set.of(
            "subscribeToNonTradingActivities",
            "subscribeToAccountStatus",
            "subscribeToJournalStatusLegacy",
            "getAccountActivityEventAsync",
            "subscribeToActivities",
            "subscribeToAdminActions",
            "subscribeToFundingStatus",
            "subscribeToIpoEvents",
            "subscribeToJournalStatus",
            "subscribeToSystemEvents",
            "subscribeToTradeEvents"));
  }

  @Test
  void tradingSupportedOperationsHavePublicHandwrittenBindings() {
    assertPublicMethods(TradingEventsSseClient.class, Set.of("subscribeToActivities"));
  }

  @Test
  void dataSupportedOperationsHavePublicHandwrittenBindings() {
    assertPublicMethods(CorporateActionsSseClient.class, Set.of("subscribeToCorporateActions"));
  }

  private static void assertPublicMethods(Class<?> type, Set<String> expected) {
    Set<String> actual =
        Arrays.stream(type.getMethods()).map(Method::getName).collect(Collectors.toSet());
    expected.forEach(
        method ->
            assertTrue(
                actual.contains(method),
                () -> type.getName() + " is missing public SSE binding " + method));
  }
}
