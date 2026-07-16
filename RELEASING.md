# Releasing alpaca-java

This is maintainer documentation. It is intentionally outside `docs/content/` and is not published
by Docusaurus.

Published coordinates are `markets.alpaca:alpaca-java:<version>`. Snapshots use Central Snapshots;
immutable releases use the Central Portal. GitHub Packages is configured as an optional Gradle
repository but current workflows do not publish there.

## Credentials and signing

Create a Central Portal user token; do not use an account password. Supply it through either
`mavenCentralUsername` and `mavenCentralPassword` in `~/.gradle/gradle.properties`, or
`MAVEN_CENTRAL_USERNAME` and `MAVEN_CENTRAL_PASSWORD`.

Never place tokens, private keys, or passphrases in this repository, command-line arguments, or
shell history. Releases also require a password-protected OpenPGP key, provided only through the
in-memory Gradle properties `signingKey` and `signingPassword`. Confirm a private-key export begins
with `-----BEGIN PGP PRIVATE KEY BLOCK-----`, but never print it.

The protected `maven-central` environment must require an authorized reviewer and allow only
`main`. Configure its secrets as `SIGNING_KEY` and `SIGNING_PASSWORD`; repository or organization
secrets are `MAVEN_CENTRAL_USERNAME` and `MAVEN_CENTRAL_PASSWORD`.

## Local publication

Publish the current `-SNAPSHOT` version:

```bash
./gradlew publishMavenJavaPublicationToCentralSnapshotsRepository
```

Publish a signed release:

```bash
./gradlew publishAggregationToCentralPortal -Pversion=1.2.3
```

The build rejects a release through Central Snapshots, a snapshot through the Central Portal, and
all NMCP snapshot tasks. Use these commands rather than lower-level publishing tasks.

## Automated snapshots

After a successful current `main` push, the Build workflow publishes the version in
`gradle.properties` when it ends in `-SNAPSHOT`. It resolves and preprocesses each OpenAPI source
once, builds from read-only copies, rechecks `main`, then publishes those same copies. Pull
requests, non-`main` pushes, failed builds, and stale commits never publish.

## Release workflow

Create and push a `vMAJOR.MINOR.PATCH` tag reachable from `main`, then dispatch the Release
workflow from `main` with that tag. Do not queue release dispatches.

The workflow verifies the tag and its reachability, rejects an existing release POM, tests release
tools, resolves and archives one immutable set of preprocessed OpenAPI inputs, builds and signs the
release, publishes it, creates or publishes the GitHub Release, and opens a pull request that
changes only `gradle.properties` to the next patch `-SNAPSHOT`. Merge that PR through normal branch
protection; its Build workflow publishes the next snapshot.

Release inputs are archived for 90 days. The internal
`-Palpaca.preprocessedSpecInputs=true` flag requires local inputs and prevents a second
preprocessing pass.

## Recovery

Release coordinates are immutable. Never retry an ambiguous Central upload. First verify that the
exact POM is public:

```text
https://repo.maven.apache.org/maven2/markets/alpaca/alpaca-java/<version>/alpaca-java-<version>.pom
```

If Central did not accept the deployment, fix the cause and rerun normally. If the exact POM is
public after an ambiguous result, dispatch the workflow with `recover_existing_release=true`;
recovery verifies the POM coordinates and skips build, signing, and upload. If only GitHub Release
creation or the version-bump job failed after publication, use **Re-run failed jobs** instead.
