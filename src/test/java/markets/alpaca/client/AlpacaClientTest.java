package markets.alpaca.client;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import markets.alpaca.client.data.AlpacaStocks;
import markets.alpaca.client.data.StockTradesRequest;
import markets.alpaca.client.openapi.data.api.StockApi;
import markets.alpaca.client.openapi.data.model.Sort;
import markets.alpaca.client.openapi.data.model.StockHistoricalFeed;
import markets.alpaca.client.openapi.data.model.StockTradesResp;
import markets.alpaca.client.openapi.data.model.StockTradesRespSingle;
import markets.alpaca.client.openapi.trading.api.OrdersApi;
import markets.alpaca.client.openapi.trading.model.Order;
import markets.alpaca.client.trading.AlpacaOrders;
import markets.alpaca.client.trading.ListOrdersRequest;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

class AlpacaClientTest {

  private static final AlpacaCredentials CREDS =
      new AlpacaCredentials("test-key-id", "test-secret-key");

  @Test
  void builder_rejectsMissingCredentials() {
    assertThrows(NullPointerException.class, () -> AlpacaClient.builder(null));
  }

  @Test
  void defaultClient_configuresAllGeneratedClientsWithOneCredentialPair() {
    var client = AlpacaClient.builder(CREDS).build();

    var trading = client.newTradingClient();
    var data = client.newDataClient();
    var broker = client.newBrokerClient();

    assertEquals("https://paper-api.alpaca.markets", trading.getBasePath());
    assertEquals("test-key-id", tradingApiKey(trading));
    assertEquals("test-secret-key", tradingApiSecret(trading));
    assertEquals("test-key-id", dataApiKey(data));
    assertEquals("test-secret-key", dataApiSecret(data));
    assertEquals("https://broker-api.sandbox.alpaca.markets", broker.getBasePath());
    assertEquals("test-key-id", brokerUsername(broker));
    assertEquals("test-secret-key", brokerPassword(broker));
    assertNotNull(client.brokerEventsSseClient());
  }

  @Test
  void builder_perApiCredentialsOverrideDefaultCredentialPair() {
    var tradingCreds = new AlpacaCredentials("trading-key", "trading-secret");
    var dataCreds = new AlpacaCredentials("data-key", "data-secret");
    var brokerCreds = new AlpacaCredentials("broker-key", "broker-secret");

    var client =
        AlpacaClient.builder(CREDS)
            .tradingCredentials(tradingCreds)
            .dataCredentials(dataCreds)
            .brokerCredentials(brokerCreds)
            .build();

    var trading = client.newTradingClient();
    var data = client.newDataClient();
    var broker = client.newBrokerClient();

    assertEquals("trading-key", tradingApiKey(trading));
    assertEquals("trading-secret", tradingApiSecret(trading));
    assertEquals("data-key", dataApiKey(data));
    assertEquals("data-secret", dataApiSecret(data));
    assertEquals("broker-key", brokerUsername(broker));
    assertEquals("broker-secret", brokerPassword(broker));
  }

  @Test
  void builder_credentialOverridesRejectNullValues() {
    var builder = AlpacaClient.builder(CREDS);

    assertThrows(NullPointerException.class, () -> builder.tradingCredentials(null));
    assertThrows(NullPointerException.class, () -> builder.dataCredentials(null));
    assertThrows(NullPointerException.class, () -> builder.brokerCredentials(null));
  }

  @Test
  void builder_configuresEnvironmentsAndHttpClients() {
    var tradingHttp = new OkHttpClient.Builder().readTimeout(Duration.ofSeconds(17)).build();
    var dataHttp = new OkHttpClient.Builder().writeTimeout(Duration.ofSeconds(19)).build();
    var brokerHttp = new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(23)).build();

    var client =
        AlpacaClient.builder(CREDS)
            .tradingEnvironment(TradingApiEnvironment.PRODUCTION)
            .brokerEnvironment(BrokerApiEnvironment.PRODUCTION)
            .tradingHttpClient(tradingHttp)
            .dataHttpClient(dataHttp)
            .brokerHttpClient(brokerHttp)
            .build();

    var trading = client.newTradingClient();
    var data = client.newDataClient();
    var broker = client.newBrokerClient();

    assertEquals("https://api.alpaca.markets", trading.getBasePath());
    assertNotSame(tradingHttp, trading.getHttpClient());
    assertEquals(tradingHttp.readTimeoutMillis(), trading.getHttpClient().readTimeoutMillis());
    assertNotSame(dataHttp, data.getHttpClient());
    assertEquals(dataHttp.writeTimeoutMillis(), data.getHttpClient().writeTimeoutMillis());
    assertEquals("https://broker-api.alpaca.markets", broker.getBasePath());
    assertNotSame(brokerHttp, broker.getHttpClient());
    assertEquals(brokerHttp.connectTimeoutMillis(), broker.getHttpClient().connectTimeoutMillis());
  }

  @Test
  void builder_baseUrlOverridesTakePrecedenceOverEnvironmentDefaults() {
    var client =
        AlpacaClient.builder(CREDS)
            .tradingEnvironment(TradingApiEnvironment.PRODUCTION)
            .brokerEnvironment(BrokerApiEnvironment.PRODUCTION)
            .tradingBaseUrl("https://trading-proxy.example")
            .dataBaseUrl("https://data-proxy.example")
            .brokerBaseUrl("https://broker-proxy.example")
            .build();

    assertEquals("https://trading-proxy.example", client.newTradingClient().getBasePath());
    assertEquals("https://data-proxy.example", client.newDataClient().getBasePath());
    assertEquals("https://broker-proxy.example", client.newBrokerClient().getBasePath());
  }

  @Test
  void builder_baseUrlOverridesRejectBlankValues() {
    var builder = AlpacaClient.builder(CREDS);

    assertThrows(IllegalArgumentException.class, () -> builder.tradingBaseUrl(" "));
    assertThrows(IllegalArgumentException.class, () -> builder.dataBaseUrl(""));
    assertThrows(IllegalArgumentException.class, () -> builder.brokerBaseUrl("\t"));
    assertThrows(IllegalArgumentException.class, () -> builder.tradingBaseUrl("///"));
  }

  @Test
  void sharedHttpClient_appliesToAllGeneratedClients() {
    var httpClient = new OkHttpClient.Builder().readTimeout(Duration.ofSeconds(17)).build();

    var client = AlpacaClient.builder(CREDS).httpClient(httpClient).build();

    var trading = client.newTradingClient().getHttpClient();
    var data = client.newDataClient().getHttpClient();
    var broker = client.newBrokerClient().getHttpClient();

    assertNotSame(httpClient, trading);
    assertSame(trading, data);
    assertSame(trading, broker);
    assertEquals(httpClient.readTimeoutMillis(), trading.readTimeoutMillis());
  }

  @Test
  void generatedEscapeHatchClientsAreFreshCopies() {
    var client =
        AlpacaClient.builder(CREDS)
            .tradingEnvironment(TradingApiEnvironment.PRODUCTION)
            .dataBaseUrl("https://data-proxy.example")
            .build();

    var firstTrading = client.newTradingClient();
    var secondTrading = client.newTradingClient();
    firstTrading.setBasePath("https://mutated.example");

    assertNotSame(firstTrading, secondTrading);
    assertEquals("https://api.alpaca.markets", secondTrading.getBasePath());
    assertEquals("https://api.alpaca.markets", client.newTradingClient().getBasePath());

    var firstBroker = client.newBrokerClient();
    var secondBroker = client.newBrokerClient();
    firstBroker.setBasePath("https://mutated.example");

    assertNotSame(firstBroker, secondBroker);
    assertEquals("https://broker-api.sandbox.alpaca.markets", secondBroker.getBasePath());
    assertEquals(
        "https://broker-api.sandbox.alpaca.markets", client.newBrokerClient().getBasePath());

    var firstData = client.newDataClient();
    var secondData = client.newDataClient();
    firstData.setBasePath("https://mutated.example");

    assertNotSame(firstData, secondData);
    assertEquals("https://data-proxy.example", secondData.getBasePath());
    assertEquals("https://data-proxy.example", client.newDataClient().getBasePath());
  }

  @Test
  void workflowFacadesRejectNullRequests() {
    var client = AlpacaClient.builder(CREDS).build();

    assertThrows(NullPointerException.class, () -> client.orders().list(null));
    assertThrows(NullPointerException.class, () -> client.orders().listWithHttpInfo(null));
    assertThrows(NullPointerException.class, () -> client.stocks().trades(null));
    assertThrows(NullPointerException.class, () -> client.stocks().tradesWithHttpInfo(null));
    assertThrows(NullPointerException.class, () -> client.stocks().tradesForSymbol(null));
    assertThrows(
        NullPointerException.class, () -> client.stocks().tradesForSymbolWithHttpInfo(null));
  }

  @Test
  void orderFacadeDelegatesToWorkflowHelper() throws Exception {
    var generated = new CapturingOrdersApi();
    var expectedOrders = List.of(new Order().symbol("AAPL"));
    var expectedResponse =
        new markets.alpaca.client.openapi.trading.http.ApiResponse<>(200, Map.of(), expectedOrders);
    generated.orders = expectedOrders;
    generated.response = expectedResponse;
    var orders = new AlpacaClient.Orders(new AlpacaOrders(generated));

    assertSame(expectedOrders, orders.list(ListOrdersRequest.empty()));
    assertSame(expectedResponse, orders.listWithHttpInfo(ListOrdersRequest.empty()));
  }

  @Test
  void stockFacadeDelegatesToWorkflowHelper() throws Exception {
    var generated = new CapturingStockApi();
    var trades = new StockTradesResp();
    var singleTrades = new StockTradesRespSingle();
    var tradesResponse =
        new markets.alpaca.client.openapi.data.http.ApiResponse<>(200, Map.of(), trades);
    var singleTradesResponse =
        new markets.alpaca.client.openapi.data.http.ApiResponse<>(200, Map.of(), singleTrades);
    generated.tradesResponse = trades;
    generated.singleTradesResponse = singleTrades;
    generated.tradesHttpResponse = tradesResponse;
    generated.singleTradesHttpResponse = singleTradesResponse;
    var stocks = new AlpacaClient.Stocks(new AlpacaStocks(generated));
    var request = StockTradesRequest.builder().symbols("AAPL").build();

    assertSame(trades, stocks.trades(request));
    assertSame(tradesResponse, stocks.tradesWithHttpInfo(request));
    assertSame(singleTrades, stocks.tradesForSymbol(request));
    assertSame(singleTradesResponse, stocks.tradesForSymbolWithHttpInfo(request));
  }

  @Test
  void alpacaClients_clientFactoriesCreateClients() {
    var httpClient = new OkHttpClient.Builder().readTimeout(Duration.ofSeconds(17)).build();

    assertEquals(
        "https://paper-api.alpaca.markets",
        AlpacaClientFactory.client(CREDS).newTradingClient().getBasePath());
    assertEquals(
        "https://api.alpaca.markets",
        AlpacaClientFactory.client(CREDS, TradingApiEnvironment.PRODUCTION)
            .newTradingClient()
            .getBasePath());
    assertEquals(
        httpClient.readTimeoutMillis(),
        AlpacaClientFactory.client(CREDS, httpClient)
            .newTradingClient()
            .getHttpClient()
            .readTimeoutMillis());
    assertEquals(
        httpClient.readTimeoutMillis(),
        AlpacaClientFactory.client(CREDS, TradingApiEnvironment.PRODUCTION, httpClient)
            .newTradingClient()
            .getHttpClient()
            .readTimeoutMillis());
  }

  private static String tradingApiKey(markets.alpaca.client.openapi.trading.http.ApiClient client) {
    return ((markets.alpaca.client.openapi.trading.http.auth.ApiKeyAuth)
            client.getAuthentications().get("API_Key"))
        .getApiKey();
  }

  private static String tradingApiSecret(
      markets.alpaca.client.openapi.trading.http.ApiClient client) {
    return ((markets.alpaca.client.openapi.trading.http.auth.ApiKeyAuth)
            client.getAuthentications().get("API_Secret"))
        .getApiKey();
  }

  private static String dataApiKey(markets.alpaca.client.openapi.data.http.ApiClient client) {
    return ((markets.alpaca.client.openapi.data.http.auth.ApiKeyAuth)
            client.getAuthentications().get("apiKey"))
        .getApiKey();
  }

  private static String dataApiSecret(markets.alpaca.client.openapi.data.http.ApiClient client) {
    return ((markets.alpaca.client.openapi.data.http.auth.ApiKeyAuth)
            client.getAuthentications().get("apiSecret"))
        .getApiKey();
  }

  private static String brokerUsername(markets.alpaca.client.openapi.broker.http.ApiClient client) {
    return ((markets.alpaca.client.openapi.broker.http.auth.HttpBasicAuth)
            client.getAuthentications().get("BasicAuth"))
        .getUsername();
  }

  private static String brokerPassword(markets.alpaca.client.openapi.broker.http.ApiClient client) {
    return ((markets.alpaca.client.openapi.broker.http.auth.HttpBasicAuth)
            client.getAuthentications().get("BasicAuth"))
        .getPassword();
  }

  private static final class CapturingOrdersApi extends OrdersApi {
    private List<Order> orders = List.of();
    private markets.alpaca.client.openapi.trading.http.ApiResponse<List<Order>> response =
        new markets.alpaca.client.openapi.trading.http.ApiResponse<>(200, Map.of(), List.of());

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
        throws markets.alpaca.client.openapi.trading.http.ApiException {
      return orders;
    }

    @Override
    public markets.alpaca.client.openapi.trading.http.ApiResponse<List<Order>>
        getAllOrdersWithHttpInfo(
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
            throws markets.alpaca.client.openapi.trading.http.ApiException {
      return response;
    }
  }

  private static final class CapturingStockApi extends StockApi {
    private StockTradesResp tradesResponse = new StockTradesResp();
    private StockTradesRespSingle singleTradesResponse = new StockTradesRespSingle();
    private markets.alpaca.client.openapi.data.http.ApiResponse<StockTradesResp>
        tradesHttpResponse =
            new markets.alpaca.client.openapi.data.http.ApiResponse<>(
                200, Map.of(), new StockTradesResp());
    private markets.alpaca.client.openapi.data.http.ApiResponse<StockTradesRespSingle>
        singleTradesHttpResponse =
            new markets.alpaca.client.openapi.data.http.ApiResponse<>(
                200, Map.of(), new StockTradesRespSingle());

    @Override
    public StockTradesResp stockTrades(
        String symbols,
        OffsetDateTime start,
        OffsetDateTime end,
        Integer limit,
        String asof,
        StockHistoricalFeed feed,
        String currency,
        String pageToken,
        Sort sort)
        throws markets.alpaca.client.openapi.data.http.ApiException {
      return tradesResponse;
    }

    @Override
    public markets.alpaca.client.openapi.data.http.ApiResponse<StockTradesResp>
        stockTradesWithHttpInfo(
            String symbols,
            OffsetDateTime start,
            OffsetDateTime end,
            Integer limit,
            String asof,
            StockHistoricalFeed feed,
            String currency,
            String pageToken,
            Sort sort)
            throws markets.alpaca.client.openapi.data.http.ApiException {
      return tradesHttpResponse;
    }

    @Override
    public StockTradesRespSingle stockTradeSingle(
        String symbol,
        OffsetDateTime start,
        OffsetDateTime end,
        Integer limit,
        String asof,
        StockHistoricalFeed feed,
        String currency,
        String pageToken,
        Sort sort)
        throws markets.alpaca.client.openapi.data.http.ApiException {
      return singleTradesResponse;
    }

    @Override
    public markets.alpaca.client.openapi.data.http.ApiResponse<StockTradesRespSingle>
        stockTradeSingleWithHttpInfo(
            String symbol,
            OffsetDateTime start,
            OffsetDateTime end,
            Integer limit,
            String asof,
            StockHistoricalFeed feed,
            String currency,
            String pageToken,
            Sort sort)
            throws markets.alpaca.client.openapi.data.http.ApiException {
      return singleTradesHttpResponse;
    }
  }
}
