package markets.alpaca.client.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import markets.alpaca.client.AlpacaClientFactory;
import markets.alpaca.client.AlpacaCredentials;
import markets.alpaca.client.BrokerApiEnvironment;
import markets.alpaca.client.broker.sse.BrokerSseDateOptions;
import markets.alpaca.client.broker.sse.BrokerSseEventListener;
import markets.alpaca.client.openapi.broker.api.AccountsApi;
import markets.alpaca.client.openapi.broker.api.TradingApi;
import okhttp3.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Broker integration tests that mirror read-only Broker example workflows.
 *
 * <p>State-changing example paths (account creation, order submission, ACH creation, and transfers)
 * remain opt-in examples and are intentionally not run automatically.
 */
@Tag("integration")
class BrokerIntegrationIT {

  private static markets.alpaca.client.openapi.broker.http.ApiClient brokerClient;
  private static AccountsApi accountsApi;

  private static String credential(String key) {
    String v = System.getProperty(key);
    return (v != null && !v.isBlank()) ? v : System.getenv(key);
  }

  private static BrokerApiEnvironment brokerEnvironment() {
    String value = credential("APCA_BROKER_ENVIRONMENT");
    return value == null ? BrokerApiEnvironment.SANDBOX : BrokerApiEnvironment.from(value);
  }

  @BeforeAll
  static void setupClient() {
    String keyId = credential("APCA_BROKER_KEY_ID");
    String secretKey = credential("APCA_BROKER_SECRET_KEY");

    assumeTrue(
        keyId != null && !keyId.isBlank() && secretKey != null && !secretKey.isBlank(),
        "Skipping Broker integration tests — set APCA_BROKER_KEY_ID and APCA_BROKER_SECRET_KEY "
            + "(env vars or local.properties) to run");

    brokerClient =
        AlpacaClientFactory.brokerClient(
            new AlpacaCredentials(keyId, secretKey), brokerEnvironment());
    accountsApi = new AccountsApi(brokerClient);
  }

  @Test
  void brokerApi_getAllAccounts_returnsList() throws Exception {
    var accounts = accountsApi.getAllAccounts(null, null, null, null, "desc", null);

    assertNotNull(accounts, "accounts list must not be null");
  }

  @Test
  void brokerApi_getAccountAndTradingAccount_returnsAccountDetailsWhenAccountExists()
      throws Exception {
    var accounts = accountsApi.getAllAccounts(null, null, null, null, "desc", null);
    assumeFalse(
        accounts.isEmpty(), "Skipping account-detail workflow because sandbox has no accounts");

    UUID accountId = accounts.get(0).getId();
    var account = accountsApi.getAccount(accountId);
    var tradingAccount = accountsApi.getTradingAccount(accountId);

    assertNotNull(account, "account must not be null");
    assertEquals(accountId, account.getId(), "account.id must match requested account");
    assertNotNull(tradingAccount, "trading account must not be null");
    assertNotNull(tradingAccount.getAccountNumber(), "trading account number must not be null");
  }

  @Test
  void brokerApi_getOpenOrdersForAccount_returnsListWhenAccountExists() throws Exception {
    var accounts = accountsApi.getAllAccounts(null, null, null, null, "desc", null);
    assumeFalse(
        accounts.isEmpty(), "Skipping broker-orders workflow because sandbox has no accounts");

    var tradingApi = new TradingApi(brokerClient);
    var orders =
        tradingApi.getAllOrdersForAccount(
            accounts.get(0).getId(),
            "open",
            50,
            null,
            null,
            null,
            "desc",
            true,
            null,
            null,
            null,
            null);

    assertNotNull(orders, "broker account orders list must not be null");
  }

  @Test
  void brokerSse_subscribeToTradeEvents_opensStream() throws Exception {
    var brokerEvents = AlpacaClientFactory.brokerEventsSseClient(brokerClient);
    var completed = new CountDownLatch(1);
    var failure = new AtomicReference<Throwable>();
    var failureResponseCode = new AtomicReference<Integer>();

    try (var subscription =
        brokerEvents.subscribeToTradeEvents(
            BrokerSseDateOptions.empty(),
            new BrokerSseEventListener<>() {
              @Override
              public void onOpen() {
                completed.countDown();
              }

              @Override
              public void onFailure(Throwable throwable, Response response) {
                failure.set(throwable);
                if (response != null) failureResponseCode.set(response.code());
                completed.countDown();
              }
            })) {
      assertTrue(
          completed.await(20, TimeUnit.SECONDS), "Broker SSE stream did not open within 20s");
    }

    if (failure.get() != null) {
      fail(
          "Broker SSE stream failed with response code " + failureResponseCode.get(),
          failure.get());
    }
  }
}
