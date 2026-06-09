# Alpaca Java Client Examples

Runnable examples for the same workflows covered by the Alpaca Python SDK guides:

- Trading API: account, assets, orders, positions, portfolio history, and trading stream concepts.
- Broker API: account management, sub-account trading, funding, and Broker Events SSE.
- Market Data API: stock bars/quotes, crypto bars, news, and live stock data streaming.

The examples use `AlpacaClientFactory` instead of instantiating generated `ApiClient` classes directly. This keeps authentication and base URLs consistent with the rest of this Java client.

## Run

From the repository root:

```bash
./gradlew -p examples runTrading
./gradlew -p examples runBroker
./gradlew -p examples runMarketData
./gradlew -p examples runPagination
```

The nested Gradle build uses `includeBuild('..')`, so it compiles against the local checkout.
The root project also runs `./gradlew -p examples compileJava` from its `check` lifecycle, so examples stay compile-checked without being packaged in the published client artifact.

## Credentials

Trading and market data use trading API credentials. Like the integration tests, the examples read the repository root `local.properties` first:

```properties
tradingApiKeyId=PK...
tradingApiSecretKey=...
tradingApiEnvironment=paper
```

Environment variables are still supported for CI and shell runs:

```bash
export APCA_TRADING_KEY_ID=PK...
export APCA_TRADING_SECRET_KEY=...
export APCA_TRADING_ENVIRONMENT=paper
```

Broker examples prefer broker sandbox credentials. You can add these to `local.properties`:

```properties
brokerApiKeyId=...
brokerApiSecretKey=...
brokerApiEnvironment=sandbox
```

Or set them as environment variables:

```bash
export APCA_BROKER_KEY_ID=...
export APCA_BROKER_SECRET_KEY=...
export APCA_BROKER_ENVIRONMENT=sandbox
```

## Safe Defaults

By default, examples perform read-only calls. State-changing operations require explicit opt-in:

```bash
export APCA_EXAMPLE_PLACE_ORDER=true                 # TradingApiExample paper limit order
export APCA_EXAMPLE_CREATE_BROKER_ACCOUNT=true      # BrokerApiExample sandbox account creation
export APCA_EXAMPLE_BROKER_PLACE_ORDER=true         # BrokerApiExample sub-account order
export APCA_EXAMPLE_BROKER_FUNDING=true             # BrokerApiExample sandbox ACH + deposit
export APCA_EXAMPLE_STREAM=true                     # MarketDataExample stock quote stream
export APCA_EXAMPLE_BROKER_SSE=true                 # BrokerApiExample trade-events SSE stream
```

Useful optional settings:

```bash
export APCA_EXAMPLE_SYMBOL=AAPL
export APCA_EXAMPLE_CRYPTO_SYMBOLS=BTC/USD,ETH/USD
export APCA_TRADING_BASE_URL=https://paper-api.alpaca.markets
export APCA_BROKER_BASE_URL=https://broker-api.sandbox.alpaca.markets
export APCA_BROKER_ACCOUNT_ID=<sandbox-account-uuid>
```

## Files

- `TradingApiExample.java` creates a paper Trading client, reads account/assets/orders/positions/portfolio history, and can submit then cancel a far-away limit order.
- `BrokerApiExample.java` creates a Broker client, lists accounts, loads a trading account, and can create sandbox accounts, orders, ACH relationships, deposits, and SSE subscriptions.
- `MarketDataExample.java` creates a Market Data client, requests stock/crypto/news data, and can subscribe to live IEX stock quotes.
- `PaginationExample.java` uses `AlpacaPagination.collectDataItems` with `NewsApi.newsWithHttpInfo(...)` and `NewsResp::getNextPageToken`.
