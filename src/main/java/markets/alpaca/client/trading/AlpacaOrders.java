package markets.alpaca.client.trading;

import java.util.List;
import java.util.Objects;
import markets.alpaca.client.openapi.trading.api.OrdersApi;
import markets.alpaca.client.openapi.trading.http.ApiException;
import markets.alpaca.client.openapi.trading.http.ApiResponse;
import markets.alpaca.client.openapi.trading.model.Order;

/**
 * Handwritten convenience facade for common Trading API order workflows.
 *
 * <p>This class wraps the generated Trading {@link OrdersApi} and provides typed request objects
 * for common operations while preserving access to the generated API through {@link
 * #generatedApi()}. For endpoint-level details, see the Trading OpenAPI spec used to generate
 * {@code markets.alpaca.client.openapi.trading}.
 *
 * @see <a href="https://docs.alpaca.markets/openapi/trading-api.json">Trading OpenAPI spec</a>
 */
public final class AlpacaOrders {

  private final OrdersApi generatedApi;

  /** Creates a facade around a generated Trading {@link OrdersApi}. */
  public AlpacaOrders(OrdersApi generatedApi) {
    this.generatedApi = Objects.requireNonNull(generatedApi, "generatedApi must not be null");
  }

  /** Creates a facade from a generated Trading {@code ApiClient}. */
  public AlpacaOrders(markets.alpaca.client.openapi.trading.http.ApiClient tradingClient) {
    this(new OrdersApi(Objects.requireNonNull(tradingClient, "tradingClient must not be null")));
  }

  /** Returns the generated API for operations not covered by this facade. */
  public OrdersApi generatedApi() {
    return generatedApi;
  }

  /**
   * Lists orders using named request parameters for {@code GET /v2/orders}.
   *
   * <p>The request object validates SDK-level constraints such as the documented 1-500 limit range
   * and mutually exclusive time-vs-order-id pagination modes.
   */
  public List<Order> list(ListOrdersRequest request) throws ApiException {
    Objects.requireNonNull(request, "request must not be null");
    return generatedApi.getAllOrders(
        request.status(),
        request.limit(),
        request.after(),
        request.until(),
        request.direction(),
        request.nested(),
        request.symbols(),
        request.side(),
        request.assetClasses(),
        request.beforeOrderId(),
        request.afterOrderId());
  }

  /**
   * Lists orders and includes the generated client's HTTP response metadata.
   *
   * <p>Use this overload when callers need status codes, response headers, or rate-limit headers in
   * addition to the deserialized order list.
   */
  public ApiResponse<List<Order>> listWithHttpInfo(ListOrdersRequest request) throws ApiException {
    Objects.requireNonNull(request, "request must not be null");
    return generatedApi.getAllOrdersWithHttpInfo(
        request.status(),
        request.limit(),
        request.after(),
        request.until(),
        request.direction(),
        request.nested(),
        request.symbols(),
        request.side(),
        request.assetClasses(),
        request.beforeOrderId(),
        request.afterOrderId());
  }
}
