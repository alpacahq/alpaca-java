Contributing to alpaca-java-client
===================================

Thank you for taking the time to contribute!
We are very open to contributions that improve the client, add missing features, or fix issues.

## How do I file a bug / ask a question / request a feature?

We track these here on GitHub via our [issues](https://github.com/alpacahq/alpaca-java-client/issues/new/choose), with
a template for each case that will ask you to fill out some information to better help us solve your issue.
(Note that not filling out the template or skipping questions is likely to delay resolution and may result in the issue
being closed for missing information.)

> **NOTE** that we can only solve issues with the SDK. If you are reporting an issue with the API itself, please open
> an issue over [here](https://github.com/alpacahq/Alpaca-API) instead.

## How do I contribute code?

1. Fork this repo and create a branch for your changes.
2. Follow the [Getting started](README.md#getting-started) section in the README to configure your local environment.
3. Code away — see [Coding Guidelines](#coding-guidelines) below for what belongs where.
4. Run `./gradlew check` and `./gradlew build` to verify everything passes. The root `check` task runs unit tests, code checks, and compiles `examples/` so they stay in sync without being packaged in the published artifact.
5. Open a PR with your changes.

This repo uses merge commits, so there is no need to squash commits before opening a PR.

## Coding Guidelines

### Generated vs handwritten code

**Never edit files under `build/generated/`** — they are overwritten on every `./gradlew generateApis` run.

Handwritten code belongs in:

- `src/main/java/markets/alpaca/client/` — top-level SDK concepts such as `AlpacaClientFactory` and `AlpacaCredentials`
- `src/main/java/markets/alpaca/client/data/` — handwritten Market Data helpers
- `src/main/java/markets/alpaca/client/http/` — handwritten OkHttp and retry helpers
- `src/main/java/markets/alpaca/client/rest/` — handwritten REST utilities
- `src/main/java/markets/alpaca/client/trading/` — handwritten Trading helpers
- `src/main/java/markets/alpaca/client/broker/sse/` — Broker Events SSE wrapper
- `src/main/java/markets/alpaca/client/ws/` — WebSocket stream clients, listeners, subscriptions, and models

The generated REST packages under `markets.alpaca.client.openapi/**` are owned by OpenAPI Generator. Do not add
handwritten code there.

### Fixing generated code behaviour

Do **not** modify the generated file. Instead:

- **Spec bug** → add a preprocessing fix to the relevant task in `build.gradle`.
- **Generator limitation** → wrap the generated class in a handwritten decorator under `src/main/java/`.

### Spec preprocessing fixes

If generated code breaks due to an upstream spec issue, add a fix to the appropriate `preprocess*Spec` task in
`build.gradle`. Use the SnakeYAML-based helpers already in the build script (`loadSpec` / `dumpYaml`). Do **not** patch
OAS YAML with regex or raw string substitution, and do **not** modify the source specs in `alpaca-docs-private` from
this repository.

### WebSocket models

Monetary and price values in WebSocket model records must use `BigDecimal` — never `double` or `float`.

### Testing

See the [Testing](README.md#testing) section in the README for how to run unit and integration tests and how to supply
credentials for the live API tests.

### Code checks

Run the full verification gate:

```bash
./gradlew check
```

Useful focused checks:

```bash
./gradlew spotlessCheck                 # formatting and import order
./gradlew checkstyleMain checkstyleTest # source style checks
./gradlew spotbugsMain                  # static bug analysis for handwritten classes
./gradlew spotlessApply                 # apply formatter changes
```

Generated OpenAPI output under `build/generated/` is excluded from formatting and static-analysis checks.

## PR Checklist

- [ ] Generated code (`build/generated/`) was not edited directly.
- [ ] New behaviour has focused unit tests.
- [ ] WebSocket monetary values avoid floating-point types (`double`/`float`).
- [ ] `README.md`, `AGENTS.md`, and `CHANGELOG.md` are updated when public behaviour changes.
- [ ] `./gradlew check` and `./gradlew build` pass locally.
