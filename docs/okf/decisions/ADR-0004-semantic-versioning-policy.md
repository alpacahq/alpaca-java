---
type: Architecture Decision
title: ADR-0004 Semantic versioning tied to handwritten and generated surface
description: Versions follow Semantic Versioning, with MAJOR for breaking handwritten or generated API changes and MINOR for new spec coverage, enforced by a compatibility check against the released baseline.
status: accepted
date: unknown
deciders: []
supersedes: []
affects: [alpaca_java]
allium: []
evidence: [CHANGELOG.md, gradle.properties, RELEASING.md, .github/workflows/release.yml, .github/workflows/build.yml, "commit 91fb855"]
tags: [alpacahq, adr, generated]
timestamp: 2026-11-07T11:16:25Z
generated_by: claude-fable-5 (initial 2026-10-29), claude-opus-5-5 (refresh 2026-11-07) / layered-docs 2026-09
source_commit: 8ac57386389f643f8b81b2db9b375b44c72c8e79
source_branch: docs/layered-2026-09
generated_at: 2026-11-07T11:16:25Z
confidence: high
review_status: draft-needs-review
---

# ADR-0004 Semantic versioning tied to handwritten and generated surface

## Context
The artifact exposes both a handwritten API and a large generated API, both consumed from Maven Central, and adopting upstream specs (ADR-0003) can rename or remove generated types (README.md, CHANGELOG.md).

## Decision
Semantic Versioning with this policy (CHANGELOG.md "Versioning policy", gradle.properties):
- MAJOR — breaking change to `AlpacaClientFactory`, `AlpacaCredentials`, HTTP helpers, REST helpers or the WebSocket/SSE public API, or to the generated API surface.
- MINOR — new backward-compatible functionality, endpoint or model coverage.
- PATCH — bug fixes, dependency updates, preprocessing fixes.
- `markets.alpaca.client.sse.internal` is implementation-only and excluded from Javadocs and compatibility checks (CHANGELOG.md, AGENTS.md).
While the version is `0.x` the public API is initial development; the policy applies strictly from `1.0.0` (CHANGELOG.md).

## Consequences
- `./gradlew checkApiCompatibility` (japicmp) compares the public JAR against the released `apiBaselineVersion` (currently 0.1.4) on every pull request and snapshot build (gradle.properties, .github/workflows/build.yml, commit 91fb855 "build: add public API compatibility checks").
- Releases are cut by the manually dispatched release workflow, restricted to `main` and the protected `maven-central` environment; development continues on a `-SNAPSHOT` version that the post-release bump pull request advances together with the baseline (gradle.properties `version=0.2.0-SNAPSHOT`, .github/workflows/release.yml, RELEASING.md).
