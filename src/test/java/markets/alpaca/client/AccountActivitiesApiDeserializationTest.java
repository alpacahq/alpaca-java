package markets.alpaca.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import markets.alpaca.client.http.AlpacaHttpConfig;
import markets.alpaca.client.openapi.trading.api.AccountActivitiesApi;
import markets.alpaca.client.openapi.trading.model.GetAccountActivitiesByActivityType200ResponseInner;
import markets.alpaca.client.openapi.trading.model.NonTradeActivities;
import markets.alpaca.client.openapi.trading.model.TradingActivities;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

class AccountActivitiesApiDeserializationTest {

  private static final String TRADING_ACTIVITY =
      """
      {
        "activity_type": "FILL",
        "cum_qty": "2",
        "id": "20220202135509981::2d7be4ff-d1f3-43e9-856a-0f5cf5c5088e",
        "leaves_qty": "0",
        "order_id": "b5abe576-6a8a-49f3-a353-46b72c1ccae9",
        "order_status": "filled",
        "price": "174.78",
        "qty": "2",
        "side": "buy",
        "symbol": "AAPL",
        "transaction_time": "2022-02-02T18:55:09.981482Z",
        "type": "fill"
      }
      """;

  private static final String NON_TRADE_ACTIVITY =
      """
      {
        "activity_type": "DIV",
        "date": "2022-02-03T00:00:00Z",
        "id": "20220203000000000::045b3b8d-c566-4bef-b741-2bf598dd6ae7",
        "net_amount": "1.25",
        "symbol": "AAPL"
      }
      """;

  @Test
  void generatedResponseModelDeserializesTradingActivity() throws Exception {
    var response = GetAccountActivitiesByActivityType200ResponseInner.fromJson(TRADING_ACTIVITY);

    var activity = assertInstanceOf(TradingActivities.class, response.getActualInstance());
    assertEquals("AAPL", activity.getSymbol());
    assertEquals("2", activity.getQty());
  }

  @Test
  void generatedResponseModelDeserializesNonTradeActivity() throws Exception {
    var response = GetAccountActivitiesByActivityType200ResponseInner.fromJson(NON_TRADE_ACTIVITY);

    var activity = assertInstanceOf(NonTradeActivities.class, response.getActualInstance());
    assertEquals("AAPL", activity.getSymbol());
    assertEquals("1.25", activity.getNetAmount());
  }

  @Test
  void synchronousApiMethodDeserializesTradingActivity() throws Exception {
    try (var server = new MockWebServer()) {
      server.enqueue(
          new MockResponse()
              .setHeader("Content-Type", "application/json")
              .setBody("[" + TRADING_ACTIVITY + "]"));
      var client =
          AlpacaClientFactory.tradingClient(
              new AlpacaCredentials("key", "secret"),
              server.url("/").toString(),
              AlpacaHttpConfig.defaultBuilder().build());
      var api = new AccountActivitiesApi(client);

      var response =
          api.getAccountActivitiesByActivityType("FILL", null, null, null, null, null, null);

      assertEquals(1, response.size());
      var activity = assertInstanceOf(TradingActivities.class, response.get(0).getActualInstance());
      assertEquals("AAPL", activity.getSymbol());
      assertEquals("2", activity.getQty());
    }
  }
}
