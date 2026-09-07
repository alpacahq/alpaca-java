#!/usr/bin/env python3
"""Compare the public generated-Java source surface with a git baseline.

This deliberately compares the checked-in generated source rather than OpenAPI
documents.  The drift workflow uses its result as the SDK compatibility gate:
an upstream OpenAPI change is relevant to that gate only when it removes or
changes a public Java declaration consumers can compile against.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path
import re
import subprocess


JAVA_SUFFIX = ".java"
ANNOTATION = re.compile(r"@[A-Za-z_$][\w.$]*(?:\([^)]*\))?\s*")
TYPE_DECLARATION = re.compile(
    r"\bpublic\s+(?:static\s+)?(?:final\s+)?(class|interface|enum)\s+([A-Za-z_$][\w$]*)([^\{]*)\{"
)
METHOD_DECLARATION = re.compile(
    r"^public\s+(?:static\s+)?(.+?)\s+([A-Za-z_$][\w$]*)\((.*)\)\s*(?:throws\s+[^\{]+)?\{$"
)
CONSTRUCTOR_DECLARATION = re.compile(
    r"^public\s+([A-Za-z_$][\w$]*)\((.*)\)\s*(?:throws\s+[^\{]+)?\{$"
)
FIELD_DECLARATION = re.compile(
    r"^public\s+(?:static\s+)?(?:final\s+)?(.+?)\s+([A-Za-z_$][\w$]*)\s*(?:=|;)"
)
ENUM_CONSTANT = re.compile(r"^([A-Z][A-Z0-9_]*)\s*(?:\(|,|;)")


@dataclass(frozen=True, order=True)
class Declaration:
    """A public Java declaration represented without names of method parameters."""

    kind: str
    owner: str
    name: str
    signature: str

    @property
    def key(self) -> tuple[str, str, str, str]:
        return self.kind, self.owner, self.name, self.signature


def _strip_annotations(text: str) -> str:
    return ANNOTATION.sub("", text)


def _split_parameters(parameters: str) -> list[str]:
    if not parameters.strip():
        return []
    values: list[str] = []
    current: list[str] = []
    depth = 0
    for char in parameters:
        if char == "<":
            depth += 1
        elif char == ">":
            depth -= 1
        if char == "," and depth == 0:
            values.append("".join(current))
            current = []
        else:
            current.append(char)
    values.append("".join(current))
    return values


def _parameter_types(parameters: str) -> str:
    types: list[str] = []
    for parameter in _split_parameters(parameters):
        normalized = " ".join(_strip_annotations(parameter).replace("final ", "").split())
        # Java generator declarations always name parameters. Preserve a malformed
        # declaration verbatim rather than accidentally declaring it compatible.
        pieces = normalized.rsplit(" ", 1)
        types.append(pieces[0] if len(pieces) == 2 else normalized)
    return ", ".join(types)


def declarations_from_java(text: str, source: str) -> dict[tuple[str, str, str, str], Declaration]:
    """Extract the stable public surface from OpenAPI-generator Java formatting."""
    declarations: dict[tuple[str, str, str], Declaration] = {}
    stack: list[tuple[str, int, str]] = []
    depth = 0
    package = next(
        (
            line.strip().removeprefix("package ").removesuffix(";").strip()
            for line in text.splitlines()
            if line.strip().startswith("package ")
        ),
        source,
    )

    for raw_line in text.splitlines():
        line = _strip_annotations(raw_line).strip()
        owner = ".".join((package, *(item[0] for item in stack)))

        type_match = TYPE_DECLARATION.search(line)
        if type_match:
            kind, name, tail = type_match.groups()
            signature = " ".join(f"{kind} {name}{tail}".split())
            declaration = Declaration("type", owner, name, signature)
            declarations[declaration.key] = declaration
            stack.append((name, depth + raw_line.count("{") - raw_line.count("}"), kind))
            owner = ".".join((package, *(item[0] for item in stack)))
        elif stack:
            method_match = METHOD_DECLARATION.match(line)
            if method_match:
                return_type, name, parameters = method_match.groups()
                signature = f"{return_type} {name}({_parameter_types(parameters)})"
                declaration = Declaration(
                    "method",
                    owner,
                    name,
                    signature,
                )
                declarations[declaration.key] = declaration
            else:
                constructor_match = CONSTRUCTOR_DECLARATION.match(line)
                if constructor_match and constructor_match.group(1) == stack[-1][0]:
                    name, parameters = constructor_match.groups()
                    declaration = Declaration(
                        "constructor", owner, name, f"{name}({_parameter_types(parameters)})"
                    )
                    declarations[declaration.key] = declaration
                else:
                    field_match = FIELD_DECLARATION.match(line)
                    if field_match:
                        field_type, name = field_match.groups()
                        declaration = Declaration("field", owner, name, " ".join(field_type.split()))
                        declarations[declaration.key] = declaration
                    elif stack[-1][2] == "enum":
                        constant_match = ENUM_CONSTANT.match(line)
                        if constant_match:
                            name = constant_match.group(1)
                            declaration = Declaration("enum value", owner, name, name)
                            declarations[declaration.key] = declaration

        depth += raw_line.count("{") - raw_line.count("}")
        while stack and depth < stack[-1][1]:
            stack.pop()

    return declarations


def _git_file(ref: str, path: str) -> str | None:
    completed = subprocess.run(
        ["git", "show", f"{ref}:{path}"],
        check=False,
        capture_output=True,
        text=True,
    )
    return completed.stdout if completed.returncode == 0 else None


def _git_paths(ref: str, root: Path) -> set[str]:
    completed = subprocess.run(
        ["git", "ls-tree", "-r", "--name-only", ref, "--", root.as_posix()],
        check=True,
        capture_output=True,
        text=True,
    )
    return {path for path in completed.stdout.splitlines() if path.endswith(JAVA_SUFFIX)}


def _compare_declarations(
    old: dict[tuple[str, str, str, str], Declaration],
    new: dict[tuple[str, str, str, str], Declaration],
) -> list[tuple[str, Declaration, Declaration | None]]:
    """Compare declarations while retaining every overload of a public member."""
    old_by_member: dict[tuple[str, str, str], list[Declaration]] = {}
    new_by_member: dict[tuple[str, str, str], list[Declaration]] = {}
    for declaration in old.values():
        old_by_member.setdefault((declaration.kind, declaration.owner, declaration.name), []).append(
            declaration
        )
    for declaration in new.values():
        new_by_member.setdefault((declaration.kind, declaration.owner, declaration.name), []).append(
            declaration
        )

    changes: list[tuple[str, Declaration, Declaration | None]] = []
    for member, old_declarations in old_by_member.items():
        remaining_old = sorted(old_declarations, key=lambda declaration: declaration.signature)
        remaining_new = sorted(
            new_by_member.get(member, []), key=lambda declaration: declaration.signature
        )

        # Unchanged overloads are not candidates for either removal or a type change.
        unchanged = {declaration.signature for declaration in remaining_old} & {
            declaration.signature for declaration in remaining_new
        }
        remaining_old = [
            declaration for declaration in remaining_old if declaration.signature not in unchanged
        ]
        remaining_new = [
            declaration for declaration in remaining_new if declaration.signature not in unchanged
        ]

        for old_declaration, new_declaration in zip(remaining_old, remaining_new):
            changes.append(("changed", old_declaration, new_declaration))
        changes.extend(("removed", declaration, None) for declaration in remaining_old[len(remaining_new) :])
    return changes


def compare_generated_sources(
    base_ref: str, new_root: Path
) -> list[tuple[str, Declaration, Declaration | None]]:
    """Return removed/changed public declarations from ``base_ref`` to ``new_root``."""
    changes: list[tuple[str, Declaration, Declaration | None]] = []
    new_root = new_root.resolve()
    current_paths = {
        path.relative_to(Path.cwd()).as_posix()
        for path in new_root.rglob(f"*{JAVA_SUFFIX}")
    }
    paths = _git_paths(base_ref, new_root.relative_to(Path.cwd())) | current_paths
    for relative in sorted(paths):
        old_text = _git_file(base_ref, relative)
        if old_text is None:
            continue
        old = declarations_from_java(old_text, relative)
        path = Path.cwd() / relative
        new = (
            declarations_from_java(path.read_text(encoding="utf-8"), relative)
            if path.exists()
            else {}
        )
        changes.extend(_compare_declarations(old, new))
    return sorted(changes, key=lambda item: (item[1].owner, item[1].kind, item[1].name))


def format_report(changes: list[tuple[str, Declaration, Declaration | None]]) -> str:
    lines = ["# Generated Java SDK compatibility report", ""]
    if not changes:
        return "\n".join(lines + ["No public generated-Java incompatibilities detected.", ""])
    lines.extend(["**Breaking Java SDK compatibility changes detected.**", ""])
    for change, old, new in changes:
        target = f"`{old.owner}.{old.name}`"
        if change == "removed":
            lines.append(f"- Remove {old.kind} {target} ({old.signature})")
        else:
            assert new is not None
            lines.append(
                f"- Change {old.kind} {target}: `{old.signature}` → `{new.signature}`"
            )
    lines.append("")
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-ref", required=True)
    parser.add_argument("--new-root", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--github-output", type=Path)
    args = parser.parse_args()

    changes = compare_generated_sources(args.base_ref, args.new_root)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(format_report(changes), encoding="utf-8")
    if args.github_output:
        with args.github_output.open("a", encoding="utf-8") as output:
            output.write(f"breaking={'true' if changes else 'false'}\n")
    print(args.report)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
