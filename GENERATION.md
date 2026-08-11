# OpenAPI generation

Maintainer playbook for pinned OpenAPI specs and generated Java clients in this
repository. Strategy (shared with other language SDKs): pin → semantic diff →
adopt → regenerate → verify. The Java pipeline is Gradle + Python helpers; it is
not shared code with JS/Python.

## Trust model

| Role | Artifact |
|---|---|
| Upstream source of trust | Live OAS at docs.alpaca.markets (URLs in `scripts/upstream_openapi_urls.json`) |
| SDK source of trust | Committed pins under `specs/{broker,data,trading}/openapi.yaml` |

Normal builds and publishes use **pins only**. Live OAS is fetched only by adopt
/ drift flows.

`oasRoot` / `-P*Spec` / `APCA_*_SPEC` affect **Gradle preprocess/generate** (local
experimentation). They do **not** change `adoptOpenApi*` / `scripts/adopt_openapi.py`,
which always compare against the public docs URLs above. To pin from a private
checkout, preprocess that tree into candidates yourself or temporarily point the
public URLs at a mirror—do not expect `oasRoot` to drive adopt.

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
./gradlew build             # compileJava depends on generateApis; check → checkGenerated
```

`compileJava` (and therefore `build`) depends on `generateApis`. That is intentional:
consumers of a checked-out commit get sources that match the pins, and maintainers
cannot compile a tree that has drifted from those pins without regenerating first.
Generator scratch still lands under `build/generated/`; only the OpenAPI packages
under `src/main/java/markets/alpaca/client/openapi/` are synced into the source
tree. If regeneration changes tracked files, commit them (or run `checkGenerated`
in CI to catch drift).

## Adopting upstream changes

Preferred entrypoints (Gradle owns download + **isolated** upstream preprocess into
`build/specs-adopt/` + pin apply; Python only diffs/applies. After pins are written,
`adoptOpenApi` / `adoptOpenApiBreaking` run **nested** `./gradlew generateApis test`
so the fresh `specs/` snapshot is used — an in-process `generateApis` sibling of the
pin-write task can keep a stale input fingerprint. Pin-based `preprocess*` is never
rewired by adopt, so `./gradlew build adoptOpenApi` remains safe):

```bash
# 1. Report only → build/openapi-adopt-report.md (exit 0 even if breaking)
./gradlew adoptOpenApiDryRun

# 2a. Apply additive-only adopts (new ops/schemas/enum values)
./gradlew adoptOpenApi

# 2b. Apply after reviewing breaking changes (removals, renames, schema edits, …)
./gradlew adoptOpenApiBreaking
```

Equivalent shell (bootstraps `.venv-openapi`, then runs the full Python path which
also nests `./gradlew` for preprocess/generate):

```bash
scripts/run_adopt_openapi.sh --dry-run
scripts/run_adopt_openapi.sh --yes
scripts/run_adopt_openapi.sh --yes --allow-breaking
```

Then edit `CHANGELOG.md`, commit `specs/` + `src/main/java/markets/alpaca/client/openapi/`
(+ changelog).

Pins are adopted whenever the preprocessed upstream document differs from the
committed one, even when the semantic diff is empty — the classifier ignores
spellings that cannot reach the generated Java surface (`nullable` forms, binary
media types), and those pins must still catch up. Such an adopt is reported as a
pin spelling catch-up and is never breaking. Operation-level `security` changes
are classified as breaking (they feed generated auth method names).

`./gradlew adoptOpenApi` refuses to write and exits with code 2 when the
semantic diff is breaking. Re-run `./gradlew adoptOpenApiBreaking` after review.
Adopt is all-or-nothing across broker/data/trading: if any API is breaking,
additive changes on the others are not applied until `--allow-breaking`.

Applying pins backs the previous ones up to `build/specs-pin-backup/`. That backup
exists until generated sources compile successfully. `clearOpenApiPinBackup`
(pulled in by `test`, always runs even when `compileJava` is UP-TO-DATE) deletes
it after compile; a lone `generateApis` deletes it when compile is not in the same
build. A failed generate or compile restores from it. Because generation syncs
each API into `src/main/java/markets/alpaca/client/openapi/` as that API finishes,
a failure part way through can leave some packages already rewritten, so the
restore also re-runs `clearOpenApiPinBackup` against the restored pins to bring
both trees back in step and recompile. A failure after a successful compile
(failing tests, say) keeps the adopted pins and sources — revert both with git to
abandon the adopt.

To undo a pin write by hand before regenerating, run
`scripts/run_adopt_openapi.sh --restore-pins` (one-shot: it consumes the backup),
then `./gradlew generateApis` to resync the generated sources.

Semantic categories: operations added/removed/modified/extended/renamed/moved;
schemas added/removed/modified/extended; enum values added/removed. `modified`
holds only changes that alter the generated Java surface; `extended` holds
changes that do not. Modified and extended entries carry a detail note naming
the affected properties, parameters, or responses.

**Breaking** (requires `adoptOpenApiBreaking`): removals, renames, moves, enum
value removals, and modifications — a schema whose existing properties are
removed, retyped, or newly required, an added `allOf` member (intersection can
tighten the model), or an operation whose parameters, request body, responses,
or `security` requirements change. Note that *adding* an operation parameter is
breaking for this SDK: the generator widens every overload's signature.

**Additive** (`adoptOpenApi` alone): new operations, schemas, and enum values;
schemas that only gain properties; additive `oneOf` / `anyOf` members; and
documentation-only edits (`description`, `summary`, `example`, `examples`), which
are still adopted so pins stay faithful to upstream but never classified as
breaking.

### Manual venv (optional)

```bash
python3 -m venv .venv-openapi
source .venv-openapi/bin/activate
pip install -r scripts/requirements.txt
PYTHONPATH=. python3 scripts/adopt_openapi.py --dry-run
```

Or on CI images that allow user installs:
`pip install --user -r scripts/requirements.txt`.

## CI

- PR/`main` builds run `./gradlew build` (`check` → `checkGenerated`).
- Snapshot and release freeze committed `specs/` (no live fetch at publish time).
- Weekly `openapi-drift.yml`:
  - Any drift → bot PR on `bot/openapi-adopt` (force-pushed). Additive/equivalent
    changes open a ready PR; classifier-breaking changes open a **draft** PR with
    pins and generated sources already updated for review. Local
    `./gradlew adoptOpenApi` still refuses breaking writes without
    `adoptOpenApiBreaking`.
  - A breaking adopt usually fails its nested `generateApis test` run. The workflow
    still commits whatever pins and generated sources survived, opens the PR as a
    **draft** noting the failure, and only then fails the run. If adopt restored the
    pins and left nothing to commit, the PR step is skipped with a notice.

## Spec resolution order

Per API (Gradle generate/preprocess only): `-P*Spec` → env `APCA_*_SPEC` →
`local.properties` → `oasRoot` → committed `specs/{api}/openapi.yaml` if present →
public docs URL.
