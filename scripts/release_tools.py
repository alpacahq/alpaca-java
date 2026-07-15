#!/usr/bin/env python3
"""Durable release validation and version-maintenance utilities."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
import os
from pathlib import Path
import re
import stat
import sys
import tempfile
import xml.etree.ElementTree as ET


MAX_RECOVERY_POM_BYTES = 1_048_576
_COMPONENT = r"(?:0|[1-9][0-9]*)"
_TAG_PATTERN = re.compile(rf"^v({_COMPONENT})\.({_COMPONENT})\.({_COMPONENT})$")
_VERSION_PATTERN = re.compile(rf"^({_COMPONENT})\.({_COMPONENT})\.({_COMPONENT})$")
_SNAPSHOT_PATTERN = re.compile(
    rf"^({_COMPONENT})\.({_COMPONENT})\.({_COMPONENT})-SNAPSHOT$"
)
_VERSION_PROPERTY_PATTERN = re.compile(r"^\s*version\s*=(.*)$")
_DECIMAL_PATTERN = re.compile(rf"^{_COMPONENT}$")
_FORBIDDEN_XML_DECLARATION_PATTERN = re.compile(
    r"<!\s*(?:DOCTYPE|ENTITY)\b", re.IGNORECASE
)
_XML_IGNORABLE_CONTENT_PATTERN = re.compile(
    r"<!--.*?-->|<!\[CDATA\[.*?\]\]>|<\?.*?\?>", re.DOTALL
)
_XML_ENCODING_PATTERN = re.compile(
    r"^\ufeff?\s*<\?xml\b[^?]*\bencoding\s*=\s*['\"]([^'\"]+)['\"]",
    re.IGNORECASE,
)
_UTF8_BOM = b"\xef\xbb\xbf"
_UNSUPPORTED_UNICODE_BOMS = (
    b"\x00\x00\xfe\xff",
    b"\xff\xfe\x00\x00",
    b"\xfe\xff",
    b"\xff\xfe",
)


class ReleaseToolError(ValueError):
    """Raised when release input fails closed validation."""


@dataclass(frozen=True)
class VersionUpdate:
    version: str
    status: str
    changed: bool


def parse_release_tag(tag: str) -> str:
    """Validate an exact release tag and return its normalized version."""
    match = _TAG_PATTERN.fullmatch(tag)
    if match is None:
        raise ReleaseToolError(
            "release tag must exactly match vMAJOR.MINOR.PATCH without leading zeros"
        )
    return ".".join(match.groups())


def parse_release_version(version: str) -> tuple[str, str, str]:
    match = _VERSION_PATTERN.fullmatch(version)
    if match is None:
        raise ReleaseToolError(
            "release version must exactly match MAJOR.MINOR.PATCH without leading zeros"
        )
    return match.groups()


def compare_canonical_decimal(left: str, right: str) -> int:
    """Compare arbitrary-length canonical non-negative decimal strings."""
    if _DECIMAL_PATTERN.fullmatch(left) is None:
        raise ReleaseToolError(f"non-canonical decimal component: {left}")
    if _DECIMAL_PATTERN.fullmatch(right) is None:
        raise ReleaseToolError(f"non-canonical decimal component: {right}")
    if len(left) != len(right):
        return -1 if len(left) < len(right) else 1
    if left == right:
        return 0
    return -1 if left < right else 1


def increment_canonical_decimal(value: str) -> str:
    """Increment an arbitrary-length canonical decimal string without int()."""
    if _DECIMAL_PATTERN.fullmatch(value) is None:
        raise ReleaseToolError(f"non-canonical decimal component: {value}")
    digits = list(value)
    for index in range(len(digits) - 1, -1, -1):
        if digits[index] != "9":
            digits[index] = chr(ord(digits[index]) + 1)
            return "".join(digits)
        digits[index] = "0"
    return "1" + "".join(digits)


def compare_semver_parts(
    left: tuple[str, str, str], right: tuple[str, str, str]
) -> int:
    """Compare semantic-version components without integer conversion."""
    for left_component, right_component in zip(left, right, strict=True):
        comparison = compare_canonical_decimal(left_component, right_component)
        if comparison != 0:
            return comparison
    return 0


def validate_recovery_pom(
    pom_path: Path, *, group: str, artifact: str, version: str
) -> dict[str, str]:
    """Validate exact direct project coordinates in a safe recovery POM."""
    try:
        with pom_path.open("rb") as source:
            document = source.read(MAX_RECOVERY_POM_BYTES + 1)
    except OSError as exc:
        raise ReleaseToolError(f"unable to read recovery POM: {exc}") from exc
    if len(document) > MAX_RECOVERY_POM_BYTES:
        raise ReleaseToolError(
            f"recovery POM exceeds {MAX_RECOVERY_POM_BYTES} bytes"
        )
    if document.startswith(_UNSUPPORTED_UNICODE_BOMS):
        raise ReleaseToolError("recovery POM uses unsupported UTF-16 or UTF-32")
    try:
        text = document.decode("utf-8-sig")
    except UnicodeDecodeError as exc:
        raise ReleaseToolError("recovery POM is not valid UTF-8") from exc
    if "\x00" in text:
        raise ReleaseToolError(
            "recovery POM contains NUL bytes or unsupported UTF-16/UTF-32 text"
        )
    encoding_match = _XML_ENCODING_PATTERN.match(text)
    if encoding_match is not None:
        encoding = encoding_match.group(1).lower().replace("_", "-")
        if encoding not in ("utf-8", "utf8"):
            raise ReleaseToolError(
                f"recovery POM declares unsupported encoding: {encoding_match.group(1)}"
            )
    xml_declarations = _XML_IGNORABLE_CONTENT_PATTERN.sub("", text)
    if _FORBIDDEN_XML_DECLARATION_PATTERN.search(xml_declarations):
        raise ReleaseToolError(
            "recovery POM contains a forbidden DOCTYPE or ENTITY declaration"
        )
    try:
        root = ET.fromstring(text)
    except ET.ParseError as exc:
        raise ReleaseToolError("recovery POM is malformed XML") from exc

    def local_name(tag: str) -> str:
        return tag.rsplit("}", 1)[-1]

    if local_name(root.tag) != "project":
        raise ReleaseToolError("recovery POM root element is not project")

    expected = {"groupId": group, "artifactId": artifact, "version": version}
    actual: dict[str, str] = {}
    for coordinate, expected_value in expected.items():
        values = [
            (child.text or "").strip()
            for child in root
            if local_name(child.tag) == coordinate
        ]
        if len(values) != 1 or not values[0]:
            raise ReleaseToolError(
                f"recovery POM must contain exactly one project {coordinate}"
            )
        actual[coordinate] = values[0]
        if actual[coordinate] != expected_value:
            raise ReleaseToolError(
                f"recovery POM {coordinate} does not match the requested release"
            )
    return actual


def _atomic_write_text(path: Path, text: str, *, default_mode: int = 0o644) -> None:
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        mode = stat.S_IMODE(path.stat().st_mode) if path.exists() else default_mode
        descriptor, temporary_name = tempfile.mkstemp(
            dir=path.parent, prefix=f".{path.name}.", suffix=".tmp"
        )
        try:
            os.fchmod(descriptor, mode)
            with os.fdopen(
                descriptor, "w", encoding="utf-8", newline="", closefd=True
            ) as output:
                output.write(text)
                output.flush()
                os.fsync(output.fileno())
            os.replace(temporary_name, path)
        except BaseException:
            try:
                os.close(descriptor)
            except OSError:
                pass
            try:
                os.unlink(temporary_name)
            except OSError:
                pass
            raise
    except OSError as exc:
        raise ReleaseToolError(f"unable to atomically write {path}: {exc}") from exc


def _read_utf8_document(path: Path) -> tuple[str, bool]:
    try:
        document = path.read_bytes()
    except OSError as exc:
        raise ReleaseToolError(f"unable to read {path}: {exc}") from exc
    has_utf8_bom = document.startswith(_UTF8_BOM)
    try:
        return document.decode("utf-8-sig"), has_utf8_bom
    except UnicodeDecodeError as exc:
        raise ReleaseToolError(f"{path} is not valid UTF-8") from exc


def read_snapshot_version(properties_path: Path) -> str:
    """Return the effective semantic SNAPSHOT version from a properties file."""
    text, _ = _read_utf8_document(properties_path)
    matches: list[tuple[int, str]] = []
    for index, line in enumerate(text.splitlines()):
        match = _VERSION_PROPERTY_PATTERN.fullmatch(line)
        if match is not None:
            matches.append((index, match.group(1).strip()))
    if not matches:
        raise ReleaseToolError("no version property was found")

    version_index, current_version = matches[-1]
    if _SNAPSHOT_PATTERN.fullmatch(current_version) is None:
        raise ReleaseToolError(
            f"effective version on line {version_index + 1} is not a semantic SNAPSHOT"
        )
    return current_version


def update_next_snapshot(properties_path: Path, release_version: str) -> VersionUpdate:
    """Safely advance the effective final version property without downgrading."""
    release_parts = parse_release_version(release_version)
    next_parts = (
        release_parts[0],
        release_parts[1],
        increment_canonical_decimal(release_parts[2]),
    )
    next_version = ".".join(next_parts) + "-SNAPSHOT"

    text, has_utf8_bom = _read_utf8_document(properties_path)
    lines = text.splitlines(keepends=True)

    matches: list[tuple[int, str]] = []
    for index, line in enumerate(lines):
        match = _VERSION_PROPERTY_PATTERN.fullmatch(line.rstrip("\r\n"))
        if match is not None:
            matches.append((index, match.group(1).strip()))
    if not matches:
        raise ReleaseToolError("no version property was found")

    version_index, current_version = matches[-1]
    current_match = _SNAPSHOT_PATTERN.fullmatch(current_version)
    if current_match is None:
        raise ReleaseToolError(
            f"effective version on line {version_index + 1} is not a semantic SNAPSHOT"
        )
    current_parts = current_match.groups()
    comparison = compare_semver_parts(current_parts, next_parts)

    if comparison == 0:
        return VersionUpdate(next_version, "equal", False)
    if comparison > 0:
        return VersionUpdate(next_version, "ahead", False)

    original_line = lines[version_index]
    if original_line.endswith("\r\n"):
        newline = "\r\n"
    elif original_line.endswith("\n"):
        newline = "\n"
    elif original_line.endswith("\r"):
        newline = "\r"
    else:
        newline = ""
    lines[version_index] = f"version={next_version}{newline}"
    updated_text = "".join(lines)
    if has_utf8_bom:
        updated_text = "\ufeff" + updated_text
    _atomic_write_text(properties_path, updated_text)
    return VersionUpdate(next_version, "behind", True)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)

    parse_tag = subparsers.add_parser("parse-tag")
    parse_tag.add_argument("tag")

    validate_pom = subparsers.add_parser("validate-pom")
    validate_pom.add_argument("--pom", required=True)
    validate_pom.add_argument("--group", required=True)
    validate_pom.add_argument("--artifact", required=True)
    validate_pom.add_argument("--version", required=True)

    update_version = subparsers.add_parser("update-version")
    update_version.add_argument("--properties", required=True)
    update_version.add_argument("--release-version", required=True)
    update_version.add_argument("--github-output")

    read_version = subparsers.add_parser("read-version")
    read_version.add_argument("--properties", required=True)
    return parser


def main(argv: list[str] | None = None) -> int:
    arguments = build_parser().parse_args(argv)
    try:
        if arguments.command == "parse-tag":
            print(parse_release_tag(arguments.tag))
        elif arguments.command == "validate-pom":
            validate_recovery_pom(
                Path(arguments.pom),
                group=arguments.group,
                artifact=arguments.artifact,
                version=arguments.version,
            )
        elif arguments.command == "update-version":
            update = update_next_snapshot(
                Path(arguments.properties), arguments.release_version
            )
            values = (
                f"version={update.version}\n"
                f"should_push={str(update.changed).lower()}\n"
                f"status={update.status}\n"
            )
            if arguments.github_output:
                with Path(arguments.github_output).open(
                    "a", encoding="utf-8", newline=""
                ) as output:
                    output.write(values)
            else:
                print(values, end="")
        elif arguments.command == "read-version":
            print(read_snapshot_version(Path(arguments.properties)))
        else:
            raise AssertionError(f"unsupported command: {arguments.command}")
    except (OSError, ReleaseToolError) as exc:
        print(f"release tool error: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
