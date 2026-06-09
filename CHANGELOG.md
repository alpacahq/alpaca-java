# Changelog

All notable changes to this project will be documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
This project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html). While the
version is still `0.x`, the public API should be considered initial development; after `1.0.0`,
the policy below applies strictly.

## Versioning policy

| Change type                                                                                                   | Version bump |
|---------------------------------------------------------------------------------------------------------------|--------------|
| Breaking change to `AlpacaClientFactory`, `AlpacaCredentials`, HTTP helpers, REST helpers, or WebSocket public API | MAJOR        |
| Breaking change to the generated API surface (renamed/removed class or method)                                | MAJOR        |
| New endpoint or model coverage from a spec version update                                                     | MINOR        |
| Bug fix, dependency update, or preprocessing fix                                                              | PATCH        |

---

## [Unreleased]

### Added
- `AlpacaClientFactory`, `AlpacaCredentials`, HTTP helpers, REST helpers, and Trading/Market Data workflow helpers.
- Handwritten WebSocket clients for stock data, crypto data, news, and trading updates.
- `authenticationFuture()` and `waitForAuthentication(Duration)` for WebSocket streams.
- Broker Events SSE wrapper for generated Broker SSE endpoints.
- WebSocket monetary fields use `BigDecimal` rather than floating-point types.
- OpenAPI Generator pipeline for Broker, Market Data, and Trading APIs (`okhttp-gson` library).
- Maven publishing metadata, Apache-2.0 license, GitHub Actions build workflow, Dependabot configuration, and contributor guide.
- Source and Javadoc JAR artifacts for published Maven packages.
- Example applications for Broker, Market Data, Trading, and pagination workflows.
- Read-only integration tests for live Alpaca REST, WebSocket, and Broker SSE workflows; tests skip when credentials are absent.
- Spec preprocessing tasks in `build.gradle`: `removeEmptyKeyProperties`, `removeDiscriminatorEnums`, `removeActivityV2DetailTrdRequired`, `removeInternalSchemaMarkers`.
- `printSpecVersions` Gradle task — prints `info.version` and last-modified date for each OAS spec.
- JUnit 5 test suite for REST helpers, Broker SSE, and WebSocket code.
- JaCoCo coverage reporting, scoped to handwritten classes and excluding generated REST packages.
- `AlpacaCredentials.toString()` redacts `apiSecretKey` (`***`).
- `AlpacaClientFactory.applyTradingAuth` / `applyDataAuth` throw `IllegalStateException` with an actionable message if the generated auth entry names change.
- `markets.alpaca.client.http.AlpacaHttpConfig.loggingClient` redacts `APCA-API-KEY-ID` and `APCA-API-SECRET-KEY` headers at all logging levels.
