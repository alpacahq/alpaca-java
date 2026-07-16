# alpaca-java

[![Java 17+](https://img.shields.io/badge/Java-17%2B-007396?logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/17/)
[![License](https://img.shields.io/badge/License-Apache--2.0-blue.svg)](LICENSE)

Java client for [Alpaca Markets](https://alpaca.markets) APIs.

REST clients are generated at build time from Alpaca OpenAPI specs. WebSocket stream clients and
Broker Events SSE helpers are handwritten and committed in `src/main/java/markets/alpaca/client/`.

## Documentation

- [Project documentation](https://alpacahq.github.io/alpaca-java/)
- [Getting started](https://alpacahq.github.io/alpaca-java/getting-started)
- [API reference](https://alpacahq.github.io/alpaca-java/api)
- [LLM usage guide](LLMS.md)

Use the hosted documentation for SDK usage, installation, credentials, Trading, Market Data,
Broker, streaming, pagination, stream reconnect behavior, and API examples. This README is the
repository landing page for people who want to understand, build, test, or contribute to the SDK
itself.

## API coverage

| API         | Auth                      | Default base URL                            |
|-------------|---------------------------|---------------------------------------------|
| Broker      | HTTP Basic (`key:secret`) | `https://broker-api.sandbox.alpaca.markets` |
| Market Data | Header key pair           | `https://data.alpaca.markets`               |
| Trading     | Header key pair           | `https://paper-api.alpaca.markets`          |

The SDK also includes WebSocket clients for stock data, crypto data, news, and trading updates.
WebSocket price and fractional size fields use `BigDecimal` to avoid floating-point precision loss.
Broker Events SSE endpoints are exposed through a handwritten OkHttp SSE wrapper so live streams do
not use the blocking generated REST methods.

## Where to start

- Building an application with this SDK: start with the
  [hosted getting started guide](https://alpacahq.github.io/alpaca-java/getting-started).
- Looking for exact classes, methods, generated REST models, or package details: use the
  [API reference](https://alpacahq.github.io/alpaca-java/api).
- Asking an LLM or coding assistant to write application code with this SDK: give it
  [`LLMS.md`](LLMS.md).
- Contributing to this repository: read [`AGENTS.md`](AGENTS.md), then use the build and test
  commands below.

## Requirements

- Java 17+
- No local OAS spec files required for normal builds; by default Gradle fetches the official Alpaca
  public specs from `docs.alpaca.markets`.
- Node.js/npm only when working on the Docusaurus documentation site in `docs/`.

## Build from source

Generated REST sources are not committed. They are produced under `build/generated/` before
compilation.

```bash
./gradlew build
```

For IDE setup, generate the REST clients once before opening the project:

```bash
./gradlew generateApis
```

Until generated sources exist, IDEs will show unresolved imports for generated Broker, Data, and
Trading OpenAPI classes. IntelliJ IDEA's Gradle plugin picks up the generated source roots
automatically once the task has run.

## Common Gradle tasks

```bash
./gradlew build                      # generate, compile, and test
./gradlew generateApis               # regenerate all three API clients
./gradlew generateBrokerApi          # regenerate broker only
./gradlew generateDataApi            # regenerate data only
./gradlew generateTradingApi         # regenerate trading only
./gradlew test                       # run unit tests
./gradlew integrationTest            # run live integration tests when credentials are present
./gradlew compileExamples            # compile examples without packaging them
./gradlew generateJavadocs           # generate Javadoc API reference
```

Run `./gradlew tasks --group="openapi tools"` to list generation-related tasks.

## Package structure

```text
markets.alpaca.client
├── AlpacaClient             # immutable facade for common workflows
├── AlpacaClientFactory      # entry point for preconfigured generated and streaming clients
├── AlpacaCredentials        # API key ID + secret value object
├── broker/sse/              # handwritten Broker Events SSE wrapper
├── data/                    # handwritten Market Data helpers
├── http/                    # handwritten OkHttp/retry helpers
├── rest/                    # handwritten REST utilities
├── trading/                 # handwritten Trading helpers
├── ws/                      # handwritten WebSocket stream clients and models
└── openapi/                 # generated at build time
    ├── broker/{api,model,http}
    ├── data/{api,model,http}
    └── trading/{api,model,http}
```

Generated code lives in `build/` and uses `markets.alpaca.client.openapi/**`. Do not edit generated
files directly; they are overwritten during generation. Handwritten SDK code belongs under
`src/main/java/markets/alpaca/client/` outside the `openapi` namespace.

## OAS spec sources

By default, the build fetches Alpaca's public specs:

| Spec        | Default URL                                                |
|-------------|------------------------------------------------------------|
| Broker      | `https://docs.alpaca.markets/openapi/broker-api.json`      |
| Market Data | `https://docs.alpaca.markets/openapi/market-data-api.json` |
| Trading     | `https://docs.alpaca.markets/openapi/trading-api.json`     |

To work against local or alternate specs, use Gradle properties, environment variables, or
`local.properties`. The most common local setup is:

```properties
oasRoot=/path/to/alpaca-docs-private/oas
```

That resolves specs from:

```text
<oasRoot>/broker/openapi.yaml
<oasRoot>/data/openapi.yaml
<oasRoot>/trading/openapi.yaml
```

Use individual `brokerSpec`, `dataSpec`, and `tradingSpec` values when only one API spec should be
overridden.

## Tests

Unit tests do not require credentials or network access:

```bash
./gradlew test
```

The full local verification gate is:

```bash
./gradlew check
```

`check` runs unit tests, compiles the examples project, and executes the configured code-quality
tools against committed handwritten sources.

Integration tests call live Alpaca paper-trading, market-data, WebSocket, and Broker sandbox APIs:

```bash
./gradlew integrationTest
```

They skip automatically when credentials are absent. See `AGENTS.md` for the supported
`local.properties` keys and environment variables.

## Publishing

Every successful, current push to `main` automatically publishes the version in `gradle.properties`
to Sonatype's Central Snapshots repository. To consume this module's snapshots without enabling
snapshot resolution for every dependency:

```groovy
repositories {
    mavenCentral()
    maven {
        url = uri("https://central.sonatype.com/repository/maven-snapshots/")
        mavenContent { snapshotsOnly() }
        content { includeModule("markets.alpaca", "alpaca-java") }
    }
}

dependencies {
    implementation("markets.alpaca:alpaca-java:0.1.0-SNAPSHOT")
}
```

Releases are dispatched only from `main` with an existing `vMAJOR.MINOR.PATCH` tag reachable from
`main`. The protected `maven-central` environment must require reviewers and allow deployments only
from `main`. The workflow resolves each OpenAPI spec once for the build and publication, publishes a
signed release to Maven Central, creates the corresponding GitHub Release, and commits the next
patch `-SNAPSHOT` version directly to `main`. It then explicitly builds, tests, and publishes that
new development snapshot from one frozen set of OpenAPI inputs; this explicit step is necessary
because GitHub does not trigger another workflow from the version-bump bot push.

Maintainers: see [`AGENTS.md`](AGENTS.md#publishing) for local commands, credentials, workflow
ordering, environment protection, recovery, and partial-failure handling.

## Documentation site

The Docusaurus documentation site lives in `docs/`.

```bash
cd docs
npm install
npm run start
```

To include the generated Javadoc API reference in a local docs preview:

```bash
cd docs
npm run sync:javadocs
npm run start
```

The GitHub Pages deployment is managed by `.github/workflows/deploy-docs.yml`.
