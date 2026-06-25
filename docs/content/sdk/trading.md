---
id: trading
title: Trading
---

The Trading API lets an Alpaca account inspect account state, discover tradable assets, submit and
manage orders, read positions, and receive live order lifecycle updates.

For common workflows, start with the immutable `AlpacaClient` facade. When you need an endpoint
that does not yet have a handwritten helper, create a fresh generated Trading client from
`client.newTradingClient()` or `AlpacaClientFactory.tradingClient(...)`.

## Paper trading

Paper trading is the default environment for `AlpacaClient`. Make sure the credentials you load are
paper credentials when you use `TradingApiEnvironment.PAPER`.

```java
import markets.alpaca.client.AlpacaClient;
import markets.alpaca.client.AlpacaCredentials;
import markets.alpaca.client.TradingApiEnvironment;

var credentials = AlpacaCredentials.fromTradingApiEnvironmentVariables();

var client = AlpacaClient.builder(credentials)
    .tradingEnvironment(TradingApiEnvironment.PAPER)
    .build();
```

Use `TradingApiEnvironment.PRODUCTION` only when you intentionally want live trading.

## Account details

Account, asset, position, and portfolio-history endpoints are available through the generated
Trading REST APIs. The generated client is already configured with the correct base URL and
authentication headers.

```java
import markets.alpaca.client.openapi.trading.api.AccountsApi;

var trading = client.newTradingClient();
var accounts = new AccountsApi(trading);

var account = accounts.getAccount();
System.out.println(account.getBuyingPower());
```

## Assets

The Assets API can be used to confirm that a symbol is active, tradable, and fractionable before
placing an order.

```java
import markets.alpaca.client.openapi.trading.api.AssetsApi;

var assets = new AssetsApi(client.newTradingClient());
var aapl = assets.getV2AssetsSymbolOrAssetId("AAPL");

System.out.printf(
    "symbol=%s tradable=%s fractionable=%s%n",
    aapl.getSymbol(),
    aapl.getTradable(),
    aapl.getFractionable());
```

## Orders

Use `client.orders()` for the SDK's typed order-list helper. It wraps the generated
`GET /v2/orders` method with named parameters and local validation for limits and pagination modes.

```java
import markets.alpaca.client.openapi.trading.model.AssetClass;
import markets.alpaca.client.trading.ListOrdersRequest;

var openOrders = client.orders().list(ListOrdersRequest.builder()
    .status(ListOrdersRequest.Status.OPEN)
    .symbols("AAPL")
    .assetClasses(AssetClass.US_EQUITY)
    .limit(50)
    .direction(ListOrdersRequest.Direction.DESC)
    .nested(true)
    .build());
```

Submitting, canceling, and loading orders by ID are available through the generated `OrdersApi`.
Keep order-submission retries conservative: order placement is not an idempotent operation unless
your application manages client order IDs carefully.

```java
import java.util.UUID;
import markets.alpaca.client.openapi.trading.api.OrdersApi;
import markets.alpaca.client.openapi.trading.model.OrderSide;
import markets.alpaca.client.openapi.trading.model.OrderType;
import markets.alpaca.client.openapi.trading.model.PostOrderRequest;
import markets.alpaca.client.openapi.trading.model.TimeInForce;

var ordersApi = new OrdersApi(client.newTradingClient());
var clientOrderId = "my-app-" + UUID.randomUUID();

var request = new PostOrderRequest()
    .symbol("AAPL")
    .qty("1")
    .side(OrderSide.BUY)
    .type(OrderType.LIMIT)
    .limitPrice("0.01")
    .timeInForce(TimeInForce.DAY)
    .clientOrderId(clientOrderId);

var order = ordersApi.postOrder(request);
ordersApi.deleteOrderByOrderID(order.getId());
```

## Positions

Use the generated `PositionsApi` to inspect open positions. State-changing calls such as closing
positions should be guarded in your application in the same way as order submission.

```java
import markets.alpaca.client.openapi.trading.api.PositionsApi;

var positions = new PositionsApi(client.newTradingClient());

positions.getAllOpenPositions().forEach(position ->
    System.out.printf(
        "%s qty=%s marketValue=%s%n",
        position.getSymbol(),
        position.getQty(),
        position.getMarketValue()));
```

## Portfolio history

Portfolio history is also exposed through the generated Trading client.

```java
import markets.alpaca.client.openapi.trading.api.PortfolioHistoryApi;

var portfolio = new PortfolioHistoryApi(client.newTradingClient());
var history = portfolio.getAccountPortfolioHistory(
    "1M", "1D", null, null, null, null, null, null);

System.out.println(history.getEquity());
```

## Streaming trade updates

Trading stream updates arrive through a WebSocket client. Use the `PAPER` stream for paper keys and
`PRODUCTION` for live keys.

```java
import markets.alpaca.client.AlpacaClientFactory;
import markets.alpaca.client.ws.TradingEnvironment;
import markets.alpaca.client.ws.TradingStreamListener;
import markets.alpaca.client.ws.TradingSubscription;
import markets.alpaca.client.ws.model.TradeUpdate;

var stream = AlpacaClientFactory.tradingStream(
    credentials,
    TradingEnvironment.PAPER,
    new TradingStreamListener() {
        @Override
        public void onTradeUpdate(TradeUpdate update) {
            System.out.println(update);
        }
    });

stream.connect(TradingSubscription.TRADE_UPDATES);
```

For authentication confirmation, listener executors, reconnect behavior, and the differences between
Trading, Market Data, and Broker live-event clients, see [Streaming and events](./streaming).

See the full runnable Trading workflow in
`examples/src/main/java/markets/alpaca/client/examples/TradingApiExample.java`.
