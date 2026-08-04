#!/usr/bin/env python3
"""Semantic OpenAPI diff and adopt/changelog report helpers."""

from __future__ import annotations

import argparse
from dataclasses import dataclass, field
import json
from pathlib import Path
import sys
from typing import Any


def _require_yaml():
    try:
        import yaml  # type: ignore
    except ImportError as exc:  # pragma: no cover
        raise SystemExit(
            "PyYAML is required for OpenAPI tools. Install with:\n"
            "  python3 -m venv .venv-openapi && "
            "source .venv-openapi/bin/activate && "
            "pip install -r scripts/requirements.txt"
        ) from exc
    return yaml


def load_spec(path: str | Path) -> dict[str, Any]:
    """Load an OpenAPI document from JSON or YAML."""
    file_path = Path(path)
    text = file_path.read_text(encoding="utf-8")
    if file_path.suffix.lower() == ".json":
        data = json.loads(text)
    else:
        yaml = _require_yaml()
        data = yaml.safe_load(text)
    if not isinstance(data, dict):
        raise ValueError(f"OpenAPI document must be a mapping: {file_path}")
    return data


@dataclass
class DiffResult:
    operations_added: list[str] = field(default_factory=list)
    operations_removed: list[str] = field(default_factory=list)
    operations_modified: list[str] = field(default_factory=list)
    operations_renamed: list[str] = field(default_factory=list)
    operations_moved: list[str] = field(default_factory=list)
    schemas_added: list[str] = field(default_factory=list)
    schemas_removed: list[str] = field(default_factory=list)
    schemas_modified: list[str] = field(default_factory=list)
    enum_values_added: list[str] = field(default_factory=list)
    enum_values_removed: list[str] = field(default_factory=list)

    def is_empty(self) -> bool:
        return not any(
            [
                self.operations_added,
                self.operations_removed,
                self.operations_modified,
                self.operations_renamed,
                self.operations_moved,
                self.schemas_added,
                self.schemas_removed,
                self.schemas_modified,
                self.enum_values_added,
                self.enum_values_removed,
            ]
        )

    def is_breaking(self) -> bool:
        # Any non-additive surface change can alter generated Java types/methods.
        return bool(
            self.operations_removed
            or self.operations_renamed
            or self.operations_modified
            or self.operations_moved
            or self.schemas_removed
            or self.schemas_modified
            or self.enum_values_removed
        )


def _operations(spec: dict[str, Any]) -> dict[str, dict[str, Any]]:
    result: dict[str, dict[str, Any]] = {}
    paths = spec.get("paths") or {}
    if not isinstance(paths, dict):
        return result
    for path, item in paths.items():
        if not isinstance(item, dict):
            continue
        for method, operation in item.items():
            if method.startswith("x-") or method in {"parameters", "servers", "summary", "description"}:
                continue
            if not isinstance(operation, dict):
                continue
            key = f"{method.upper()} {path}"
            result[key] = operation
    return result


def _schemas(spec: dict[str, Any]) -> dict[str, Any]:
    components = spec.get("components") or {}
    if not isinstance(components, dict):
        return {}
    schemas = components.get("schemas") or {}
    return schemas if isinstance(schemas, dict) else {}


def _operation_id(operation: dict[str, Any]) -> str | None:
    value = operation.get("operationId")
    return value if isinstance(value, str) and value else None


def _first_tag(operation: dict[str, Any]) -> str | None:
    tags = operation.get("tags")
    if isinstance(tags, list) and tags and isinstance(tags[0], str):
        return tags[0]
    return None


def _schema_fingerprint(schema: Any) -> str:
    return json.dumps(schema, sort_keys=True, default=str)


def _collect_enums(schema: Any, path: str, out: dict[str, set[str]]) -> None:
    if isinstance(schema, dict):
        enum_values = schema.get("enum")
        if isinstance(enum_values, list):
            out[path] = {str(v) for v in enum_values}
        for key, value in schema.items():
            if key == "properties" and isinstance(value, dict):
                for prop, prop_schema in value.items():
                    _collect_enums(prop_schema, f"{path}.{prop}", out)
            elif key in {"items", "additionalProperties"}:
                _collect_enums(value, f"{path}.{key}", out)
            elif key in {"allOf", "oneOf", "anyOf"} and isinstance(value, list):
                for index, part in enumerate(value):
                    _collect_enums(part, f"{path}.{key}[{index}]", out)
    elif isinstance(schema, list):
        for index, item in enumerate(schema):
            _collect_enums(item, f"{path}[{index}]", out)


def semantic_diff(old: dict[str, Any], new: dict[str, Any]) -> DiffResult:
    """Compare two OpenAPI documents and classify surface changes."""
    diff = DiffResult()

    old_ops = _operations(old)
    new_ops = _operations(new)
    old_keys = set(old_ops)
    new_keys = set(new_ops)

    diff.operations_added = sorted(new_keys - old_keys)
    diff.operations_removed = sorted(old_keys - new_keys)

    old_by_id = {
        _operation_id(op): key
        for key, op in old_ops.items()
        if _operation_id(op) is not None
    }
    new_by_id = {
        _operation_id(op): key
        for key, op in new_ops.items()
        if _operation_id(op) is not None
    }

    for op_id, old_key in old_by_id.items():
        new_key = new_by_id.get(op_id)
        if new_key is None or old_key == new_key:
            continue
        if old_key in diff.operations_removed and new_key in diff.operations_added:
            diff.operations_renamed.append(f"{old_key} -> {new_key} (operationId={op_id})")
            diff.operations_removed.remove(old_key)
            diff.operations_added.remove(new_key)

    for key in sorted(old_keys & new_keys):
        old_op = old_ops[key]
        new_op = new_ops[key]
        old_tag = _first_tag(old_op)
        new_tag = _first_tag(new_op)
        if old_tag != new_tag:
            diff.operations_moved.append(
                f"{key}: tag {old_tag!r} -> {new_tag!r}"
            )
        comparable_old = {
            k: v
            for k, v in old_op.items()
            if k not in {"tags", "summary", "description", "externalDocs", "x-codegen-request-body-name"}
        }
        comparable_new = {
            k: v
            for k, v in new_op.items()
            if k not in {"tags", "summary", "description", "externalDocs", "x-codegen-request-body-name"}
        }
        if _schema_fingerprint(comparable_old) != _schema_fingerprint(comparable_new):
            diff.operations_modified.append(key)

    old_schemas = _schemas(old)
    new_schemas = _schemas(new)
    old_schema_keys = set(old_schemas)
    new_schema_keys = set(new_schemas)
    diff.schemas_added = sorted(new_schema_keys - old_schema_keys)
    diff.schemas_removed = sorted(old_schema_keys - new_schema_keys)
    for name in sorted(old_schema_keys & new_schema_keys):
        if _schema_fingerprint(old_schemas[name]) != _schema_fingerprint(new_schemas[name]):
            diff.schemas_modified.append(name)

    old_enums: dict[str, set[str]] = {}
    new_enums: dict[str, set[str]] = {}
    for name, schema in old_schemas.items():
        _collect_enums(schema, name, old_enums)
    for name, schema in new_schemas.items():
        _collect_enums(schema, name, new_enums)
    for path in sorted(set(old_enums) | set(new_enums)):
        before = old_enums.get(path, set())
        after = new_enums.get(path, set())
        for value in sorted(after - before):
            diff.enum_values_added.append(f"{path}: {value}")
        for value in sorted(before - after):
            diff.enum_values_removed.append(f"{path}: {value}")

    return diff


def _section(title: str, items: list[str]) -> list[str]:
    if not items:
        return []
    lines = [f"### {title}", ""]
    lines.extend(f"- `{item}`" for item in items)
    lines.append("")
    return lines


def format_maintainer_report(diff: DiffResult, *, api: str | None = None) -> str:
    header = "# OpenAPI adopt report"
    if api:
        header += f" ({api})"
    lines = [header, ""]
    if diff.is_empty():
        lines.append("No semantic OpenAPI surface changes detected.")
        lines.append("")
        return "\n".join(lines)
    if diff.is_breaking():
        lines.append("**Breaking changes detected.**")
        lines.append("")
    lines.extend(_section("Operations added", diff.operations_added))
    lines.extend(_section("Operations removed", diff.operations_removed))
    lines.extend(_section("Operations modified", diff.operations_modified))
    lines.extend(_section("Operations renamed", diff.operations_renamed))
    lines.extend(_section("Operations moved", diff.operations_moved))
    lines.extend(_section("Schemas added", diff.schemas_added))
    lines.extend(_section("Schemas removed", diff.schemas_removed))
    lines.extend(_section("Schemas modified", diff.schemas_modified))
    lines.extend(_section("Enum values added", diff.enum_values_added))
    lines.extend(_section("Enum values removed", diff.enum_values_removed))
    return "\n".join(lines).rstrip() + "\n"


def format_changelog_draft(diff: DiffResult) -> str:
    lines = ["## Changelog draft", ""]
    if diff.is_empty():
        lines.append("_No public OpenAPI surface changes._")
        lines.append("")
        return "\n".join(lines)

    breaking: list[str] = []
    added: list[str] = []

    breaking.extend(f"Remove operation `{item}`" for item in diff.operations_removed)
    breaking.extend(f"Rename operation `{item}`" for item in diff.operations_renamed)
    breaking.extend(f"Modify operation `{item}`" for item in diff.operations_modified)
    breaking.extend(f"Move operation `{item}`" for item in diff.operations_moved)
    breaking.extend(f"Remove schema `{item}`" for item in diff.schemas_removed)
    breaking.extend(f"Modify schema `{item}`" for item in diff.schemas_modified)
    breaking.extend(f"Remove enum value `{item}`" for item in diff.enum_values_removed)

    added.extend(f"Add operation `{item}`" for item in diff.operations_added)
    added.extend(f"Add schema `{item}`" for item in diff.schemas_added)
    added.extend(f"Add enum value `{item}`" for item in diff.enum_values_added)

    if breaking:
        lines.append("### Breaking")
        lines.append("")
        lines.extend(f"- {item}" for item in breaking)
        lines.append("")
    if added:
        lines.append("### Added")
        lines.append("")
        lines.extend(f"- {item}" for item in added)
        lines.append("")
    return "\n".join(lines).rstrip() + "\n"


def merge_diffs(diffs: list[DiffResult]) -> DiffResult:
    merged = DiffResult()
    for diff in diffs:
        merged.operations_added.extend(diff.operations_added)
        merged.operations_removed.extend(diff.operations_removed)
        merged.operations_modified.extend(diff.operations_modified)
        merged.operations_renamed.extend(diff.operations_renamed)
        merged.operations_moved.extend(diff.operations_moved)
        merged.schemas_added.extend(diff.schemas_added)
        merged.schemas_removed.extend(diff.schemas_removed)
        merged.schemas_modified.extend(diff.schemas_modified)
        merged.enum_values_added.extend(diff.enum_values_added)
        merged.enum_values_removed.extend(diff.enum_values_removed)
    return merged


def _cmd_diff(args: argparse.Namespace) -> int:
    old = load_spec(args.old)
    new = load_spec(args.new)
    diff = semantic_diff(old, new)
    sys.stdout.write(format_maintainer_report(diff, api=args.api))
    sys.stdout.write("\n")
    sys.stdout.write(format_changelog_draft(diff))
    if args.fail_on_breaking and diff.is_breaking():
        return 2
    if args.fail_on_changes and not diff.is_empty():
        return 1
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    diff_parser = subparsers.add_parser("diff", help="Semantic diff of two OpenAPI files")
    diff_parser.add_argument("--old", required=True, help="Pinned / previous spec path")
    diff_parser.add_argument("--new", required=True, help="Candidate / new spec path")
    diff_parser.add_argument("--api", default=None, help="API label for the report header")
    diff_parser.add_argument(
        "--fail-on-breaking",
        action="store_true",
        help="Exit 2 when breaking changes are present",
    )
    diff_parser.add_argument(
        "--fail-on-changes",
        action="store_true",
        help="Exit 1 when any semantic changes are present",
    )

    args = parser.parse_args(argv)
    if args.command == "diff":
        return _cmd_diff(args)
    raise AssertionError(f"Unhandled command: {args.command}")


if __name__ == "__main__":
    raise SystemExit(main())
