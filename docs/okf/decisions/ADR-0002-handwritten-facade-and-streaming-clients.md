---
type: Architecture Decision
title: ADR-0002 Handwritten facade and streaming clients over generated code
description: A client facade, WebSocket stream clients and SSE wrappers are handwritten outside the generated package, and generator limitations are handled by handwritten wrappers rather than edits to generated code.
status: accepted
date: unknown
deciders: []
supersedes: []
affects: [alpaca_java.client_facade, alpaca_java.ws_streams, alpaca_java.sse_clients, alpaca_java.generated_rest]
allium: []
evidence: [README.md, AGENTS.md, CONTRIBUTING.md, LLMS.md, src/main/java/markets/alpaca/client/AlpacaClientFactory.java, src/main/java/markets/alpaca/client/ws/, src/main/java/markets/alpaca/client/sse/package-info.java]
tags: [alpacahq, adr, generated]
timestamp: 2026-11-07T11:16:25Z
generated_by: claude-fable-5 (initial 2026-10-29), claude-opus-5-5 (refresh 2026-11-07) / layered-docs 2026-09
source_commit: 8ac57386389f643f8b81b2db9b375b44c72c8e79
source_branch: docs/layered-2026-09
generated_at: 2026-11-07T11:16:25Z
confidence: medium
review_status: draft-needs-review
---

# ADR-0002 Handwritten facade and streaming clients over generated code

## Context
The REST surface comes from OpenAPI generation (ADR-0001), but the generated Broker, Data and Trading `ApiClient` classes are distinct and non-interchangeable, and the generated SSE operations are "buffered REST methods and are not suitable for live streams" (AGENTS.md "Architecture invariants", src/main/java/markets/alpaca/client/sse/package-info.java). WebSocket streaming is not described by the OpenAPI documents at all.

## Decision
- WebSocket stream clients and the Trading, Broker and Market Data corporate-actions SSE clients are handwritten and committed in `src/main/java/markets/alpaca/client/` (README.md).
- Generated `ApiClient` instances are always constructed through `AlpacaClientFactory`, which sets the API-specific authentication and base URL; users are told never to instantiate them directly (AGENTS.md, LLMS.md rule 4, examples/README.md).
- Common SDK behaviour (HTTP configuration, retry, REST utilities, trading and data helpers) lives in handwritten packages listed in CONTRIBUTING.md; a spec defect is fixed in preprocessing and a generator limitation by a handwritten wrapper, never by editing generated files (CONTRIBUTING.md "Fixing generated code behaviour", AGENTS.md).
- Users reach `markets.alpaca.client.openapi.*` only when a handwritten facade lacks the endpoint (LLMS.md rules 1-3).
- WebSocket price and fractional-size fields use `BigDecimal`, never `double` or `float` (AGENTS.md).

## Consequences
- Two code regimes with different rules: handwritten code is analysed by SpotBugs and checked by japicmp against the released baseline, and both the handwritten and the generated surface are MAJOR-version sensitive (CONTRIBUTING.md, CHANGELOG.md "Versioning policy", gradle.properties `apiBaselineVersion`).
- SSE reuses the API-key authentication of the generated clients; there is no SDK-wide OAuth token-provider abstraction yet, and an SSE-only token path is explicitly ruled out (AGENTS.md "Architecture invariants").

> Unverified: the repository states the split and its rules but no dated discussion of why the WebSocket clients were handwritten; the rationale above is taken from AGENTS.md, LLMS.md and the sse package Javadoc (confidence medium).
