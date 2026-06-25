---
id: broker
title: Broker
---

The Broker API allows you to build investment services. The Broker API lets you create brokerage accounts on behalf of
your users, fund those accounts, place and manage orders on behalf of those accounts, journal cash and securities
between accounts, and more.

Some common use cases of Broker API are:

* Trading/investing app (non-financial institution)
* Broker dealer (fully-disclosed, non-disclosed, omnibus)
* Registered Investment Advisor (RIA)

We support most use cases internationally.

The Broker API lets you build brokerage experiences: create and manage end-user accounts, inspect
trading accounts, fund accounts, journal cash or securities, place orders for sub-accounts, and
stream Broker Events.

Broker REST endpoints are generated from Alpaca's Broker OpenAPI spec. Use
`AlpacaClientFactory.brokerClient(...)` or `client.newBrokerClient()` to get a generated Broker
client with the correct HTTP Basic authentication scheme.

## Sandbox client

Sandbox is the default Broker environment. Load Broker credentials separately from Trading
credentials.

```java
import markets.alpaca.client.AlpacaClientFactory;
import markets.alpaca.client.AlpacaCredentials;
import markets.alpaca.client.BrokerApiEnvironment;

var brokerCredentials = AlpacaCredentials.fromBrokerApiEnvironmentVariables();

var brokerClient = AlpacaClientFactory.brokerClient(
    brokerCredentials,
    BrokerApiEnvironment.SANDBOX);
```

Use `BrokerApiEnvironment.PRODUCTION` only when you intentionally want the live Broker API.

## Generated request models

Most Broker REST methods take generated request models from
`markets.alpaca.client.openapi.broker.model`. These models mirror the Broker OpenAPI spec used by
your build. Keep Broker models separate from Trading API models even when the class names are
similar, especially for orders, positions, and enums.

## Accounts

Use the generated `AccountsApi` to create accounts, list accounts, and inspect a selected account.
Account creation requires the full Broker account payload: `contact`, `identity`, `disclosures`,
and `agreements`. Depending on the account workflow, you may also include `documents`,
`trusted_contact`, `account_type`, and `enabled_assets`.

```java
import java.time.OffsetDateTime;
import markets.alpaca.client.openapi.broker.api.AccountsApi;
import markets.alpaca.client.openapi.broker.model.AccountCreationRequest;

var accountsApi = new AccountsApi(brokerClient);

var accountRequest = AccountCreationRequest.fromJson("""
    {
      "contact": {
        "email_address": "jane.alpaca@example.com",
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
        "funding_source": ["employment_income"]
      },
      "disclosures": {
        "is_control_person": false,
        "is_affiliated_exchange_or_finra": false,
        "is_politically_exposed": false,
        "immediate_family_exposed": false
      },
      "agreements": [
        {
          "agreement": "customer_agreement",
          "signed_at": "2024-01-01T00:00:00Z",
          "ip_address": "127.0.0.1"
        }
      ],
      "account_type": "trading",
      "enabled_assets": ["us_equity"]
    }
    """);
var createdAccount = accountsApi.createAccount(accountRequest);

var createdAfter = OffsetDateTime.parse("2022-01-30T00:00:00Z");
var accounts = accountsApi.getAllAccounts(
    null,                 // query
    createdAfter,         // created_after
    null,                 // created_before
    null,                 // status
    "desc",               // sort
    "contact,identity");  // include related entities
accounts.stream().limit(5).forEach(account ->
    System.out.printf(
        "account=%s number=%s status=%s%n",
        account.getId(),
        account.getAccountNumber(),
        account.getStatus()));
```

`BrokerApiExample.java` contains a sandbox-only account creation sample guarded by
`APCA_EXAMPLE_CREATE_BROKER_ACCOUNT=true`.

## Trading accounts

Broker accounts have account-management data and trading-account data. Use `AccountsApi` to load
the trading account before placing orders for a sub-account.

```java
import java.util.UUID;

UUID accountId = UUID.fromString("00000000-0000-0000-0000-000000000000");

var account = accountsApi.getAccount(accountId);
var tradingAccount = accountsApi.getTradingAccount(accountId);

System.out.printf(
    "account=%s status=%s buyingPower=%s%n",
    account.getId(),
    account.getStatus(),
    tradingAccount.getBuyingPower());
```

## Sub-account orders

Broker sub-account trading uses generated Broker models, not the generated Trading API models. Keep
the two packages separate when importing order request classes and enums.

```java
import java.math.BigDecimal;
import markets.alpaca.client.openapi.broker.api.TradingApi;
import markets.alpaca.client.openapi.broker.model.CreateOrderRequest;
import markets.alpaca.client.openapi.broker.model.OrderSide;
import markets.alpaca.client.openapi.broker.model.OrderType;
import markets.alpaca.client.openapi.broker.model.TimeInForce;

var tradingApi = new TradingApi(brokerClient);

var request = new CreateOrderRequest()
    .symbol("AAPL")
    .qty(BigDecimal.ONE)
    .side(OrderSide.BUY)
    .type(OrderType.LIMIT)
    .limitPrice(new BigDecimal("0.01"))
    .timeInForce(TimeInForce.DAY);

var order = tradingApi.createOrderForAccount(accountId, request);
tradingApi.deleteOrderForAccount(accountId, order.getId().toString());
```

The same generated `TradingApi` exposes Broker positions for the sub-account.

```java
var positions = tradingApi.getPositionsForAccount(accountId);

positions.forEach(position ->
    System.out.printf(
        "symbol=%s qty=%s marketValue=%s%n",
        position.getSymbol(),
        position.getQty(),
        position.getMarketValue()));
```

## Funding

Use the generated `FundingApi` for ACH relationships, bank relationships, and transfers. Before you
can fund an account, create an external account connection. ACH relationships can be created with
routing and account numbers or with a Plaid processor token for Alpaca.

Transfers use `CreateTransferRequest`: ACH transfers set `transferType(TransferType.ACH)` and a
`relationshipId`, while wire withdrawals set `transferType(TransferType.WIRE)` and a `bankId` from a
recipient bank. The generated `timing` field is deprecated and ignored by the API.

These calls create real Broker resources in the selected environment, so keep sandbox and production
credentials visibly separate.

```java
import java.math.BigDecimal;
import markets.alpaca.client.openapi.broker.api.FundingApi;
import markets.alpaca.client.openapi.broker.model.CreateACHRelationshipRequest;
import markets.alpaca.client.openapi.broker.model.CreateTransferRequest;
import markets.alpaca.client.openapi.broker.model.TransferDirection;
import markets.alpaca.client.openapi.broker.model.TransferType;

var funding = new FundingApi(brokerClient);

var ach = new CreateACHRelationshipRequest()
    .accountOwnerName("Jane Alpaca")
    .bankAccountNumber("123456789ABC")
    .bankRoutingNumber("121000358")
    .bankAccountType(CreateACHRelationshipRequest.BankAccountTypeEnum.CHECKING);

var relationship = funding.createACHRelationshipForAccount(accountId, ach);

var plaidAch = new CreateACHRelationshipRequest()
    .processorToken("processor-token-from-plaid");

var transfer = new CreateTransferRequest()
    .transferType(TransferType.ACH)
    .direction(TransferDirection.INCOMING)
    .relationshipId(relationship.getId())
    .amount(new BigDecimal("10.00"));

funding.createTransferForAccount(accountId, transfer);
```

## Journals

The journals API moves cash or securities between accounts under your management. Use
`CreateJournalRequest` for a single one-to-one journal and `BatchJournalRequest` for one-to-many
cash journals. Supply an idempotency key for production journal creation so retries do not duplicate
the movement.

```java
import java.util.UUID;
import markets.alpaca.client.openapi.broker.api.JournalsApi;
import markets.alpaca.client.openapi.broker.model.BatchJournalRequest;
import markets.alpaca.client.openapi.broker.model.BatchJournalRequestEntriesInner;
import markets.alpaca.client.openapi.broker.model.CreateJournalRequest;
import markets.alpaca.client.openapi.broker.model.JournalEntryType;

var journals = new JournalsApi(brokerClient);

UUID fromAccountId = UUID.fromString("00000000-0000-0000-0000-000000000001");
UUID toAccountId = UUID.fromString("00000000-0000-0000-0000-000000000002");

var cashJournal = new CreateJournalRequest()
    .fromAccount(fromAccountId)
    .toAccount(toAccountId)
    .entryType(JournalEntryType.JNLC)
    .amount("50.00")
    .currency("USD");

var journal = journals.createJournal(cashJournal, UUID.randomUUID().toString());

var batch = new BatchJournalRequest()
    .fromAccount(fromAccountId)
    .entryType(BatchJournalRequest.EntryTypeEnum.JNLC)
    .addEntriesItem(new BatchJournalRequestEntriesInner()
        .toAccount(toAccountId)
        .amount("50.00")
        .currency("USD"))
    .addEntriesItem(new BatchJournalRequestEntriesInner()
        .toAccount(UUID.fromString("00000000-0000-0000-0000-000000000003"))
        .amount("100.00")
        .currency("USD"));

var batchJournals = journals.createBatchJournal(batch, UUID.randomUUID().toString());
```

## Broker Events SSE

Broker Events use Server-Sent Events rather than the generated blocking REST methods. This SDK
provides a handwritten SSE wrapper.

```java
import markets.alpaca.client.AlpacaClientFactory;
import markets.alpaca.client.broker.sse.BrokerSseDateOptions;
import markets.alpaca.client.broker.sse.BrokerSseEventListener;
import markets.alpaca.client.openapi.broker.model.TradeUpdateEventV2;

var events = AlpacaClientFactory.brokerEventsSseClient(brokerClient);

var subscription = events.subscribeToTradeEvents(
    BrokerSseDateOptions.empty(),
    new BrokerSseEventListener<TradeUpdateEventV2>() {
        @Override
        public void onEvent(TradeUpdateEventV2 event) {
            System.out.println(event);
        }
    });

// later:
subscription.close();
```

For the broader live-events model, listener callback guidance, and how Broker SSE differs from the
WebSocket stream clients, see [Streaming and events](./streaming).

See the full runnable Broker workflow in
`examples/src/main/java/markets/alpaca/client/examples/BrokerApiExample.java`.
