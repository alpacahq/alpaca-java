# Alpaca Java Client Docs

Local documentation site for `alpaca-java-client`.

The narrative wiki is authored in `content/`. The generated Java API reference is produced by
Gradle Javadocs and copied into `static/javadocs/` for local preview.

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
