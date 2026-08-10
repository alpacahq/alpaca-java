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
    operations_extended: list[str] = field(default_factory=list)
    operations_renamed: list[str] = field(default_factory=list)
    operations_moved: list[str] = field(default_factory=list)
    schemas_added: list[str] = field(default_factory=list)
    schemas_removed: list[str] = field(default_factory=list)
    schemas_modified: list[str] = field(default_factory=list)
    schemas_extended: list[str] = field(default_factory=list)
    enum_values_added: list[str] = field(default_factory=list)
    enum_values_removed: list[str] = field(default_factory=list)
    # Keyed by "operation <key>" / "schema <name>"; explains why an entry was classified.
    change_details: dict[str, str] = field(default_factory=dict)

    def is_empty(self) -> bool:
        return not any(
            [
                self.operations_added,
                self.operations_removed,
                self.operations_modified,
                self.operations_extended,
                self.operations_renamed,
                self.operations_moved,
                self.schemas_added,
                self.schemas_removed,
                self.schemas_modified,
                self.schemas_extended,
                self.enum_values_added,
                self.enum_values_removed,
            ]
        )

    def is_breaking(self) -> bool:
        # operations_modified / schemas_modified hold only non-additive changes;
        # additive ones land in operations_extended / schemas_extended.
        return bool(
            self.operations_removed
            or self.operations_renamed
            or self.operations_modified
            or self.operations_moved
            or self.schemas_removed
            or self.schemas_modified
            or self.enum_values_removed
        )

    def detail(self, kind: str, name: str) -> str | None:
        return self.change_details.get(f"{kind} {name}")


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


# Keywords that only feed generated Javadoc, never Java types or method signatures.
_DOC_KEYS = frozenset({"description", "summary", "example", "examples", "externalDocs"})

# Operation fields ignored when comparing generated client surface.
_IGNORABLE_OPERATION_KEYS = frozenset({"tags", "x-codegen-request-body-name", "security"})

# Maps whose keys are author-chosen names (property names, status codes, media types)
# rather than OpenAPI keywords. Their keys must survive doc stripping — a schema may
# legitimately declare a property called "description".
_NAME_KEYED_MAPS = frozenset(
    {
        "properties",
        "patternProperties",
        "responses",
        "content",
        "headers",
        "encoding",
        "schemas",
        "requestBodies",
        "variables",
        "links",
        "callbacks",
        "securitySchemes",
    }
)


def _strip_docs(node: Any, *, name_keyed: bool = False) -> Any:
    """Drop documentation-only keywords so fingerprints reflect generated surface."""
    if isinstance(node, dict):
        if name_keyed:
            return {key: _strip_docs(value) for key, value in node.items()}
        return {
            key: _strip_docs(value, name_keyed=key in _NAME_KEYED_MAPS)
            for key, value in node.items()
            if key not in _DOC_KEYS
        }
    if isinstance(node, list):
        return [_strip_docs(item) for item in node]
    return node


# Media types the generator maps to a binary payload, so `contentMediaType` there is
# just another spelling of `format: binary`. Textual media types (text/csv, …) still
# generate a String and must stay a real difference.
_BINARY_MEDIA_TYPES = frozenset({"application/octet-stream", "application/pdf"})
_BINARY_MEDIA_PREFIXES = ("image/",)


def _is_binary_media_type(value: Any) -> bool:
    if not isinstance(value, str):
        return False
    media_type = value.split(";", 1)[0].strip().lower()
    return media_type in _BINARY_MEDIA_TYPES or media_type.startswith(_BINARY_MEDIA_PREFIXES)


def _normalize_equivalences(node: Any) -> Any:
    """Canonicalize OAS spellings that do not change the generated Java surface."""
    if isinstance(node, dict):
        normalized = {key: _normalize_equivalences(value) for key, value in node.items()}
        type_value = normalized.get("type")
        if (
            isinstance(type_value, list)
            and len(type_value) == 2
            and "null" in type_value
        ):
            non_null = [item for item in type_value if item != "null"]
            if len(non_null) == 1:
                normalized["type"] = non_null[0]
                normalized["nullable"] = True
        if normalized.get("type") == "string" and (
            normalized.get("format") == "binary"
            or _is_binary_media_type(normalized.get("contentMediaType"))
        ):
            normalized["format"] = "binary"
            normalized.pop("contentMediaType", None)
        return normalized
    if isinstance(node, list):
        return [_normalize_equivalences(item) for item in node]
    return node


def _fold_added_enum_values(old_node: Any, new_node: Any) -> tuple[Any, bool]:
    """Fold widened ``new_node`` enums back to ``old_node``, reporting whether any value was added."""
    if isinstance(old_node, dict) and isinstance(new_node, dict):
        folded: dict[str, Any] = {}
        widened = False
        for key, new_value in new_node.items():
            old_value = old_node.get(key)
            if key == "enum" and isinstance(old_value, list) and isinstance(new_value, list):
                old_values = {str(item) for item in old_value}
                new_values = {str(item) for item in new_value}
                if old_values <= new_values:
                    folded[key] = old_value
                    widened = widened or old_values < new_values
                    continue
            if (
                key in _COMPOSITION_KEYS
                and isinstance(old_value, list)
                and isinstance(new_value, list)
            ):
                # Composition-member alignment is semantic (matched by fingerprint/
                # widening, not position) and is the job of
                # _fold_added_composition_members/_match_composition_members, run
                # after this pass. Recursing positionally here for equal-length
                # lists can permanently mis-pair members (e.g. folding new[0] to
                # match old[0] steals the only valid widen candidate for old[1]),
                # so leave composition lists untouched for the later, smarter pass.
                folded[key] = new_value
                continue
            folded[key], nested_widened = _fold_added_enum_values(old_value, new_value)
            widened = widened or nested_widened
        return folded, widened
    if (
        isinstance(old_node, list)
        and isinstance(new_node, list)
        and len(old_node) == len(new_node)
    ):
        folded_items = []
        widened = False
        for old_item, new_item in zip(old_node, new_node, strict=True):
            folded_item, item_widened = _fold_added_enum_values(old_item, new_item)
            folded_items.append(folded_item)
            widened = widened or item_widened
        return folded_items, widened
    return new_node, False


# Union compositions only: adding a member widens the accepted set. ``allOf`` is an
# intersection — a new member can introduce required/retyped fields — so it is never
# folded as additive here (equal-length ``allOf`` lists still recurse pairwise so a
# nested ``oneOf`` inside ``allOf: [ { oneOf: [...] } ]`` can widen).
_COMPOSITION_KEYS = frozenset({"oneOf", "anyOf"})


def _member_pair_widen(old_item: Any, new_item: Any) -> tuple[bool, bool, bool]:
    """Check whether ``new_item`` is an additive widening of ``old_item``.

    Tries, in order: exact fingerprint equality, enum-only widening, and (mirroring
    the top-level classify flow) enum widening followed by a recursive composition-
    member fold — so a nested ``oneOf``/``anyOf`` inside this member can itself gain
    members (or be reordered) and the outer member still counts as a match. Returns
    ``(matched, enum_widened, composition_widened)``.
    """
    old_fp = _schema_fingerprint(old_item)
    if _schema_fingerprint(new_item) == old_fp:
        return True, False, False
    enum_folded, enum_widened = _fold_added_enum_values(old_item, new_item)
    if _schema_fingerprint(enum_folded) == old_fp:
        return True, enum_widened, False
    comp_folded, comp_widened, comp_enum_widened = _fold_added_composition_members(
        old_item, enum_folded
    )
    if _schema_fingerprint(comp_folded) == old_fp:
        return True, enum_widened or comp_enum_widened, comp_widened
    return False, False, False


def _match_composition_members(
    old_members: list[Any], new_members: list[Any]
) -> tuple[list[Any], bool, bool, list[tuple[int, int]]] | None:
    """Match each ``old_members`` entry to a distinct ``new_members`` entry.

    Uses multiset (consume-once) matching so duplicate members are never matched
    away for free: ``[A, A] -> [A]`` fails to match because the second ``A`` has
    no remaining candidate, even though the fingerprint *set* of ``[A]`` contains
    everything in the fingerprint set of ``[A, A]``.

    Matching happens in two passes:

    1. Exact fingerprint matches are assigned first (unambiguous and
       order-independent).
    2. Remaining old members are assigned via additive widening (see
       ``_member_pair_widen``), resolving the *most-constrained* old member
       (fewest remaining compatible candidates) first each round. Greedily
       resolving in original order can starve a more-constrained member: old
       ``[enum(x), enum(x, y)]`` vs new ``[enum(x, y, z), enum(x, z)]`` only
       matches if ``enum(x, y)`` (whose only candidate is ``enum(x, y, z)``) is
       resolved before ``enum(x)`` (which is compatible with both candidates).

    Returns ``(extra_new_members, enum_widened, composition_widened, pairs)`` when
    every old member found a match, or ``None`` when some old member has no
    match (i.e. the list did not purely widen and must be left as a genuine
    diff). ``pairs`` maps each old index to its matched new index.
    """
    remaining = list(range(len(new_members)))
    unmatched: list[tuple[int, Any]] = []
    pairs: list[tuple[int, int]] = []
    for old_idx, old_item in enumerate(old_members):
        old_fp = _schema_fingerprint(old_item)
        exact_idx = next(
            (idx for idx in remaining if _schema_fingerprint(new_members[idx]) == old_fp),
            None,
        )
        if exact_idx is not None:
            remaining.remove(exact_idx)
            pairs.append((old_idx, exact_idx))
        else:
            unmatched.append((old_idx, old_item))

    enum_widened = False
    composition_widened = False
    while unmatched:
        best_pos = None
        best_candidates: list[tuple[int, bool, bool]] = []
        for pos, (_, old_item) in enumerate(unmatched):
            candidates = []
            for idx in remaining:
                matched, item_enum_widened, item_comp_widened = _member_pair_widen(
                    old_item, new_members[idx]
                )
                if matched:
                    candidates.append((idx, item_enum_widened, item_comp_widened))
            if best_pos is None or len(candidates) < len(best_candidates):
                best_pos, best_candidates = pos, candidates
                if len(candidates) <= 1:
                    break
        old_idx, _ = unmatched.pop(best_pos)
        if not best_candidates:
            return None
        chosen_idx, item_enum_widened, item_comp_widened = best_candidates[0]
        remaining.remove(chosen_idx)
        pairs.append((old_idx, chosen_idx))
        enum_widened = enum_widened or item_enum_widened
        composition_widened = composition_widened or item_comp_widened

    return [new_members[idx] for idx in remaining], enum_widened, composition_widened, pairs


def _fold_added_composition_members(old_node: Any, new_node: Any) -> tuple[Any, bool, bool]:
    """Fold widened ``new_node`` oneOf/anyOf lists back to ``old_node``.

    A composition list is folded only when every old member (counted with
    multiplicity) matches a distinct new member, so removals, duplicate removals,
    and replacements are never treated as additive. ``allOf`` lists are not folded
    (intersection growth is breaking); equal-length ``allOf`` still recurses into
    each member so nested unions can widen. Returns
    ``(folded_node, composition_members_added, enum_values_added)`` — the latter
    reports enum values folded inside a *kept* composition member, so a combined
    "member widened its enum" + "list grew a member" change is still classified
    as additive/extended rather than losing the enum widen.
    """
    if isinstance(old_node, dict) and isinstance(new_node, dict):
        folded: dict[str, Any] = {}
        composition_widened = False
        enum_widened = False
        for key, new_value in new_node.items():
            old_value = old_node.get(key)
            if (
                key in _COMPOSITION_KEYS
                and isinstance(old_value, list)
                and isinstance(new_value, list)
            ):
                match = _match_composition_members(old_value, new_value)
                if match is not None:
                    extra_members, member_enum_widened, member_comp_widened, _ = match
                    folded[key] = old_value
                    composition_widened = (
                        composition_widened or bool(extra_members) or member_comp_widened
                    )
                    enum_widened = enum_widened or member_enum_widened
                    continue
            folded[key], nested_widened, nested_enum_widened = _fold_added_composition_members(
                old_value, new_value
            )
            composition_widened = composition_widened or nested_widened
            enum_widened = enum_widened or nested_enum_widened
        return folded, composition_widened, enum_widened
    if (
        isinstance(old_node, list)
        and isinstance(new_node, list)
        and len(old_node) == len(new_node)
    ):
        items = []
        composition_widened = False
        enum_widened = False
        for old_item, new_item in zip(old_node, new_node, strict=True):
            folded_item, item_widened, item_enum_widened = _fold_added_composition_members(
                old_item, new_item
            )
            items.append(folded_item)
            composition_widened = composition_widened or item_widened
            enum_widened = enum_widened or item_enum_widened
        return items, composition_widened, enum_widened
    return new_node, False, False


def _comparable_operation(operation: dict[str, Any]) -> dict[str, Any]:
    return _strip_docs(
        {k: v for k, v in operation.items() if k not in _IGNORABLE_OPERATION_KEYS}
    )


def _mapping(node: Any, key: str) -> dict[str, Any]:
    value = node.get(key) if isinstance(node, dict) else None
    return value if isinstance(value, dict) else {}


def _required(schema: Any) -> set[str]:
    value = schema.get("required") if isinstance(schema, dict) else None
    return {str(item) for item in value} if isinstance(value, list) else set()


def _parameter_map(operation: dict[str, Any]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    parameters = operation.get("parameters")
    if not isinstance(parameters, list):
        return result
    for parameter in parameters:
        if isinstance(parameter, dict):
            result[f"{parameter.get('in')}:{parameter.get('name')}"] = parameter
    return result


def _diff_keys(old: dict[str, Any], new: dict[str, Any]) -> tuple[list[str], list[str], list[str]]:
    removed = sorted(set(old) - set(new))
    added = sorted(set(new) - set(old))
    changed = sorted(
        name
        for name in set(old) & set(new)
        if _schema_fingerprint(old[name]) != _schema_fingerprint(new[name])
    )
    return removed, added, changed


def _classify_schema_change(old_schema: Any, new_schema: Any) -> tuple[bool, str] | None:
    """Classify a component schema change as (breaking, detail), or None when identical."""
    old_bare = _normalize_equivalences(_strip_docs(old_schema))
    new_bare = _normalize_equivalences(_strip_docs(new_schema))
    if _schema_fingerprint(old_bare) == _schema_fingerprint(new_bare):
        old_stripped = _strip_docs(old_schema)
        new_stripped = _strip_docs(new_schema)
        if _schema_fingerprint(old_stripped) != _schema_fingerprint(new_stripped):
            return None
        if _schema_fingerprint(old_schema) == _schema_fingerprint(new_schema):
            return None
        return False, "documentation only"

    # A widened enum keeps every value callers already compile against, so classify
    # against a schema whose added enum values are folded back to the pinned ones.
    # The additions are still reported under enum values added.
    new_surface, enum_values_added = _fold_added_enum_values(old_bare, new_bare)
    if _schema_fingerprint(old_bare) == _schema_fingerprint(new_surface):
        return False, "added enum values" if enum_values_added else "enum values reordered"

    new_surface, composition_members_added, composition_enum_widened = (
        _fold_added_composition_members(old_bare, new_surface)
    )
    enum_values_added = enum_values_added or composition_enum_widened
    if _schema_fingerprint(old_bare) == _schema_fingerprint(new_surface):
        fold_parts: list[str] = []
        if enum_values_added:
            fold_parts.append("added enum values")
        if composition_members_added:
            fold_parts.append("added composition members")
        return False, "; ".join(fold_parts) or "composition members reordered"

    removed, added, changed = _diff_keys(
        _mapping(old_bare, "properties"), _mapping(new_surface, "properties")
    )
    newly_required = sorted(_required(new_surface) - _required(old_bare))
    no_longer_required = sorted(_required(old_bare) - _required(new_surface))
    old_rest = {k: v for k, v in old_bare.items() if k != "properties"}
    new_rest = {k: v for k, v in new_surface.items() if k != "properties"}
    keywords_changed = _schema_fingerprint(old_rest) != _schema_fingerprint(new_rest)

    parts: list[str] = []
    if removed:
        parts.append("removed properties: " + ", ".join(removed))
    if changed:
        parts.append("changed properties: " + ", ".join(changed))
    if newly_required:
        parts.append("newly required: " + ", ".join(newly_required))
    if no_longer_required:
        parts.append("no longer required: " + ", ".join(no_longer_required))
    if keywords_changed and not (newly_required or no_longer_required):
        parts.append("schema keywords changed")
    if added:
        parts.append("added properties: " + ", ".join(added))
    if enum_values_added:
        parts.append("added enum values")
    if composition_members_added:
        parts.append("added composition members")

    breaking = bool(removed or changed or keywords_changed)
    return breaking, "; ".join(parts) or "schema changed"


def _classify_operation_change(
    old_op: dict[str, Any], new_op: dict[str, Any]
) -> tuple[bool, str] | None:
    """Classify an operation change as (breaking, detail), or None when identical."""
    old_bare = _normalize_equivalences(_comparable_operation(old_op))
    new_bare = _normalize_equivalences(_comparable_operation(new_op))
    if _schema_fingerprint(old_bare) == _schema_fingerprint(new_bare):
        old_comparable = _comparable_operation(old_op)
        new_comparable = _comparable_operation(new_op)
        if _schema_fingerprint(old_comparable) != _schema_fingerprint(new_comparable):
            return None
        old_without_ignorable = {
            k: v for k, v in old_op.items() if k not in _IGNORABLE_OPERATION_KEYS
        }
        new_without_ignorable = {
            k: v for k, v in new_op.items() if k not in _IGNORABLE_OPERATION_KEYS
        }
        if _schema_fingerprint(old_without_ignorable) == _schema_fingerprint(
            new_without_ignorable
        ):
            return None
        return False, "documentation only"

    new_surface, enum_values_added = _fold_added_enum_values(old_bare, new_bare)
    if _schema_fingerprint(old_bare) == _schema_fingerprint(new_surface):
        return False, "added enum values" if enum_values_added else "enum values reordered"

    new_surface, composition_members_added, composition_enum_widened = (
        _fold_added_composition_members(old_bare, new_surface)
    )
    enum_values_added = enum_values_added or composition_enum_widened
    if _schema_fingerprint(old_bare) == _schema_fingerprint(new_surface):
        fold_parts: list[str] = []
        if enum_values_added:
            fold_parts.append("added enum values")
        if composition_members_added:
            fold_parts.append("added composition members")
        return False, "; ".join(fold_parts) or "composition members reordered"

    parts: list[str] = []
    removed, added, changed = _diff_keys(
        _parameter_map(old_bare), _parameter_map(new_surface)
    )
    if removed:
        parts.append("removed parameters: " + ", ".join(removed))
    # A new parameter is breaking here: the Java generator widens every overload's
    # signature, so existing call sites stop compiling.
    if added:
        parts.append("added parameters: " + ", ".join(added))
    if changed:
        parts.append("changed parameters: " + ", ".join(changed))
    if _schema_fingerprint(old_bare.get("requestBody")) != _schema_fingerprint(
        new_surface.get("requestBody")
    ):
        parts.append("request body changed")
    resp_removed, resp_added, resp_changed = _diff_keys(
        _mapping(old_bare, "responses"), _mapping(new_surface, "responses")
    )
    if resp_removed:
        parts.append("removed responses: " + ", ".join(resp_removed))
    if resp_added:
        parts.append("added responses: " + ", ".join(resp_added))
    if resp_changed:
        parts.append("changed responses: " + ", ".join(resp_changed))
    if enum_values_added:
        parts.append("added enum values")
    if composition_members_added:
        parts.append("added composition members")

    # An unknown residual delta after the known-additive folds leaves `parts` empty;
    # stay breaking rather than treat an unrecognized change as additive.
    return True, "; ".join(parts) or "operation definition changed"


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


def _collect_enums_paired(
    old_schema: Any,
    new_schema: Any,
    path: str,
    old_out: dict[str, set[str]],
    new_out: dict[str, set[str]],
) -> None:
    """Collect enum values from paired schemas, aligning composition members semantically."""
    if old_schema is None:
        _collect_enums(new_schema, path, new_out)
        return
    if new_schema is None:
        _collect_enums(old_schema, path, old_out)
        return
    if isinstance(old_schema, dict) and isinstance(new_schema, dict):
        old_enum = old_schema.get("enum")
        new_enum = new_schema.get("enum")
        if isinstance(old_enum, list):
            old_out[path] = {str(v) for v in old_enum}
        if isinstance(new_enum, list):
            new_out[path] = {str(v) for v in new_enum}
        for key in set(old_schema) | set(new_schema):
            old_value = old_schema.get(key)
            new_value = new_schema.get(key)
            if key == "properties" and isinstance(old_value, dict) and isinstance(new_value, dict):
                for prop in set(old_value) | set(new_value):
                    _collect_enums_paired(
                        old_value.get(prop),
                        new_value.get(prop),
                        f"{path}.{prop}",
                        old_out,
                        new_out,
                    )
            elif key in {"items", "additionalProperties"}:
                _collect_enums_paired(old_value, new_value, f"{path}.{key}", old_out, new_out)
            elif (
                key in _COMPOSITION_KEYS
                and isinstance(old_value, list)
                and isinstance(new_value, list)
            ):
                match = _match_composition_members(old_value, new_value)
                if match is not None:
                    _, _, _, pairs = match
                    paired_new = {new_idx for _, new_idx in pairs}
                    for old_idx, new_idx in pairs:
                        _collect_enums_paired(
                            old_value[old_idx],
                            new_value[new_idx],
                            f"{path}.{key}[{old_idx}]",
                            old_out,
                            new_out,
                        )
                    for new_idx, item in enumerate(new_value):
                        if new_idx not in paired_new:
                            _collect_enums(item, f"{path}.{key}[+{new_idx}]", new_out)
                else:
                    for index, part in enumerate(old_value):
                        _collect_enums(part, f"{path}.{key}[{index}]", old_out)
                    for index, part in enumerate(new_value):
                        _collect_enums(part, f"{path}.{key}[{index}]", new_out)
            else:
                if key in old_schema:
                    _collect_enums(old_value, f"{path}.{key}", old_out)
                if key in new_schema:
                    _collect_enums(new_value, f"{path}.{key}", new_out)
    elif (
        isinstance(old_schema, list)
        and isinstance(new_schema, list)
        and len(old_schema) == len(new_schema)
    ):
        for index, (old_item, new_item) in enumerate(
            zip(old_schema, new_schema, strict=True)
        ):
            _collect_enums_paired(old_item, new_item, f"{path}[{index}]", old_out, new_out)
    else:
        _collect_enums(old_schema, path, old_out)
        _collect_enums(new_schema, path, new_out)


def _collect_content_enums(content: Any, path: str, out: dict[str, set[str]]) -> None:
    if not isinstance(content, dict):
        return
    for media_type, media in content.items():
        if isinstance(media, dict):
            _collect_enums(media.get("schema"), f"{path} {media_type}", out)


def _collect_content_enums_paired(
    old_content: Any,
    new_content: Any,
    path: str,
    old_out: dict[str, set[str]],
    new_out: dict[str, set[str]],
) -> None:
    """Semantic-pairing counterpart of ``_collect_content_enums``."""
    old_content = old_content if isinstance(old_content, dict) else {}
    new_content = new_content if isinstance(new_content, dict) else {}
    for media_type in sorted(set(old_content) | set(new_content)):
        old_media = old_content.get(media_type)
        new_media = new_content.get(media_type)
        media_path = f"{path} {media_type}"
        old_schema = old_media.get("schema") if isinstance(old_media, dict) else None
        new_schema = new_media.get("schema") if isinstance(new_media, dict) else None
        _collect_enums_paired(old_schema, new_schema, media_path, old_out, new_out)


def _collect_operation_enums_paired(
    old_operation: dict[str, Any],
    new_operation: dict[str, Any],
    path: str,
    old_out: dict[str, set[str]],
    new_out: dict[str, set[str]],
) -> None:
    """Semantic-pairing counterpart of ``_collect_operation_enums``.

    Aligns each parameter/response/media-type by key (as before, since those
    are already matched by identity) but walks their schemas with
    ``_collect_enums_paired`` so a reordered/widened composition inside a
    parameter, request body, or response schema doesn't report false
    ``enum_values_removed`` the way positional ``_collect_enums`` would.
    """
    old_params = _parameter_map(old_operation)
    new_params = _parameter_map(new_operation)
    for key in sorted(set(old_params) | set(new_params)):
        old_parameter = old_params.get(key) or {}
        new_parameter = new_params.get(key) or {}
        parameter_path = f"{path} parameter {key}"
        _collect_enums_paired(
            old_parameter.get("schema"), new_parameter.get("schema"), parameter_path, old_out, new_out
        )
        _collect_content_enums_paired(
            old_parameter.get("content"), new_parameter.get("content"), parameter_path, old_out, new_out
        )

    old_request_body = _mapping(old_operation, "requestBody")
    new_request_body = _mapping(new_operation, "requestBody")
    _collect_content_enums_paired(
        old_request_body.get("content"),
        new_request_body.get("content"),
        f"{path} request body",
        old_out,
        new_out,
    )

    old_responses = _mapping(old_operation, "responses")
    new_responses = _mapping(new_operation, "responses")
    for status in sorted(set(old_responses) | set(new_responses)):
        old_response = old_responses.get(status) or {}
        new_response = new_responses.get(status) or {}
        _collect_content_enums_paired(
            old_response.get("content") if isinstance(old_response, dict) else None,
            new_response.get("content") if isinstance(new_response, dict) else None,
            f"{path} response {status}",
            old_out,
            new_out,
        )


def _collect_operation_enums(
    operation: dict[str, Any], path: str, out: dict[str, set[str]]
) -> None:
    parameters = operation.get("parameters")
    if isinstance(parameters, list):
        for parameter in parameters:
            if not isinstance(parameter, dict):
                continue
            parameter_path = (
                f"{path} parameter {parameter.get('in')}:{parameter.get('name')}"
            )
            _collect_enums(parameter.get("schema"), parameter_path, out)
            _collect_content_enums(parameter.get("content"), parameter_path, out)

    request_body = operation.get("requestBody")
    if isinstance(request_body, dict):
        _collect_content_enums(
            request_body.get("content"), f"{path} request body", out
        )

    responses = operation.get("responses")
    if isinstance(responses, dict):
        for status, response in responses.items():
            if isinstance(response, dict):
                _collect_content_enums(
                    response.get("content"), f"{path} response {status}", out
                )


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
        classified = _classify_operation_change(old_op, new_op)
        if classified is not None:
            breaking, detail = classified
            target = diff.operations_modified if breaking else diff.operations_extended
            target.append(key)
            diff.change_details[f"operation {key}"] = detail

    old_schemas = _schemas(old)
    new_schemas = _schemas(new)
    old_schema_keys = set(old_schemas)
    new_schema_keys = set(new_schemas)
    diff.schemas_added = sorted(new_schema_keys - old_schema_keys)
    diff.schemas_removed = sorted(old_schema_keys - new_schema_keys)
    for name in sorted(old_schema_keys & new_schema_keys):
        classified = _classify_schema_change(old_schemas[name], new_schemas[name])
        if classified is not None:
            breaking, detail = classified
            target = diff.schemas_modified if breaking else diff.schemas_extended
            target.append(name)
            diff.change_details[f"schema {name}"] = detail

    old_enums: dict[str, set[str]] = {}
    new_enums: dict[str, set[str]] = {}
    for name in sorted(old_schema_keys & new_schema_keys):
        _collect_enums_paired(old_schemas[name], new_schemas[name], name, old_enums, new_enums)
    for name in sorted(old_schema_keys - new_schema_keys):
        _collect_enums(old_schemas[name], name, old_enums)
    for name in sorted(new_schema_keys - old_schema_keys):
        _collect_enums(new_schemas[name], name, new_enums)
    for key in sorted(old_keys & new_keys):
        _collect_operation_enums_paired(old_ops[key], new_ops[key], key, old_enums, new_enums)
    for key in sorted(old_keys - new_keys):
        _collect_operation_enums(old_ops[key], key, old_enums)
    for key in sorted(new_keys - old_keys):
        _collect_operation_enums(new_ops[key], key, new_enums)
    for path in sorted(set(old_enums) | set(new_enums)):
        before = old_enums.get(path, set())
        after = new_enums.get(path, set())
        for value in sorted(after - before):
            diff.enum_values_added.append(f"{path}: {value}")
        for value in sorted(before - after):
            diff.enum_values_removed.append(f"{path}: {value}")

    return diff


def _section(
    title: str,
    items: list[str],
    *,
    diff: DiffResult | None = None,
    kind: str | None = None,
) -> list[str]:
    if not items:
        return []
    lines = [f"### {title}", ""]
    for item in items:
        detail = diff.detail(kind, item) if diff is not None and kind else None
        lines.append(f"- `{item}`" + (f" — {detail}" if detail else ""))
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
    lines.extend(
        _section("Operations modified", diff.operations_modified, diff=diff, kind="operation")
    )
    lines.extend(
        _section(
            "Operations extended (additive)",
            diff.operations_extended,
            diff=diff,
            kind="operation",
        )
    )
    lines.extend(_section("Operations renamed", diff.operations_renamed))
    lines.extend(_section("Operations moved", diff.operations_moved))
    lines.extend(_section("Schemas added", diff.schemas_added))
    lines.extend(_section("Schemas removed", diff.schemas_removed))
    lines.extend(_section("Schemas modified", diff.schemas_modified, diff=diff, kind="schema"))
    lines.extend(
        _section("Schemas extended (additive)", diff.schemas_extended, diff=diff, kind="schema")
    )
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

    def annotate(kind: str, name: str) -> str:
        detail = diff.detail(kind, name)
        return f" ({detail})" if detail else ""

    breaking.extend(f"Remove operation `{item}`" for item in diff.operations_removed)
    breaking.extend(f"Rename operation `{item}`" for item in diff.operations_renamed)
    breaking.extend(
        f"Modify operation `{item}`" + annotate("operation", item)
        for item in diff.operations_modified
    )
    breaking.extend(f"Move operation `{item}`" for item in diff.operations_moved)
    breaking.extend(f"Remove schema `{item}`" for item in diff.schemas_removed)
    breaking.extend(
        f"Modify schema `{item}`" + annotate("schema", item) for item in diff.schemas_modified
    )
    breaking.extend(f"Remove enum value `{item}`" for item in diff.enum_values_removed)

    added.extend(f"Add operation `{item}`" for item in diff.operations_added)
    added.extend(f"Add schema `{item}`" for item in diff.schemas_added)
    added.extend(f"Add enum value `{item}`" for item in diff.enum_values_added)
    added.extend(
        f"Extend operation `{item}`" + annotate("operation", item)
        for item in diff.operations_extended
    )
    added.extend(
        f"Extend schema `{item}`" + annotate("schema", item) for item in diff.schemas_extended
    )

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
        merged.operations_extended.extend(diff.operations_extended)
        merged.operations_renamed.extend(diff.operations_renamed)
        merged.operations_moved.extend(diff.operations_moved)
        merged.schemas_added.extend(diff.schemas_added)
        merged.schemas_removed.extend(diff.schemas_removed)
        merged.schemas_modified.extend(diff.schemas_modified)
        merged.schemas_extended.extend(diff.schemas_extended)
        merged.enum_values_added.extend(diff.enum_values_added)
        merged.enum_values_removed.extend(diff.enum_values_removed)
        merged.change_details.update(diff.change_details)
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
