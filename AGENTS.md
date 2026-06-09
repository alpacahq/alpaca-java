# AGENTS.md — alpaca-java-client

Java client for Alpaca Markets APIs. REST clients are **generated from OpenAPI specs** at build time. WebSocket clients are handwritten and committed. Read this before making any changes.

If you are generating application code that uses this SDK rather than modifying the SDK itself, read `LLMS.md` first. `AGENTS.md` is for repo maintenance and contribution rules; `LLMS.md` is for consumer-facing SDK usage.

## Build commands

```bash
./gradlew build                      # full build (generates + compiles + tests)
./gradlew generateApis               # regenerate all three API clients only
./gradlew generateBrokerApi          # regenerate broker only
./gradlew generateDataApi            # regenerate data only
./gradlew generateTradingApi         # regenerate trading only
./gradlew clean generateApis build   # force clean regeneration
./gradlew test                       # run unit tests (no credentials needed)
./gradlew integrationTest            # run integration tests (requires paper credentials — see below)
./gradlew compileExamples            # compile examples without packaging them
./gradlew javadoc                    # generate Javadoc
./gradlew generateJavadocs           # generate Javadoc via the project documentation task
```

Run `./gradlew tasks --group="openapi tools"` to list all generation-related tasks.

## Project layout

```
src/main/java/markets/alpaca/client/   ← HANDWRITTEN only — committed to git
├── AlpacaClient.java
├── AlpacaClientFactory.java
├── AlpacaCredentials.java
├── data/                             ← handwritten Market Data helpers
├── http/                             ← handwritten OkHttp/retry helpers
├── rest/                             ← handwritten REST utility helpers
├── trading/                          ← handwritten Trading helpers
├── broker/sse/                       ← Broker Events SSE wrapper
└── ws/                               ← WebSocket stream clients, listeners, subscriptions, models

build/generated/                      ← raw generator output (gitignored)
├── broker/src/main/java/markets/alpaca/client/openapi/broker/{api,model,http}/
├── data/src/main/java/markets/alpaca/client/openapi/data/{api,model,http}/
└── trading/src/main/java/markets/alpaca/client/openapi/trading/{api,model,http}/

build/specs/                           ← preprocessed (sanitised) OAS copies (gitignored)
```

OAS spec sources — by default the official Alpaca public documentation URLs are used.
To use local files, see the [OAS spec sources](#oas-spec-path-configuration) section below.
When working against the private repo:
```
~/source/alpacah/alpaca-docs-private/oas/
├── broker/openapi.yaml
├── data/openapi.yaml
└── trading/openapi.yaml
```

## Three independent API clients

Each API has its own `ApiClient` in its own `http` subpackage with a different default base URL and server configuration. Always use `AlpacaClientFactory` — never instantiate `ApiClient` directly:

| API | `ApiClient` class | Auth | Default base URL |
|---|---|---|---|
| Broker | `markets.alpaca.client.openapi.broker.http.ApiClient` | HTTP Basic | `https://broker-api.sandbox.alpaca.markets` |
| Data | `markets.alpaca.client.openapi.data.http.ApiClient` | Header keys | `https://data.alpaca.markets` |
| Trading | `markets.alpaca.client.openapi.trading.http.ApiClient` | Header keys | `https://paper-api.alpaca.markets` |

Always use `AlpacaClientFactory` factory methods — never instantiate `ApiClient` directly:

```java
var creds   = new AlpacaCredentials(keyId, secret);
var broker  = AlpacaClientFactory.brokerClient(creds);
var data    = AlpacaClientFactory.dataClient(creds);
var trading = AlpacaClientFactory.tradingClient(creds);
```

## What is generated vs handwritten

**Generated** (in `build/generated/`, gitignored — never edit):
- `markets.alpaca.client.openapi.broker.{api,model,http}` — Broker endpoints, models, and HTTP client
- `markets.alpaca.client.openapi.data.{api,model,http}` — Data endpoints, models, and HTTP client
- `markets.alpaca.client.openapi.trading.{api,model,http}` — Trading endpoints, models, and HTTP client

Each `http` package contains a distinct `ApiClient` with the correct default base URL and server configuration for that API. They are not interchangeable.

**Handwritten** (in `src/main/java/markets/alpaca/client/`, committed to git):
- `AlpacaClientFactory` — pre-configures auth and HTTP client for each API
- `AlpacaCredentials` — credential value object (key ID + secret key)
- `data/**` — handwritten Market Data helpers
- `http/**` — handwritten OkHttp singleton, builder factory, retry policy, and retry interceptor
- `rest/**` — handwritten REST utilities such as pagination and futures adapters
- `trading/**` — handwritten Trading helpers
- `broker/sse/**` — handwritten Broker Events SSE wrapper
- `ws/**` — handwritten WebSocket stream clients, listeners, subscriptions, and model records

## How to add new features

### Adding a helper / utility
Add it to the appropriate handwritten package under `src/main/java/markets/alpaca/client/`: `trading/`, `data/`, `rest/`, `http/`, `broker/sse/`, or `ws/`. Keep root-package additions for top-level SDK concepts only. Never add handwritten code under `markets.alpaca.client.openapi/**` — that namespace is owned by the generator.

### Fixing a generated class behaviour
Do NOT modify the generated file. Instead:
1. If it's a **spec bug** → add a fix to the relevant preprocess task in `build.gradle` (see below)
2. If it's a **generator limitation** → wrap the generated class in a handwritten decorator in `src/main/java/`

### Changing generator options
Edit `commonConfigOptions` in `build.gradle`. Options apply to all three generators. Run `./gradlew clean generateApis build` after any change to regenerate cleanly.

## Spec preprocessing pipeline

Before code generation, specs are sanitised by tasks in `build.gradle`. The original specs are **never modified**.

| Task | Input | Fix applied |
|---|---|---|
| `preprocessBrokerSpec` | `broker/openapi.yaml` | Removes empty-key properties; removes `enum` from discriminator properties; removes `ActivityV2DetailTRD.required` |
| `preprocessDataSpec` | `data/openapi.yaml` | Removes `x-internal: true` from schemas |
| `preprocessTradingSpec` | `trading/openapi.yaml` | Removes `ActivityV2DetailTRD.required` |

Preprocessed copies go to `build/specs/{broker,data,trading}/openapi.yaml`.

To add a new fix, add it to the appropriate `removeXxx()` helper method or write a new one in `build.gradle`. Helper methods use SnakeYAML to manipulate the parsed YAML tree — do NOT use string/regex manipulation on the raw YAML text.

## Adding a new preprocess fix — example

```groovy
// In build.gradle — add a new helper method at the script level:
static void fixSomeIssue(Map spec) {
    spec?.components?.schemas?.each { name, schema ->
        // manipulate the parsed tree
    }
}

// Then call it inside the relevant preprocess task's doLast block.
// The spec is already loaded from the configured source (file or URL) into a Map;
// just call your helper and re-serialise with dumpYaml():
tasks.named('preprocessTradingSpec').configure {
    doLast {
        def outputFile = preprocessedTradingSpec.get().asFile
        outputFile.parentFile.mkdirs()
        def spec = loadSpec(tradingSpecSource) as Map
        removeActivityV2DetailTrdRequired(spec)
        fixSomeIssue(spec)
        outputFile.text = dumpYaml(spec)
    }
}
```

## OAS spec path configuration

Each spec can be a **local file path** or a **remote URL** (`http`/`https`).
By default the build fetches the official Alpaca public specs — no local files are required.

| Spec | Default URL |
|---|---|
| Broker | `https://docs.alpaca.markets/openapi/broker-api.json` |
| Market Data | `https://docs.alpaca.markets/openapi/market-data-api.json` |
| Trading | `https://docs.alpaca.markets/openapi/trading-api.json` |

Resolution order per spec (first match wins):

1. **Individual Gradle property** (highest priority):
   ```bash
   ./gradlew -PbrokerSpec=/path/or/url -PdataSpec=/path/or/url -PtradingSpec=/path/or/url build
   ```
2. **Individual environment variable**:
   ```bash
   APCA_BROKER_SPEC=... APCA_DATA_SPEC=... APCA_TRADING_SPEC=... ./gradlew build
   ```
3. **`local.properties`** individual keys (gitignored):
   ```properties
   brokerSpec=/path/to/broker/openapi.yaml
   dataSpec=/path/to/data/openapi.yaml
   tradingSpec=/path/to/trading/openapi.yaml
   ```
4. **Legacy `oasRoot`** — sets all three specs to `<oasRoot>/{broker,data,trading}/openapi.yaml`:
   ```bash
   # Gradle flag:
   ./gradlew -PoasRoot=/path/to/alpaca-docs-private/oas build
   # Environment variable:
   APCA_OAS_ROOT=/path/to/alpaca-docs-private/oas ./gradlew build
   # local.properties (recommended for daily local dev against the private repo):
   oasRoot=/path/to/alpaca-docs-private/oas
   ```
5. **Default public URL** (lowest priority, no configuration needed)

## Incremental builds

Gradle tracks local spec files as task inputs. If the specs haven't changed since the last run, the generate tasks are skipped as "up-to-date". A `./gradlew build` is safe to run frequently.

When using remote URL sources, the preprocessing tasks always re-run (Gradle cannot track remote URLs for up-to-date checks).

Only run `./gradlew clean generateApis build` when:
- A preprocessing fix is added or changed
- The generator version changes
- Generated output looks stale or corrupted

## DO NOT

- **Do not edit generated files** — everything under `build/` is overwritten on every build. Edit the OAS spec or add a preprocessing fix instead.
- **Do not instantiate `ApiClient` directly** — always go through `AlpacaClientFactory` so the correct auth scheme is configured for the target API.
- **Do not use regex or string substitution to patch OAS YAML** — use the `loadSpec` / `dumpYaml` / SnakeYAML helpers already in `build.gradle`.
- **Do not modify the OAS specs** to work around generator issues — add a preprocessing fix in `build.gradle` instead.
- **Do not put handwritten code under `markets.alpaca.client.openapi/**`** — generated REST packages are overwritten on every build. WebSocket endpoints are not defined by the OAS specs, so handwritten streaming code belongs under `src/main/java/markets/alpaca/client/ws/`.
- **Do not use `./gradlew clean` routinely** — it deletes all generated code and forces a full regeneration. Only use it when explicitly needed.

## Tests

### Unit tests

Located in `src/test/java/markets/alpaca/client/`. Run with `./gradlew test`. No credentials or network access required.

```
src/test/java/markets/alpaca/client/
├── AlpacaClientFactoryTest.java
├── AlpacaCredentialsTest.java
├── data/                     ← Market Data helper tests
├── http/                     ← OkHttp/retry helper tests
├── rest/                     ← REST utility tests
├── trading/                  ← Trading helper tests
├── ws/                       ← WebSocket unit tests and model deserialization tests
└── integration/              ← @Tag("integration") — excluded from ./gradlew test
    └── IntegrationIT.java
```

Use JUnit 5 (`org.junit.jupiter`). New tests go in the same package as the class under test.

### Integration tests

Integration tests call the real Alpaca paper-trading, market-data, and WebSocket APIs. Run with:

```bash
./gradlew integrationTest
```

These tests are tagged `@Tag("integration")` and are **excluded** from `./gradlew test`, so they never break a local build when credentials are absent — they skip automatically. They mirror the read-only workflows in `examples/`; state-changing example paths remain opt-in examples and are not run automatically.

**Supplying credentials (pick one):**

1. `local.properties` (recommended for local dev, gitignored):
   ```properties
   tradingApiKeyId=PKxxxxxxxxxxxxxxxxxx
   tradingApiSecretKey=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
   tradingApiEnvironment=paper
   brokerApiKeyId=xxxxxxxxxxxxxxxxxx
   brokerApiSecretKey=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
   brokerApiEnvironment=sandbox
   ```
2. Environment variables (recommended for CI):
   ```bash
   export APCA_TRADING_KEY_ID=PKxxxxxxxxxxxxxxxxxx
   export APCA_TRADING_SECRET_KEY=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
   export APCA_TRADING_ENVIRONMENT=paper
   export APCA_BROKER_KEY_ID=xxxxxxxxxxxxxxxxxx
   export APCA_BROKER_SECRET_KEY=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
   export APCA_BROKER_ENVIRONMENT=sandbox
   ./gradlew integrationTest
   ```

Paper API keys are available from [app.alpaca.markets](https://app.alpaca.markets) → Paper Trading → API Keys. Broker sandbox credentials are required only for Broker integration tests.

**Covered endpoints (all read-only, no orders placed):**

| Test | Endpoint | API |
|---|---|---|
| `tradingApi_getAccount_returnsValidAccount` | `GET /v2/account` | Trading (paper) |
| `tradingApi_getAsset_aapl_returnsMatchingSymbol` | `GET /v2/assets/AAPL` | Trading (paper) |
| `tradingApi_getOpenOrders_returnsList` | `GET /v2/orders` | Trading (paper) |
| `tradingApi_getOpenPositions_returnsList` | `GET /v2/positions` | Trading (paper) |
| `tradingApi_getPortfolioHistory_returnsHistory` | `GET /v2/account/portfolio/history` | Trading (paper) |
| `dataApi_stockLatestBar_aapl_returnsBar` | `GET /v2/stocks/AAPL/bars/latest` | Market Data |
| `dataApi_stockHistoricalBars_aapl_returnsBars` | `GET /v2/stocks/AAPL/bars` | Market Data |
| `dataApi_stockLatestQuote_aapl_returnsQuote` | `GET /v2/stocks/AAPL/quotes/latest` | Market Data |
| `dataApi_cryptoLatestBars_returnsBars` | `GET /v1beta3/crypto/us/latest/bars` | Market Data |
| `dataApi_cryptoHistoricalBars_returnsBars` | `GET /v1beta3/crypto/us/bars` | Market Data |
| `dataApi_news_aapl_returnsNewsList` | `GET /v1beta1/news` | Market Data |
| `brokerApi_getAllAccounts_returnsList` | `GET /v1/accounts` | Broker sandbox |
| `brokerApi_getAccountAndTradingAccount_returnsAccountDetailsWhenAccountExists` | `GET /v1/accounts/{account_id}` + trading account | Broker sandbox |
| `brokerApi_getOpenOrdersForAccount_returnsListWhenAccountExists` | `GET /v1/trading/accounts/{account_id}/orders` | Broker sandbox |
| `brokerSse_subscribeToTradeEvents_opensStream` | Broker trade-events SSE | Broker sandbox |
| `stockStream_iex_authenticatesAndReceivesSubscriptionConfirmation` | `wss://stream.data.alpaca.markets/v2/iex` | Stock data stream |
| `cryptoStream_production_authenticatesAndReceivesSubscriptionConfirmation` | `wss://stream.data.alpaca.markets/v1beta3/crypto/us` | Crypto data stream |
| `newsStream_production_authenticatesAndReceivesSubscriptionConfirmation` | `wss://stream.data.alpaca.markets/v1beta1/news` | News stream |
| `tradingStream_paper_authenticatesAndReceivesListeningConfirmation` | `wss://paper-api.alpaca.markets/stream` | Trading stream |
