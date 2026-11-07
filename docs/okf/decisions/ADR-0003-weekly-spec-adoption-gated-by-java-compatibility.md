---
type: Architecture Decision
title: ADR-0003 Weekly upstream spec adoption gated by Java surface compatibility
description: A weekly workflow adopts upstream OpenAPI changes into the pins through a bot pull request whose draft/ready state depends on generated Java SDK compatibility.
status: accepted
date: unknown
deciders: []
supersedes: []
affects: [alpaca_java.codegen, alpaca_java.generated_rest]
allium: []
evidence: [GENERATION.md, .github/workflows/openapi-drift.yml, scripts/adopt_openapi.py, scripts/sdk_api_diff.py, CHANGELOG.md, "commit 0d689e6", "commit cbc31ea"]
tags: [alpacahq, adr, generated]
timestamp: 2026-11-07T11:16:25Z
generated_by: claude-fable-5 (initial 2026-10-29), claude-opus-5-5 (refresh 2026-11-07) / layered-docs 2026-09
source_commit: 8ac57386389f643f8b81b2db9b375b44c72c8e79
source_branch: docs/layered-2026-09
generated_at: 2026-11-07T11:16:25Z
confidence: high
review_status: draft-needs-review
---

# ADR-0003 Weekly upstream spec adoption gated by Java surface compatibility

## Context
Pins (ADR-0001) must keep up with the live API documents while protecting SDK users from unreviewed breaking changes in the generated Java surface (GENERATION.md "Adopting upstream changes"). Earlier adopts had landed pins and generated sources out of sync, and failed adopts had reused merged branches or opened ready pull requests (CHANGELOG.md "[0.1.2]" and "[0.1.4]" Changed sections; commit 0d689e6).

## Decision
- Adoption follows pin, semantic diff, adopt, regenerate, verify (GENERATION.md). Local `./gradlew adoptOpenApi` refuses breaking changes with exit code 2 until `adoptOpenApiBreaking` is run after review; adoption is all-or-nothing across broker, data and trading (GENERATION.md).
- The weekly `openapi-drift.yml` (cron `15 9 * * 1`, also manually dispatchable) opens or updates a bot pull request on `bot/openapi-adopt`. The draft/ready gate is generated Java compatibility from `scripts/sdk_api_diff.py`: removed or changed public generated Java opens a draft labelled `breaking`; upstream OpenAPI classifier findings remain diagnostics only (GENERATION.md "CI", .github/workflows/openapi-drift.yml).
- `adoptOpenApi` and `adoptOpenApiBreaking` regenerate the clients in the same invocation, and a failed generate or compile restores the previous pins from `build/specs-pin-backup/` (GENERATION.md, CHANGELOG.md "[0.1.2]" and "[0.1.4]").
- Pins are adopted even when the semantic diff is empty, as a "pin spelling catch-up" that is never breaking (GENERATION.md).

## Consequences
- Adding an operation parameter counts as breaking for this SDK because the generator widens every overload signature (GENERATION.md).
- Failed or unclassified adopts still produce a draft pull request for triage instead of disappearing (GENERATION.md "CI"; commits 0d689e6, cbc31ea).
- `checkGenerated` is skipped inside the drift job because it diffs against HEAD before the adopt is committed; freshness is only verified by the full Build workflow on the bot branch, which a maintainer must approve (GENERATION.md "CI", commit cbc31ea).
