---
type: Architecture Decision
title: ADR-0005 Shared bounded reconnecting SSE transport
description: Version 0.2.0 moves all Server-Sent Events clients onto one handwritten, bounded, reconnecting transport with a common subscription interface, instead of exposing the OkHttp EventSource.
status: accepted
date: unknown
deciders: []
supersedes: []
affects: [alpaca_java.sse_clients, alpaca_java.client_facade]
allium: []
evidence: [docs/content/sdk/sse-migration.md, MIGRATIONS.md, CHANGELOG.md, src/main/java/markets/alpaca/client/sse/package-info.java, src/main/java/markets/alpaca/client/sse/internal/SseTransport.java, scripts/migrate_sse_0_1_4_to_0_2_0.py, "commit e199590", "commit 1c931e8"]
tags: [alpacahq, adr, generated]
timestamp: 2026-10-29T08:30:00Z
generated_by: claude-fable-5 / layered-docs 2026-09
source_commit: 9a1a58e4da6da89c86a46c5dbc23dd6b21e3b1f0
source_branch: docs/layered-2026-09
generated_at: 2026-10-29T08:30:00Z
confidence: medium
review_status: draft-needs-review
---

# ADR-0005 Shared bounded reconnecting SSE transport

## Context
Up to 0.1.4 only Broker SSE existed, and `BrokerSseSubscription.eventSource()` exposed the live OkHttp `EventSource` to callers (docs/content/sdk/sse-migration.md). Version 0.2.0 adds Trading account-activity and Market Data corporate-actions SSE, so three APIs need the same connection, reconnect and failure semantics (CHANGELOG.md "[Unreleased]", commit e199590 "feat: sse support update").

## Decision
- One shared SSE layer in `markets.alpaca.client.sse` provides lifecycle, reconnect policy, options, callback and subscription types; the transport, parser and decoders live in `sse.internal`, which is not public API (src/main/java/markets/alpaca/client/sse/package-info.java, CONTRIBUTING.md).
- Broker SSE moves onto that transport while keeping its method signatures, one-connection default and per-event malformed-payload handling; `BrokerSseSubscription` implements the shared `AlpacaSseSubscription` interface, and `eventSource()` now returns a stable facade rather than the live OkHttp implementation (docs/content/sdk/sse-migration.md, CHANGELOG.md).
- Callbacks are serialized per subscription; lifecycle completion, cancellation and timers never wait for user callbacks; a malformed event is reported through `onEventFailure`, its ID is committed as the resume cursor and the connection continues (src/main/java/markets/alpaca/client/sse/package-info.java, CHANGELOG.md, commit 1c931e8).
- A conservative scanner/codemod rewrites the narrow `eventSource().cancel()` pattern to `close()` and reports uncertain cases for manual review (MIGRATIONS.md, scripts/migrate_sse_0_1_4_to_0_2_0.py).

## Consequences
- Applications must review cancellation, callback scheduling and failure handling when upgrading; a dedicated migration guide and codemod ship with the release (MIGRATIONS.md).
- `checkSseContracts` pins the wire signature, schema constraints and authentication alternatives of every SSE operation consumed by a handwritten client, so a spec adopt cannot silently break them (GENERATION.md).
- Release validation must fail rather than skip when SSE credentials or a replay window are absent (TESTING.md).

> Unverified: the decision date is taken from the unreleased 0.2.0 changelog section and the SSE commit series (e199590 through 1c931e8); no pull-request discussion is in the clone (confidence medium).
