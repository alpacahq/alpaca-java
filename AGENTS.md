# AGENTS.md — alpaca-java

Repository-maintenance guidance for the Alpaca Java SDK. For Java application code that consumes
this SDK, read `LLMS.md` instead.

## Commands

```bash
./gradlew build                    # generate, compile, and test
./gradlew generateApis             # generate all REST clients
./gradlew generateBrokerApi        # generate Broker only
./gradlew generateDataApi          # generate Market Data only
./gradlew generateTradingApi       # generate Trading only
./gradlew test                     # unit tests
./gradlew integrationTest          # live read-only integration tests
./gradlew compileExamples          # compile examples without packaging
./gradlew generateJavadocs         # generate the API reference
```

Do not routinely run `clean`; use `./gradlew clean generateApis build` only after a preprocessing
fix, generator-version change, or corrupted/stale generated output.

## Architecture invariants

- Handwritten, committed SDK code lives in `src/main/java/markets/alpaca/client/`, including
  `data/`, `http/`, `rest/`, `trading/`, `broker/sse/`, and `ws/`.
- REST clients, models, and HTTP classes are generated into `build/generated/` under
  `markets.alpaca.client.openapi.{broker,data,trading}`. Never edit or add handwritten code there.
- Generated Broker, Data, and Trading `ApiClient` classes are distinct and non-interchangeable.
  Always construct them through `AlpacaClientFactory`; it sets the API-specific authentication and
  base URL.
- Add common SDK behavior to handwritten packages. For generated behavior, fix a spec defect in
  preprocessing or add a handwritten wrapper for a generator limitation.
- WebSocket price and fractional-size fields use `BigDecimal`, never `double` or `float`.

## Generation and OpenAPI specs

`build-logic/src/main/groovy/alpaca.openapi-generation.gradle` configures generation.
`build-logic/src/main/groovy/markets/alpaca/gradle/OpenApiSpecSupport.groovy` contains parsed-YAML
SnakeYAML fixes. The source specs are never modified: add a helper there, call it from the relevant
preprocessing task, and serialize the result. Never patch OAS YAML with regex or string
replacement.

Default sources:

| API | URL |
|---|---|
| Broker | `https://docs.alpaca.markets/openapi/broker-api.json` |
| Market Data | `https://docs.alpaca.markets/openapi/market-data-api.json` |
| Trading | `https://docs.alpaca.markets/openapi/trading-api.json` |

Per API, resolution is: Gradle property (`brokerSpec`, `dataSpec`, `tradingSpec`), environment
variable (`APCA_BROKER_SPEC`, `APCA_DATA_SPEC`, `APCA_TRADING_SPEC`), `local.properties`, legacy
`oasRoot`, then the default URL. `oasRoot` points to `<root>/{broker,data,trading}/openapi.yaml`;
use it for a local checkout of the private specs, normally `~/source/alpacah/alpaca-docs-private/oas`.

Local-file inputs are tracked incrementally. Remote inputs are always reprocessed. Preprocessed
copies are written to `build/specs/`.

## Runbooks

- Before publishing, changing publication workflows, or handling a failed release, read
  [`RELEASING.md`](RELEASING.md). Never place secrets in tracked files, command arguments, or shell
  history.
- Before modifying tests or using integration credentials, read [`TESTING.md`](TESTING.md).

## Do not

- Do not edit generated output or instantiate `ApiClient` directly.
- Do not modify source OAS documents from this repository.
- Do not use string substitution to patch OAS YAML.
- Do not add handwritten code under `markets.alpaca.client.openapi/**`.
