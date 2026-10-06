#!/usr/bin/env python3
"""Conservatively migrate Broker SSE usage from alpaca-java 0.1.4 to 0.1.5.

The tool intentionally recognizes only a small, mechanically safe Java subset.
Everything else is reported for manual review and left unchanged.
"""

from __future__ import annotations

import argparse
from dataclasses import asdict, dataclass
import json
from pathlib import Path
import re
import sys
from typing import Iterable


BROKER_SUBSCRIPTION = "markets.alpaca.client.broker.sse.BrokerSseSubscription"
EXACT_IMPORT = re.compile(
    rf"(?m)^\s*import\s+{re.escape(BROKER_SUBSCRIPTION)}\s*;"
)
BROKER_DECLARATION = re.compile(
    rf"\b(?P<type>BrokerSseSubscription|{re.escape(BROKER_SUBSCRIPTION)})\s+"
    r"(?P<name>[A-Za-z_$][\w$]*)\b(?!\s*\()"
)
JAVA_IDENTIFIER = r"[A-Za-z_$][\w$]*"
JAVA_TYPE = (
    rf"{JAVA_IDENTIFIER}(?:\s*\.\s*{JAVA_IDENTIFIER})*"
    rf"(?:\s*<[^;{{}}()=]+>)?(?:\s*\[\s*\])*"
)
ANY_DECLARATION = re.compile(
    rf"(?<![\w$.])(?P<type>{JAVA_TYPE})\s+(?:\.\.\.\s*)?"
    rf"(?P<name>{JAVA_IDENTIFIER})\b(?!\s*\()"
)
NON_TYPE_KEYWORDS = {
    "assert",
    "break",
    "case",
    "continue",
    "delete",
    "else",
    "new",
    "return",
    "throw",
    "yield",
}
CANCEL_CALL = re.compile(
    r"\b([A-Za-z_$][\w$]*)\s*\.\s*eventSource\s*\(\s*\)"
    r"\s*\.\s*cancel\s*\(\s*\)"
)


@dataclass(frozen=True)
class Finding:
    path: str
    line: int
    code: str
    message: str
    rewritten: bool = False


@dataclass(frozen=True)
class Analysis:
    text: str
    findings: tuple[Finding, ...]


def _code_mask(text: str) -> str:
    """Return text with comments and literals blanked while preserving offsets."""
    result = list(text)
    index = 0
    state = "code"
    while index < len(text):
        char = text[index]
        following = text[index + 1] if index + 1 < len(text) else ""
        if state == "code":
            if char == "/" and following == "/":
                result[index] = result[index + 1] = " "
                state = "line_comment"
                index += 2
                continue
            if char == "/" and following == "*":
                result[index] = result[index + 1] = " "
                state = "block_comment"
                index += 2
                continue
            if text.startswith('"""', index):
                result[index : index + 3] = "   "
                state = "text_block"
                index += 3
                continue
            if char in {'"', "'"}:
                result[index] = " "
                state = "string" if char == '"' else "character"
        elif state == "line_comment":
            if char == "\n":
                state = "code"
            else:
                result[index] = " "
        elif state == "block_comment":
            if char == "*" and following == "/":
                result[index] = result[index + 1] = " "
                state = "code"
                index += 2
                continue
            if char != "\n":
                result[index] = " "
        elif state == "text_block":
            if text.startswith('"""', index):
                result[index : index + 3] = "   "
                state = "code"
                index += 3
                continue
            if char != "\n":
                result[index] = " "
        else:
            if char == "\\":
                result[index] = " "
                if index + 1 < len(text):
                    if text[index + 1] != "\n":
                        result[index + 1] = " "
                    index += 2
                    continue
            terminator = '"' if state == "string" else "'"
            if char == terminator:
                state = "code"
            if char != "\n":
                result[index] = " "
        index += 1
    return "".join(result)


def _line(text: str, offset: int) -> int:
    return text.count("\n", 0, offset) + 1


def _broker_binders(mask: str, *, has_exact_import: bool) -> dict[str, list[int]]:
    binders: dict[str, list[int]] = {}
    for declaration in BROKER_DECLARATION.finditer(mask):
        type_name = declaration.group("type")
        if type_name == BROKER_SUBSCRIPTION or has_exact_import:
            binders.setdefault(declaration.group("name"), []).append(
                declaration.start("name")
            )
    return binders


def _all_binders(mask: str) -> dict[str, set[int]]:
    """Conservatively find explicit declarations and inferred lambda parameters."""
    binders: dict[str, set[int]] = {}
    for declaration in ANY_DECLARATION.finditer(mask):
        if declaration.group("type") in NON_TYPE_KEYWORDS:
            continue
        binders.setdefault(declaration.group("name"), set()).add(
            declaration.start("name")
        )

    inferred_lambda = re.compile(
        rf"(?<![\w$])(?P<name>{JAVA_IDENTIFIER})\s*->"
        rf"|(?P<name_parenthesized>{JAVA_IDENTIFIER})"
        rf"(?=\s*(?:,\s*{JAVA_IDENTIFIER}\s*)*\)\s*->)"
    )
    for parameter in inferred_lambda.finditer(mask):
        group = "name" if parameter.group("name") is not None else "name_parenthesized"
        binders.setdefault(parameter.group(group), set()).add(parameter.start(group))
    return binders


def _is_bare_receiver(mask: str, offset: int) -> bool:
    index = offset - 1
    while index >= 0 and mask[index].isspace():
        index -= 1
    return index < 0 or mask[index] != "."


def _contains_masked_syntax(text: str, mask: str, start: int, end: int) -> bool:
    return any(
        original != masked and not original.isspace()
        for original, masked in zip(text[start:end], mask[start:end])
    )


def _diagnostic_patterns(
    mask: str, *, sse_context: bool
) -> Iterable[tuple[int, str, str]]:
    generated_sse = re.compile(
        r"\.\s*(?:subscribeTo[A-Za-z0-9_]*SSE|getV1EventsNta|getAccountActivityEvent)\s*\("
    )
    match = generated_sse.search(mask)
    if match:
        yield (
            match.start(),
            "SSE006",
            "generated blocking SSE operation should be replaced with a handwritten SSE client",
        )
    if not sse_context:
        return

    checks = (
        (
            re.compile(r"\bEventSource\b.*(?:=|instanceof|==).*eventSource\s*\(", re.DOTALL),
            "SSE002",
            "EventSource identity, assignment, or cast requires manual review",
        ),
        (
            re.compile(r"\b(?:JsonSyntaxException|JsonParseException)\b"),
            "SSE003",
            "legacy Gson exception checks should be reviewed against rich failure callbacks",
        ),
        (
            re.compile(r"\.\s*(?:handshake|networkResponse|cacheResponse|priorResponse)\s*\("),
            "SSE004",
            "deep OkHttp Response internals are unavailable on bounded compatibility responses",
        ),
        (
            re.compile(r"\b(?:Thread\.sleep|CompletableFuture\..*?join|\.await|\.join)\s*\("),
            "SSE005",
            "blocking work in an SSE listener should be moved to a dedicated executor",
        ),
    )
    for pattern, code, message in checks:
        match = pattern.search(mask)
        if match:
            yield match.start(), code, message

    rich = any(
        re.search(rf"\bvoid\s+{name}\s*\(", mask)
        for name in ("onEventFailure", "onHttpFailure")
    )
    legacy = re.search(r"\bvoid\s+onFailure\s*\(", mask)
    if rich and legacy:
        yield (
            legacy.start(),
            "SSE007",
            "listener overrides both rich and legacy failure callbacks; verify single-delivery intent",
        )


def analyze_text(text: str, path: str = "<memory>") -> Analysis:
    mask = _code_mask(text)
    has_exact_import = bool(EXACT_IMPORT.search(mask))
    broker_binders = _broker_binders(mask, has_exact_import=has_exact_import)
    all_binders = _all_binders(mask)

    replacements: list[tuple[int, int, str]] = []
    findings: list[Finding] = []
    for call in CANCEL_CALL.finditer(mask):
        receiver = call.group(1)
        safe = (
            _is_bare_receiver(mask, call.start())
            and len(broker_binders.get(receiver, ())) == 1
            and len(all_binders.get(receiver, ())) == 1
            and not _contains_masked_syntax(text, mask, call.start(), call.end())
        )
        if safe:
            replacements.append((call.start(), call.end(), f"{receiver}.close()"))
            findings.append(
                Finding(
                    path,
                    _line(text, call.start()),
                    "SSE000",
                    "replace legacy eventSource().cancel() with close()",
                    True,
                )
            )
        else:
            findings.append(
                Finding(
                    path,
                    _line(text, call.start()),
                    "SSE001",
                    "ambiguous eventSource().cancel() receiver; migrate manually",
                )
            )

    migrated = text
    for start, end, replacement in reversed(replacements):
        migrated = migrated[:start] + replacement + migrated[end:]

    sse_context = "BrokerSseEventListener" in mask or "eventSource" in mask
    for offset, code, message in _diagnostic_patterns(mask, sse_context=sse_context):
        findings.append(Finding(path, _line(text, offset), code, message))

    return Analysis(
        migrated,
        tuple(sorted(findings, key=lambda item: (item.path, item.line, item.code))),
    )


def _java_files(paths: Iterable[Path]) -> list[Path]:
    files: set[Path] = set()
    for path in paths:
        if path.is_file() and path.suffix == ".java":
            files.add(path)
        elif path.is_dir():
            files.update(
                candidate
                for candidate in path.rglob("*.java")
                if not {".git", ".gradle", "build"}.intersection(candidate.parts)
            )
    return sorted(files)


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("paths", nargs="+", type=Path, help="Java file or source directory")
    parser.add_argument("--write", action="store_true", help="apply safe rewrites")
    parser.add_argument(
        "--check",
        action="store_true",
        help="exit non-zero when a rewrite or manual-review diagnostic is found",
    )
    parser.add_argument("--json", action="store_true", help="emit machine-readable findings")
    return parser


def main(argv: list[str] | None = None) -> int:
    arguments = _parser().parse_args(argv)
    findings: list[Finding] = []
    for path in _java_files(arguments.paths):
        original = path.read_text(encoding="utf-8")
        analysis = analyze_text(original, str(path))
        findings.extend(analysis.findings)
        if arguments.write and analysis.text != original:
            path.write_text(analysis.text, encoding="utf-8")

    if arguments.json:
        print(json.dumps([asdict(finding) for finding in findings], indent=2))
    else:
        for finding in findings:
            action = "rewrite" if finding.rewritten else "review"
            print(
                f"{finding.path}:{finding.line}: {finding.code} [{action}] "
                f"{finding.message}"
            )
        print(
            f"{len(findings)} finding(s); "
            f"{sum(finding.rewritten for finding in findings)} safe rewrite(s)"
        )
    return 1 if arguments.check and findings else 0


if __name__ == "__main__":
    sys.exit(main())
