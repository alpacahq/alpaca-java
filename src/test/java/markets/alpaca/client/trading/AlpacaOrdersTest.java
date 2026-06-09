package markets.alpaca.client.trading;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import markets.alpaca.client.openapi.trading.api.OrdersApi;
import markets.alpaca.client.openapi.trading.http.ApiException;
import markets.alpaca.client.openapi.trading.http.ApiResponse;
import markets.alpaca.client.openapi.trading.model.AssetClass;
import markets.alpaca.client.openapi.trading.model.Order;
import markets.alpaca.client.openapi.trading.model.OrderSide;
import org.junit.jupiter.api.Test;

class AlpacaOrdersTest {

  @Test
  void generatedApi_returnsWrappedApi() {
    var generated = new CapturingOrdersApi();
    var orders = new AlpacaOrders(generated);

    assertSame(generated, orders.generatedApi());
  }

  @Test
  void list_mapsNamedRequestToGeneratedApi() throws Exception {
    var generated = new CapturingOrdersApi();
    var orders = new AlpacaOrders(generated);
    var expected = List.of(new Order().symbol("AAPL"));
    generated.orders = expected;

    var actual =
        orders.list(
            ListOrdersRequest.builder()
                .status(ListOrdersRequest.Status.OPEN)
                .limit(50)
                .after("2026-06-19T10:15:30Z")
                .direction(ListOrdersRequest.Direction.DESC)
                .nested(true)
                .symbols("AAPL", "MSFT")
                .side(OrderSide.BUY)
                .assetClasses(AssetClass.US_EQUITY)
                .build());

    assertSame(expected, actual);
    assertEquals("open", generated.status);
    assertEquals(50, generated.limit);
    assertEquals("2026-06-19T10:15:30Z", generated.after);
    assertNull(generated.until);
    assertEquals("desc", generated.direction);
    assertEquals(true, generated.nested);
    assertEquals("AAPL,MSFT", generated.symbols);
    assertEquals("buy", generated.side);
    assertEquals(List.of("us_equity"), generated.assetClass);
    assertNull(generated.beforeOrderId);
    assertNull(generated.afterOrderId);
  }

  @Test
  void listWithHttpInfo_returnsGeneratedResponse() throws Exception {
    var generated = new CapturingOrdersApi();
    var response =
        new ApiResponse<List<Order>>(200, Map.of("x-request-id", List.of("request-id")), List.of());
    generated.response = response;

    var actual = new AlpacaOrders(generated).listWithHttpInfo(ListOrdersRequest.empty());

    assertSame(response, actual);
  }

  @Test
  void list_rejectsNullRequest() {
    var orders = new AlpacaOrders(new CapturingOrdersApi());

    assertThrows(NullPointerException.class, () -> orders.list(null));
    assertThrows(NullPointerException.class, () -> orders.listWithHttpInfo(null));
  }

  private static final class CapturingOrdersApi extends OrdersApi {
    private List<Order> orders = List.of();
    private ApiResponse<List<Order>> response = new ApiResponse<>(200, Map.of(), List.of());
    private String status;
    private Integer limit;
    private String after;
    private String until;
    private String direction;
    private Boolean nested;
    private String symbols;
    private String side;
    private List<String> assetClass;
    private String beforeOrderId;
    private String afterOrderId;

    @Override
    public List<Order> getAllOrders(
        String status,
        Integer limit,
        String after,
        String until,
        String direction,
        Boolean nested,
        String symbols,
        String side,
        List<String> assetClass,
        String beforeOrderId,
        String afterOrderId)
        throws ApiException {
      capture(
          status,
          limit,
          after,
          until,
          direction,
          nested,
          symbols,
          side,
          assetClass,
          beforeOrderId,
          afterOrderId);
      return orders;
    }

    @Override
    public ApiResponse<List<Order>> getAllOrdersWithHttpInfo(
        String status,
        Integer limit,
        String after,
        String until,
        String direction,
        Boolean nested,
        String symbols,
        String side,
        List<String> assetClass,
        String beforeOrderId,
        String afterOrderId)
        throws ApiException {
      capture(
          status,
          limit,
          after,
          until,
          direction,
          nested,
          symbols,
          side,
          assetClass,
          beforeOrderId,
          afterOrderId);
      return response;
    }

    private void capture(
        String status,
        Integer limit,
        String after,
        String until,
        String direction,
        Boolean nested,
        String symbols,
        String side,
        List<String> assetClass,
        String beforeOrderId,
        String afterOrderId) {
      this.status = status;
      this.limit = limit;
      this.after = after;
      this.until = until;
      this.direction = direction;
      this.nested = nested;
      this.symbols = symbols;
      this.side = side;
      this.assetClass = assetClass;
      this.beforeOrderId = beforeOrderId;
      this.afterOrderId = afterOrderId;
    }
  }
}
