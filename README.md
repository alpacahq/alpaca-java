# alpaca-java

[![Build](https://github.com/alpacahq/alpaca-java/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/alpacahq/alpaca-java/actions/workflows/build.yml)
[![Maven Central](https://img.shields.io/maven-central/v/markets.alpaca/alpaca-java)](https://central.sonatype.com/artifact/markets.alpaca/alpaca-java)
[![Java 17+](https://img.shields.io/badge/Java-17%2B-007396?logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/17/)
[![License](https://img.shields.io/badge/License-Apache--2.0-blue.svg)](LICENSE)
[![GitHub Issues](https://img.shields.io/github/issues/alpacahq/alpaca-java)](https://github.com/alpacahq/alpaca-java/issues)
[![Forum](https://img.shields.io/badge/Forum-Alpaca%20Community-blue?logo=data%3Aimage%2Fpng%3Bbase64%2CiVBORw0KGgoAAAANSUhEUgAAADAAAAAwCAYAAABXAvmHAAAGuElEQVRogcWaXYhdVxXHf2udr3vHVCmtlcYHsXaKD%2B2DVixJDdpWIT4oFakQLMUvRH1QfEkp9sEXH0qFqohFaAolgWraYEvQRiwRUjC2hSKmtEUSG6pNI5hOTZM795x99lo%2B3HNn7tzM3LlnvvKHO9y79%2Fr4r73P2nvtfUbYGFydZdntieoO3D4qcL2LXCmqVwC42XnB54CToK9G979WVXUUOLdex7JGHQfeWxTZV0C%2BnqjuaGnPAY9mxzF%2FtAzhSeD8iO1WZNriqiLLfiiJfkdFrmocrsUOQ11zP%2BfRflWG8BAwR4tA2jhOsiy7O1F9UFXez%2FqIj8MBcfd3LNq9ZQiPAvU0ilMR6NL9oOfxEVHZLSIRSNZBdhKiuyfu%2FEFK%2FfY882%2BuprBqAGma7kwTfUJVt7Oxo74SBo%2BV2Zt1tLvquj4%2BSXgimaJIPquSPiUi3UZ2s8kP4YC7exmt%2FnJVxWdWElyRUFEkn1NJj4iIAzpJdpPggLk75vXusozPLie0LKks4%2BNpUhy7DCM%2FjuFM9OpY7gqBv40LLJeM1xR5fkREP9D8bkveAHHnn7g9C1wNYO5PAkFErmX6XBIAESkSSe4o6vibCnqjAjqmUHSL%2FKci%2BpEhkZbkAUxEAHtjvqy%2Ba86t5v60Jzze75c3x2j7G7s2pb2BrMhsLPIHgXy0c8kM5EmyW5PkARGxZYJrE4C6%2B6m0js%2BUMZ4WkTMYb5vZWVU9qSI7VPRaIE7pZxjwx9Q5Ht1PDjtGlbdpkvxiMHob88xLM8p1Xb8UQngZkBDCCYd%2FNR7alA0iImia%2FhyYGTamwy9Flu3RRK9j4LTt6A92UrM3DD8I%2BqqInuzD201%2FYKSGEnxIoM1ACWCqMltk2Z4yhH2jAXQkkb3N9zbkm2T0GGt7qAzh%2FobssG9UTgFy8u0gV7bwMQoFENV7gQNAOTCaJLep6PW0m9IheY9mD5Qh7G3ahp9xRCCpqF6ro33DzF6RQQ5Om8wLflVltkiS22BgQNM8%2B7GK3NQITDutBqhF%2F2NZhW82tuJqzoHMzM6koucR%2BYyIzNC%2BRBEX8Rjj0woUInJnS%2FIOJO7%2Brog91bQp081gDWT9EA6481zT1mYWmlVGvgTkmqbpJ3QwCm3gTahnapMXW5JYCFI8HnX3HoPZa3WQUZVtaZrerKnqrjaKS2i4nw0hnBonNqU2Fv0f4gsrVasAAFLVXerCjW0dA2YxPjxfVl9g8ShoeZ7vnZnperdTnEg76c5GdrlyxQGiyH9dlpYGbeDCjanADS3Ii7udxvh%2BvwqHR8g5kEM8Vdf8MkY5FGP9FwZ5sWJiNzu%2Btz8JN%2FpwQwpsn5Y8YOby27IqDwMZi2s%2BQFVV8RDEQyNtbZfIttiuiGybQnBYi6jitzZtW3E6mwyRK1RG6orVxBulm4qs%2BBaD5XCzzsbTEppRoJpeHhOR92ni93XT9JNc5iDc%2FV119wstdBRwEb3OEz3cybK7mb4k3nCIMKfAmbZ6AKp6jabJ%2Fk6Rvdbt5PvyPP9i059O0N1QuHNOwV9fjxHVZFZEv6YidzRNW%2FZICZxWXC45KLfAQpmMy7mRti1BdJ5Xi%2FGF9RpyvHLxuY0g1QZm9qJWMT7n7uUabXjz96KIbekMuHu%2FruuXFOi525%2FW49yhZ2Zziz83FYNC0P13QE8Bi8b%2BtVob3HrJRbOtzQGv4wHAFCCE8HszO0v70sARcLxX17pVOSBm9lYV41FY3IB65vazBVLt7CHORai2YgYMwKP9BChhMQCvqvrXZvafNZkV7zG4Qpn2WLlWqJn9u6zrx4Z%2BRkuAdxy%2Fj3bXfg4gyPBQ0vpo2AIDTuI%2FAhbKnyU1TFmGx93jkaZ9miDE3XHn%2FHL2NhCDGxCPh%2Fr9cHC0Y9xh3%2Frhe%2B4LCT1pNBd2YV%2B8gdsMDE%2BCb0k%2F%2FADoj3ZeMmIlvB7q6k53D6z%2BOAi4I76ZCWzububVV%2Bfhkndmy055XfN8NL%2BLVc60AO70xDdtF45AEs33lCV%2FXk5gxWe2qqrDVsd73H1oaBxDsj3fnE0sAonV8Z6qqp5YSWhS0nk%2FhP2hjreb%2B%2F9Y6c7T6bnZuv9lYInFwWulC9HC5%2FshTKwSVl016ro%2BFqN92sz%2FztgS29zxzZvqmi%2BnxmCAmPmJJM12lmU8sprCVG9HQggv98vyljraXnfvM5yNwcuQXkhC20fIAUIIZ4GLTf1i7l7W0e7vl%2BUtFy5ceIXNuPUoCj5cFPnBbqdTvmem692iOAYMr2ba7AMJQCdNP9XN832dPH84z%2FPZDSc8AR8qivyxTp4%2F0vy%2BHO%2BS%2BT8lMTeMbm%2FRxQAAAABJRU5ErkJggg%3D%3D)](https://forum.alpaca.markets/)
[![Slack](https://img.shields.io/badge/Slack-Alpaca%20Community-4A154B?logo=slack&logoColor=white)](https://alpaca.markets/slack)

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

Java 17+ is required. Normal builds use the committed OpenAPI pins under `specs/`
(no live fetch). Generated REST sources are committed under
`src/main/java/markets/alpaca/client/openapi/`
(see [`GENERATION.md`](GENERATION.md)).

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

## Support

- **Library / SDK issues:** Bugs, feature requests, or questions specific to this Java library → [GitHub Issues](https://github.com/alpacahq/alpaca-java/issues/new/choose).
- **General Alpaca support & API discussion:** Account questions, platform issues, or broader API topics → [Alpaca Community Forum](https://forum.alpaca.markets/).
- **Slack community:** Chat with other developers and the Alpaca community on [Slack](https://alpaca.markets/slack).

## Contributing

Read the [contributing guide](CONTRIBUTING.md) for the pull-request workflow. Before changing the
SDK, also read [`AGENTS.md`](AGENTS.md) for generated-code boundaries, local OpenAPI spec
configuration, testing, and publishing safeguards.
