Contributing to alpaca-java
===================================

Thank you for taking the time to contribute!
We are very open to contributions that improve the client, add missing features, or fix issues.

## How do I file a bug / ask a question / request a feature?

We track these here on GitHub via our [issues](https://github.com/alpacahq/alpaca-java/issues/new/choose), with
a template for each case that will ask you to fill out some information to better help us solve your issue.
(Note that not filling out the template or skipping questions is likely to delay resolution and may result in the issue
being closed for missing information.)

> **NOTE** that we can only solve issues with the SDK. If you are reporting an issue with the API itself, please open
> an issue over [here](https://github.com/alpacahq/Alpaca-API) instead.

## How do I contribute code?

1. Fork this repo and create a branch for your changes.
2. Follow the [Build from source](README.md#build-from-source) section in the README to configure your local environment.
3. Code away — see [Coding Guidelines](#coding-guidelines) below for what belongs where.
4. Run `./gradlew check` and `./gradlew build` to verify everything passes. The root `check` task runs unit tests, code checks, and compiles `examples/` so they stay in sync without being packaged in the published artifact.
5. Open a PR with your changes.

This repo uses merge commits, so there is no need to squash commits before opening a PR.

## Maintainer releases

Stable releases are performed through GitHub. Create the tag and GitHub Release in the GitHub UI,
then use the GitHub Actions **Release** workflow to publish the artifacts to Maven Central.

Before starting, ensure the intended release commit and the current Release workflow are on
`main`. You must also be allowed to create release tags by the repository ruleset. If GitHub
rejects tag creation with `GH013` and “creations being restricted,” ask a repository administrator
to allow release-tag creation or add you to the ruleset bypass list.

### 1. Create the tag and GitHub Release

In GitHub, open **Releases → Draft a new release**:

1. In **Choose a tag**, enter a stable version in the exact form `vMAJOR.MINOR.PATCH`, such as
   `v1.2.3`, then select **Create new tag**.
2. Set the target branch to `main`.
3. Enter the release title and notes.
4. Select **Publish release**.

Publishing the GitHub Release creates the remote tag. The tag must point to a commit reachable
from `main`.

### 2. Run the GitHub Release workflow

In GitHub, open **Actions → Release → Run workflow**:

1. Select the `main` branch.
2. Set `tag` to the tag created with the GitHub Release, such as `v1.2.3`.
3. Leave `recover_existing_release` disabled for a normal release.
4. Run the workflow.

### 3. Approve Maven Central publication

Approve the protected `maven-central` environment deployment when GitHub requests approval.

The workflow validates the tag, builds and signs the artifacts, publishes to Maven Central,
leaves the already-published GitHub Release unchanged, and opens a pull request for the next
patch `-SNAPSHOT` version.

### 4. Merge the next-development-version pull request

After the release workflow opens its version-bump pull request, approve and merge it through the
normal `main` branch-protection process. The Build workflow then runs for the resulting `main`
commit and publishes the next development snapshot.

### 5. Verify the release

Confirm that the GitHub Actions workflow completed successfully and that the release POM is
available from Maven Central:

```text
https://repo.maven.apache.org/maven2/markets/alpaca/alpaca-java/1.2.3/alpaca-java-1.2.3.pom
```

### Recovery after an ambiguous Maven Central result

Enable `recover_existing_release` only after confirming the exact release POM is publicly
available from Maven Central following an ambiguous publication failure. Never use recovery for a
new release or merely because a workflow run failed.

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

- **Spec bug** → add a preprocessing fix to the relevant task in
  `build-logic/src/main/groovy/alpaca.openapi-generation.gradle`.
- **Generator limitation** → wrap the generated class in a handwritten decorator under `src/main/java/`.

### Spec preprocessing fixes

If generated code breaks due to an upstream spec issue, add a fix to the appropriate `preprocess*Spec` task in
`build-logic/src/main/groovy/alpaca.openapi-generation.gradle`. Put reusable SnakeYAML transformations in
`build-logic/src/main/groovy/markets/alpaca/gradle/OpenApiSpecSupport.groovy` and use its `loadSpec` / `dumpYaml`
helpers. Do **not** patch OAS YAML with regex or raw string substitution, and do **not** modify the source specs in
`alpaca-docs-private` from this repository.

### WebSocket models

Monetary and price values in WebSocket model records must use `BigDecimal` — never `double` or `float`.

### Testing

See the [Tests](README.md#tests) section in the README for how to run unit and integration tests and how to supply
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
npm run build --prefix docs             # validate generated Docusaurus docs
pre-commit run markdown-links --all-files  # validate non-Docusaurus Markdown links
```

Generated OpenAPI output under `build/generated/` is excluded from formatting and static-analysis checks.

To run non-Docusaurus Markdown link validation before each commit, install the local hook once. The
hook uses the local `lychee` binary, matching the CI link checker.

```bash
pre-commit install
```

## PR Checklist

- [ ] Generated code (`build/generated/`) was not edited directly.
- [ ] New behaviour has focused unit tests.
- [ ] WebSocket monetary values avoid floating-point types (`double`/`float`).
- [ ] `README.md`, `AGENTS.md`, and `CHANGELOG.md` are updated when public behaviour changes.
- [ ] `./gradlew check` and `./gradlew build` pass locally.
