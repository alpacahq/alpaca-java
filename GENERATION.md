# OpenAPI generation

Maintainer playbook for pinned OpenAPI specs and generated Java clients in this
repository. Strategy (shared with other language SDKs): pin → semantic diff →
adopt → regenerate → verify. The Java pipeline is Gradle + Python helpers; it is
not shared code with JS/Python.

## Trust model

| Role | Artifact |
|---|---|
| Upstream source of trust | Live OAS at docs.alpaca.markets (or a local `oasRoot`) |
| SDK source of trust | Committed pins under `specs/{broker,data,trading}/openapi.yaml` |

Normal builds and publishes use **pins only**. Live OAS is fetched only by adopt
/ drift flows.

## Layout

| Path | Role |
|---|---|
| `specs/{broker,data,trading}/openapi.yaml` | Pinned, post-preprocess OpenAPI |
| `src/main/java/markets/alpaca/client/openapi/**` | Committed generated Java clients |
| `build/generated/{broker,data,trading}/` | Ephemeral OpenAPI Generator project output |

Do **not** hand-edit files under `markets.alpaca.client.openapi`. Fix issues via SnakeYAML
preprocessing (`OpenApiSpecSupport`) or Mustache templates under
`src/main/openapi-templates/`. Package Javadoc under that tree states the same rule.

## Everyday commands

```bash
./gradlew generateApis      # regenerate from committed pins into src/main/java
./gradlew checkGenerated    # regenerate + fail if specs/ or openapi sources drift
./gradlew build             # compileJava depends on generateApis, then tests
```

`compileJava` (and therefore `build`) depends on `generateApis`. That is intentional:
consumers of a checked-out commit get sources that match the pins, and maintainers
cannot compile a tree that has drifted from those pins without regenerating first.
Generator scratch still lands under `build/generated/`; only the OpenAPI packages
under `src/main/java/markets/alpaca/client/openapi/` are synced into the source
tree. If regeneration changes tracked files, commit them (or run `checkGenerated`
in CI to catch drift).

Requires PyYAML for adopt/diff against YAML pins:

```bash
python3 -m venv .venv-openapi
source .venv-openapi/bin/activate
pip install pyyaml
PYTHONPATH=. python3 scripts/adopt_openapi.py --dry-run
```

Or on CI images that allow user installs: `pip install --user pyyaml`.

## Adopting upstream changes

```bash
# Report only (writes build/openapi-adopt-report.md)
python3 scripts/adopt_openapi.py --dry-run

# Apply additive adopts
python3 scripts/adopt_openapi.py --yes

# Apply after reviewing breaking changes (modified/moved ops, schema edits, removals)
python3 scripts/adopt_openapi.py --yes --allow-breaking
```

Then edit `CHANGELOG.md`, commit `specs/` + `src/main/java/markets/alpaca/client/openapi/`
(+ changelog).

Semantic categories: operations added/removed/modified/renamed/moved; schemas
added/removed/modified; enum values added/removed.

**Breaking** (requires `--allow-breaking` to apply): removals, renames, moves,
operation/schema modifications, and enum value removals. **Additive-only**
adopts (new operations/schemas/enum values) may use `--yes` alone.

## CI

- PR/`main` builds run `./gradlew checkGenerated`.
- Snapshot and release freeze committed `specs/` (no live fetch at publish time).
- Weekly `openapi-drift.yml`:
  - Additive drift → bot PR on `bot/openapi-adopt` (human merge).
  - Breaking drift → GitHub issue with the report; pins are **not** updated until
    someone runs `--yes --allow-breaking` and opens a normal PR.

## Spec resolution order

Per API: Gradle `-P*Spec` → env `APCA_*_SPEC` → `local.properties` → `oasRoot` →
committed `specs/{api}/openapi.yaml` if present → public docs URL.
