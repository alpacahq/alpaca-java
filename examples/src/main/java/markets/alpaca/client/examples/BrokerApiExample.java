package markets.alpaca.client.examples;

import java.math.BigDecimal;
import java.util.UUID;
import markets.alpaca.client.AlpacaClientFactory;
import markets.alpaca.client.broker.sse.BrokerSseDateOptions;
import markets.alpaca.client.broker.sse.BrokerSseEventListener;
import markets.alpaca.client.openapi.broker.api.AccountsApi;
import markets.alpaca.client.openapi.broker.api.FundingApi;
import markets.alpaca.client.openapi.broker.api.TradingApi;
import markets.alpaca.client.openapi.broker.model.AccountCreationRequest;
import markets.alpaca.client.openapi.broker.model.CreateACHRelationshipRequest;
import markets.alpaca.client.openapi.broker.model.CreateOrderRequest;
import markets.alpaca.client.openapi.broker.model.CreateTransferRequest;
import markets.alpaca.client.openapi.broker.model.OrderSide;
import markets.alpaca.client.openapi.broker.model.OrderType;
import markets.alpaca.client.openapi.broker.model.TimeInForce;
import markets.alpaca.client.openapi.broker.model.TransferDirection;
import markets.alpaca.client.openapi.broker.model.TransferType;

/**
 * Broker API workflow: authenticate, inspect accounts, optionally create a sandbox account, trade
 * for a sub-account, fund a sub-account, and subscribe to Broker Events SSE.
 */
public final class BrokerApiExample {
  private BrokerApiExample() {}

  public static void main(String[] args) throws Exception {
    var credentials = ExampleSupport.brokerCredentials();
    String baseUrlOverride =
        ExampleSupport.setting("brokerApiBaseUrl", "APCA_BROKER_BASE_URL", null);
    var brokerClient =
        baseUrlOverride == null
            ? AlpacaClientFactory.brokerClient(credentials, ExampleSupport.brokerApiEnvironment())
            : AlpacaClientFactory.brokerClient(credentials, baseUrlOverride);

    var accounts = new AccountsApi(brokerClient);
    var trading = new TradingApi(brokerClient);
    var funding = new FundingApi(brokerClient);

    ExampleSupport.printSection("Accounts");
    var allAccounts = accounts.getAllAccounts(null, null, null, null, "desc", null);
    allAccounts.stream()
        .limit(5)
        .forEach(
            account ->
                System.out.printf(
                    "account=%s number=%s status=%s%n",
                    account.getId(), account.getAccountNumber(), account.getStatus()));

    UUID accountId = configuredAccountId(allAccounts.isEmpty() ? null : allAccounts.get(0).getId());
    if (accountId != null) {
      ExampleSupport.printSection("Trading Account");
      var account = accounts.getAccount(accountId);
      System.out.printf("loaded account=%s status=%s%n", account.getId(), account.getStatus());

      var tradingAccount = accounts.getTradingAccount(accountId);
      System.out.printf(
          "trading account=%s buyingPower=%s%n",
          tradingAccount.getAccountNumber(), tradingAccount.getBuyingPower());

      ExampleSupport.printSection("Broker Orders");
      trading
          .getAllOrdersForAccount(
              accountId, "open", 50, null, null, null, "desc", true, null, null, null, null)
          .forEach(
              order ->
                  System.out.printf(
                      "order=%s symbol=%s status=%s%n",
                      order.getId(), order.getSymbol(), order.getStatus()));
    }

    if (ExampleSupport.enabled("APCA_EXAMPLE_CREATE_BROKER_ACCOUNT")) {
      createSandboxAccount(accounts);
    } else {
      ExampleSupport.printSection("Account Creation");
      System.out.println(
          "Skipped. Set APCA_EXAMPLE_CREATE_BROKER_ACCOUNT=true to create a sandbox account.");
    }

    if (accountId != null && ExampleSupport.enabled("APCA_EXAMPLE_BROKER_PLACE_ORDER")) {
      placeAndCancelBrokerOrder(trading, accountId);
    } else {
      ExampleSupport.printSection("Broker Order Submission");
      System.out.println(
          "Skipped. Set APCA_EXAMPLE_BROKER_PLACE_ORDER=true and APCA_BROKER_ACCOUNT_ID to trade.");
    }

    if (accountId != null && ExampleSupport.enabled("APCA_EXAMPLE_BROKER_FUNDING")) {
      createAchAndDeposit(funding, accountId);
    } else {
      ExampleSupport.printSection("Funding");
      System.out.println(
          "Skipped. Set APCA_EXAMPLE_BROKER_FUNDING=true and APCA_BROKER_ACCOUNT_ID to fund.");
    }

    if (ExampleSupport.enabled("APCA_EXAMPLE_BROKER_SSE")) {
      subscribeToTradeEvents(brokerClient);
    } else {
      ExampleSupport.printSection("Broker Events SSE");
      System.out.println(
          "Skipped. Set APCA_EXAMPLE_BROKER_SSE=true to open a trade-events stream.");
    }
  }

  private static UUID configuredAccountId(UUID fallback) {
    String configured = ExampleSupport.env("APCA_BROKER_ACCOUNT_ID");
    return configured == null ? fallback : UUID.fromString(configured);
  }

  private static void createSandboxAccount(AccountsApi accounts) throws Exception {
    ExampleSupport.printSection("Account Creation");
    String uniqueEmail = "java-example+" + UUID.randomUUID() + "@example.com";

    var request =
        AccountCreationRequest.fromJson(
            SANDBOX_ACCOUNT_JSON
                .replace("${EMAIL}", uniqueEmail)
                .replace("${SIGNED_AT}", java.time.OffsetDateTime.now().toString()));

    var account = accounts.createAccount(request);
    System.out.printf(
        "created account=%s number=%s status=%s%n",
        account.getId(), account.getAccountNumber(), account.getStatus());
  }

  private static void placeAndCancelBrokerOrder(TradingApi trading, UUID accountId)
      throws Exception {
    ExampleSupport.printSection("Broker Order Submission");

    var request =
        new CreateOrderRequest()
            .symbol(ExampleSupport.env("APCA_EXAMPLE_SYMBOL", "AAPL"))
            .qty(BigDecimal.ONE)
            .side(OrderSide.fromValue("buy"))
            .type(OrderType.fromValue("limit"))
            .limitPrice(new BigDecimal("0.01"))
            .timeInForce(TimeInForce.fromValue("day"))
            .clientOrderId("alpaca-java-broker-example-" + UUID.randomUUID());

    var order = trading.createOrderForAccount(accountId, request);
    System.out.printf("submitted order=%s status=%s%n", order.getId(), order.getStatus());

    trading.deleteOrderForAccount(accountId, order.getId());
    System.out.printf("cancel requested for order=%s%n", order.getId());
  }

  private static void createAchAndDeposit(FundingApi funding, UUID accountId) throws Exception {
    ExampleSupport.printSection("Funding");

    var ach =
        new CreateACHRelationshipRequest()
            .accountOwnerName("Jane Alpaca")
            .bankAccountNumber("123456789ABC")
            .bankRoutingNumber("121000358")
            .bankAccountType(
                CreateACHRelationshipRequest.BankAccountTypeEnum.fromValue("CHECKING"));

    var relationship = funding.createACHRelationshipForAccount(accountId, ach);
    System.out.printf("created ACH relationship=%s%n", relationship.getId());

    var transfer =
        new CreateTransferRequest()
            .transferType(TransferType.fromValue("ach"))
            .direction(TransferDirection.fromValue("INCOMING"))
            .relationshipId(relationship.getId())
            .amount(new BigDecimal("10.00"));

    var deposit = funding.createTransferForAccount(accountId, transfer);
    System.out.printf("created transfer=%s status=%s%n", deposit.getId(), deposit.getStatus());
  }

  private static void subscribeToTradeEvents(
      markets.alpaca.client.openapi.broker.http.ApiClient brokerClient) throws Exception {
    ExampleSupport.printSection("Broker Events SSE");

    var events = AlpacaClientFactory.brokerEventsSseClient(brokerClient);
    var subscription =
        events.subscribeToTradeEvents(
            BrokerSseDateOptions.empty(),
            new BrokerSseEventListener<>() {
              @Override
              public void onEvent(
                  markets.alpaca.client.openapi.broker.model.TradeUpdateEventV2 event) {
                System.out.println(event);
              }
            });

    try {
      Thread.sleep(15_000);
    } finally {
      subscription.close();
    }
  }

  private static final String SANDBOX_ACCOUNT_JSON =
      """
            {
              "contact": {
                "email_address": "${EMAIL}",
                "phone_number": "555-666-7788",
                "street_address": ["20 N San Mateo Dr"],
                "city": "San Mateo",
                "state": "CA",
                "postal_code": "94401",
                "country": "USA"
              },
              "identity": {
                "given_name": "Jane",
                "family_name": "Alpaca",
                "date_of_birth": "1990-01-01",
                "tax_id": "666-55-4321",
                "tax_id_type": "USA_SSN",
                "country_of_citizenship": "USA",
                "country_of_birth": "USA",
                "country_of_tax_residence": "USA",
                "funding_source": ["employment_income"],
                "annual_income_min": "50000",
                "annual_income_max": "100000",
                "liquid_net_worth_min": "50000",
                "liquid_net_worth_max": "100000",
                "total_net_worth_min": "50000",
                "total_net_worth_max": "100000",
                "investment_objective": "growth",
                "investment_experience_with_stocks": "over_5_years",
                "risk_tolerance": "medium",
                "liquidity_needs": "does_not_matter",
                "investment_time_horizon": "more_than_10_years",
                "marital_status": "single",
                "number_of_dependents": 0
              },
              "disclosures": {
                "is_control_person": false,
                "is_affiliated_exchange_or_finra": false,
                "is_politically_exposed": false,
                "immediate_family_exposed": false,
                "employment_status": "EMPLOYED",
                "employment_position": "Software Engineer",
                "employer_name": "Example Co",
                "employer_address": "20 N San Mateo Dr, San Mateo, CA 94401",
                "employment_sector": "technology"
              },
              "agreements": [
                {
                  "agreement": "customer_agreement",
                  "signed_at": "${SIGNED_AT}",
                  "ip_address": "127.0.0.1"
                }
              ],
              "account_type": "trading",
              "enabled_assets": ["us_equity"]
            }
            """;
}
