"""Tests for the generated Java SDK compatibility classifier."""

from __future__ import annotations

import unittest

from scripts import sdk_api_diff


class DeclarationParsingTests(unittest.TestCase):
    def test_ignores_parameter_names_and_annotations(self):
        source = """
            package sample;

            public class Example {
              public String find(@javax.annotation.Nullable String oldName) {
                return oldName;
              }
            }
        """
        declarations = sdk_api_diff.declarations_from_java(source, "Example.java")
        method = declarations[("method", "sample.Example", "find", "String find(String)")]
        self.assertEqual(method.signature, "String find(String)")

    def test_records_public_constructors(self):
        source = """
            package sample;

            public class Example {
              public Example(String id) {
              }
            }
        """
        declarations = sdk_api_diff.declarations_from_java(source, "Example.java")
        self.assertIn(
            ("constructor", "sample.Example", "Example", "Example(String)"), declarations
        )

    def test_retains_and_compares_same_arity_overloads(self):
        old_source = """
            package sample;

            public class Example {
              public String join(String[] values, String separator) {
                return "";
              }
              public String join(java.util.Collection<String> values, String separator) {
                return "";
              }
            }
        """
        new_source = """
            package sample;

            public class Example {
              public String join(java.util.Collection<String> values, String separator) {
                return "";
              }
            }
        """
        changes = sdk_api_diff._compare_declarations(
            sdk_api_diff.declarations_from_java(old_source, "Example.java"),
            sdk_api_diff.declarations_from_java(new_source, "Example.java"),
        )
        self.assertEqual(
            [("removed", "String join(String[], String)")],
            [(change, old.signature) for change, old, _ in changes],
        )

    def test_reports_constructor_parameter_change(self):
        old_source = """
            package sample;

            public class Example {
              public Example(String id) {
              }
            }
        """
        new_source = """
            package sample;

            public class Example {
              public Example(java.util.UUID id) {
              }
            }
        """
        changes = sdk_api_diff._compare_declarations(
            sdk_api_diff.declarations_from_java(old_source, "Example.java"),
            sdk_api_diff.declarations_from_java(new_source, "Example.java"),
        )
        self.assertEqual(1, len(changes))
        self.assertEqual("changed", changes[0][0])
        self.assertEqual("Example(String)", changes[0][1].signature)
        self.assertEqual("Example(java.util.UUID)", changes[0][2].signature)

    def test_records_nested_enum_values(self):
        source = """
            package sample;

            public class Example {
              public enum Type {
                OLD("old"),
                NEW("new");
              }
            }
        """
        declarations = sdk_api_diff.declarations_from_java(source, "Example.java")
        self.assertIn(("enum value", "sample.Example.Type", "OLD", "OLD"), declarations)

    def test_report_explains_changed_and_removed_declarations(self):
        old = sdk_api_diff.Declaration("method", "Example", "find/1", "String find(String)")
        new = sdk_api_diff.Declaration("method", "Example", "find/1", "String find(UUID)")
        report = sdk_api_diff.format_report([("changed", old, new), ("removed", old, None)])
        self.assertIn("Breaking Java SDK compatibility", report)
        self.assertIn("String find(String)", report)
        self.assertIn("Remove method", report)
