# Testing alpaca-java

This is maintainer documentation. It is intentionally outside `docs/content/` and is not published
by Docusaurus.

## Commands

```bash
./gradlew test             # unit tests; no credentials or network required
./gradlew integrationTest  # live read-only tests; skips when credentials are absent
./gradlew check            # unit tests, quality checks, and example compilation
```

Release validation must fail instead of skip when an SSE credential pair or required replay window
is absent:

```bash
./gradlew integrationTest -Palpaca.requireSseIntegration=true \
  --tests 'markets.alpaca.client.integration.IntegrationIT.tradingActivitySse_decodesKnownReplayEvent' \
  --tests 'markets.alpaca.client.integration.IntegrationIT.corporateActionsSse_decodesKnownReplayEvent' \
  --tests 'markets.alpaca.client.integration.BrokerIntegrationIT.brokerSse_subscribeToTradeEvents_opensStream'
```

Use JUnit 5. Add unit tests in the same package as the class under test. Integration tests are
`@Tag("integration")` tests in `src/test/java/markets/alpaca/client/integration/`.

## Integration credentials

For local development, use the gitignored repository-root `local.properties`:

```properties
tradingApiKeyId=PKxxxxxxxxxxxxxxxxxx
tradingApiSecretKey=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
tradingApiEnvironment=paper
sseActivitySince=2026-09-01T00:00:00Z
sseActivityUntil=2026-09-01T01:00:00Z
sseCorporateActionsSince=2026-09-01T00:00:00Z
sseCorporateActionsUntil=2026-09-01T01:00:00Z
brokerApiKeyId=xxxxxxxxxxxxxxxxxx
brokerApiSecretKey=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
brokerApiEnvironment=sandbox
```

For CI or shell use, set the equivalent `APCA_TRADING_*` and `APCA_BROKER_*` environment
variables, plus `APCA_SSE_ACTIVITY_SINCE`, `APCA_SSE_ACTIVITY_UNTIL`,
`APCA_SSE_CORPORATE_ACTIONS_SINCE`, and `APCA_SSE_CORPORATE_ACTIONS_UNTIL`. The RFC 3339 replay
windows must be bounded ranges known to contain at least one event for the supplied credentials.
Paper API keys are available from the Alpaca dashboard. Broker tests additionally require sandbox
credentials.

## Coverage

All integration tests are read-only. They cover Trading account, asset, orders, positions, and
portfolio history; Market Data stock bars, quotes, crypto bars, and news; Broker accounts and
orders; Broker Events SSE connection; decoded Trading activity and Market Data corporate-actions
SSE bounded replay; stock, crypto, and news WebSocket authentication; and Trading stream
authentication.
State-changing example paths remain opt-in and are never run by these tests.
