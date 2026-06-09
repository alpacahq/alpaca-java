package markets.alpaca.client.trading;

import static org.junit.jupiter.api.Assertions.*;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import markets.alpaca.client.openapi.trading.model.AssetClass;
import markets.alpaca.client.openapi.trading.model.OrderSide;
import org.junit.jupiter.api.Test;

class ListOrdersRequestTest {

  @Test
  void empty_usesApiDefaults() {
    var request = ListOrdersRequest.empty();

    assertNull(request.status());
    assertNull(request.limit());
    assertNull(request.after());
    assertNull(request.until());
    assertNull(request.direction());
    assertNull(request.nested());
    assertNull(request.symbols());
    assertNull(request.side());
    assertTrue(request.assetClasses().isEmpty());
    assertNull(request.beforeOrderId());
    assertNull(request.afterOrderId());
  }

  @Test
  void builder_setsNamedParameters() {
    var after = OffsetDateTime.parse("2026-06-19T10:15:30Z");
    var request =
        ListOrdersRequest.builder()
            .status(ListOrdersRequest.Status.OPEN)
            .limit(50)
            .after(after)
            .direction(ListOrdersRequest.Direction.DESC)
            .nested(true)
            .symbols("AAPL", "MSFT")
            .side(OrderSide.BUY)
            .assetClasses(AssetClass.US_EQUITY, AssetClass.US_OPTION)
            .build();

    assertEquals("open", request.status());
    assertEquals(50, request.limit());
    assertEquals(after.toString(), request.after());
    assertEquals("desc", request.direction());
    assertEquals(true, request.nested());
    assertEquals("AAPL,MSFT", request.symbols());
    assertEquals("buy", request.side());
    assertEquals(List.of("us_equity", "us_option"), request.assetClasses());
  }

  @Test
  void builder_acceptsRawValuesAndCollectionParameters() {
    var until = OffsetDateTime.parse("2026-06-19T16:00:00Z");
    var request =
        ListOrdersRequest.builder()
            .status("all")
            .until(until)
            .direction("asc")
            .symbols(List.of("AAPL", "MSFT"))
            .side("sell")
            .assetClasses(List.of("us_equity", "crypto"))
            .build();

    assertEquals("all", request.status());
    assertEquals(until.toString(), request.until());
    assertEquals("asc", request.direction());
    assertEquals("AAPL,MSFT", request.symbols());
    assertEquals("sell", request.side());
    assertEquals(List.of("us_equity", "crypto"), request.assetClasses());
  }

  @Test
  void builder_acceptsCsvAndStringAssetClasses() {
    var request =
        ListOrdersRequest.builder()
            .until("2026-06-19T16:00:00Z")
            .symbolsCsv("AAPL,MSFT")
            .assetClasses("us_equity")
            .build();

    assertEquals("2026-06-19T16:00:00Z", request.until());
    assertEquals("AAPL,MSFT", request.symbols());
    assertEquals(List.of("us_equity"), request.assetClasses());
  }

  @Test
  void constructor_defensivelyCopiesAssetClasses() {
    var assetClasses = new ArrayList<>(List.of("us_equity"));
    var request =
        new ListOrdersRequest(
            null, null, null, null, null, null, null, null, assetClasses, null, null);

    assetClasses.add("crypto");

    assertEquals(List.of("us_equity"), request.assetClasses());
    assertThrows(UnsupportedOperationException.class, () -> request.assetClasses().add("crypto"));
  }

  @Test
  void build_rejectsInvalidLimit() {
    assertThrows(
        IllegalArgumentException.class, () -> ListOrdersRequest.builder().limit(0).build());
    assertThrows(
        IllegalArgumentException.class, () -> ListOrdersRequest.builder().limit(501).build());
  }

  @Test
  void build_rejectsBlankValues() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ListOrdersRequest.builder().symbols("AAPL", " ").build());
    assertThrows(
        IllegalArgumentException.class, () -> ListOrdersRequest.builder().assetClasses("").build());
  }

  @Test
  void build_rejectsNullListValues() {
    var assetClasses = new ArrayList<String>();
    assetClasses.add("us_equity");
    assetClasses.add(null);

    assertThrows(
        IllegalArgumentException.class,
        () -> ListOrdersRequest.builder().symbols("AAPL", null).build());
    assertThrows(
        IllegalArgumentException.class,
        () -> ListOrdersRequest.builder().assetClasses((String) null).build());
    assertThrows(
        IllegalArgumentException.class,
        () -> ListOrdersRequest.builder().assetClasses((AssetClass) null).build());
    assertThrows(
        IllegalArgumentException.class,
        () -> ListOrdersRequest.builder().assetClasses(assetClasses).build());
  }

  @Test
  void build_rejectsMutuallyExclusivePagination() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ListOrdersRequest.builder()
                .beforeOrderId("before-order")
                .afterOrderId("after-order")
                .build());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            ListOrdersRequest.builder()
                .after("2026-06-19T10:15:30Z")
                .beforeOrderId("before-order")
                .build());
  }
}
