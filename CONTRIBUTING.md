# Contributing

Thanks for helping improve alpaca-java. By participating, you agree to follow our [Code of Conduct](./CODE_OF_CONDUCT.md).

## Choose the right channel

- **Library / SDK issues:** Bugs, feature requests, or questions specific to this SDK → [GitHub Issues](https://github.com/alpacahq/alpaca-java/issues/new/choose).
- **Security vulnerabilities:** Do not report them publicly. Follow the private reporting process in our [Security Policy](./SECURITY.md).
- **Alpaca API bugs:** Bugs in the Alpaca API itself, its OpenAPI specs, or its documentation → [alpacahq/alpaca-api](https://github.com/alpacahq/alpaca-api/issues/new/choose).
- **Community:** API feature requests, general questions, and dashboard bugs → [Alpaca Community Forum](https://forum.alpaca.markets/) or [Slack](https://alpaca.markets/slack).
- **Account help:** Questions about your account, funding, or orders → [Support request](https://alpaca.markets/support/request).
- **Outages:** Service status and incident updates → [Status page](https://status.alpaca.markets/).

Please do not include credentials, account details, request or trace IDs, or other sensitive information in an issue, discussion, pull request, test, or example.

## Contribution policy

### Contributions we welcome

Focused contributions are welcome when they improve this SDK, including:

- bug fixes and compatibility improvements;
- support for documented Alpaca API capabilities;
- focused tests and reliability improvements;
- documentation and examples; and
- maintainability improvements that preserve public behavior.

### Discuss substantial changes first

Open an issue before investing in a substantial SDK design, new dependency, broad refactor, or third-party integration. Early discussion helps confirm that the proposal fits the SDK's scope and avoids duplicated or unusable work.

Bugs in the Alpaca API itself, its OpenAPI specs, or its documentation must be fixed upstream, not patched in this SDK. Report them in [alpacahq/alpaca-api](https://github.com/alpacahq/alpaca-api/issues/new/choose). API feature requests, general questions, and dashboard bugs belong on the [Alpaca Community Forum](https://forum.alpaca.markets/) or [Slack](https://alpaca.markets/slack). Contributions that add support for already documented API capabilities are welcome after the SDK design is agreed with maintainers.

### Out of scope

This repository does not accept:

- trading strategies, algorithm design, or investment advice;
- bugs in the Alpaca API, its OpenAPI specs, or docs.alpaca.markets;
- API feature requests, dashboard bugs, or outage reports;
- questions that aren't about this SDK, including questions about your account, funding, or orders;
- credentials, private account data, request or trace IDs, or other secrets;
- referral links or promotional content; or
- unapproved integrations with third-party products or services.

Contributions that add or promote third-party products or services, including provider-specific clients, API calls, hosted endpoints, referral links, or promotional content, require prior written approval from Alpaca before implementation. Open an issue describing the technical scope; repository maintainers will coordinate the necessary internal review and confirm whether the proposal may proceed. Contributors do not need to identify or contact internal approvers.

Using an ordinary open-source dependency or improving generic interoperability does not by itself make a contribution a provider-specific integration. Maintainers will explain when a proposal falls outside this policy. A pull request that proceeds without the required approval may be closed as out of scope, so open an issue first if you are unsure whether a proposal qualifies.

## License and contribution terms

The project is licensed under the [Apache License 2.0](./LICENSE). Subject to its full terms, the license permits use, reproduction, modification, and distribution of the software. It does not grant permission to use Alpaca trade names, trademarks, service marks, or product names except as described in the license.

Unless you explicitly state otherwise, a contribution intentionally submitted for inclusion in this project is provided under Apache-2.0 without additional terms, as described in Section 5 of the license. This project does not currently require a separate Contributor License Agreement (CLA) or Developer Certificate of Origin (DCO) sign-off.

## Contribution workflow

1. Search existing issues and pull requests.
2. Open an issue first for work described under [Discuss substantial changes first](#discuss-substantial-changes-first).
3. Fork the repository and create a focused branch.
4. Make the change in handwritten source files or through the documented OpenAPI generation workflow.
5. Add focused tests for new or changed behavior and update relevant documentation.
6. Run the relevant checks listed below.
7. Open a focused pull request, explain why the change is needed, and respond to review feedback.

Use clear, descriptive commit messages and pull request titles. Conventional Commits are not required, and contributors do not need to squash commits before opening a pull request. Maintainers use GitHub's squash-merge workflow for the final merge.

## Prerequisites

- Java 17 or newer.
- The repository's Gradle Wrapper (`./gradlew`).
- Node.js `>=22.23.4 <23` for Docusaurus documentation checks, matching CI.
- Python 3 and Bash when using the `adoptOpenApi*` tasks.
- `pre-commit` and `lychee` when running the optional local hooks.

## Setup and checks

Run the standard Java verification from the repository root:

```bash
./gradlew build
```

`build` includes `check`, which runs unit tests, quality checks, generated-source drift checks, and example compilation.

Run additional checks when they apply to your change:

```bash
./gradlew checkApiCompatibility         # public SDK surface changes
./gradlew integrationTest               # live API behavior; skips without credentials
npm ci --prefix docs                    # install locked documentation dependencies
npm run build --prefix docs             # Docusaurus or Javadoc changes
pre-commit run markdown-links --all-files
```

`checkApiCompatibility` compares the public JAR with the released baseline. It reports source or binary incompatibilities as warnings and does not fail the build, so inspect its output and update migration guidance when needed.

Useful focused code-quality checks are:

```bash
./gradlew spotlessCheck                 # formatting and import order
./gradlew checkstyleMain checkstyleTest # source style checks
./gradlew spotbugsMain                  # static bug analysis for handwritten classes
./gradlew checkSseContracts             # verify pinned SSE coverage
```

Apply formatter fixes with `./gradlew spotlessApply`. Generated OpenAPI output under `src/main/java/markets/alpaca/client/openapi/` is excluded from formatting and static-analysis checks.

To run the same checks locally before each commit, install the hooks once:

```bash
pre-commit install
```

This installs Markdown link validation (`lychee`), matching CI, and Gradle code checks (`./gradlew spotlessCheck checkstyleMain checkstyleTest spotbugsMain`). To skip Gradle checks for a single commit, use `SKIP=gradle-code-checks git commit ...`.

There is no numeric coverage threshold, but changed behavior must have focused tests. See [`TESTING.md`](./TESTING.md) for unit and integration test commands and live-test credentials.

## Testing and financial safety

- Default repository tests and CI must never place live orders or depend on Alpaca credentials. They must be deterministic and use mocks, fakes, or synthetic fixtures for API behavior.
- Opt-in integration tests are read-only, skip without credentials, use Trading paper and Broker sandbox credentials, and require bounded SSE replay windows where applicable.
- Runnable trading examples must use paper trading by default and require an explicit user choice before targeting live trading.
- Real credentials, account identifiers, account data, and production payloads must never appear in tests, fixtures, examples, or committed logs and artifacts. Remove request and trace IDs from public or committed material; share private diagnostics only through Alpaca Support channels when needed.

## Coding guidelines

### Generated versus handwritten code

Never hand-edit files under `src/main/java/markets/alpaca/client/openapi/`. Regenerate with `./gradlew generateApis` or adopt upstream via `./gradlew adoptOpenApi` or `./gradlew adoptOpenApiBreaking`. See [`GENERATION.md`](./GENERATION.md). When a pinned spec changes an SSE operation, run `./gradlew checkSseContracts`; `checkGenerated` and `build` run it as well.

Pinned OpenAPI documents live under `specs/`. Handwritten code belongs under `src/main/java/markets/alpaca/client/`, including the `data/`, `data/sse/`, `http/`, `rest/`, `trading/`, `trading/sse/`, `broker/sse/`, `sse/`, and `ws/` packages. The generated REST packages under `markets.alpaca.client.openapi/**` are owned by OpenAPI Generator. Do not add handwritten code there.

`markets.alpaca.client.sse.internal` is an implementation package, not supported public API. It is excluded from published Javadocs and API compatibility checks. Keep user-facing lifecycle types in `markets.alpaca.client.sse`, and do not expose internal transport types from public signatures.

### Generated-code fixes

Do not modify generated files. For a spec bug, add a preprocessing fix to the relevant task in `build-logic/src/main/groovy/alpaca.openapi-generation.gradle`. For a generator limitation, wrap the generated class in a handwritten decorator under `src/main/java/`.

Put reusable parsed-YAML SnakeYAML transformations in `build-logic/src/main/groovy/markets/alpaca/gradle/OpenApiSpecSupport.groovy` and use its `loadSpec` and `dumpYaml` helpers. Do not patch OpenAPI YAML with regex or raw string substitution, and do not modify source specs in `alpaca-docs-private` from this repository.

### Public API and documentation

Run `./gradlew checkApiCompatibility` before merging changes that could affect the public SDK surface. Read [`MIGRATIONS.md`](./MIGRATIONS.md) and update it with compatibility and upgrade guidance when applicable. Update Javadocs, Docusaurus documentation, examples, and the changelog when public behavior changes.

### WebSocket models

Monetary and price values in WebSocket model records must use `BigDecimal`, never `double` or `float`.

## Maintainer releases

Read [`RELEASING.md`](./RELEASING.md) before creating a release, changing release automation, or recovering from a failed publication.

## Pull request checklist

Before requesting review, confirm that:

- the change is focused and follows the contribution policy;
- any substantial API, dependency, or third-party work was discussed with maintainers before implementation;
- `./gradlew checkApiCompatibility` was run when the change could affect the public SDK surface;
- generated code was not edited directly;
- new or changed behavior has focused tests when applicable;
- default tests are deterministic and credential-free and cannot place live orders, trading examples default to paper trading, and fixtures use synthetic data;
- documentation and examples are updated when applicable;
- applicable formatting, generation, test, API compatibility, and documentation checks pass;
- no credentials, account data, request or trace IDs, or other secrets are included; and
- any provider-specific integration or promotional contribution has written Alpaca approval confirmed by repository maintainers.
