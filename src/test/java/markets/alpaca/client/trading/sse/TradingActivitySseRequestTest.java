package markets.alpaca.client.trading.sse;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TradingActivitySseRequestTest {

  private static final String START = "01J9RPMV5TKB8WX3M4F1KZ7QH2";
  private static final String END = "01J9RVB6Y4ZK8M3N7QD2WX1RFP";

  @Test
  void fromEventIdCreatesUnboundedIdCursor() {
    var request = TradingActivitySseRequest.fromEventId(START);

    assertEquals(START, request.sinceId());
    assertNull(request.untilId());
    assertFalse(request.bounded());
  }

  @Test
  void throughEventIdCreatesBoundedIdCursor() {
    var request = TradingActivitySseRequest.throughEventId(START, END);

    assertEquals(START, request.sinceId());
    assertEquals(END, request.untilId());
    assertTrue(request.bounded());
  }

  @Test
  void namedEventIdFactoriesRejectNullAndInvalidValues() {
    assertThrows(NullPointerException.class, () -> TradingActivitySseRequest.fromEventId(null));
    assertThrows(IllegalArgumentException.class, () -> TradingActivitySseRequest.fromEventId(" "));
    assertThrows(
        NullPointerException.class, () -> TradingActivitySseRequest.throughEventId(START, null));
    assertThrows(
        IllegalArgumentException.class, () -> TradingActivitySseRequest.throughEventId("", END));
    assertThrows(
        IllegalArgumentException.class, () -> TradingActivitySseRequest.throughEventId(END, START));
  }
}
