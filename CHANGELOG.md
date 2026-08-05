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

### Breaking
Adopting the current upstream OpenAPI documents renamed and removed parts of the generated API
surface relative to `0.1.1` (which generated from live specs at build time).

- Renamed Trading order request models: `PostOrderRequest`, `PostOrderRequestStopLoss`, and
  `PostOrderRequestTakeProfit` are now `CreateOrderRequest`, `CreateOrderRequestStopLoss`, and
  `CreateOrderRequestTakeProfit`.
- Replaced inline Trading response models with named schemas: `GetOptionsContracts200Response` is
  now `OptionContractsResponse`, and `GetV2CorporateActionsAnnouncements200ResponseInner` /
  `GetV2CorporateActionsAnnouncementsId200Response` are now `CorporateAnnouncement`.
- Changed the Trading `getV2CorporateActionsAnnouncements` `caTypes` parameter from `String` to
  `List<CorporateActionCaType>`.
- Renamed Broker model properties: `BatchJournalRequest.description` is now `correspondent`, and
  `DailyTradingLimit.getDailyNetLimitInUse()` is now `getInUseLimit()`.
- Removed the superseded Broker `TradeUpdateEvent` model; use `TradeUpdateEventV2`, which
  `BrokerEventsSseClient.subscribeToTradeEvents` already emits.
- Removed Market Data endpoints no longer published upstream: `FixedIncomeApi`, `IndexApi`,
  `CryptoPerpetualFuturesApi`, and their response models.
- Added an `orderId` parameter to every Trading `AccountActivitiesApi.getAccountActivities` and
  `getAccountActivitiesByActivityType` overload, which widens the generated method signatures.

### Added
- Committed OpenAPI pins under `specs/` and generated clients under
  `src/main/java/markets/alpaca/client/openapi/`, with
  `checkGenerated` CI verification, semantic adopt reports (`scripts/adopt_openapi.py`), and a
  weekly drift workflow (additive adopt PRs; breaking changes open an issue).
- Market Data corporate-action event models (`CorporateActionEvent` and its per-event variants).
- Broker and Trading activity models `FixedIncomeInterestActivityV2`,
  `CommonFixedIncomeInterestActivityV2`, `DIVWHActivityV2`, `MEMActivityV2`, and `OCTActivityV2`,
  plus Broker `JournalStatusFrom` / `TransferStatusFrom`.
- Broker `DailyTradingLimit` properties `cash_held`, `correspondent`, `executed_buys`,
  `executed_sells`, `open_buys`, and `open_sells`.

### Changed
- `adoptOpenApi` and `adoptOpenApiBreaking` now regenerate the clients in the same invocation, so
  adopted pins and generated sources can no longer land out of sync.
- The semantic OpenAPI diff now separates additive schema and operation changes from breaking ones,
  ignores documentation-only differences (descriptions, summaries, examples) when classifying
  severity, and reports which properties, parameters, or responses changed.

## [0.1.1] - 2026-07-16

### Added
- Initial release of the Alpaca Java client SDK.
- Generated REST API clients for Alpaca Trading, Market Data, and Broker APIs.
- Authenticated client factories and shared HTTP, pagination, and asynchronous helper utilities.
- WebSocket streaming clients for stocks, crypto, news, and trading updates.
- Broker trade-event SSE support.
- Type-safe monetary values using `BigDecimal` in handwritten streaming models.
- Examples and read-only integration tests for REST, streaming, and broker workflows.
- Maven Central publishing with source and Javadoc artifacts.
