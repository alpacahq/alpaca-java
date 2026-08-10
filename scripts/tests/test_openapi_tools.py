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

    def test_added_enum_values_are_additive(self):
        def spec(values):
            return _spec(
                schemas={
                    "ActivityType": {"type": "string", "enum": values},
                    "Order": {
                        "type": "object",
                        "properties": {"status": {"type": "string", "enum": values}},
                    },
                }
            )

        diff = openapi_tools.semantic_diff(spec(["FILL"]), spec(["FILL", "CSD"]))
        self.assertEqual(sorted(diff.schemas_extended), ["ActivityType", "Order"])
        self.assertEqual(diff.schemas_modified, [])
        self.assertEqual(diff.enum_values_added, ["ActivityType: CSD", "Order.status: CSD"])
        self.assertEqual(diff.enum_values_removed, [])
        self.assertFalse(diff.is_breaking())
        self.assertEqual(diff.detail("schema", "ActivityType"), "added enum values")
        changelog = openapi_tools.format_changelog_draft(diff)
        self.assertIn("### Added", changelog)
        self.assertNotIn("### Breaking", changelog)

    def test_reordered_enum_values_are_not_reported_as_additions(self):
        old = _spec(schemas={"S": {"type": "string", "enum": ["a", "b"]}})
        new = _spec(schemas={"S": {"type": "string", "enum": ["b", "a"]}})
        diff = openapi_tools.semantic_diff(old, new)
        self.assertEqual(diff.schemas_extended, ["S"])
        self.assertEqual(diff.enum_values_added, [])
        self.assertFalse(diff.is_breaking())
        self.assertEqual(diff.detail("schema", "S"), "enum values reordered")

    def test_added_enum_values_beside_removed_property_stay_breaking(self):
        old = _spec(
            schemas={
                "Order": {
                    "type": "object",
                    "properties": {
                        "status": {"type": "string", "enum": ["new"]},
                        "qty": {"type": "string"},
                    },
                }
            }
        )
        new = _spec(
            schemas={
                "Order": {
                    "type": "object",
                    "properties": {"status": {"type": "string", "enum": ["new", "filled"]}},
                }
            }
        )
        diff = openapi_tools.semantic_diff(old, new)
        self.assertEqual(diff.schemas_modified, ["Order"])
        self.assertTrue(diff.is_breaking())
        detail = diff.detail("schema", "Order")
        self.assertIn("removed properties: qty", detail)
        self.assertIn("added enum values", detail)

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

    def test_added_inline_parameter_enum_value_is_additive(self):
        def spec(values):
            return _spec(
                paths={
                    "/v2/orders": {
                        "get": {
                            "operationId": "getOrders",
                            "parameters": [
                                {
                                    "name": "status",
                                    "in": "query",
                                    "schema": {"type": "string", "enum": values},
                                }
                            ],
                            "responses": {"200": {}},
                        }
                    }
                }
            )

        diff = openapi_tools.semantic_diff(spec(["open"]), spec(["open", "closed"]))
        self.assertEqual(diff.operations_extended, ["GET /v2/orders"])
        self.assertEqual(diff.operations_modified, [])
        self.assertEqual(
            diff.enum_values_added,
            ["GET /v2/orders parameter query:status: closed"],
        )
        self.assertFalse(diff.is_breaking())

    def test_reordered_widened_operation_oneof_enum_is_additive(self):
        # Positional enum collection would pair old[0]/new[0] and old[1]/new[1]
        # by index, falsely reporting "x" and "a" as removed. Semantic pairing
        # (via _match_composition_members) must align members by identity first.
        def spec(status_one_of):
            return _spec(
                paths={
                    "/v2/orders": {
                        "get": {
                            "operationId": "getOrders",
                            "parameters": [
                                {
                                    "name": "status",
                                    "in": "query",
                                    "schema": {"oneOf": status_one_of},
                                }
                            ],
                            "responses": {"200": {}},
                        }
                    }
                }
            )

        old = spec(
            [
                {"type": "string", "enum": ["x"]},
                {"type": "string", "enum": ["a"]},
            ]
        )
        new = spec(
            [
                {"type": "string", "enum": ["a"]},
                {"type": "string", "enum": ["x", "y"]},
            ]
        )
        diff = openapi_tools.semantic_diff(old, new)
        self.assertEqual(diff.operations_modified, [])
        self.assertIn("GET /v2/orders", diff.operations_extended)
        self.assertEqual(diff.enum_values_removed, [])
        self.assertEqual(
            diff.enum_values_added,
            ["GET /v2/orders parameter query:status.oneOf[0]: y"],
        )
        self.assertFalse(diff.is_breaking())

    def test_added_inline_request_body_enum_value_is_additive(self):
        def spec(values):
            return _spec(
                paths={
                    "/v2/orders": {
                        "post": {
                            "operationId": "createOrder",
                            "requestBody": {
                                "content": {
                                    "application/json": {
                                        "schema": {
                                            "type": "object",
                                            "properties": {
                                                "status": {
                                                    "type": "string",
                                                    "enum": values,
                                                }
                                            },
                                        }
                                    }
                                }
                            },
                            "responses": {"200": {}},
                        }
                    }
                }
            )

        diff = openapi_tools.semantic_diff(spec(["new"]), spec(["new", "filled"]))
        self.assertEqual(diff.operations_extended, ["POST /v2/orders"])
        self.assertEqual(diff.operations_modified, [])
        self.assertEqual(
            diff.enum_values_added,
            ["POST /v2/orders request body application/json.status: filled"],
        )
        self.assertFalse(diff.is_breaking())

    def test_added_inline_response_enum_value_is_additive(self):
        def spec(values):
            return _spec(
                paths={
                    "/v2/orders": {
                        "get": {
                            "operationId": "getOrders",
                            "responses": {
                                "200": {
                                    "content": {
                                        "application/json": {
                                            "schema": {
                                                "type": "object",
                                                "properties": {
                                                    "status": {
                                                        "type": "string",
                                                        "enum": values,
                                                    }
                                                },
                                            }
                                        }
                                    }
                                }
                            },
                        }
                    }
                }
            )

        diff = openapi_tools.semantic_diff(spec(["new"]), spec(["new", "filled"]))
        self.assertEqual(diff.operations_extended, ["GET /v2/orders"])
        self.assertEqual(diff.operations_modified, [])
        self.assertEqual(
            diff.enum_values_added,
            ["GET /v2/orders response 200 application/json.status: filled"],
        )
        self.assertFalse(diff.is_breaking())

    def test_merge_diffs_preserves_details_and_extended_lists(self):
        old = _spec(schemas={"S": {"type": "object", "properties": {}}})
        new = _spec(schemas={"S": {"type": "object", "properties": {"a": {"type": "string"}}}})
        diff = openapi_tools.semantic_diff(old, new)
        merged = openapi_tools.merge_diffs([diff, openapi_tools.DiffResult()])
        self.assertEqual(merged.schemas_extended, ["S"])
        self.assertIn("added properties: a", merged.detail("schema", "S"))
        self.assertFalse(merged.is_breaking())

    def test_nullable_type_array_rewrite_is_not_breaking(self):
        old = _spec(
            schemas={
                "next_page_token": {"type": "string", "nullable": True},
                "news": {
                    "type": "object",
                    "properties": {
                        "url": {"type": "string", "format": "uri", "nullable": True}
                    },
                },
            }
        )
        new = _spec(
            schemas={
                "next_page_token": {"type": ["string", "null"]},
                "news": {
                    "type": "object",
                    "properties": {"url": {"type": ["string", "null"], "format": "uri"}},
                },
            }
        )
        diff = openapi_tools.semantic_diff(old, new)
        self.assertEqual(diff.schemas_modified, [])
        self.assertEqual(diff.schemas_extended, [])
        self.assertFalse(diff.is_breaking())
        self.assertTrue(diff.is_empty())
        self.assertNotIn("next_page_token", diff.schemas_modified)
        self.assertNotIn("news", diff.schemas_modified)
        self.assertNotIn("next_page_token", diff.schemas_extended)
        self.assertNotIn("news", diff.schemas_extended)

    def test_nullable_type_array_null_first_is_not_breaking(self):
        old = _spec(schemas={"token": {"type": "string", "nullable": True}})
        new = _spec(schemas={"token": {"type": ["null", "string"]}})
        diff = openapi_tools.semantic_diff(old, new)
        self.assertEqual(diff.schemas_modified, [])
        self.assertEqual(diff.schemas_extended, [])
        self.assertFalse(diff.is_breaking())
        self.assertTrue(diff.is_empty())

    def test_binary_format_vs_content_media_type_is_not_breaking(self):
        old = _spec(
            paths={
                "/v1beta1/logos/{symbol}": {
                    "get": {
                        "operationId": "getLogo",
                        "tags": ["Logos"],
                        "responses": {
                            "200": {
                                "content": {
                                    "image/png": {
                                        "schema": {"type": "string", "format": "binary"}
                                    }
                                }
                            }
                        },
                    }
                }
            }
        )
        new = _spec(
            paths={
                "/v1beta1/logos/{symbol}": {
                    "get": {
                        "operationId": "getLogo",
                        "tags": ["Logos"],
                        "responses": {
                            "200": {
                                "content": {
                                    "image/png": {
                                        "schema": {
                                            "type": "string",
                                            "contentMediaType": "image/png",
                                        }
                                    }
                                }
                            }
                        },
                    }
                }
            }
        )
        diff = openapi_tools.semantic_diff(old, new)
        self.assertEqual(diff.operations_modified, [])
        self.assertEqual(diff.operations_extended, [])
        self.assertFalse(diff.is_breaking())
        self.assertTrue(diff.is_empty())

    def test_textual_content_media_type_is_not_treated_as_binary(self):
        # text/csv still generates a String, so losing `format: binary` is a real change.
        old = _spec(schemas={"Export": {"type": "string", "format": "binary"}})
        new = _spec(schemas={"Export": {"type": "string", "contentMediaType": "text/csv"}})
        diff = openapi_tools.semantic_diff(old, new)
        self.assertEqual(diff.schemas_modified, ["Export"])
        self.assertTrue(diff.is_breaking())

    def test_octet_stream_content_media_type_is_binary(self):
        old = _spec(schemas={"Export": {"type": "string", "format": "binary"}})
        new = _spec(
            schemas={"Export": {"type": "string", "contentMediaType": "application/octet-stream"}}
        )
        diff = openapi_tools.semantic_diff(old, new)
        self.assertTrue(diff.is_empty())

    def test_security_only_operation_change_is_not_breaking(self):
        def op(**extra):
            body = {
                "operationId": "getBars",
                "tags": ["Bars"],
                "responses": {"200": {"description": "ok"}},
            }
            body.update(extra)
            return body

        old = _spec(paths={"/v2/stocks/bars": {"get": op()}})
        new = _spec(
            paths={
                "/v2/stocks/bars": {
                    "get": op(security=[{"BasicAuth": []}, {"apiKey": [], "apiSecret": []}])
                }
            }
        )
        diff = openapi_tools.semantic_diff(old, new)
        self.assertEqual(diff.operations_modified, [])
        self.assertEqual(diff.operations_extended, [])
        self.assertFalse(diff.is_breaking())
        self.assertTrue(diff.is_empty())

    def test_added_oneof_member_is_additive(self):
        old = _spec(
            schemas={
                "ActivityV2DetailNTA": {
                    "type": "object",
                    "allOf": [
                        {
                            "oneOf": [
                                {"$ref": "#/components/schemas/OCTActivityV2"},
                                {"$ref": "#/components/schemas/FEEActivityV2"},
                            ]
                        }
                    ],
                }
            }
        )
        new = _spec(
            schemas={
                "ActivityV2DetailNTA": {
                    "type": "object",
                    "allOf": [
                        {
                            "oneOf": [
                                {"$ref": "#/components/schemas/REOActivityV2"},
                                {"$ref": "#/components/schemas/OCTActivityV2"},
                                {"$ref": "#/components/schemas/FEEActivityV2"},
                            ]
                        }
                    ],
                },
                "REOActivityV2": {"type": "object"},
            }
        )
        diff = openapi_tools.semantic_diff(old, new)
        self.assertEqual(diff.schemas_modified, [])
        self.assertIn("ActivityV2DetailNTA", diff.schemas_extended)
        self.assertIn("added composition members", diff.detail("schema", "ActivityV2DetailNTA"))
        self.assertIn("REOActivityV2", diff.schemas_added)
        self.assertFalse(diff.is_breaking())

    def test_removed_oneof_member_is_breaking(self):
        old = _spec(
            schemas={
                "S": {
                    "oneOf": [
                        {"$ref": "#/components/schemas/A"},
                        {"$ref": "#/components/schemas/B"},
                    ]
                }
            }
        )
        new = _spec(schemas={"S": {"oneOf": [{"$ref": "#/components/schemas/A"}]}})
        diff = openapi_tools.semantic_diff(old, new)
        self.assertEqual(diff.schemas_modified, ["S"])
        self.assertTrue(diff.is_breaking())

    def test_combined_enum_widen_and_added_oneof_member_is_additive(self):
        old = _spec(
            schemas={
                "S": {
                    "oneOf": [
                        {"type": "string", "enum": ["x"]},
                    ]
                }
            }
        )
        new = _spec(
            schemas={
                "S": {
                    "oneOf": [
                        {"type": "string", "enum": ["x", "y"]},
                        {"$ref": "#/components/schemas/New"},
                    ]
                },
                "New": {"type": "object"},
            }
        )
        diff = openapi_tools.semantic_diff(old, new)
        self.assertEqual(diff.schemas_modified, [])
        self.assertIn("S", diff.schemas_extended)
        detail = diff.detail("schema", "S")
        self.assertIn("added enum values", detail)
        self.assertIn("added composition members", detail)
        self.assertFalse(diff.is_breaking())

    def test_greedy_enum_widen_blocked_by_exact_oneof_match_is_additive(self):
        old = _spec(
            schemas={
                "S": {
                    "oneOf": [
                        {"type": "string", "enum": ["x"]},
                        {"type": "string", "enum": ["x", "y"]},
                    ]
                }
            }
        )
        new = _spec(
            schemas={
                "S": {
                    "oneOf": [
                        {"type": "string", "enum": ["x", "y"]},
                        {"type": "string", "enum": ["x", "z"]},
                        {"$ref": "#/components/schemas/New"},
                    ]
                },
                "New": {"type": "object"},
            }
        )
        diff = openapi_tools.semantic_diff(old, new)
        self.assertEqual(diff.schemas_modified, [])
        self.assertIn("S", diff.schemas_extended)
        self.assertIn("added composition members", diff.detail("schema", "S"))
        self.assertFalse(diff.is_breaking())

    def test_nested_oneof_inside_allof_is_additive(self):
        # Equal-length allOf still recurses, so a nested oneOf can widen without
        # treating the outer allOf list as an additive composition fold.
        old = _spec(
            schemas={
                "S": {
                    "allOf": [
                        {"oneOf": [{"$ref": "#/components/schemas/A"}]},
                    ]
                }
            }
        )
        new = _spec(
            schemas={
                "S": {
                    "allOf": [
                        {
                            "oneOf": [
                                {"$ref": "#/components/schemas/A"},
                                {"$ref": "#/components/schemas/B"},
                            ]
                        },
                    ]
                },
                "B": {"type": "object"},
            }
        )
        diff = openapi_tools.semantic_diff(old, new)
        self.assertEqual(diff.schemas_modified, [])
        self.assertIn("S", diff.schemas_extended)
        self.assertIn("added composition members", diff.detail("schema", "S"))
        self.assertFalse(diff.is_breaking())

    def test_added_allof_member_is_breaking(self):
        # allOf growth is an intersection: a new member can introduce required
        # fields and change the generated model, so it must stay breaking.
        old = _spec(
            schemas={
                "S": {
                    "type": "object",
                    "allOf": [{"properties": {"a": {"type": "string"}}}],
                }
            }
        )
        new = _spec(
            schemas={
                "S": {
                    "type": "object",
                    "allOf": [
                        {"properties": {"a": {"type": "string"}}},
                        {
                            "type": "object",
                            "required": ["b"],
                            "properties": {"b": {"type": "string"}},
                        },
                    ],
                }
            }
        )
        diff = openapi_tools.semantic_diff(old, new)
        self.assertEqual(diff.schemas_modified, ["S"])
        self.assertTrue(diff.is_breaking())

    def test_outer_allof_member_add_with_nested_oneof_is_breaking(self):
        # Nested oneOf growth alone is additive, but growing the outer allOf
        # list is not — keep the whole schema change breaking.
        old = _spec(
            schemas={
                "S": {
                    "allOf": [
                        {"oneOf": [{"$ref": "#/components/schemas/A"}]},
                    ]
                }
            }
        )
        new = _spec(
            schemas={
                "S": {
                    "allOf": [
                        {
                            "oneOf": [
                                {"$ref": "#/components/schemas/A"},
                                {"$ref": "#/components/schemas/B"},
                            ]
                        },
                        {"$ref": "#/components/schemas/C"},
                    ]
                },
                "B": {"type": "object"},
                "C": {"type": "object"},
            }
        )
        diff = openapi_tools.semantic_diff(old, new)
        self.assertEqual(diff.schemas_modified, ["S"])
        self.assertTrue(diff.is_breaking())

    def test_enum_widen_most_constrained_first_is_additive(self):
        # old [x], [x,y] vs new [x,y,z], [x,z]: a greedy left-to-right widen pass
        # matches [x] to [x,y,z] first (both candidates are compatible), starving
        # [x,y] (whose only compatible candidate is [x,y,z]) and falsely reporting
        # this as breaking. Resolving the most-constrained old member ([x,y], with
        # a single candidate) first must fix this.
        old = _spec(
            schemas={
                "S": {
                    "oneOf": [
                        {"type": "string", "enum": ["x"]},
                        {"type": "string", "enum": ["x", "y"]},
                    ]
                }
            }
        )
        new = _spec(
            schemas={
                "S": {
                    "oneOf": [
                        {"type": "string", "enum": ["x", "y", "z"]},
                        {"type": "string", "enum": ["x", "z"]},
                    ]
                }
            }
        )
        diff = openapi_tools.semantic_diff(old, new)
        self.assertEqual(diff.schemas_modified, [])
        self.assertIn("S", diff.schemas_extended)
        self.assertIn("added enum values", diff.detail("schema", "S"))
        self.assertFalse(diff.is_breaking())

    def test_duplicate_oneof_member_removal_is_breaking(self):
        old = _spec(
            schemas={
                "S": {
                    "oneOf": [
                        {"$ref": "#/components/schemas/A"},
                        {"$ref": "#/components/schemas/A"},
                    ]
                }
            }
        )
        new = _spec(schemas={"S": {"oneOf": [{"$ref": "#/components/schemas/A"}]}})
        diff = openapi_tools.semantic_diff(old, new)
        self.assertEqual(diff.schemas_modified, ["S"])
        self.assertTrue(diff.is_breaking())

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
