#!/usr/bin/env python3
"""Adopt upstream OpenAPI specs into committed pins and regenerate clients."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import shutil
import subprocess
import sys
import urllib.error
import urllib.request

from scripts import openapi_tools

ROOT = Path(__file__).resolve().parents[1]
APIS = ("broker", "data", "trading")
UPSTREAM_URLS_PATH = ROOT / "scripts" / "upstream_openapi_urls.json"


def load_upstream_defaults() -> dict[str, str]:
    data = json.loads(UPSTREAM_URLS_PATH.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise SystemExit(f"{UPSTREAM_URLS_PATH} must be a JSON object")
    urls: dict[str, str] = {}
    for api in APIS:
        value = data.get(api)
        if not isinstance(value, str) or not value.strip():
            raise SystemExit(f"{UPSTREAM_URLS_PATH} missing non-empty '{api}' URL")
        urls[api] = value
    return urls


def _run(command: list[str]) -> None:
    print("+", " ".join(command))
    subprocess.run(command, cwd=ROOT, check=True)


def _download(url: str, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    request = urllib.request.Request(
        url,
        headers={
            "User-Agent": "alpaca-java-openapi-adopt/1.0",
            "Accept": "application/json, application/yaml, text/yaml, */*",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=60) as response:  # noqa: S310
            status = getattr(response, "status", None)
            if status is not None and not (200 <= int(status) < 300):
                raise SystemExit(f"Failed to download OpenAPI spec from {url} (HTTP {status})")
            body = response.read()
    except urllib.error.HTTPError as exc:
        raise SystemExit(
            f"Failed to download OpenAPI spec from {url} (HTTP {exc.code})"
        ) from exc
    except urllib.error.URLError as exc:
        raise SystemExit(f"Failed to download OpenAPI spec from {url}: {exc.reason}") from exc
    if not body:
        raise SystemExit(f"Failed to download OpenAPI spec from {url}: empty response body")
    destination.write_bytes(body)


def resolve_adopt_exit_code(
    *,
    dry_run: bool,
    yes: bool,
    allow_breaking: bool,
    any_changes: bool,
    any_breaking: bool,
) -> int:
    """Exit code after the report is written (0 proceed/ok, 1 needs --yes, 2 breaking)."""
    if not any_changes:
        return 0
    if dry_run:
        return 0
    if any_breaking and not allow_breaking:
        return 2
    if not yes:
        return 1
    return 0


def print_adopt_summary(
    *,
    dry_run: bool,
    allow_breaking: bool,
    any_breaking: bool,
    breaking_apis: list[str],
    changed_apis: list[str],
    report_path: Path,
) -> None:
    print()
    print("=" * 72)
    if any_breaking:
        print("BREAKING CHANGES FOUND")
        print(f"  APIs: {', '.join(breaking_apis)}")
        print(f"  Full report: {report_path}")
        if dry_run:
            print("  Dry-run only; pins were NOT updated.")
            print("  To apply: ./gradlew adoptOpenApiBreaking")
        elif allow_breaking:
            print("  --allow-breaking is set; adopt may proceed.")
        else:
            print("  Pins were NOT updated.")
            print("  Re-run: ./gradlew adoptOpenApiBreaking")
            print("  (or: scripts/run_adopt_openapi.sh --yes --allow-breaking)")
    else:
        print("Additive changes only (no breaking surface changes).")
        print(f"  APIs: {', '.join(changed_apis)}")
        if dry_run:
            print("  Dry-run only; pins were NOT updated.")
            print("  To apply: ./gradlew adoptOpenApi")
    print("=" * 72)
    print(flush=True)


def adopt(
    *,
    dry_run: bool,
    yes: bool,
    allow_breaking: bool,
    skip_generate: bool,
    skip_fetch: bool,
    skip_preprocess: bool,
) -> int:
    upstream_root = ROOT / "build" / "upstream"
    adopt_specs_root = ROOT / "build" / "specs-adopt"
    built_specs_root = ROOT / "build" / "specs"
    report_path = ROOT / "build" / "openapi-adopt-report.md"
    upstream_defaults = load_upstream_defaults()

    if not skip_fetch:
        gradle_props: list[str] = []
        for api in APIS:
            url = upstream_defaults[api]
            local = upstream_root / f"{api}.json"
            print(f"Fetching {api} from {url}")
            _download(url, local)
            gradle_props.append(f"-P{api}Spec={local}")
        if not skip_preprocess:
            _run(
                [
                    str(ROOT / "gradlew"),
                    "preprocessBrokerSpec",
                    "preprocessDataSpec",
                    "preprocessTradingSpec",
                    *gradle_props,
                ]
            )
    elif not skip_preprocess:
        raise SystemExit("--skip-fetch requires --skip-preprocess (Gradle owns fetch+preprocess)")

    if skip_preprocess:
        # Gradle preprocessUpstream* writes here; keep pin-based build/specs untouched.
        candidates_root = adopt_specs_root
    else:
        if adopt_specs_root.exists():
            shutil.rmtree(adopt_specs_root)
        shutil.copytree(built_specs_root, adopt_specs_root)
        candidates_root = adopt_specs_root

    report_parts: list[str] = []
    changelog_parts: list[str] = []
    any_breaking = False
    any_changes = False
    breaking_apis: list[str] = []
    changed_apis: list[str] = []

    for api in APIS:
        pinned = ROOT / "specs" / api / "openapi.yaml"
        candidate = candidates_root / api / "openapi.yaml"
        if not pinned.exists():
            raise SystemExit(f"Missing pinned spec: {pinned}")
        if not candidate.exists():
            raise SystemExit(f"Missing adopt candidate: {candidate}")
        diff = openapi_tools.semantic_diff(
            openapi_tools.load_spec(pinned),
            openapi_tools.load_spec(candidate),
        )
        if not diff.is_empty():
            any_changes = True
            changed_apis.append(api)
        if diff.is_breaking():
            any_breaking = True
            breaking_apis.append(api)
        report_parts.append(openapi_tools.format_maintainer_report(diff, api=api))
        changelog_parts.append(f"## {api}\n\n" + openapi_tools.format_changelog_draft(diff))

    report = "\n".join(report_parts).rstrip() + "\n\n" + "\n".join(changelog_parts)
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(report, encoding="utf-8")
    print(report)
    print(f"Wrote {report_path}")

    if not any_changes:
        print("Pinned specs already match upstream (after preprocess). Nothing to adopt.")
        return 0

    print_adopt_summary(
        dry_run=dry_run,
        allow_breaking=allow_breaking,
        any_breaking=any_breaking,
        breaking_apis=breaking_apis,
        changed_apis=changed_apis,
        report_path=report_path,
    )

    exit_code = resolve_adopt_exit_code(
        dry_run=dry_run,
        yes=yes,
        allow_breaking=allow_breaking,
        any_changes=any_changes,
        any_breaking=any_breaking,
    )
    if dry_run:
        print("Dry-run complete; pins and generated OpenAPI sources were not updated.")
        return exit_code
    if exit_code == 2:
        print(
            "ERROR: refusing to adopt breaking OpenAPI changes without --allow-breaking.\n"
            "  Re-run: ./gradlew adoptOpenApiBreaking",
            file=sys.stderr,
            flush=True,
        )
        return exit_code
    if exit_code == 1:
        print("Refusing to write without --yes (non-interactive adopt).")
        return exit_code

    for api in APIS:
        destination = ROOT / "specs" / api / "openapi.yaml"
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(candidates_root / api / "openapi.yaml", destination)
        print(f"Updated {destination}")

    if not skip_generate:
        _run([str(ROOT / "gradlew"), "generateApis", "test"])

    print("Adopt complete. Review openapi sources + CHANGELOG, then commit.")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Fetch/preprocess/diff only; do not update specs/ or generated OpenAPI sources",
    )
    parser.add_argument(
        "--yes",
        action="store_true",
        help="Apply pin updates (and regenerate unless --skip-generate)",
    )
    parser.add_argument(
        "--allow-breaking",
        action="store_true",
        help=(
            "Allow adopts with non-additive surface changes "
            "(removals, renames, moves, operation/schema modifications, enum removals)"
        ),
    )
    parser.add_argument(
        "--skip-generate",
        action="store_true",
        help="Update pins only; caller will run ./gradlew generateApis",
    )
    parser.add_argument(
        "--skip-fetch",
        action="store_true",
        help="Do not download upstream OAS (Gradle downloadUpstreamOpenApi already did)",
    )
    parser.add_argument(
        "--skip-preprocess",
        action="store_true",
        help="Diff against build/specs-adopt (Gradle preprocessUpstream* already ran)",
    )
    args = parser.parse_args(argv)
    return adopt(
        dry_run=args.dry_run,
        yes=args.yes,
        allow_breaking=args.allow_breaking,
        skip_generate=args.skip_generate,
        skip_fetch=args.skip_fetch,
        skip_preprocess=args.skip_preprocess,
    )


if __name__ == "__main__":
    raise SystemExit(main())
