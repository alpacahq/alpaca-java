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
