---
type: Architecture Decision
title: ADR-0001 Generate REST clients from committed OpenAPI pins
description: REST clients are generated with OpenAPI Generator from OpenAPI documents pinned in the repository, and the generated sources are committed.
status: accepted
date: 2026-08-05
deciders: []
supersedes: []
affects: [alpaca_java.generated_rest, alpaca_java.codegen]
allium: []
evidence: [GENERATION.md, README.md, AGENTS.md, CONTRIBUTING.md, CHANGELOG.md, specs/broker/openapi.yaml, specs/data/openapi.yaml, specs/trading/openapi.yaml, build-logic/src/main/groovy/alpaca.openapi-generation.gradle]
tags: [alpacahq, adr, generated]
timestamp: 2026-11-07T11:16:25Z
generated_by: claude-fable-5 (initial 2026-10-29), claude-opus-5-5 (refresh 2026-11-07) / layered-docs 2026-09
source_commit: 8ac57386389f643f8b81b2db9b375b44c72c8e79
source_branch: docs/layered-2026-09
generated_at: 2026-11-07T11:16:25Z
confidence: high
review_status: draft-needs-review
---

# ADR-0001 Generate REST clients from committed OpenAPI pins

## Context
The SDK covers three large REST APIs (Broker, Market Data, Trading); the generated model packages alone hold several hundred classes (src/main/java/markets/alpaca/client/openapi/*/model/). Release 0.1.1 "generated from live specs at build time"; release 0.1.2 (2026-08-05) adopted committed pins and notes that the live documents had renamed and removed parts of the generated surface in the meantime (CHANGELOG.md "[0.1.2]"). GENERATION.md defines a trust model: the live OpenAPI documents at docs.alpaca.markets are the upstream source of trust, and the committed pins under `specs/{broker,data,trading}/openapi.yaml` are the SDK source of trust.

## Decision
- REST clients are produced by OpenAPI Generator (okhttp-gson library) from the committed pins, after SnakeYAML preprocessing in `OpenApiSpecSupport` (GENERATION.md, build-logic/src/main/groovy/markets/alpaca/gradle/OpenApiSpecSupport.groovy, gradle/libs.versions.toml).
- Normal builds and publishes use the pins only; the live documents are fetched only by adopt/drift flows (GENERATION.md "Trust model").
- Generated sources are committed under `src/main/java/markets/alpaca/client/openapi/` and are never hand-edited; fixes go to preprocessing or Mustache templates under `src/main/openapi-templates/` (GENERATION.md "Layout", AGENTS.md "Do not", CONTRIBUTING.md "Generated vs handwritten code").
- `compileJava` depends on `generateApis`, which GENERATION.md states is intentional: consumers of a checked-out commit get sources that match the pins, and maintainers cannot compile a tree that has drifted from those pins without regenerating first.

## Consequences
- `./gradlew check` runs `checkGenerated`, which fails if `specs/` or the generated sources drift, and `checkSseContracts`, which fails if a pinned SSE operation loses its handwritten binding (GENERATION.md "Everyday commands").
- Builds are reproducible without network access to the upstream documents (GENERATION.md).
- Upstream changes reach users only through an explicit adopt commit that regenerates all three clients (see ADR-0003); adopting the pins in 0.1.2 and 0.1.4 produced documented breaking changes in the generated surface (CHANGELOG.md).
