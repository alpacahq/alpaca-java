# AGENTS.md — alpaca-java-client

Java client for Alpaca Markets APIs. REST clients are **generated from OpenAPI specs** at build time. WebSocket clients are handwritten and committed. Read this before making any changes.

If you are generating application code that uses this SDK rather than modifying the SDK itself, read `LLMS.md` first. `AGENTS.md` is for repo maintenance and contribution rules; `LLMS.md` is for consumer-facing SDK usage.

## Build commands

```bash
./gradlew build                      # full build (generates + compiles + tests)
./gradlew generateApis               # regenerate all three API clients only
./gradlew generateBrokerApi          # regenerate broker only
./gradlew generateDataApi            # regenerate data only
./gradlew generateTradingApi         # regenerate trading only
./gradlew clean generateApis build   # force clean regeneration
./gradlew test                       # run unit tests (no credentials needed)
./gradlew integrationTest            # run integration tests (requires paper credentials — see below)
./gradlew compileExamples            # compile examples without packaging them
./gradlew javadoc                    # generate Javadoc
./gradlew generateJavadocs           # generate Javadoc via the project documentation task
```

Run `./gradlew tasks --group="openapi tools"` to list all generation-related tasks.

## Project layout

```
src/main/java/markets/alpaca/client/   ← HANDWRITTEN only — committed to git
├── AlpacaClient.java
├── AlpacaClientFactory.java
├── AlpacaCredentials.java
├── data/                             ← handwritten Market Data helpers
├── http/                             ← handwritten OkHttp/retry helpers
├── rest/                             ← handwritten REST utility helpers
├── trading/                          ← handwritten Trading helpers
├── broker/sse/                       ← Broker Events SSE wrapper
└── ws/                               ← WebSocket stream clients, listeners, subscriptions, models

build/generated/                      ← raw generator output (gitignored)
├── broker/src/main/java/markets/alpaca/client/openapi/broker/{api,model,http}/
├── data/src/main/java/markets/alpaca/client/openapi/data/{api,model,http}/
└── trading/src/main/java/markets/alpaca/client/openapi/trading/{api,model,http}/

build/specs/                           ← preprocessed (sanitised) OAS copies (gitignored)

build.gradle                           ← thin application of alpaca.* convention plugins
build-logic/src/main/groovy/
├── alpaca.java-library.gradle
├── alpaca.openapi-generation.gradle
├── alpaca.publishing.gradle
├── alpaca.quality.gradle
├── alpaca.testing.gradle
└── markets/alpaca/gradle/             ← shared typed build support
```

OAS spec sources — by default the official Alpaca public documentation URLs are used.
To use local files, see the [OAS spec sources](#oas-spec-path-configuration) section below.
When working against the private repo:
```
~/source/alpacah/alpaca-docs-private/oas/
├── broker/openapi.yaml
├── data/openapi.yaml
└── trading/openapi.yaml
```

## Three independent API clients

Each API has its own `ApiClient` in its own `http` subpackage with a different default base URL and server configuration. Always use `AlpacaClientFactory` — never instantiate `ApiClient` directly:

| API | `ApiClient` class | Auth | Default base URL |
|---|---|---|---|
| Broker | `markets.alpaca.client.openapi.broker.http.ApiClient` | HTTP Basic | `https://broker-api.sandbox.alpaca.markets` |
| Data | `markets.alpaca.client.openapi.data.http.ApiClient` | Header keys | `https://data.alpaca.markets` |
| Trading | `markets.alpaca.client.openapi.trading.http.ApiClient` | Header keys | `https://paper-api.alpaca.markets` |

Always use `AlpacaClientFactory` factory methods — never instantiate `ApiClient` directly:

```java
var creds   = new AlpacaCredentials(keyId, secret);
var broker  = AlpacaClientFactory.brokerClient(creds);
var data    = AlpacaClientFactory.dataClient(creds);
var trading = AlpacaClientFactory.tradingClient(creds);
```

## What is generated vs handwritten

**Generated** (in `build/generated/`, gitignored — never edit):
- `markets.alpaca.client.openapi.broker.{api,model,http}` — Broker endpoints, models, and HTTP client
- `markets.alpaca.client.openapi.data.{api,model,http}` — Data endpoints, models, and HTTP client
- `markets.alpaca.client.openapi.trading.{api,model,http}` — Trading endpoints, models, and HTTP client

Each `http` package contains a distinct `ApiClient` with the correct default base URL and server configuration for that API. They are not interchangeable.

**Handwritten** (in `src/main/java/markets/alpaca/client/`, committed to git):
- `AlpacaClientFactory` — pre-configures auth and HTTP client for each API
- `AlpacaCredentials` — credential value object (key ID + secret key)
- `data/**` — handwritten Market Data helpers
- `http/**` — handwritten OkHttp singleton, builder factory, retry policy, and retry interceptor
- `rest/**` — handwritten REST utilities such as pagination and futures adapters
- `trading/**` — handwritten Trading helpers
- `broker/sse/**` — handwritten Broker Events SSE wrapper
- `ws/**` — handwritten WebSocket stream clients, listeners, subscriptions, and model records

## How to add new features

### Adding a helper / utility
Add it to the appropriate handwritten package under `src/main/java/markets/alpaca/client/`: `trading/`, `data/`, `rest/`, `http/`, `broker/sse/`, or `ws/`. Keep root-package additions for top-level SDK concepts only. Never add handwritten code under `markets.alpaca.client.openapi/**` — that namespace is owned by the generator.

### Fixing a generated class behaviour
Do NOT modify the generated file. Instead:
1. If it's a **spec bug** → add a fix to the relevant preprocess task in `build-logic/src/main/groovy/alpaca.openapi-generation.gradle` (see below)
2. If it's a **generator limitation** → wrap the generated class in a handwritten decorator in `src/main/java/`

### Changing generator options
Edit `commonConfigOptions` in `build-logic/src/main/groovy/alpaca.openapi-generation.gradle`. Options apply to all three generators. Run `./gradlew clean generateApis build` after any change to regenerate cleanly.

## Spec preprocessing pipeline

Before code generation, specs are sanitised by tasks in `build-logic/src/main/groovy/alpaca.openapi-generation.gradle`. The original specs are **never modified**.

| Task | Input | Fix applied |
|---|---|---|
| `preprocessBrokerSpec` | `broker/openapi.yaml` | Removes empty-key properties; removes `enum` from discriminator properties; removes `ActivityV2DetailTRD.required` |
| `preprocessDataSpec` | `data/openapi.yaml` | Removes `x-internal: true` from schemas |
| `preprocessTradingSpec` | `trading/openapi.yaml` | Removes `ActivityV2DetailTRD.required`; constrains account-activity `oneOf` branches to distinct `activity_type` values |

Preprocessed copies go to `build/specs/{broker,data,trading}/openapi.yaml`.

To add a new fix, add it to the appropriate helper in `build-logic/src/main/groovy/markets/alpaca/gradle/OpenApiSpecSupport.groovy`, then call it from the relevant task in `build-logic/src/main/groovy/alpaca.openapi-generation.gradle`. Helpers use SnakeYAML to manipulate the parsed YAML tree — do NOT use string/regex manipulation on the raw YAML text.

## Adding a new preprocess fix — example

```groovy
// In OpenApiSpecSupport.groovy:
static void fixSomeIssue(Map spec) {
    spec?.components?.schemas?.each { name, schema ->
        // manipulate the parsed tree
    }
}

// In alpaca.openapi-generation.gradle, call it inside the relevant task's doLast block.
// The spec is already loaded from the configured source (file or URL) into a Map;
// call the support methods and re-serialise:
tasks.named('preprocessTradingSpec').configure {
    doLast {
        def outputFile = preprocessedTradingSpec.get().asFile
        outputFile.parentFile.mkdirs()
        def spec = OpenApiSpecSupport.loadSpec(tradingSpecSource) as Map
        OpenApiSpecSupport.removeActivityV2DetailTrdRequired(spec)
        OpenApiSpecSupport.fixSomeIssue(spec)
        outputFile.text = OpenApiSpecSupport.dumpYaml(spec)
    }
}
```

## OAS spec path configuration

Each spec can be a **local file path** or a **remote URL** (`http`/`https`).
By default the build fetches the official Alpaca public specs — no local files are required.

| Spec | Default URL |
|---|---|
| Broker | `https://docs.alpaca.markets/openapi/broker-api.json` |
| Market Data | `https://docs.alpaca.markets/openapi/market-data-api.json` |
| Trading | `https://docs.alpaca.markets/openapi/trading-api.json` |

Resolution order per spec (first match wins):

1. **Individual Gradle property** (highest priority):
   ```bash
   ./gradlew -PbrokerSpec=/path/or/url -PdataSpec=/path/or/url -PtradingSpec=/path/or/url build
   ```
2. **Individual environment variable**:
   ```bash
   APCA_BROKER_SPEC=... APCA_DATA_SPEC=... APCA_TRADING_SPEC=... ./gradlew build
   ```
3. **`local.properties`** individual keys (gitignored):
   ```properties
   brokerSpec=/path/to/broker/openapi.yaml
   dataSpec=/path/to/data/openapi.yaml
   tradingSpec=/path/to/trading/openapi.yaml
   ```
4. **Legacy `oasRoot`** — sets all three specs to `<oasRoot>/{broker,data,trading}/openapi.yaml`:
   ```bash
   # Gradle flag:
   ./gradlew -PoasRoot=/path/to/alpaca-docs-private/oas build
   # Environment variable:
   APCA_OAS_ROOT=/path/to/alpaca-docs-private/oas ./gradlew build
   # local.properties (recommended for daily local dev against the private repo):
   oasRoot=/path/to/alpaca-docs-private/oas
   ```
5. **Default public URL** (lowest priority, no configuration needed)

## Incremental builds

Gradle tracks local spec files as task inputs. If the specs haven't changed since the last run, the generate tasks are skipped as "up-to-date". A `./gradlew build` is safe to run frequently.

When using remote URL sources, the preprocessing tasks always re-run (Gradle cannot track remote URLs for up-to-date checks).

Only run `./gradlew clean generateApis build` when:
- A preprocessing fix is added or changed
- The generator version changes
- Generated output looks stale or corrupted

## Publishing

The published coordinates are `markets.alpaca:alpaca-java-client:<version>`.
`gradle.properties` normally contains the next development `-SNAPSHOT` version.

### Repository endpoints

| Repository | URL | Purpose |
|---|---|---|
| Central Snapshots | `https://central.sonatype.com/repository/maven-snapshots/` | File-by-file, unsigned `-SNAPSHOT` deployments |
| Maven Central | `https://repo.maven.apache.org/maven2/` | Public, immutable release artifacts after Portal validation |
| Central Portal | `https://central.sonatype.com/` | Token management, namespace verification, and NMCP deployments |
| GitHub Packages | `https://maven.pkg.github.com/alpacahq/alpaca-java-client` | Optional configured Gradle repository; current workflows do not publish here |

Central Snapshots and Central releases use different publication paths. Snapshots use Gradle's
standard `maven-publish` file uploads. Releases use NMCP to assemble, sign, upload, validate, and
automatically publish a Central Portal bundle.

### Credentials and signing

Create a Central Portal **user token** at central.sonatype.com → account → "Generate User Token";
do not use the account login password. The `markets.alpaca` namespace must be verified in the Portal.
For local use, put only the token in `~/.gradle/gradle.properties`:

```properties
mavenCentralUsername=<token-username>
mavenCentralPassword=<token-password>
```

Alternatively, set `MAVEN_CENTRAL_USERNAME` and `MAVEN_CENTRAL_PASSWORD`. The user-level
`~/.gradle/gradle.properties` file described above is explicitly permitted. Never put tokens,
private keys, or passphrases in this repository's project-level `gradle.properties`, any other
tracked file, command-line arguments, or shell history.

Releases require a password-protected OpenPGP key. Publish its public key to a keyserver supported
by Maven Central before releasing. Supply the multiline, ASCII-armored private key and passphrase
as Gradle project properties named `signingKey` and `signingPassword`. For a local release, use
in-memory environment-backed properties and disable shell tracing:

```bash
set +x
export ORG_GRADLE_PROJECT_signingKey="$(gpg --armor --export-secret-keys <KEY_ID>)"
read -r -s ORG_GRADLE_PROJECT_signingPassword
export ORG_GRADLE_PROJECT_signingPassword
printf '\n'

./gradlew publishAggregationToCentralPortal -Pversion=1.2.3

unset ORG_GRADLE_PROJECT_signingKey ORG_GRADLE_PROJECT_signingPassword
```

Use a dedicated release key where possible. Confirm the export starts with
`-----BEGIN PGP PRIVATE KEY BLOCK-----`, but never print or paste the private key into logs.
Signing is publication-wide: every non-SNAPSHOT `mavenJava` publication requires both signing
properties, including publication to GitHub Packages. SNAPSHOT publications remain unsigned.

GitHub configuration uses these exact secrets:

- Repository or organization secrets: `MAVEN_CENTRAL_USERNAME` and `MAVEN_CENTRAL_PASSWORD`.
- Protected `maven-central` environment secrets: `SIGNING_KEY` and `SIGNING_PASSWORD`.
  `SIGNING_KEY` is the complete multiline ASCII-armored private key; `SIGNING_PASSWORD` is its
  passphrase.

The `maven-central` environment must require an authorized reviewer **and** set its deployment
branch policy to selected branches/tags with only `main` allowed. The workflow also skips its
Central job unless `github.ref` is `refs/heads/main`, but the environment branch policy is the
actual protection against running modified workflow files from another branch. Because the
environment is attached to the Central publication job, these controls gate validation and upload.

### Local publication commands and guards

Publish the current `-SNAPSHOT` version to Central Snapshots:

```bash
./gradlew publishMavenJavaPublicationToCentralSnapshotsRepository
```

Publish a signed, non-SNAPSHOT release through the Central Portal:

```bash
./gradlew publishAggregationToCentralPortal -Pversion=1.2.3
```

The release command also requires the Central token and signing properties described above.
Optional `-PbrokerSpec`, `-PdataSpec`, and `-PtradingSpec` arguments can pin local preprocessed or
source specs.

Build guards reject unsafe publication routes before upload:

- A non-SNAPSHOT version cannot use the `CentralSnapshots` repository.
- A `-SNAPSHOT` version cannot use an NMCP Central Portal release task.
- NMCP Central Portal snapshot tasks are unsupported for every version; use the standard
  Central Snapshots task for snapshots.

Run `./gradlew tasks --group publishing` to inspect the available publication tasks. Use the two
commands above for Central publication instead of invoking lower-level tasks directly.

### Automatic snapshots

The reusable Publish Snapshot workflow is called after the Gradle build succeeds for a push to
`main`. It verifies that the requested commit is current and that `gradle.properties` ends in
`-SNAPSHOT`, then resolves and preprocesses all three OpenAPI sources exactly once. It copies those
inputs to a read-only temporary directory, runs the complete build against the local copies,
rechecks remote `main`, and publishes using the same files:

```bash
./gradlew publishMavenJavaPublicationToCentralSnapshotsRepository
```

It reads `MAVEN_CENTRAL_USERNAME` and `MAVEN_CENTRAL_PASSWORD` from repository or organization
secrets. Pull requests, pushes to other branches, failed builds, and stale `main` pushes do not
publish. The Release workflow also calls this reusable workflow with the exact version-bump commit,
because pushes made with `GITHUB_TOKEN` do not start another Build workflow.

### Release spec inputs

A release resolves and preprocesses each configured OpenAPI source once. It copies those three
results to a read-only temporary directory, archives them for 90 days, and uses the same local files
for both the tagged build and publication. This prevents the upstream URLs from changing between
those two operations without requiring a frequently updated tracked spec lock. Workflows pass
`-Palpaca.preprocessedSpecInputs=true` with these files so Gradle copies them byte-for-byte instead
of applying the sanitising transformations a second time; this internal flag requires local inputs.

CI runs `python3 -m unittest discover -s scripts/tests -p 'test_*.py' -v` for release-tool behavior.

### Manual releases

Create and push a release tag before dispatching the Release workflow. The input must exactly match
`vMAJOR.MINOR.PATCH`, without prerelease or build metadata; for example:

```bash
git tag v1.2.3
git push origin v1.2.3
```

Dispatch `.github/workflows/release.yml` from the `main` branch with `tag=v1.2.3`. A dispatch from
any other ref skips the Central job and all dependent release jobs. After approval for the
protected `maven-central` environment, the workflow runs in this order:

1. Validate the tag format, require the tag to exist, resolve it to a commit, and verify that commit
   is reachable from remote `main`; then check out the tag detached.
2. Run the complete release-tool Python test suite once from the tagged commit; the same tested
   commands support the later normal and recovery branches.
3. Query the exact canonical POM at Maven Central and refuse a normal publication if that immutable
   version already exists.
4. Resolve and preprocess each configured OpenAPI source once, archive the results, and use those
   same read-only inputs for both the tagged build and publication.
5. Build with the tag-derived non-SNAPSHOT version.
6. Sign and run `publishAggregationToCentralPortal`. NMCP uploads the bundle and uses automatic
   Central Portal publication.
7. Create the tag's GitHub Release with generated notes. If a published release exists, leave it
   unchanged; if a draft exists, publish that draft without replacing its title, notes, or assets.
8. Read the latest `main`, calculate the next patch version (for example,
   `1.2.3` → `1.2.4-SNAPSHOT`), and commit only `gradle.properties` directly to `main`.
9. Pass the exact version-bump commit to the reusable Publish Snapshot workflow, which builds and
   publishes the new development snapshot from frozen OpenAPI inputs.

If a version-bump push reports an ambiguous failure, the workflow checks whether remote `main`
contains the exact local commit before failing. A rerun that finds the expected next version already
on `main` recovers that commit and requests snapshot publication again.

Do not queue multiple release dispatches. With `cancel-in-progress: false`, GitHub retains at most
one running and one pending run in the release concurrency group; a newer dispatch can replace an
older pending run.

The workflow defaults to `contents: read`; only the GitHub Release and version-bump jobs receive
`contents: write`. Branch protection or repository rulesets must allow `github-actions[bot]` to
push the direct version-bump commit to `main`; otherwise Central and the GitHub Release can succeed
while the final bump fails.

### Recovery and partial failures

Release coordinates are immutable. Do not blindly rerun a Central upload after a timeout or other
ambiguous NMCP failure:

- If Central did not accept the deployment, fix the cause and run the normal workflow again.
- If Central accepted and published the release but the workflow reported failure, wait until the
  exact POM is public at
  `https://repo.maven.apache.org/maven2/markets/alpaca/alpaca-java-client/<version>/alpaca-java-client-<version>.pom`,
  then dispatch a new workflow run with `recover_existing_release=true`.
- Recovery mode verifies that canonical POM is well-formed and exactly matches
  `markets.alpaca:alpaca-java-client:<version>`. It skips preprocessing, build, signing, and
  republishing, then continues with the GitHub Release and next-patch bump. The POM transfer is
  limited to 1 MiB before XML parsing.
- If only the GitHub Release or version-bump job failed after Central publication succeeded, use
  GitHub Actions' **Re-run failed jobs**. This does not rerun the successful Central publication
  job. GitHub Release creation and the version bump are designed to tolerate an already-completed
  result; an existing draft release is published in place.

Never enable recovery merely because a run failed. First verify that the exact release is public on
Maven Central; recovery refuses missing, unexpected, or mismatched POMs.

## DO NOT

- **Do not edit generated files** — everything under `build/` is overwritten on every build. Edit the OAS spec or add a preprocessing fix instead.
- **Do not instantiate `ApiClient` directly** — always go through `AlpacaClientFactory` so the correct auth scheme is configured for the target API.
- **Do not use regex or string substitution to patch OAS YAML** — use the SnakeYAML helpers in `build-logic/src/main/groovy/markets/alpaca/gradle/OpenApiSpecSupport.groovy`.
- **Do not modify the OAS specs** to work around generator issues — add a preprocessing fix in `build-logic/src/main/groovy/alpaca.openapi-generation.gradle` instead.
- **Do not put handwritten code under `markets.alpaca.client.openapi/**`** — generated REST packages are overwritten on every build. WebSocket endpoints are not defined by the OAS specs, so handwritten streaming code belongs under `src/main/java/markets/alpaca/client/ws/`.
- **Do not use `./gradlew clean` routinely** — it deletes all generated code and forces a full regeneration. Only use it when explicitly needed.

## Tests

### Unit tests

Located in `src/test/java/markets/alpaca/client/`. Run with `./gradlew test`. No credentials or network access required.

```
src/test/java/markets/alpaca/client/
├── AlpacaClientFactoryTest.java
├── AlpacaCredentialsTest.java
├── data/                     ← Market Data helper tests
├── http/                     ← OkHttp/retry helper tests
├── rest/                     ← REST utility tests
├── trading/                  ← Trading helper tests
├── ws/                       ← WebSocket unit tests and model deserialization tests
└── integration/              ← @Tag("integration") — excluded from ./gradlew test
    └── IntegrationIT.java
```

Use JUnit 5 (`org.junit.jupiter`). New tests go in the same package as the class under test.

### Integration tests

Integration tests call the real Alpaca paper-trading, market-data, and WebSocket APIs. Run with:

```bash
./gradlew integrationTest
```

These tests are tagged `@Tag("integration")` and are **excluded** from `./gradlew test`, so they never break a local build when credentials are absent — they skip automatically. They mirror the read-only workflows in `examples/`; state-changing example paths remain opt-in examples and are not run automatically.

**Supplying credentials (pick one):**

1. `local.properties` (recommended for local dev, gitignored):
   ```properties
   tradingApiKeyId=PKxxxxxxxxxxxxxxxxxx
   tradingApiSecretKey=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
   tradingApiEnvironment=paper
   brokerApiKeyId=xxxxxxxxxxxxxxxxxx
   brokerApiSecretKey=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
   brokerApiEnvironment=sandbox
   ```
2. Environment variables (recommended for CI):
   ```bash
   export APCA_TRADING_KEY_ID=PKxxxxxxxxxxxxxxxxxx
   export APCA_TRADING_SECRET_KEY=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
   export APCA_TRADING_ENVIRONMENT=paper
   export APCA_BROKER_KEY_ID=xxxxxxxxxxxxxxxxxx
   export APCA_BROKER_SECRET_KEY=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
   export APCA_BROKER_ENVIRONMENT=sandbox
   ./gradlew integrationTest
   ```

Paper API keys are available from [app.alpaca.markets](https://app.alpaca.markets) → Paper Trading → API Keys. Broker sandbox credentials are required only for Broker integration tests.

**Covered endpoints (all read-only, no orders placed):**

| Test | Endpoint | API |
|---|---|---|
| `tradingApi_getAccount_returnsValidAccount` | `GET /v2/account` | Trading (paper) |
| `tradingApi_getAsset_aapl_returnsMatchingSymbol` | `GET /v2/assets/AAPL` | Trading (paper) |
| `tradingApi_getOpenOrders_returnsList` | `GET /v2/orders` | Trading (paper) |
| `tradingApi_getOpenPositions_returnsList` | `GET /v2/positions` | Trading (paper) |
| `tradingApi_getPortfolioHistory_returnsHistory` | `GET /v2/account/portfolio/history` | Trading (paper) |
| `dataApi_stockLatestBar_aapl_returnsBar` | `GET /v2/stocks/AAPL/bars/latest` | Market Data |
| `dataApi_stockHistoricalBars_aapl_returnsBars` | `GET /v2/stocks/AAPL/bars` | Market Data |
| `dataApi_stockLatestQuote_aapl_returnsQuote` | `GET /v2/stocks/AAPL/quotes/latest` | Market Data |
| `dataApi_cryptoLatestBars_returnsBars` | `GET /v1beta3/crypto/us/latest/bars` | Market Data |
| `dataApi_cryptoHistoricalBars_returnsBars` | `GET /v1beta3/crypto/us/bars` | Market Data |
| `dataApi_news_aapl_returnsNewsList` | `GET /v1beta1/news` | Market Data |
| `brokerApi_getAllAccounts_returnsList` | `GET /v1/accounts` | Broker sandbox |
| `brokerApi_getAccountAndTradingAccount_returnsAccountDetailsWhenAccountExists` | `GET /v1/accounts/{account_id}` + trading account | Broker sandbox |
| `brokerApi_getOpenOrdersForAccount_returnsListWhenAccountExists` | `GET /v1/trading/accounts/{account_id}/orders` | Broker sandbox |
| `brokerSse_subscribeToTradeEvents_opensStream` | Broker trade-events SSE | Broker sandbox |
| `stockStream_iex_authenticatesAndReceivesSubscriptionConfirmation` | `wss://stream.data.alpaca.markets/v2/iex` | Stock data stream |
| `cryptoStream_production_authenticatesAndReceivesSubscriptionConfirmation` | `wss://stream.data.alpaca.markets/v1beta3/crypto/us` | Crypto data stream |
| `newsStream_production_authenticatesAndReceivesSubscriptionConfirmation` | `wss://stream.data.alpaca.markets/v1beta1/news` | News stream |
| `tradingStream_paper_authenticatesAndReceivesListeningConfirmation` | `wss://paper-api.alpaca.markets/stream` | Trading stream |
