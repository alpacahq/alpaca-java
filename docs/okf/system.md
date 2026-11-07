---
type: System
title: Alpaca Java SDK
description: Java 17+ client library for the Alpaca Trading, Market Data and Broker APIs, published to Maven Central as markets.alpaca:alpaca-java.
resource: likec4://alpacahq/alpaca_java
tags: [alpacahq, sdk, java, generated]
status: active
owners: ["@alpacahq/sdk-java"]
timestamp: 2026-11-07T11:16:25Z
generated_by: claude-fable-5 (initial 2026-10-29), claude-opus-5-5 (refresh 2026-11-07) / layered-docs 2026-09
source_commit: 8ac57386389f643f8b81b2db9b375b44c72c8e79
source_branch: docs/layered-2026-09
generated_at: 2026-11-07T11:16:25Z
confidence: medium
review_status: draft-needs-review
links:
  repository: https://github.com/alpacahq/alpaca-java
  architecture: ../architecture/alpaca_java.c4
---

# Alpaca Java SDK

## Purpose
`alpaca-java` is the Java client for the Alpaca Markets APIs, for Java application developers who trade, read market data or run a broker integration on Alpaca (README.md, docs/content/getting-started.md, LLMS.md). It ships as one Maven artifact, `markets.alpaca:alpaca-java`, requires Java 17+, is licensed Apache-2.0 and is owned by the `@alpacahq/sdk-java` team (README.md, settings.gradle, LICENSE, .github/CODEOWNERS).

REST clients are generated at build time from OpenAPI documents pinned under `specs/` and committed to the source tree. WebSocket stream clients and the Trading, Broker and Market Data corporate-actions SSE clients are handwritten (README.md, GENERATION.md, CONTRIBUTING.md). Status `active` is evidenced by the latest commit date (2026-11-06) and the `0.2.0-SNAPSHOT` development version (git log, gradle.properties).

## Capabilities
- Generated REST clients for the Broker, Market Data and Trading APIs (api, model and http packages) — evidence: `src/main/java/markets/alpaca/client/openapi/`, `GENERATION.md`
- Handwritten `AlpacaClient` facade (orders, stocks, SSE clients) with builder, `AlpacaClientFactory`, `AlpacaCredentials`, paper/production and sandbox/production environment enums — evidence: `src/main/java/markets/alpaca/client/AlpacaClient.java`, `src/main/java/markets/alpaca/client/AlpacaClientFactory.java`, `src/main/java/markets/alpaca/client/TradingApiEnvironment.java`, `src/main/java/markets/alpaca/client/BrokerApiEnvironment.java`
- Shared OkHttp configuration with User-Agent and retry interceptors; REST pagination, futures and rate-limit helpers — evidence: `src/main/java/markets/alpaca/client/http/`, `src/main/java/markets/alpaca/client/rest/`
- WebSocket streams for stock, crypto and news market data and trade updates — evidence: `src/main/java/markets/alpaca/client/ws/`
- Server-Sent Events: shared bounded, reconnecting transport plus Trading account-activity, Market Data corporate-actions and Broker events wrappers (Broker includes the rebalancer events stream `GET /v2/events/rebalancer`) — evidence: `src/main/java/markets/alpaca/client/sse/`, `src/main/java/markets/alpaca/client/trading/sse/`, `src/main/java/markets/alpaca/client/data/sse/`, `src/main/java/markets/alpaca/client/broker/sse/`, `src/main/java/markets/alpaca/client/broker/sse/BrokerEventsSseClient.java`, `src/main/java/markets/alpaca/client/broker/sse/BrokerRebalancerEventDecoder.java`, `CHANGELOG.md` (Unreleased, rebalancer entry)
- OpenAPI pin adoption, semantic diff and Java-surface compatibility tooling — evidence: `build-logic/src/main/groovy/alpaca.openapi-generation.gradle`, `scripts/adopt_openapi.py`, `scripts/sdk_api_diff.py`
- Docusaurus documentation site with synced Javadocs and runnable examples — evidence: `docs/README.md`, `examples/README.md`

## Interfaces
**Inbound:** a Java library API (no server, CLI or webhook): `AlpacaClient`, `AlpacaClientFactory`, generated `*Api` classes, stream and SSE clients (LLMS.md, src/main/java/markets/alpaca/client/package-info.java). Credentials come from constructor arguments or `APCA_*` environment variables (LLMS.md, examples/README.md).

**Outbound:**
- Trading API REST at `https://api.alpaca.markets` / `https://paper-api.alpaca.markets`; trade-update WebSocket at `/stream` on the same hosts; account-activity SSE (src/main/java/markets/alpaca/client/TradingApiEnvironment.java, src/main/java/markets/alpaca/client/ws/TradingEnvironment.java, src/main/java/markets/alpaca/client/trading/sse/TradingEventsSseClient.java).
- Market Data API REST at `https://data.alpaca.markets` / `https://data.sandbox.alpaca.markets`; WebSocket and corporate-actions SSE on `stream.data.alpaca.markets` / `stream.data.sandbox.alpaca.markets` (src/main/java/markets/alpaca/client/openapi/data/http/ApiClient.java, src/main/java/markets/alpaca/client/ws/AlpacaStreamEnvironment.java, src/main/java/markets/alpaca/client/data/sse/MarketDataSseEnvironment.java).
- Broker API REST and SSE at `https://broker-api.alpaca.markets` / `https://broker-api.sandbox.alpaca.markets`, including the Broker rebalancer events SSE stream (`GET /v2/events/rebalancer`) (src/main/java/markets/alpaca/client/BrokerApiEnvironment.java, src/main/java/markets/alpaca/client/broker/sse/BrokerEventsSseClient.java, specs/broker/openapi.yaml, CHANGELOG.md, docs/content/sdk/broker.md).
- Build-time only: public OpenAPI documents at docs.alpaca.markets for adopt/drift flows (scripts/upstream_openapi_urls.json, GENERATION.md).

## Dependencies
- OkHttp 5.5.1 (okhttp, okhttp-sse, logging-interceptor), Gson 2.15.0, gson-fire, commons-lang3, swagger-annotations — runtime HTTP, WebSocket, SSE and JSON — evidence `gradle/libs.versions.toml`
- OpenAPI Generator 7.26.0 (okhttp-gson library) and SnakeYAML 2.7 — build-time generation and spec preprocessing — evidence `gradle/libs.versions.toml`, `GENERATION.md`
- JUnit 6.1.3, MockWebServer, JaCoCo, SpotBugs, Spotless, Checkstyle, japicmp — tests, quality and public-API compatibility checks — evidence `gradle/libs.versions.toml`, `build-logic/src/main/groovy/alpaca.quality.gradle`, `config/checkstyle/checkstyle.xml`
- Python 3 helpers for adopt, diff, migration and release tooling — evidence `scripts/requirements.txt`, `scripts/`
- Node 22 / Docusaurus for the documentation site — evidence `docs/package.json`, `.github/workflows/deploy-docs.yml`
- Alpaca Trading, Market Data and Broker APIs — the systems the SDK wraps — evidence as in Interfaces

## Data & storage
None. The library holds no persistent state. Pinned OpenAPI documents under `specs/{broker,data,trading}/openapi.yaml` are build inputs, not runtime storage (GENERATION.md). SSE subscriptions keep an in-memory resume cursor only (src/main/java/markets/alpaca/client/sse/package-info.java).

## Operations
- Build: Gradle wrapper with a `build-logic` included build of convention plugins; `compileJava` depends on `generateApis`, `check` includes `checkGenerated` and `checkSseContracts` (GENERATION.md, settings.gradle, build.gradle, build-logic/).
- CI: `.github/workflows/build.yml` on pushes to main and pull requests — jobs markdown-links, docs, release-tools, build (spotless, checkstyle, spotbugs, build, checkApiCompatibility) and publish-snapshot; the snapshot job runs only for pushes to `main` and publishes the `-SNAPSHOT` version to Central Snapshots (README.md "Snapshots", .github/workflows/build.yml).
- Release: manually dispatched `.github/workflows/release.yml`, restricted to `main` and the protected `maven-central` environment; publishes through the Central Portal with the NMCP Gradle plugin, creates a GitHub Release from CHANGELOG.md and opens a post-release version-bump pull request (settings.gradle, RELEASING.md, .github/workflows/release.yml).
- Docs: Docusaurus site with synced Javadocs deployed to GitHub Pages by `.github/workflows/deploy-docs.yml` (docs/README.md, docs/docusaurus.config.js).
- Spec drift: weekly `.github/workflows/openapi-drift.yml` (cron `15 9 * * 1`) opens or updates a bot pull request on `bot/openapi-adopt` from the upstream OpenAPI documents (GENERATION.md "CI").
- Tests: `./gradlew test` needs no credentials; `./gradlew integrationTest` runs live read-only tests and skips when credentials are absent (TESTING.md).
- No Dockerfile, compose, k8s or terraform file exists in the repository (git ls-files), so no deployment model is published.

## Behaviour (Allium)
none — no accepted spec (no `.allium` files in the repository; mode fresh).

## Decisions
- [ADR-0001 Generate REST clients from committed OpenAPI pins](decisions/ADR-0001-generate-rest-clients-from-committed-openapi-pins.md)
- [ADR-0002 Handwritten facade and streaming clients over generated code](decisions/ADR-0002-handwritten-facade-and-streaming-clients.md)
- [ADR-0003 Weekly upstream spec adoption gated by Java surface compatibility](decisions/ADR-0003-weekly-spec-adoption-gated-by-java-compatibility.md)
- [ADR-0004 Semantic versioning tied to handwritten and generated surface](decisions/ADR-0004-semantic-versioning-policy.md)
- [ADR-0005 Shared bounded reconnecting SSE transport](decisions/ADR-0005-shared-sse-transport.md)

## Evidence
- README.md, GENERATION.md, AGENTS.md, CONTRIBUTING.md, LLMS.md, TESTING.md, RELEASING.md, MIGRATIONS.md, CHANGELOG.md, LICENSE
- settings.gradle, build.gradle, gradle.properties, gradle/libs.versions.toml, build-logic/
- .github/workflows/build.yml, .github/workflows/release.yml, .github/workflows/deploy-docs.yml, .github/workflows/openapi-drift.yml, .github/CODEOWNERS, .github/dependabot.yml
- src/main/java/markets/alpaca/client/ (AlpacaClient.java, AlpacaClientFactory.java, TradingApiEnvironment.java, BrokerApiEnvironment.java, http/, rest/, ws/, sse/, trading/sse/, data/sse/, broker/sse/, openapi/)
- specs/broker/openapi.yaml, specs/data/openapi.yaml, specs/trading/openapi.yaml, scripts/upstream_openapi_urls.json, scripts/
- docs/README.md, docs/docusaurus.config.js, docs/content/sdk/sse-migration.md, examples/README.md

## Open questions
- > Unverified: the container split in the LikeC4 model groups Java packages and sibling builds of a single published artifact along the handwritten-package boundaries in CONTRIBUTING.md; it is a readability choice, not a deployment boundary.
- > Unverified (confidence: low): the local clone is shallow — 73 commits starting 2026-10-07; the full commit history was not available, so design rationale recorded only in older commits or pull requests may be missing (CHANGELOG.md records releases from 0.1.1, 2026-07-16).
- > Unverified: provenance `generated_at` is the refresh time taken from the system clock (UTC); the initial generation time on 2026-10-29 is recorded date-only in log.md.
- > Unverified: the ADR candidate `src/main/java/markets/alpaca/client/openapi/broker/model/OwnerDocumentUploadRequest.java` is a generated model and records no decision.
