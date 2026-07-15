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

## [0.1.0] - YYYY-MM-DD

### Added
- Initial release of the Alpaca Java client SDK.
- Generated REST API clients for Alpaca Trading, Market Data, and Broker APIs.
- Authenticated client factories and shared HTTP, pagination, and asynchronous helper utilities.
- WebSocket streaming clients for stocks, crypto, news, and trading updates.
- Broker trade-event SSE support.
- Type-safe monetary values using `BigDecimal` in handwritten streaming models.
- Examples and read-only integration tests for REST, streaming, and broker workflows.
- Maven Central publishing with source and Javadoc artifacts.
