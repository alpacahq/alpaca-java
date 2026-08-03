#!/usr/bin/env python3
"""Adopt upstream OpenAPI specs into committed pins and regenerate clients."""

from __future__ import annotations

import argparse
from pathlib import Path
import shutil
import subprocess
import sys
import urllib.request

from scripts import openapi_tools

ROOT = Path(__file__).resolve().parents[1]
APIS = ("broker", "data", "trading")
UPSTREAM_DEFAULTS = {
    "broker": "https://docs.alpaca.markets/openapi/broker-api.json",
    "data": "https://docs.alpaca.markets/openapi/market-data-api.json",
    "trading": "https://docs.alpaca.markets/openapi/trading-api.json",
}


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
    with urllib.request.urlopen(request, timeout=60) as response:  # noqa: S310
        destination.write_bytes(response.read())


def adopt(
    *,
    dry_run: bool,
    yes: bool,
    allow_breaking: bool,
    skip_generate: bool,
) -> int:
    upstream_root = ROOT / "build" / "upstream"
    adopt_specs_root = ROOT / "build" / "specs-adopt"
    report_path = ROOT / "build" / "openapi-adopt-report.md"

    gradle_props: list[str] = []
    for api in APIS:
        url = UPSTREAM_DEFAULTS[api]
        local = upstream_root / f"{api}.json"
        print(f"Fetching {api} from {url}")
        _download(url, local)
        gradle_props.append(f"-P{api}Spec={local}")

    _run(
        [
            str(ROOT / "gradlew"),
            "preprocessBrokerSpec",
            "preprocessDataSpec",
            "preprocessTradingSpec",
            *gradle_props,
        ]
    )

    # Copy preprocess outputs to adopt staging (Gradle writes build/specs/).
    built = ROOT / "build" / "specs"
    if adopt_specs_root.exists():
        shutil.rmtree(adopt_specs_root)
    shutil.copytree(built, adopt_specs_root)

    report_parts: list[str] = []
    changelog_parts: list[str] = []
    any_breaking = False
    any_changes = False

    for api in APIS:
        pinned = ROOT / "specs" / api / "openapi.yaml"
        candidate = adopt_specs_root / api / "openapi.yaml"
        if not pinned.exists():
            raise SystemExit(f"Missing pinned spec: {pinned}")
        if not candidate.exists():
            raise SystemExit(f"Missing adopt candidate: {candidate}")
        diff = openapi_tools.semantic_diff(
            openapi_tools.load_spec(pinned),
            openapi_tools.load_spec(candidate),
        )
        any_breaking = any_breaking or diff.is_breaking()
        any_changes = any_changes or not diff.is_empty()
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

    if any_breaking and not allow_breaking:
        print(
            "Breaking changes present. Re-run with --allow-breaking after review.",
            file=sys.stderr,
        )
        return 2

    if dry_run:
        print("Dry-run complete; pins and generated OpenAPI sources were not updated.")
        return 0

    if not yes:
        print("Refusing to write without --yes (non-interactive adopt).")
        return 1

    for api in APIS:
        destination = ROOT / "specs" / api / "openapi.yaml"
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(adopt_specs_root / api / "openapi.yaml", destination)
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
    args = parser.parse_args(argv)
    return adopt(
        dry_run=args.dry_run,
        yes=args.yes,
        allow_breaking=args.allow_breaking,
        skip_generate=args.skip_generate,
    )


if __name__ == "__main__":
    raise SystemExit(main())
