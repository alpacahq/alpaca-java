#!/usr/bin/env python3
"""Tests for scripts/openapi_tools.py."""

from __future__ import annotations

import unittest

from scripts import openapi_tools


def _spec(*, paths=None, schemas=None):
    return {
        "openapi": "3.0.3",
        "info": {"title": "t", "version": "1"},
        "paths": paths or {},
        "components": {"schemas": schemas or {}},
    }


class SemanticDiffTests(unittest.TestCase):
    def test_added_and_removed_operations(self):
        old = _spec(
            paths={
                "/v2/clock": {
                    "get": {
                        "operationId": "getClock",
                        "tags": ["Clock"],
                        "responses": {"200": {}},
                    }
                }
            }
        )
        new = _spec(
            paths={
                "/v2/assets": {
                    "get": {
                        "operationId": "getAssets",
                        "tags": ["Assets"],
                        "responses": {"200": {}},
                    }
                }
            }
        )
        diff = openapi_tools.semantic_diff(old, new)
        self.assertEqual(diff.operations_added, ["GET /v2/assets"])
        self.assertEqual(diff.operations_removed, ["GET /v2/clock"])
        self.assertTrue(diff.is_breaking())

    def test_renamed_operation_by_operation_id(self):
        old = _spec(
            paths={
                "/v2/clock": {
                    "get": {
                        "operationId": "getClock",
                        "tags": ["Clock"],
                        "responses": {"200": {}},
                    }
                }
            }
        )
        new = _spec(
            paths={
                "/v3/clock": {
                    "get": {
                        "operationId": "getClock",
                        "tags": ["Clock"],
                        "responses": {"200": {}},
                    }
                }
            }
        )
        diff = openapi_tools.semantic_diff(old, new)
        self.assertEqual(len(diff.operations_renamed), 1)
        self.assertIn("GET /v2/clock -> GET /v3/clock", diff.operations_renamed[0])
        self.assertEqual(diff.operations_added, [])
        self.assertEqual(diff.operations_removed, [])
        self.assertTrue(diff.is_breaking())

    def test_moved_operation_tag_change(self):
        old = _spec(
            paths={
                "/v2/clock": {
                    "get": {
                        "operationId": "getClock",
                        "tags": ["Calendar"],
                        "parameters": [{"name": "x", "in": "query"}],
                        "responses": {"200": {}},
                    }
                }
            }
        )
        new = _spec(
            paths={
                "/v2/clock": {
                    "get": {
                        "operationId": "getClock",
                        "tags": ["Clock"],
                        "parameters": [{"name": "x", "in": "query"}],
                        "responses": {"200": {}},
                    }
                }
            }
        )
        diff = openapi_tools.semantic_diff(old, new)
        self.assertEqual(
            diff.operations_moved, ["GET /v2/clock: tag 'Calendar' -> 'Clock'"]
        )
        self.assertTrue(diff.is_breaking())

    def test_schema_property_removal_is_breaking(self):
        old = _spec(
            schemas={
                "Order": {
                    "type": "object",
                    "properties": {
                        "qty": {"type": "string"},
                        "notional": {"type": "string"},
                    },
                }
            }
        )
        new = _spec(
            schemas={
                "Order": {
                    "type": "object",
                    "properties": {
                        "qty": {"type": "string"},
                    },
                }
            }
        )
        diff = openapi_tools.semantic_diff(old, new)
        self.assertEqual(diff.schemas_modified, ["Order"])
        self.assertEqual(diff.enum_values_removed, [])
        self.assertTrue(diff.is_breaking())
        changelog = openapi_tools.format_changelog_draft(diff)
        self.assertIn("### Breaking", changelog)
        self.assertIn("Modify schema `Order`", changelog)

    def test_schema_property_and_enum_changes(self):
        old = _spec(
            schemas={
                "Order": {
                    "type": "object",
                    "properties": {
                        "status": {"type": "string", "enum": ["new", "filled"]},
                        "qty": {"type": "string"},
                    },
                }
            }
        )
        new = _spec(
            schemas={
                "Order": {
                    "type": "object",
                    "properties": {
                        "status": {"type": "string", "enum": ["new", "canceled"]},
                        "qty": {"type": "string"},
                        "notional": {"type": "string"},
                    },
                }
            }
        )
        diff = openapi_tools.semantic_diff(old, new)
        self.assertIn("Order", diff.schemas_modified)
        self.assertIn("Order.status: canceled", diff.enum_values_added)
        self.assertIn("Order.status: filled", diff.enum_values_removed)
        self.assertTrue(diff.is_breaking())

    def test_additive_only_is_not_breaking(self):
        old = _spec(schemas={"Order": {"type": "object", "properties": {}}})
        new = _spec(
            paths={
                "/v2/clock": {
                    "get": {
                        "operationId": "getClock",
                        "tags": ["Clock"],
                        "responses": {"200": {}},
                    }
                }
            },
            schemas={
                "Order": {"type": "object", "properties": {}},
                "Asset": {"type": "object", "properties": {}},
            },
        )
        diff = openapi_tools.semantic_diff(old, new)
        self.assertEqual(diff.operations_added, ["GET /v2/clock"])
        self.assertEqual(diff.schemas_added, ["Asset"])
        self.assertFalse(diff.is_breaking())
        self.assertFalse(diff.is_empty())

    def test_added_optional_properties_are_additive(self):
        old = _spec(
            schemas={
                "DailyTradingLimit": {
                    "type": "object",
                    "properties": {"daily_net_limit": {"type": "string"}},
                }
            }
        )
        new = _spec(
            schemas={
                "DailyTradingLimit": {
                    "type": "object",
                    "properties": {
                        "daily_net_limit": {"type": "string"},
                        "open_buys": {"type": "string"},
                        "open_sells": {"type": "string"},
                    },
                }
            }
        )
        diff = openapi_tools.semantic_diff(old, new)
        self.assertEqual(diff.schemas_extended, ["DailyTradingLimit"])
        self.assertEqual(diff.schemas_modified, [])
        self.assertFalse(diff.is_breaking())
        self.assertFalse(diff.is_empty())
        self.assertIn("added properties: open_buys, open_sells", diff.detail("schema", "DailyTradingLimit"))
        changelog = openapi_tools.format_changelog_draft(diff)
        self.assertIn("### Added", changelog)
        self.assertNotIn("### Breaking", changelog)

    def test_renamed_property_is_breaking_with_detail(self):
        old = _spec(
            schemas={
                "DailyTradingLimit": {
                    "type": "object",
                    "properties": {"daily_net_limit_in_use": {"type": "string"}},
                }
            }
        )
        new = _spec(
            schemas={
                "DailyTradingLimit": {
                    "type": "object",
                    "properties": {"in_use_limit": {"type": "string"}},
                }
            }
        )
        diff = openapi_tools.semantic_diff(old, new)
        self.assertEqual(diff.schemas_modified, ["DailyTradingLimit"])
        self.assertTrue(diff.is_breaking())
        detail = diff.detail("schema", "DailyTradingLimit")
        self.assertIn("removed properties: daily_net_limit_in_use", detail)
        self.assertIn("added properties: in_use_limit", detail)
        report = openapi_tools.format_maintainer_report(diff, api="broker")
        self.assertIn("removed properties: daily_net_limit_in_use", report)

    def test_newly_required_property_is_breaking(self):
        old = _spec(schemas={"S": {"type": "object", "properties": {"a": {"type": "string"}}}})
        new = _spec(
            schemas={
                "S": {"type": "object", "required": ["a"], "properties": {"a": {"type": "string"}}}
            }
        )
        diff = openapi_tools.semantic_diff(old, new)
        self.assertEqual(diff.schemas_modified, ["S"])
        self.assertTrue(diff.is_breaking())
        self.assertIn("newly required: a", diff.detail("schema", "S"))

    def test_schema_documentation_only_change_is_not_breaking(self):
        old = _spec(
            schemas={
                "S": {"type": "object", "properties": {"a": {"type": "string", "description": "old"}}}
            }
        )
        new = _spec(
            schemas={
                "S": {"type": "object", "properties": {"a": {"type": "string", "description": "new"}}}
            }
        )
        diff = openapi_tools.semantic_diff(old, new)
        self.assertEqual(diff.schemas_extended, ["S"])
        self.assertEqual(diff.schemas_modified, [])
        self.assertFalse(diff.is_breaking())
        self.assertEqual(diff.detail("schema", "S"), "documentation only")

    def test_property_named_description_is_not_treated_as_documentation(self):
        old = _spec(
            schemas={
                "BatchJournalRequest": {
                    "type": "object",
                    "properties": {"description": {"type": "string"}},
                }
            }
        )
        new = _spec(
            schemas={
                "BatchJournalRequest": {
                    "type": "object",
                    "properties": {"correspondent": {"type": "string"}},
                }
            }
        )
        diff = openapi_tools.semantic_diff(old, new)
        self.assertEqual(diff.schemas_modified, ["BatchJournalRequest"])
        self.assertTrue(diff.is_breaking())
        self.assertIn(
            "removed properties: description", diff.detail("schema", "BatchJournalRequest")
        )

    def test_response_examples_only_change_is_not_breaking(self):
        def spec(with_examples: bool):
            response: dict = {"content": {"application/json": {"schema": {"type": "object"}}}}
            if with_examples:
                response["content"]["application/json"]["examples"] = {
                    "sample": {"value": {"a": 1}}
                }
            return _spec(
                paths={
                    "/v1/limits": {
                        "get": {
                            "operationId": "getLimits",
                            "tags": ["Funding"],
                            "responses": {"200": response},
                        }
                    }
                }
            )

        diff = openapi_tools.semantic_diff(spec(False), spec(True))
        self.assertEqual(diff.operations_extended, ["GET /v1/limits"])
        self.assertEqual(diff.operations_modified, [])
        self.assertFalse(diff.is_breaking())
        self.assertFalse(diff.is_empty())
        self.assertEqual(diff.detail("operation", "GET /v1/limits"), "documentation only")

    def test_added_parameter_is_breaking_for_generated_signatures(self):
        def spec(parameters):
            return _spec(
                paths={
                    "/v2/account/activities": {
                        "get": {
                            "operationId": "getAccountActivities",
                            "tags": ["Activities"],
                            "parameters": parameters,
                            "responses": {"200": {}},
                        }
                    }
                }
            )

        old = spec([{"name": "category", "in": "query"}])
        new = spec(
            [
                {"name": "category", "in": "query"},
                {"name": "order_id", "in": "query"},
            ]
        )
        diff = openapi_tools.semantic_diff(old, new)
        self.assertEqual(diff.operations_modified, ["GET /v2/account/activities"])
        self.assertTrue(diff.is_breaking())
        self.assertIn(
            "added parameters: query:order_id",
            diff.detail("operation", "GET /v2/account/activities"),
        )

    def test_merge_diffs_preserves_details_and_extended_lists(self):
        old = _spec(schemas={"S": {"type": "object", "properties": {}}})
        new = _spec(schemas={"S": {"type": "object", "properties": {"a": {"type": "string"}}}})
        diff = openapi_tools.semantic_diff(old, new)
        merged = openapi_tools.merge_diffs([diff, openapi_tools.DiffResult()])
        self.assertEqual(merged.schemas_extended, ["S"])
        self.assertIn("added properties: a", merged.detail("schema", "S"))
        self.assertFalse(merged.is_breaking())

    def test_reports_include_breaking_and_changelog_sections(self):
        old = _spec(schemas={"A": {"type": "string", "enum": ["x"]}})
        new = _spec(schemas={"B": {"type": "string", "enum": ["y"]}})
        diff = openapi_tools.semantic_diff(old, new)
        report = openapi_tools.format_maintainer_report(diff, api="trading")
        changelog = openapi_tools.format_changelog_draft(diff)
        self.assertIn("OpenAPI adopt report (trading)", report)
        self.assertIn("Breaking changes detected", report)
        self.assertIn("### Breaking", changelog)
        self.assertIn("### Added", changelog)


if __name__ == "__main__":
    unittest.main()
