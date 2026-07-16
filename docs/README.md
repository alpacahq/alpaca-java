# Alpaca Java Client Docs

Local documentation site for `alpaca-java`.

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

## GitHub Pages deployment

The repository deploys this Docusaurus site with `.github/workflows/deploy-docs.yml`.
The workflow uses `actions/configure-pages` to detect the actual GitHub Pages URL (handling
custom domains automatically) and enables GitHub Actions as the Pages source if not already set.
It then builds the site with the detected values:

```bash
# equivalent to what CI does (adjust if a custom domain is configured)
DOCUSAURUS_URL=https://alpacahq.github.io DOCUSAURUS_BASE_URL=/alpaca-java/ npm run build
```
