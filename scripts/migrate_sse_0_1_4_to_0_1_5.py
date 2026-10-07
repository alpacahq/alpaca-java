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
BROKER_EVENTS_CLIENT = "markets.alpaca.client.broker.sse.BrokerEventsSseClient"
EXACT_IMPORT = re.compile(
    rf"(?m)^\s*import\s+{re.escape(BROKER_SUBSCRIPTION)}\s*;"
)
EXACT_CLIENT_IMPORT = re.compile(
    rf"(?m)^\s*import\s+{re.escape(BROKER_EVENTS_CLIENT)}\s*;"
)
BROKER_DECLARATION = re.compile(
    rf"\b(?P<type>BrokerSseSubscription|{re.escape(BROKER_SUBSCRIPTION)})\s+"
    r"(?P<name>[A-Za-z_$][\w$]*)\b(?!\s*\()"
)
BROKER_TYPE_DECLARATION = re.compile(
    r"\b(?:class|interface|enum|record|@interface)\s+BrokerSseSubscription\b"
)
BROKER_TYPE_PARAMETER = re.compile(
    r"(?:<|,)\s*(?:@\s*[A-Za-z_$][\w$]*(?:\s*\.\s*[A-Za-z_$][\w$]*)*"
    r"(?:\s*\([^>]*?\))?\s*)*"
    r"BrokerSseSubscription\b(?=\s*(?:extends\b|,|>))"
)
BROKER_CLIENT_DECLARATION = re.compile(
    rf"\b(?P<type>BrokerEventsSseClient|{re.escape(BROKER_EVENTS_CLIENT)})\s+"
    r"(?P<name>[A-Za-z_$][\w$]*)\b(?!\s*\()"
)
BROKER_CLIENT_TYPE_DECLARATION = re.compile(
    r"\b(?:class|interface|enum|record|@interface)\s+BrokerEventsSseClient\b"
)
BROKER_CLIENT_TYPE_PARAMETER = re.compile(
    r"(?:<|,)\s*(?:@\s*[A-Za-z_$][\w$]*(?:\s*\.\s*[A-Za-z_$][\w$]*)*"
    r"(?:\s*\([^>]*?\))?\s*)*"
    r"BrokerEventsSseClient\b(?=\s*(?:extends\b|,|>))"
)
JAVA_UNICODE_ESCAPE = re.compile(r"\\u+[0-9A-Fa-f]{4}")
JAVA_IDENTIFIER = r"[A-Za-z_$][\w$]*"
ANNOTATION_ARGUMENTS = re.compile(
    rf"@\s*{JAVA_IDENTIFIER}(?:\s*\.\s*{JAVA_IDENTIFIER})*\s*(?P<opening>\()"
)
TYPE_DECLARATION = re.compile(
    rf"\b(?:class|interface|enum|record|@interface)\s+{JAVA_IDENTIFIER}\b"
)
JAVA_TYPE = (
    rf"{JAVA_IDENTIFIER}(?:\s*\.\s*{JAVA_IDENTIFIER})*"
    rf"(?:\s*<[^;{{}}()=]+>)?(?:\s*\[\s*\])*"
)
JAVA_MODIFIER = (
    r"(?:public|protected|private|static|final|abstract|synchronized|native|strictfp|"
    r"transient|volatile|sealed|non-sealed|default)"
)
ANY_DECLARATION = re.compile(
    rf"(?<![\w$.])(?:{JAVA_MODIFIER}\s+)*(?P<type>{JAVA_TYPE})\s+(?:\.\.\.\s*)?"
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
HANDWRITTEN_SINGLE_EVENT_USAGE = re.compile(
    rf"\b(?P<receiver>{JAVA_IDENTIFIER})\s*"
    rf"(?P<separator>\.|::)\s*getAccountActivityEventAsync"
    rf"(?:\s*\(|\b)"
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


def _is_escaped(text: str, offset: int) -> bool:
    backslashes = 0
    index = offset - 1
    while index >= 0 and text[index] == "\\":
        backslashes += 1
        index -= 1
    return backslashes % 2 == 1


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
            if text.startswith('"""', index) and not _is_escaped(text, index):
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


def _secondary_declarators(mask: str, declaration_end: int) -> tuple[tuple[str, int], ...]:
    """Return comma-separated names from a declaration statement, if unambiguous."""
    semicolon = mask.find(";", declaration_end)
    opening_brace = mask.find("{", declaration_end)
    if semicolon < 0 or (opening_brace >= 0 and opening_brace < semicolon):
        return ()

    tail = mask[declaration_end:semicolon]
    results: list[tuple[str, int]] = []
    round_depth = square_depth = angle_depth = 0
    for index, character in enumerate(tail):
        if character == "(":
            round_depth += 1
        elif character == ")":
            round_depth = max(0, round_depth - 1)
        elif character == "[":
            square_depth += 1
        elif character == "]":
            square_depth = max(0, square_depth - 1)
        elif character == "<":
            angle_depth += 1
        elif character == ">":
            angle_depth = max(0, angle_depth - 1)
        elif character == "," and round_depth == square_depth == angle_depth == 0:
            declarator = re.match(
                rf"\s*(?:\[\s*\]\s*)?(?P<name>{JAVA_IDENTIFIER})\b",
                tail[index + 1 :],
            )
            if declarator is not None:
                offset = declaration_end + index + 1 + declarator.start("name")
                results.append((declarator.group("name"), offset))
    return tuple(results)


def _broker_binders(mask: str, *, has_exact_import: bool) -> dict[str, list[int]]:
    binders: dict[str, list[int]] = {}
    for declaration in BROKER_DECLARATION.finditer(mask):
        type_name = declaration.group("type")
        if type_name == BROKER_SUBSCRIPTION or has_exact_import:
            binders.setdefault(declaration.group("name"), []).append(
                declaration.start("name")
            )
            for name, offset in _secondary_declarators(mask, declaration.end()):
                binders.setdefault(name, []).append(offset)
    return binders


def _broker_client_binders(
    mask: str, *, has_exact_import: bool
) -> dict[str, list[int]]:
    binders: dict[str, list[int]] = {}
    for declaration in BROKER_CLIENT_DECLARATION.finditer(mask):
        type_name = declaration.group("type")
        if type_name == BROKER_EVENTS_CLIENT or has_exact_import:
            binders.setdefault(declaration.group("name"), []).append(
                declaration.start("name")
            )
            for name, offset in _secondary_declarators(mask, declaration.end()):
                binders.setdefault(name, []).append(offset)
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
        for name, offset in _secondary_declarators(mask, declaration.end()):
            binders.setdefault(name, set()).add(offset)

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


def _receiver_qualification(mask: str, offset: int) -> str:
    index = offset - 1
    while index >= 0 and mask[index].isspace():
        index -= 1
    if index < 0 or mask[index] != ".":
        return "bare"

    index -= 1
    while index >= 0 and mask[index].isspace():
        index -= 1
    if index < 3 or mask[index - 3 : index + 1] != "this":
        return "qualified"

    this_start = index - 3
    if this_start > 0 and (mask[this_start - 1].isalnum() or mask[this_start - 1] in "_$"):
        return "qualified"
    index = this_start - 1
    while index >= 0 and mask[index].isspace():
        index -= 1
    return "this" if index < 0 or mask[index] != "." else "qualified"


def _brace_pairs(mask: str) -> dict[int, int]:
    pairs: dict[int, int] = {}
    stack: list[int] = []
    for index, character in enumerate(mask):
        if character == "{":
            stack.append(index)
        elif character == "}" and stack:
            pairs[stack.pop()] = index
    return pairs


def _parenthesis_pairs(mask: str) -> dict[int, int]:
    pairs: dict[int, int] = {}
    stack: list[int] = []
    for index, character in enumerate(mask):
        if character == "(":
            stack.append(index)
        elif character == ")" and stack:
            pairs[stack.pop()] = index
    return pairs


def _mask_annotation_arguments(mask: str) -> str:
    result = list(mask)
    parenthesis_pairs = _parenthesis_pairs(mask)
    for annotation in ANNOTATION_ARGUMENTS.finditer(mask):
        opening = annotation.start("opening")
        closing = parenthesis_pairs.get(opening)
        if closing is None:
            continue
        for index in range(opening, closing + 1):
            if result[index] != "\n":
                result[index] = " "
    return "".join(result)


def _is_control_header_binder(
    mask: str, binder: int, parenthesis_pairs: dict[int, int]
) -> bool:
    """Return whether a declaration is inside a control-flow header.

    The scanner deliberately leaves these declarations for manual review. In
    particular, an unbraced for-loop variable stops being visible after the
    loop even though both locations have the same brace ancestry.
    """
    for opening, closing in parenthesis_pairs.items():
        if not opening < binder < closing:
            continue
        keyword = re.search(rf"(?P<keyword>{JAVA_IDENTIFIER})\s*$", mask[:opening])
        if keyword and keyword.group("keyword") in {
            "catch",
            "for",
            "if",
            "switch",
            "synchronized",
            "try",
            "while",
        }:
            return True
    return False


def _type_bodies(mask: str, brace_pairs: dict[int, int]) -> tuple[tuple[int, int], ...]:
    bodies: set[tuple[int, int]] = set()
    for declaration in TYPE_DECLARATION.finditer(mask):
        opening = mask.find("{", declaration.end())
        semicolon = mask.find(";", declaration.end())
        if opening < 0 or (semicolon >= 0 and semicolon < opening):
            continue
        closing = brace_pairs.get(opening)
        if closing is not None:
            bodies.add((opening, closing))

    for opening, closing in brace_pairs.items():
        previous = opening - 1
        while previous >= 0 and mask[previous].isspace():
            previous -= 1
        if previous < 0 or mask[previous] != ")":
            continue
        arguments_opening = _matching_open_parenthesis(mask, previous)
        if arguments_opening is None:
            continue
        boundary = max(
            mask.rfind("{", 0, arguments_opening),
            mask.rfind(";", 0, arguments_opening),
        )
        constructor_prefix = mask[boundary + 1 : arguments_opening]
        if re.search(r"\bnew\b[^{};()]*$", constructor_prefix):
            bodies.add((opening, closing))
    return tuple(sorted(bodies))


def _enclosing_type(
    type_bodies: tuple[tuple[int, int], ...], offset: int
) -> tuple[int, int] | None:
    containing = (body for body in type_bodies if body[0] < offset < body[1])
    return max(containing, key=lambda body: body[0], default=None)


def _brace_ancestry(brace_pairs: dict[int, int], offset: int) -> tuple[int, ...]:
    return tuple(
        opening
        for opening, closing in sorted(brace_pairs.items())
        if opening < offset < closing
    )


def _matching_open_parenthesis(mask: str, closing: int) -> int | None:
    depth = 0
    for index in range(closing, -1, -1):
        if mask[index] == ")":
            depth += 1
        elif mask[index] == "(":
            depth -= 1
            if depth == 0:
                return index
    return None


def _header_parameter_span(mask: str, body_opening: int) -> tuple[int, int] | None:
    boundary = max(mask.rfind("{", 0, body_opening), mask.rfind(";", 0, body_opening))
    closing = mask.rfind(")", boundary + 1, body_opening)
    if closing < 0:
        return None
    suffix = mask[closing + 1 : body_opening]
    if not re.fullmatch(r"\s*(?:(?:throws\s+[^{};]+)|->)?\s*", suffix):
        return None
    opening = _matching_open_parenthesis(mask, closing)
    if opening is None or opening <= boundary:
        return None
    return opening + 1, closing


def _is_expression_lambda_parameter(mask: str, binder: int) -> bool:
    arrow = mask.find("->", binder)
    if arrow < 0:
        return False
    boundaries = [
        offset
        for offset in (mask.find(";", binder), mask.find("{", binder))
        if offset >= 0
    ]
    return not boundaries or arrow < min(boundaries)


def _binder_visible_to_call(
    mask: str,
    binder: int,
    call: int,
    brace_pairs: dict[int, int],
    parenthesis_pairs: dict[int, int],
    type_bodies: tuple[tuple[int, int], ...],
) -> bool:
    if binder >= call:
        return False
    call_type = _enclosing_type(type_bodies, call)
    if call_type is None or _enclosing_type(type_bodies, binder) != call_type:
        return False

    call_ancestry = _brace_ancestry(brace_pairs, call)
    binder_ancestry = _brace_ancestry(brace_pairs, binder)
    type_depth = call_ancestry.index(call_type[0]) + 1

    if _is_control_header_binder(mask, binder, parenthesis_pairs):
        return False
    if _is_expression_lambda_parameter(mask, binder):
        return False

    header_bodies = []
    for body_opening in sorted(brace_pairs):
        span = _header_parameter_span(mask, body_opening)
        if span is not None and span[0] <= binder < span[1]:
            header_bodies.append(body_opening)
    if header_bodies:
        return any(body_opening in call_ancestry for body_opening in header_bodies)

    # Explicit local declarations in the same block or one of its lexical parents.
    if (
        len(binder_ancestry) > type_depth
        and call_ancestry[: len(binder_ancestry)] == binder_ancestry
    ):
        return True

    return False


def _field_binder_visible_to_call(
    mask: str,
    binder: int,
    call: int,
    brace_pairs: dict[int, int],
    type_bodies: tuple[tuple[int, int], ...],
) -> bool:
    call_type = _enclosing_type(type_bodies, call)
    if call_type is None or _enclosing_type(type_bodies, binder) != call_type:
        return False
    for body_opening in sorted(brace_pairs):
        span = _header_parameter_span(mask, body_opening)
        if span is not None and span[0] <= binder < span[1]:
            return False
    call_ancestry = _brace_ancestry(brace_pairs, call)
    binder_ancestry = _brace_ancestry(brace_pairs, binder)
    type_depth = call_ancestry.index(call_type[0]) + 1
    return (
        len(binder_ancestry) == type_depth
        and binder_ancestry == call_ancestry[:type_depth]
    )


def _contains_masked_syntax(text: str, mask: str, start: int, end: int) -> bool:
    return any(
        original != masked and not original.isspace()
        for original, masked in zip(text[start:end], mask[start:end])
    )


def _diagnostic_patterns(
    mask: str, *, sse_context: bool, ignored_generated_sse_offsets: set[int]
) -> Iterable[tuple[int, str, str]]:
    generated_method = (
        r"(?:(?:subscribeTo|suscribeTo)[A-Za-z0-9_]*SSE"
        r"|getV1EventsNta|getAccountActivityEvent)"
        r"(?:Call|WithHttpInfo|Async)?"
    )
    generated_sse = re.compile(
        rf"(?:\.\s*{generated_method}\s*\(|::\s*{generated_method}\b)"
    )
    for match in generated_sse.finditer(mask):
        if match.start() in ignored_generated_sse_offsets:
            continue
        yield (
            match.start(),
            "SSE006",
            "generated SSE method usage should be replaced with a handwritten SSE client",
        )
    unqualified_generated_sse = re.compile(rf"(?<![\w$]){generated_method}\s*\(")
    for match in unqualified_generated_sse.finditer(mask):
        if not _is_bare_receiver(mask, match.start()):
            continue
        yield (
            match.start(),
            "SSE006",
            "unqualified generated SSE method usage should be replaced with a handwritten "
            "SSE client",
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
            re.compile(
                rf"\(\s*(?:okhttp3\s*\.\s*sse\s*\.\s*)?EventSource\s*\)\s*"
                r"[^;{}]*?eventSource\s*\("
            ),
            "SSE002",
            "EventSource identity, assignment, or cast requires manual review",
        ),
        (
            re.compile(
                r"(?:eventSource\s*\([^;{}]*?(?:==|!=)"
                r"|(?:==|!=)[^;{}]*?eventSource\s*\()"
            ),
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
    type_parameter_mask = _mask_annotation_arguments(mask)
    has_exact_import = bool(EXACT_IMPORT.search(mask))
    has_shadowing_type = bool(
        BROKER_TYPE_DECLARATION.search(mask)
        or BROKER_TYPE_PARAMETER.search(type_parameter_mask)
    )
    has_exact_client_import = bool(EXACT_CLIENT_IMPORT.search(mask))
    has_shadowing_client_type = bool(
        BROKER_CLIENT_TYPE_DECLARATION.search(mask)
        or BROKER_CLIENT_TYPE_PARAMETER.search(type_parameter_mask)
    )
    broker_binders = _broker_binders(mask, has_exact_import=has_exact_import)
    broker_client_binders = _broker_client_binders(
        mask, has_exact_import=has_exact_client_import
    )
    all_binders = _all_binders(mask)
    brace_pairs = _brace_pairs(mask)
    parenthesis_pairs = _parenthesis_pairs(mask)
    type_bodies = _type_bodies(mask, brace_pairs)

    replacements: list[tuple[int, int, str]] = []
    findings: list[Finding] = []
    for call in CANCEL_CALL.finditer(mask):
        receiver = call.group(1)
        visible_broker_binders = [
            binder
            for binder in broker_binders.get(receiver, ())
            if _binder_visible_to_call(
                mask,
                binder,
                call.start(),
                brace_pairs,
                parenthesis_pairs,
                type_bodies,
            )
        ]
        visible_binders = [
            binder
            for binder in all_binders.get(receiver, ())
            if _binder_visible_to_call(
                mask,
                binder,
                call.start(),
                brace_pairs,
                parenthesis_pairs,
                type_bodies,
            )
        ]
        safe = (
            _is_bare_receiver(mask, call.start())
            and not has_shadowing_type
            and len(broker_binders.get(receiver, ())) == 1
            and len(all_binders.get(receiver, ())) == 1
            and len(visible_broker_binders) == 1
            and len(visible_binders) == 1
            and set(visible_broker_binders) == set(visible_binders)
            and not _contains_masked_syntax(text, mask, call.start(), call.end())
            and not JAVA_UNICODE_ESCAPE.search(text)
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

    ignored_generated_sse_offsets: set[int] = set()
    if not has_shadowing_client_type:
        for usage in HANDWRITTEN_SINGLE_EVENT_USAGE.finditer(mask):
            receiver = usage.group("receiver")
            qualification = _receiver_qualification(mask, usage.start("receiver"))
            visible_client_binders = [
                binder
                for binder in broker_client_binders.get(receiver, ())
                if _binder_visible_to_call(
                    mask,
                    binder,
                    usage.start(),
                    brace_pairs,
                    parenthesis_pairs,
                    type_bodies,
                )
                or _field_binder_visible_to_call(
                    mask, binder, usage.start(), brace_pairs, type_bodies
                )
            ]
            visible_binders = [
                binder
                for binder in all_binders.get(receiver, ())
                if _binder_visible_to_call(
                    mask,
                    binder,
                    usage.start(),
                    brace_pairs,
                    parenthesis_pairs,
                    type_bodies,
                )
                or _field_binder_visible_to_call(
                    mask, binder, usage.start(), brace_pairs, type_bodies
                )
            ]
            visible_client_field_binders = [
                binder
                for binder in broker_client_binders.get(receiver, ())
                if _field_binder_visible_to_call(
                    mask, binder, usage.start(), brace_pairs, type_bodies
                )
            ]
            visible_field_binders = [
                binder
                for binder in all_binders.get(receiver, ())
                if _field_binder_visible_to_call(
                    mask, binder, usage.start(), brace_pairs, type_bodies
                )
            ]
            bare_receiver_is_handwritten = (
                qualification == "bare"
                and len(broker_client_binders.get(receiver, ())) == 1
                and len(visible_client_binders) == 1
                and (
                    (
                        len(all_binders.get(receiver, ())) == 1
                        and len(visible_binders) == 1
                        and set(visible_client_binders) == set(visible_binders)
                    )
                    or (
                        receiver not in all_binders
                        and _field_binder_visible_to_call(
                            mask,
                            visible_client_binders[0],
                            usage.start(),
                            brace_pairs,
                            type_bodies,
                        )
                    )
                )
            )
            this_receiver_is_handwritten = (
                qualification == "this"
                and len(visible_client_field_binders) == 1
                and len(visible_field_binders) == 1
                and set(visible_client_field_binders) == set(visible_field_binders)
            )
            if (
                bare_receiver_is_handwritten or this_receiver_is_handwritten
            ):
                ignored_generated_sse_offsets.add(usage.start("separator"))

    sse_context = "BrokerSseEventListener" in mask or "eventSource" in mask
    for offset, code, message in _diagnostic_patterns(
        mask,
        sse_context=sse_context,
        ignored_generated_sse_offsets=ignored_generated_sse_offsets,
    ):
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
                if not {".git", ".gradle", "build"}.intersection(
                    candidate.relative_to(path).parts
                )
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
    invalid_paths = [
        path
        for path in arguments.paths
        if not path.exists() or (path.is_file() and path.suffix != ".java")
    ]
    if invalid_paths:
        for path in invalid_paths:
            print(f"error: input path is missing or is not Java source: {path}", file=sys.stderr)
        return 2

    findings: list[Finding] = []
    for path in _java_files(arguments.paths):
        with path.open("r", encoding="utf-8", newline="") as source:
            original = source.read()
        analysis = analyze_text(original, str(path))
        findings.extend(analysis.findings)
        if arguments.write and analysis.text != original:
            with path.open("w", encoding="utf-8", newline="") as destination:
                destination.write(analysis.text)

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
