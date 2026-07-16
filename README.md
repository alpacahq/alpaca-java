# alpaca-java

[![Build](https://github.com/alpacahq/alpaca-java/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/alpacahq/alpaca-java/actions/workflows/build.yml)
[![Maven Central](https://img.shields.io/maven-central/v/markets.alpaca/alpaca-java)](https://central.sonatype.com/artifact/markets.alpaca/alpaca-java)
[![Java 17+](https://img.shields.io/badge/Java-17%2B-007396?logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/17/)
[![License](https://img.shields.io/badge/License-Apache--2.0-blue.svg)](LICENSE)

Java client for [Alpaca Markets](https://alpaca.markets) APIs.

REST clients are generated at build time from Alpaca OpenAPI specs. WebSocket stream clients and
Broker Events SSE helpers are handwritten and committed in `src/main/java/markets/alpaca/client/`.

## Documentation

- [Getting started](https://alpacahq.github.io/alpaca-java/getting-started) — installation, credentials, and examples
- [API reference](https://alpacahq.github.io/alpaca-java/api) — classes, methods, and generated REST models
- [Runnable examples](examples/README.md) — local Trading, Market Data, Broker, and pagination workflows
- [LLM usage guide](LLMS.md) — guidance for coding assistants
- [Documentation development](docs/README.md) — local Docusaurus setup
- [Contributing guide](CONTRIBUTING.md) — issues, pull requests, and release instructions

## Build from source

Java 17+ is required. OpenAPI specs are fetched by default, so a normal build does not need local
spec files. Generated REST sources are written to `build/generated/`.

```bash
./gradlew build                  # generate, compile, and test
./gradlew generateApis           # generate clients for IDE setup
./gradlew check                  # full local verification
./gradlew integrationTest        # live tests; skips when credentials are absent
```

For the complete task list and local spec configuration, see [`AGENTS.md`](AGENTS.md). For
integration-test credentials see [`TESTING.md`](TESTING.md), and for release procedures see
[`RELEASING.md`](RELEASING.md).

## Snapshots

Every successful current push to `main` publishes the current `-SNAPSHOT` version to Central
Snapshots. Scope the snapshot repository to this module:

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
    implementation("markets.alpaca:alpaca-java:0.1.2-SNAPSHOT")
}
```

For release consumption, use Maven Central as shown in the
[Getting Started guide](https://alpacahq.github.io/alpaca-java/getting-started).

## Contributing

Read the [contributing guide](CONTRIBUTING.md) for the pull-request workflow. Before changing the
SDK, also read [`AGENTS.md`](AGENTS.md) for generated-code boundaries, local OpenAPI spec
configuration, testing, and publishing safeguards.
