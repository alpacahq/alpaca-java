# Changelog

All notable changes to this project will be documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
This project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html). While the
version is still `0.x`, the public API should be considered initial development; after `1.0.0`,
the policy below applies strictly.

## Versioning policy

| Change type                                                                                                   | Version bump |
|---------------------------------------------------------------------------------------------------------------|--------------|
| Breaking change to `AlpacaClientFactory`, `AlpacaCredentials`, HTTP helpers, REST helpers, or WebSocket/SSE public API | MAJOR        |
| Breaking change to the generated API surface (renamed/removed class or method)                                | MAJOR        |
| New backward-compatible public functionality, endpoint coverage, or model coverage                            | MINOR        |
| Bug fix, dependency update, or preprocessing fix                                                              | PATCH        |

`markets.alpaca.client.sse.internal` is implementation-only, is omitted from published Javadocs and
API compatibility checks, and is not covered by this compatibility policy. Other public types
remain covered regardless of an `.internal` package-name segment unless documented otherwise.

---

## [Unreleased]

### Breaking
Adopting upstream Broker and Trading specs ([#90](https://github.com/alpacahq/alpaca-java/pull/90)):
- Broker `EventsApi.suscribeToAccountStatusSSE` is now `subscribeToAccountStatusSSE`, including
  the `Call`, `WithHttpInfo`, and `Async` variants.
  `BrokerEventsSseClient.subscribeToAccountStatus` calls the corrected method; its own signature
  is unchanged.
- Broker `IraApi.listIRAExcessContritbutions` is now `listIRAExcessContributions`, including the
  same variants.
- Trading `PositionsApi.deleteAllOpenPositions` returns `List<PositionClosedResponse>` instead of
  `List<PositionClosedReponse>`. The misspelled type is removed.
- `getEasyToBorrow()` is removed from Broker `Asset` and Trading `Assets`. Use `getBorrowStatus()`.
- Broker and Trading `OptionContract` JSON validation now requires `ppind`.
- Broker and Trading `CommonFixedIncomeInterestActivityV2` JSON validation now requires
  `interest_type`.
- Broker SSE keeps its public `eventSource()` method, but the returned object is now a
  request/cancel compatibility facade rather than OkHttp's live implementation.
- Broker callbacks are serialized and backpressured instead of fire-and-forget. Terminal lifecycle
  completion is independent from terminal listener delivery, and callback failure semantics are
  defined below. See the migration guide before upgrading callback-heavy applications.

### Added
- Broker and Trading `FixedIncomeInterestType` (`coupon`, `accrued`), plus `interest_type`,
  `order_id`, and `parent_id` on fixed-income interest activities.
- `ppind` on Broker and Trading `OptionContract`.
- `tax_country` and `tax_rate` on Broker and Trading `DIVNRAActivityV2`.
- Broker `FundingWalletTransfer.getTotalAmount()`, and `fee_inclusive` on
  `CreateFundingWalletWithdrawalRequest`.
- `TokenizationIssuer.ONDO` and `TokenizationNetwork.HYPERCORE` on Broker and Trading.
- A typed, cancellable, reconnecting Trading account-activity SSE client with cursor filters,
  bounded streams, resume IDs, resource limits, and structured lifecycle/error reporting.
- A typed Market Data corporate-actions SSE client with production, sandbox, and custom endpoint
  selection; event-type and region filters; validated history cursors; and fail-closed decoding for
  every corporate-action discriminator in the pinned OpenAPI document.
- Shared Java SSE transport types and explicit OpenAPI SSE contract verification.
- Broker single-activity asynchronous retrieval through the endpoint's SSE framing.
- Awaitable SSE opening, immutable current-connection metadata, options-only factories, top-level
  client configuration, callback-executor factories, and named Trading and Market Data event-ID
  cursor factories.
- An optional elapsed-time budget for each initial-open or established reconnect cycle, separate
  from the whole-subscription maximum duration.

### Changed
- Broker `FundingWalletTransfer.getOriginalAmount()` is deprecated. Use `getTotalAmount()` for the
  amount debited from the account.
- Broker SSE now uses the shared bounded parser and exposes SSE IDs/types, comments, reconnect
  diagnostics, completion state, and optional resilient reconnect while preserving existing
  one-connection defaults, per-event decode-failure behavior, and failure response bodies.
- SSE listener callbacks are serialized per subscription, so terminal listener delivery follows an
  active event callback and event handling provides transport backpressure; lifecycle completion
  and timers do not wait for user callbacks. Because response parsing is backpressured, detection
  of a remote end and its following reconnect can wait for the active event callback.
- Activity V2 events use discriminant-aware decoders with a unique-most-specific structural
  fallback instead of ambiguous generated `oneOf` matching; tied matches fail closed. Documented
  `DIVROC` events resolve to `CDIVActivityV2`, and unknown envelope properties retain the generated
  model's `additionalProperties` value shapes.
- SSE resume state includes completed data-less `id:` blocks, and idle timers are scoped to active
  response bodies rather than reconnect backoff.
- Broker malformed events report failure, advance the transport cursor, and continue. Reconnect
  attempt budgets are reset after a decoded event callback is invoked.
- Broker listeners can distinguish malformed events, server retry changes, and structured normal
  closure through additive default callbacks; deserialization failures include SSE ID/type.
- The pinned Broker NTA operation now declares its actual `text/event-stream` response media type.
- The public SSE subscription boundary is an interface; transport implementation classes under
  `markets.alpaca.client.sse.internal` are excluded from Javadocs and compatibility guarantees.
- `BrokerSseSubscription` implements the shared subscription interface while retaining its
  Broker-specific compatibility surface. Reconnect delay caps apply to client backoff, server
  `retry:`, and HTTP `Retry-After` values; `initialBackoff` floors server-directed delays to prevent
  tight reconnect loops. Backoff durations require whole-millisecond precision and a value of at
  least 1 ms, matching the transport scheduler's precision. Jitter cannot reduce a positive delay
  to zero, and `Retry-After: 0` is handled by the SSE policy before OkHttp can follow it immediately.
- Each SSE subscription uses and terminally shuts down a private, one-call daemon OkHttp dispatcher,
  so long-lived streams do not consume the supplied client's REST dispatch slots, compete for a
  process-wide SSE limit, or keep the JVM alive. The SDK's HTTP retry interceptor is removed from
  the derived SSE client so application-level retries are not layered beneath the SSE reconnect
  policy.
- The migration codemod rewrites only a uniquely bound, bare `BrokerSseSubscription` parameter or
  local variable in the call's lexical scope. Fields, qualified, shadowed, same-named local/nested
  types, lambda-bound, commented, or otherwise uncertain chains are report-only. Missing inputs fail
  closed.
- Opening and terminal listener startup use separate bounded SDK dispatchers. Opening callbacks are
  admitted before `opened()` continuations run, preventing a synchronous close-and-wait continuation
  from blocking ordered `onOpen`/terminal delivery. Lifecycle futures remain authoritative;
  terminal-dispatch saturation rejects pending terminal listener delivery with a warning instead of
  creating unbounded threads.
- Closing releases an HTTP response thread waiting for opening-dispatch capacity. Terminal listener
  delivery is admitted before synchronous lifecycle-completion continuations run, while invocation
  remains ordered after lifecycle completion and any previously admitted callback.
- Markdown link checking includes the top-level migration index.

### Fixed
- Concurrent `close()` calls now return only after lifecycle completion settles, including when a
  failure wins the terminal transition. Accepted initial and reconnect responses retain their
  successful opening future and ordered open callback ahead of terminal listener delivery.
- Broker admin-action SSE events now use their required `type` discriminator instead of the
  ambiguous generated `oneOf` adapter, while retaining the generated public wrapper type.
- Closing during initial connection or reconnect can no longer publish an uncancelled call after the
  terminal transition, and closing from a listener while awaiting `completion()` no longer
  deadlocks.
- Lifecycle completion settlement no longer runs synchronous future continuations on an active
  listener callback path, so a callback can close while a completion continuation awaits the
  ordered terminal listener.
- Terminal callbacks now verify lifecycle completion even when an existing callback drain consumes
  them. Completion settlement uses isolated, direct-handoff workers so a blocked synchronous
  continuation cannot queue another subscription's settlement or occupy the scheduler. Synchronous
  lifecycle continuations must remain short because concurrently blocked continuations consume
  additional daemon workers; unrelated or potentially unbounded work belongs on an
  application-owned executor through an asynchronous continuation.
- An empty Trading SSE `id:` clears `Last-Event-ID` but retains an unbounded request's original
  `since`/`since_id` lower bound, including `initialLastEventId`, so reconnect cannot silently skip
  activity emitted during backoff. This safe replay can redeliver earlier events. ID-bounded streams
  and cursorless live streams fail closed when no replay-safe lower bound can be supplied.
- Empty initial resume IDs are rejected. Trading rejects an initial ID combined with a date-bounded
  request, and Trading and Corporate Actions reject one after an ID-bounded request's `untilId`,
  instead of relying on undocumented header-only replay or sending an invalid cursor range.
- Trading and Broker activity decoders preserve newly generated but not-yet-handled envelope fields
  through `additionalProperties` instead of silently dropping them after regeneration.
- Trading and Broker activity decoders preserve OAS-valid `DIVTXEX` events through the compatible
  generated `CDIVActivityV2` detail model until the OAS supplies a dedicated schema.
- Migration diagnostic `SSE006` covers generated SSE invocations and method references without
  flagging a uniquely bound handwritten
  `BrokerEventsSseClient.getAccountActivityEventAsync(...)` receiver. `this.receiver` is suppressed
  only for a proven handwritten field declared on the current class; every generated use is
  reported.
- Terminal lifecycle state, timers, and cancellation no longer wait behind user callbacks or block
  the shared scheduler. Terminal listener delivery remains serialized after callbacks already in
  progress, and no new callbacks are admitted after termination.
- Connection deadlines now cover bounded non-success response-body reads, timeout failures retain
  their cancellation cause, and elapsed reconnect-budget expiry preserves the preceding transport
  or HTTP failure instead of starting an immediately cancelled request.
- Accepting response headers invalidates already-started connect-timeout work, preventing a stale
  scheduler task from cancelling the accepted stream at the deadline boundary.
- Established reconnect elapsed-time budgets remain active after accepted headers until event
  delivery, including while the server sends only comments or remains silent.
- Callback-executor rejection during reconnect and Broker single-activity timeout now always settle
  their public futures; terminal completion settles before pre-open `opened()` continuations run.
  Fatal listener throwables are logged even when terminal listener delivery is asynchronous.
- Initial reconnect and open-stream idle budgets remain active while lifecycle callbacks execute.
  SSE clients disable inherited OkHttp read and whole-call timeouts in favor of SDK SSE deadlines.
- Delivered-event cursor commitment and reconnect-deadline reset are atomic, and Broker
  single-activity timeout cannot be overwritten by a callback delivered after termination.
- Canceled idle-timeout tasks are removed from the shared scheduler, and Unicode SSE IDs can be
  replayed without stranding reconnects. HTTP-incompatible cursor controls and request/scheduler
  construction failures now terminate with a protocol failure.
- Pre-open user closure is preserved as cancellation on defensive `opened()` futures. Lifecycle
  deadlines use saturating nanosecond scheduling so positive sub-millisecond and very large
  `Duration` values do not truncate or fail asynchronously, and HTTP-failure header values are
  deeply immutable.
- The migration tool preserves CRLF line endings, scans checkouts beneath ancestor directories
  named `build`, masks escaped delimiters in Java text blocks, and treats same-named type parameters
  (including qualified type-use annotations) and Java Unicode escapes as report-only. It diagnoses
  unqualified generated calls in indirect or anonymous subclasses plus chained `EventSource` casts
  and identity checks.
- Trading and Broker Activity V2 decoders preserve `CSD` events by temporarily representing their
  details as `CSWActivityV2`. The activity type remains `CSD`, and undeclared detail fields remain
  available through `getAdditionalProperties()`, pending a dedicated upstream CSD detail schema.
- SSE contract verification pins each supported stream's ordered parameter wire signatures and
  resolved schema constraints, response schema shapes, exact authentication alternatives, and
  consumed API-key/HTTP-Basic definitions in addition to inventory and handwritten bindings.
- Pull-request and frozen-snapshot CI enforce source and binary API compatibility before publication;
  reviewed exclusions enumerate exact removed generated symbols.

### Behavioral compatibility and migration
- Existing Broker listener signatures remain available. Rich callbacks delegate to the legacy
  shapes by default: malformed events pass their original deserialization cause, HTTP failures pass
  a bounded response with no throwable, and user close passes `IOException("canceled")` with no
  response. Overriding a rich callback suppresses its default legacy delegation.
- `BrokerSseSubscription.eventSource()` is now a request/cancel compatibility facade rather than
  the live OkHttp implementation. Prefer `close()`; casts, identity assumptions, and deep response
  internals require manual review. Cancellation no longer carries OkHttp's stripped active
  response, and protocol/resource failures use the SDK exception hierarchy.
- Listener runtime exceptions are logged and a delivered event's resume cursor still advances.
  Callback-executor rejection is terminal. Lifecycle `completion()` resolves before terminal
  listener delivery and cannot be held up by a blocked callback.
- Trading reconnect requests transmit the committed cursor through the documented `since_id` query
  and `Last-Event-ID`. Replay relies on `since_id`; no backend consumption claim is made for the
  standard header.
- See [`MIGRATIONS.md`](MIGRATIONS.md) for the `0.1.4` → `0.2.0` guide and conservative codemod.
  This substantial additive/behavioral release uses the repository's documented pre-1.0
  compatibility policy.

## [0.1.4] - 2026-09-23

### Breaking
Adopting upstream Broker account-creation schemas ([#83](https://github.com/alpacahq/alpaca-java/pull/83)):
- `AccountCreationRequest.getAccountType()` now returns `AccountCreationType` instead of `AccountType`.
- `enabled_assets` is now `List<EnabledAssetClass>` on `Account` and `AccountCreationRequest`
  (previously `List<AssetClass>`).

### Added
- Broker `AcatsApi` and ACATS transfer models
  ([#65](https://github.com/alpacahq/alpaca-java/pull/65)).
- Broker `AccountCreationType` / `EnabledAssetClass`, plus `entity_id` and `minor_identity` on
  account creation and `enabled_assets` on `AccountUpdateRequest`
  ([#83](https://github.com/alpacahq/alpaca-java/pull/83)).

### Fixed
- The semantic diff no longer classifies added enum values as breaking. A widened enum keeps every
  value callers already compile against, so `adoptOpenApi` adopts it without `--allow-breaking`.
  Enum value removals stay breaking.

### Changed
- A failed `generateApis` or `compileJava` after an adopt pin write now restores the previous pins
  and regenerates/recompiles from them, so a partly synced or uncompilable generation cannot leave
  `specs/` and the generated sources out of step. The pin backup is dropped by
  `clearOpenApiPinBackup` after a successful compile (including when compile is UP-TO-DATE), so
  failing tests afterward cannot rewind adopted pins.
- Failed adopt output is preserved for diagnosis, and the weekly job no longer treats an empty
  additive drift report as failure.
- `spotbugsMain` now analyses every handwritten class except `markets.alpaca.client.openapi`,
  instead of an allowlist that silently skipped new packages.
- The semantic diff also treats OAS 3.1 `nullable` equivalents (`type: [T, null]`,
  `anyOf`/`oneOf` with a `type: null` member — including `$ref` siblings) and
  `format: binary` / binary `contentMediaType` spellings as non-breaking, alongside
  the enum-value handling above and additive `oneOf` / `anyOf` composition members.
  Added `allOf` members remain breaking because an intersection can tighten the
  generated model. Operation `security` is compared after document-level inheritance:
  adding OR alternatives while keeping every previously accepted scheme set is
  additive; removing or replacing scheme sets, AND-tightening within an alternative,
  and optional/empty → required auth stay breaking.
- Adopt now updates the pins whenever the preprocessed upstream document differs from the
  committed one (structural YAML compare), not only when the semantic diff is non-empty.
  Changes the classifier treats as equivalent (`nullable` spellings, binary media types,
  inherit-vs-explicit security with the same effective requirements) previously left the
  pins stale forever when only the spelling moved. Key-order-only churn does not count as
  drift.
- The weekly drift workflow always opens a single adopt PR on `bot/openapi-adopt` (force-pushed),
  draft when the classifier finds breaking changes and ready otherwise; it no longer files a
  separate breaking-drift issue. A breaking adopt is expected to fail its nested
  `generateApis test` run, so the workflow still publishes the resulting pins and generated
  sources as a draft PR and only then fails the run.
- Pre-commit now runs the CI Gradle code checks on every commit.

## [0.1.3] - 2026-08-06

### Breaking
Adopting the upstream Broker and Trading documents, which are now OpenAPI 3.1.2 rather than 3.0.0,
changed two parts of the generated API surface.

- Broker `EventsApi.subscribeToFundingStatusSSE` now returns `List<StatusFundingEvent>`, and
  `BrokerEventsSseClient.subscribeToFundingStatus` emits `StatusFundingEvent`. The
  `SubscribeToFundingStatusSSE200ResponseInner` wrapper is removed; it only ever held a
  single-member union and now collapses to the member itself.
- Broker and Trading `OrderLeg.getLegs()` returns `Object` rather than `List<Object>`, and
  `addLegsItem` is gone. Upstream now declares the property as null-only, matching its existing
  documentation that legs are never nested beyond one level.

### Fixed
- Preprocessing now normalizes the OpenAPI 3.1 constructs that the Java generator renders into
  uncompilable or under-typed code, so adopting a 3.1 document no longer silently degrades the
  generated clients:
  - A standalone `type: 'null'` schema became a `ModelNull` class the generator never emitted,
    breaking compilation. Null-only subschemas of `anyOf` / `oneOf` are left alone, since those
    are the idiomatic 3.1 spelling of "nullable" and already generate correctly.
  - A schema with `items` and no `type` is no longer read as an array, which had turned Broker
    `FundingWalletsApi.listFundingDetails` into a bare `Object`.
  - A `oneOf` of string enums is collapsed back into one enum, keeping Broker `JournalStatusFrom`
    and `TransferStatusFrom` as enums instead of `AbstractOpenApiSchema` wrappers.
  - Path-item parameters are inlined into each operation ahead of the operation's own parameters,
    which had otherwise duplicated Broker `createTransferForAccount`'s `account_id` into a second
    `accountId2` argument and reordered `getOrderByClientOrderIdForAccount`'s arguments.

### Changed
- GitHub Releases now use the curated `CHANGELOG.md` section for the tag (with a
  non-empty `[Unreleased]` fallback), then append GitHub’s compare link for the
  tag range. The release workflow fails before Maven Central if neither section
  has content, and the post-release bump PR promotes `[Unreleased]` to a dated
  version section when needed.

## [0.1.2] - 2026-08-05

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
