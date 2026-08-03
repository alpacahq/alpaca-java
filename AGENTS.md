# AGENTS.md — alpaca-java

Repository-maintenance guidance for the Alpaca Java SDK. For Java application code that consumes
this SDK, read `LLMS.md` instead.

## Commands

```bash
./gradlew build                    # generateApis (from pins) then compile and test
./gradlew generateApis             # generate all REST clients from specs/
./gradlew generateBrokerApi        # generate Broker only
./gradlew generateDataApi          # generate Market Data only
./gradlew generateTradingApi       # generate Trading only
./gradlew checkGenerated           # fail if specs/ or generated OpenAPI sources are stale
./gradlew test                     # unit tests
./gradlew integrationTest          # live read-only integration tests
./gradlew compileExamples          # compile examples without packaging
./gradlew generateJavadocs         # generate the API reference
python3 scripts/adopt_openapi.py --dry-run   # semantic diff vs upstream OAS
```

`compileJava` depends on `generateApis`, so a normal build always regenerates from
committed pins into `src/main/java/markets/alpaca/client/openapi/` before compiling.
See [`GENERATION.md`](GENERATION.md).

Do not routinely run `clean`; use `./gradlew clean generateApis build` only after a preprocessing
fix, generator-version change, or corrupted/stale generated output.

## Architecture invariants

- Handwritten, committed SDK code lives in `src/main/java/markets/alpaca/client/`, including
  `data/`, `http/`, `rest/`, `trading/`, `broker/sse/`, and `ws/`.
- Pinned OpenAPI documents live in `specs/{broker,data,trading}/openapi.yaml` (post-preprocess).
- Generated REST clients live in `src/main/java/markets/alpaca/client/openapi/{broker,data,trading}`
  under packages `markets.alpaca.client.openapi.*`. Never hand-edit those trees; regenerate with
  `./gradlew generateApis` or `scripts/adopt_openapi.py`.
- Generated Broker, Data, and Trading `ApiClient` classes are distinct and non-interchangeable.
  Always construct them through `AlpacaClientFactory`; it sets the API-specific authentication and
  base URL.
- Add common SDK behavior to handwritten packages. For generated behavior, fix a spec defect in
  preprocessing or add a handwritten wrapper for a generator limitation.
- WebSocket price and fractional-size fields use `BigDecimal`, never `double` or `float`.

## Generation and OpenAPI specs

See [`GENERATION.md`](GENERATION.md) for the pin / adopt / drift workflow.

`build-logic/src/main/groovy/alpaca.openapi-generation.gradle` configures generation.
`build-logic/src/main/groovy/markets/alpaca/gradle/OpenApiSpecSupport.groovy` contains parsed-YAML
SnakeYAML fixes. Upstream source specs are never modified in place: add a helper there, call it from
the relevant preprocessing task (during adopt), and serialize into `specs/`. Never patch OAS YAML
with regex or string replacement.

Upstream defaults (used by adopt/drift only):

| API | URL |
|---|---|
| Broker | `https://docs.alpaca.markets/openapi/broker-api.json` |
| Market Data | `https://docs.alpaca.markets/openapi/market-data-api.json` |
| Trading | `https://docs.alpaca.markets/openapi/trading-api.json` |

Per API, resolution is: Gradle property (`brokerSpec` / `dataSpec` / `tradingSpec`), environment
variable (`APCA_*_SPEC`), `local.properties`, legacy `oasRoot`, **committed `specs/{api}/openapi.yaml`**,
then the public URL. `oasRoot` points to `<root>/{broker,data,trading}/openapi.yaml` for a local
checkout of private specs (normally `~/source/alpacah/alpaca-docs-private/oas`).

## Runbooks

- Before publishing, changing publication workflows, or handling a failed release, read
  [`RELEASING.md`](RELEASING.md). Never place secrets in tracked files, command arguments, or shell
  history.
- Before modifying tests or using integration credentials, read [`TESTING.md`](TESTING.md).

## Do not

- Do not edit generated output under `src/main/java/markets/alpaca/client/openapi` or instantiate
  `ApiClient` directly.
- Do not modify upstream OAS documents from this repository.
- Do not use string substitution to patch OAS YAML.
- Do not add handwritten code under `markets.alpaca.client.openapi/**`.
