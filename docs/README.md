# Alpaca Java Client Docs

[![Build](https://github.com/alpacahq/alpaca-java/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/alpacahq/alpaca-java/actions/workflows/build.yml)
[![Maven Central](https://img.shields.io/maven-central/v/markets.alpaca/alpaca-java)](https://central.sonatype.com/artifact/markets.alpaca/alpaca-java)
[![Java 17+](https://img.shields.io/badge/Java-17%2B-007396?logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/17/)
[![License](https://img.shields.io/badge/License-Apache--2.0-blue.svg)](../LICENSE)

Local documentation site for `alpaca-java`.

The narrative wiki is authored in `content/`. The generated Java API reference is produced by
Gradle Javadocs and copied into `static/javadocs/` for local preview.

## Consume the artifact

Published releases are available from Maven Central:

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("markets.alpaca:alpaca-java:VERSION")
}
```

```xml
<dependency>
  <groupId>markets.alpaca</groupId>
  <artifactId>alpaca-java</artifactId>
  <version>VERSION</version>
</dependency>
```

Replace `VERSION` with a released version. For development snapshots, add the scoped Sonatype
[Central Snapshots repository](../README.md#snapshots). See the
[Getting Started guide](content/getting-started.md#installation) for complete instructions.

## Local setup

```bash
cd docs
npm install
npm run start
```

Open the local URL printed by Docusaurus.

## Include Javadocs

```bash
cd docs
npm run sync:javadocs
npm run start
```

`sync:javadocs` runs `../gradlew generateJavadocs` and copies `../build/docs/javadoc` into
`static/javadocs/`. The copied files are generated local artifacts and are ignored by git.

## Production build preview

```bash
cd docs
npm run build
npm run serve
```

## GitHub Pages deployment

The repository deploys this Docusaurus site with `.github/workflows/deploy-docs.yml`.
The workflow uses `actions/configure-pages` to detect the actual GitHub Pages URL (handling
custom domains automatically) and enables GitHub Actions as the Pages source if not already set.
It then builds the site with the detected values:

```bash
# equivalent to what CI does (adjust if a custom domain is configured)
DOCUSAURUS_URL=https://alpacahq.github.io DOCUSAURUS_BASE_URL=/alpaca-java/ npm run build
```
