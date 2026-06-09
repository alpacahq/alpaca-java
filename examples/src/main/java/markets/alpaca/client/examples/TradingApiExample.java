package markets.alpaca.client.examples;

import java.util.UUID;
import markets.alpaca.client.AlpacaClientFactory;
import markets.alpaca.client.openapi.trading.api.AccountsApi;
import markets.alpaca.client.openapi.trading.api.AssetsApi;
import markets.alpaca.client.openapi.trading.api.OrdersApi;
import markets.alpaca.client.openapi.trading.api.PortfolioHistoryApi;
import markets.alpaca.client.openapi.trading.api.PositionsApi;
import markets.alpaca.client.openapi.trading.model.AssetClass;
import markets.alpaca.client.openapi.trading.model.OrderSide;
import markets.alpaca.client.openapi.trading.model.OrderType;
import markets.alpaca.client.openapi.trading.model.PostOrderRequest;
import markets.alpaca.client.openapi.trading.model.TimeInForce;
import markets.alpaca.client.trading.ListOrdersRequest;

/**
 * Trading API workflow: authenticate, inspect account state, read assets/orders/positions, and
 * optionally submit an order.
 */
public final class TradingApiExample {
  private TradingApiExample() {}

  public static void main(String[] args) throws Exception {
    var credentials = ExampleSupport.tradingCredentials();
    String baseUrlOverride =
        ExampleSupport.setting("tradingApiBaseUrl", "APCA_TRADING_BASE_URL", null);
    var tradingClient =
        baseUrlOverride == null
            ? AlpacaClientFactory.tradingClient(credentials, ExampleSupport.tradingApiEnvironment())
            : AlpacaClientFactory.tradingClient(credentials, baseUrlOverride);

    var accounts = new AccountsApi(tradingClient);
    var assets = new AssetsApi(tradingClient);
    var orders = AlpacaClientFactory.orders(tradingClient);
    var positions = new PositionsApi(tradingClient);
    var portfolio = new PortfolioHistoryApi(tradingClient);

    String symbol = ExampleSupport.env("APCA_EXAMPLE_SYMBOL", "AAPL");

    ExampleSupport.printSection("Account");
    var account = accounts.getAccount();
    System.out.printf(
        "account=%s status=%s buyingPower=%s%n",
        account.getAccountNumber(), account.getStatus(), account.getBuyingPower());

    ExampleSupport.printSection("Asset");
    var asset = assets.getV2AssetsSymbolOrAssetId(symbol);
    System.out.printf(
        "symbol=%s tradable=%s fractionable=%s%n",
        asset.getSymbol(), asset.getTradable(), asset.getFractionable());

    ExampleSupport.printSection("Open Orders");
    var openOrders =
        orders.list(
            ListOrdersRequest.builder()
                .status(ListOrdersRequest.Status.OPEN)
                .limit(50)
                .direction(ListOrdersRequest.Direction.DESC)
                .nested(true)
                .symbols(symbol)
                .assetClasses(AssetClass.US_EQUITY)
                .build());
    openOrders.forEach(
        order ->
            System.out.printf(
                "order=%s symbol=%s side=%s qty=%s status=%s%n",
                order.getId(),
                order.getSymbol(),
                order.getSide(),
                order.getQty(),
                order.getStatus()));

    ExampleSupport.printSection("Positions");
    positions
        .getAllOpenPositions()
        .forEach(
            position ->
                System.out.printf(
                    "position=%s qty=%s marketValue=%s%n",
                    position.getSymbol(), position.getQty(), position.getMarketValue()));

    ExampleSupport.printSection("Portfolio History");
    var history =
        portfolio.getAccountPortfolioHistory("1M", "1D", null, null, null, null, null, null);
    System.out.printf(
        "equity points=%d%n", history.getEquity() == null ? 0 : history.getEquity().size());

    if (ExampleSupport.enabled("APCA_EXAMPLE_PLACE_ORDER")) {
      placeAndCancelSampleOrder(orders.generatedApi(), symbol);
    } else {
      ExampleSupport.printSection("Order Submission");
      System.out.println(
          "Skipped. Set APCA_EXAMPLE_PLACE_ORDER=true to submit a paper limit order.");
    }
  }

  private static void placeAndCancelSampleOrder(OrdersApi orders, String symbol) throws Exception {
    ExampleSupport.printSection("Order Submission");

    String clientOrderId = "alpaca-java-example-" + UUID.randomUUID();
    var request =
        new PostOrderRequest()
            .symbol(symbol)
            .qty("1")
            .side(OrderSide.fromValue("buy"))
            .type(OrderType.fromValue("limit"))
            .limitPrice("0.01")
            .timeInForce(TimeInForce.fromValue("day"))
            .clientOrderId(clientOrderId);

    var order = orders.postOrder(request);
    System.out.printf(
        "submitted order=%s clientOrderId=%s status=%s%n",
        order.getId(), order.getClientOrderId(), order.getStatus());

    var byClientId = orders.getOrderByClientOrderId(clientOrderId);
    System.out.printf(
        "loaded by client order id: %s status=%s%n", byClientId.getId(), byClientId.getStatus());

    orders.deleteOrderByOrderID(UUID.fromString(order.getId()));
    System.out.printf("cancel requested for order=%s%n", order.getId());
  }
}
