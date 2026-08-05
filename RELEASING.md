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
`gradle.properties` when it ends in `-SNAPSHOT`. It freezes the committed OpenAPI pins under
`specs/`, builds from read-only copies, rechecks `main`, then publishes those same copies. Pull
requests, non-`main` pushes, failed builds, and stale commits never publish.

## Release workflow

Before tagging, prefer moving `CHANGELOG.md` `[Unreleased]` notes into a dated
`## [MAJOR.MINOR.PATCH]` section on the commit you will tag. That keeps the tagged
tree aligned with the release.

Create and push a `vMAJOR.MINOR.PATCH` tag reachable from `main`, then dispatch the Release
workflow from `main` with that tag. Do not queue release dispatches.

The workflow verifies the tag and its reachability, requires curated changelog notes on the
tagged commit (a non-empty `## [version]` section, or a non-empty `## [Unreleased]` fallback),
rejects an existing release POM, tests release tools, archives the committed OpenAPI pins under
`specs/`, builds and signs the release, publishes it, creates or publishes the GitHub Release, and
opens a pull request that advances `gradle.properties` to the next patch `-SNAPSHOT` and, when
needed, promotes `[Unreleased]` to the dated release section on `main`. Merge that PR through
normal branch protection; its Build workflow publishes the next snapshot.

GitHub Release bodies are composed as the curated changelog section, then GitHub’s
`**Full Changelog**` compare link for the tag range (not the auto-generated PR list).
Existing published GitHub Releases are left unchanged (including on recovery); edit
historical notes manually if required.

Three changelog states stop the workflow rather than guess:

- A `## [version]` heading that exists but is empty. The release fails before Maven Central; fill
  the section or delete the heading and re-dispatch.
- `[Unreleased]` on `main` no longer matching the notes published for the tag, which happens when
  other work merges between tagging and the bump job. Promote the section manually.
- An open `release/start-*` pull request whose `gradle.properties` or `CHANGELOG.md` differs from
  what this release requires. Update that pull request manually; the workflow never rewrites it.

When `main` already has a `## [version]` section whose content differs from the notes published for
the tag, the bump job warns instead of failing: the version bump still needs to land, and the
difference is usually a deliberate edit. Reconcile the section and the GitHub Release by hand.

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
