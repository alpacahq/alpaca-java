# Testing alpaca-java

This is maintainer documentation. It is intentionally outside `docs/content/` and is not published
by Docusaurus.

## Commands

```bash
./gradlew test             # unit tests; no credentials or network required
./gradlew integrationTest  # live read-only tests; skips when credentials are absent
./gradlew check            # unit tests, quality checks, and example compilation
```

Use JUnit 5. Add unit tests in the same package as the class under test. Integration tests are
`@Tag("integration")` tests in `src/test/java/markets/alpaca/client/integration/`.

## Integration credentials

For local development, use the gitignored repository-root `local.properties`:

```properties
tradingApiKeyId=PKxxxxxxxxxxxxxxxxxx
tradingApiSecretKey=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
tradingApiEnvironment=paper
brokerApiKeyId=xxxxxxxxxxxxxxxxxx
brokerApiSecretKey=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
brokerApiEnvironment=sandbox
```

For CI or shell use, set the equivalent `APCA_TRADING_*` and `APCA_BROKER_*` environment
variables. Paper API keys are available from the Alpaca dashboard. Broker tests additionally
require sandbox credentials.

## Coverage

All integration tests are read-only. They cover Trading account, asset, orders, positions, and
portfolio history; Market Data stock bars, quotes, crypto bars, and news; Broker accounts and
orders; Broker Events SSE; stock, crypto, and news WebSocket authentication; and Trading stream
authentication. State-changing example paths remain opt-in and are never run by these tests.
