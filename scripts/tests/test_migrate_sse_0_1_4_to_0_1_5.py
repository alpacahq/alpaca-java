"""Tests for the conservative Broker SSE migration tool."""

from __future__ import annotations

from contextlib import redirect_stderr, redirect_stdout
import io
import json
from pathlib import Path
import tempfile
import unittest

from scripts import migrate_sse_0_1_4_to_0_1_5 as migrate


IMPORT = "import markets.alpaca.client.broker.sse.BrokerSseSubscription;\n"
FIXTURES = Path(__file__).with_name("fixtures") / "sse_migration"


class AnalysisTests(unittest.TestCase):
    def test_safe_fixture_matches_expected_output(self):
        source = (FIXTURES / "safe_input.java").read_text(encoding="utf-8")
        expected = (FIXTURES / "safe_expected.java").read_text(encoding="utf-8")

        result = migrate.analyze_text(source)

        self.assertEqual(expected, result.text)
        self.assertEqual(["SSE000"], [finding.code for finding in result.findings])

    def test_manual_review_fixture_is_unchanged(self):
        source = (FIXTURES / "manual_review_input.java").read_text(encoding="utf-8")
        expected = (FIXTURES / "manual_review_expected.java").read_text(encoding="utf-8")

        result = migrate.analyze_text(source)

        self.assertEqual(expected, result.text)
        self.assertIn("SSE001", [finding.code for finding in result.findings])

    def test_rewrites_explicitly_typed_unique_receiver(self):
        source = (
            IMPORT
            + "class Example {\n"
            + "  void close(BrokerSseSubscription subscription) {\n"
            + "    subscription.eventSource().cancel();\n"
            + "  }\n"
            + "}\n"
        )

        result = migrate.analyze_text(source)

        self.assertIn("subscription.close();", result.text)
        self.assertNotIn("eventSource().cancel()", result.text)
        self.assertEqual(["SSE000"], [finding.code for finding in result.findings])

    def test_rewrite_is_idempotent(self):
        source = (
            IMPORT
            + "class Example {\n"
            + "  void close(BrokerSseSubscription subscription) {\n"
            + "    subscription.eventSource().cancel();\n"
            + "  }\n"
            + "}\n"
        )

        once = migrate.analyze_text(source).text
        twice = migrate.analyze_text(once)

        self.assertEqual(once, twice.text)
        self.assertEqual((), twice.findings)

    def test_reports_var_and_duplicate_declarations_without_rewriting(self):
        var_source = (
            "class Example {\n"
            "  void close() {\n"
            "    var subscription = create();\n"
            "    subscription.eventSource().cancel();\n"
            "  }\n"
            "}\n"
        )
        duplicate_source = (
            IMPORT
            + "class Example {\n"
            + "  BrokerSseSubscription subscription;\n"
            + "  void close(BrokerSseSubscription subscription) {\n"
            + "    subscription.eventSource().cancel();\n"
            + "  }\n"
            + "}\n"
        )

        for source in (var_source, duplicate_source):
            with self.subTest(source=source):
                result = migrate.analyze_text(source)
                self.assertEqual(source, result.text)
                self.assertIn("SSE001", [finding.code for finding in result.findings])

    def test_rewrites_unique_fully_qualified_receiver_without_import(self):
        source = (
            "class Example {\n"
            "  void close(\n"
            "      markets.alpaca.client.broker.sse.BrokerSseSubscription subscription) {\n"
            "    subscription.eventSource().cancel();\n"
            "  }\n"
            "}\n"
        )

        result = migrate.analyze_text(source)

        self.assertIn("subscription.close();", result.text)
        self.assertEqual(["SSE000"], [finding.code for finding in result.findings])

    def test_does_not_treat_subscription_return_method_as_a_binder(self):
        source = (
            IMPORT
            + "class Example {\n"
            + "  BrokerSseSubscription subscription() { return null; }\n"
            + "  void close() { subscription.eventSource().cancel(); }\n"
            + "}\n"
        )

        result = migrate.analyze_text(source)

        self.assertEqual(source, result.text)
        self.assertIn("SSE001", [finding.code for finding in result.findings])

    def test_reports_shadowed_or_qualified_receivers_without_rewriting(self):
        sources = (
            (
                IMPORT
                + "class Example {\n"
                + "  BrokerSseSubscription subscription;\n"
                + "  void close(String subscription) {\n"
                + "    subscription.eventSource().cancel();\n"
                + "  }\n"
                + "}\n"
            ),
            (
                IMPORT
                + "class Example {\n"
                + "  BrokerSseSubscription subscription;\n"
                + "  void close(Example other) {\n"
                + "    other.subscription.eventSource().cancel();\n"
                + "  }\n"
                + "}\n"
            ),
            (
                IMPORT
                + "class Example {\n"
                + "  BrokerSseSubscription subscription;\n"
                + "  void close() { this.subscription.eventSource().cancel(); }\n"
                + "}\n"
            ),
            (
                IMPORT
                + "class Example {\n"
                + "  BrokerSseSubscription subscription;\n"
                + "  void consume(java.util.function.Consumer<Object> consumer) {\n"
                + "    consumer.accept(subscription -> subscription);\n"
                + "    subscription.eventSource().cancel();\n"
                + "  }\n"
                + "}\n"
            ),
            (
                IMPORT
                + "class Example {\n"
                + "  BrokerSseSubscription subscription;\n"
                + "  void close() {\n"
                + "    try { run(); } catch (Exception subscription) { run(); }\n"
                + "    subscription.eventSource().cancel();\n"
                + "  }\n"
                + "}\n"
            ),
            (
                IMPORT
                + "class Example {\n"
                + "  BrokerSseSubscription subscription;\n"
                + "  void close(java.util.List<String> values) {\n"
                + "    for (String subscription : values) { run(); }\n"
                + "    subscription.eventSource().cancel();\n"
                + "  }\n"
                + "}\n"
            ),
            (
                IMPORT
                + "class Example {\n"
                + "  BrokerSseSubscription subscription;\n"
                + "  class Nested {\n"
                + "    BrokerSseSubscription subscription;\n"
                + "  }\n"
                + "  void close() { subscription.eventSource().cancel(); }\n"
                + "}\n"
            ),
        )

        for source in sources:
            with self.subTest(source=source):
                result = migrate.analyze_text(source)
                self.assertEqual(source, result.text)
                self.assertIn("SSE001", [finding.code for finding in result.findings])

    def test_reports_comment_inside_cancel_chain_without_rewriting(self):
        source = (
            IMPORT
            + "class Example {\n"
            + "  BrokerSseSubscription subscription;\n"
            + "  void close() {\n"
            + "    subscription./* keep this explanation */eventSource().cancel();\n"
            + "  }\n"
            + "}\n"
        )

        result = migrate.analyze_text(source)

        self.assertEqual(source, result.text)
        self.assertIn("SSE001", [finding.code for finding in result.findings])

    def test_reports_nested_same_name_type_without_rewriting(self):
        source = (
            IMPORT
            + "class Example {\n"
            + "  static class BrokerSseSubscription {\n"
            + "    Source eventSource() { return null; }\n"
            + "  }\n"
            + "  BrokerSseSubscription subscription;\n"
            + "  void close() { subscription.eventSource().cancel(); }\n"
            + "}\n"
        )

        result = migrate.analyze_text(source)

        self.assertEqual(source, result.text)
        self.assertIn("SSE001", [finding.code for finding in result.findings])

    def test_ignores_lookalikes_in_comments_and_literals(self):
        source = (
            IMPORT
            + "class Example {\n"
            + "  BrokerSseSubscription subscription;\n"
            + '  String text = "subscription.eventSource().cancel()";\n'
            + "  // subscription.eventSource().cancel();\n"
            + "}\n"
        )

        result = migrate.analyze_text(source)

        self.assertEqual(source, result.text)
        self.assertEqual((), result.findings)

    def test_reports_manual_review_patterns(self):
        source = """
            class Example implements BrokerSseEventListener<Object> {
              public void onEventFailure(AlpacaSseDeserializationException failure) {}
              public void onFailure(Throwable failure, Response response) {
                if (failure instanceof JsonSyntaxException) {}
                response.handshake();
                Thread.sleep(1);
              }
            }
        """

        result = migrate.analyze_text(source)
        codes = {finding.code for finding in result.findings}

        self.assertTrue({"SSE003", "SSE004", "SSE005", "SSE007"}.issubset(codes))

    def test_reports_generated_sse_call_without_other_sse_markers(self):
        source = """
            class Example {
              void open(EventsApi api) {
                api.subscribeToActivitiesSSE(null, null, null, null);
              }
            }
        """

        result = migrate.analyze_text(source)

        self.assertEqual(["SSE006"], [finding.code for finding in result.findings])


class CliTests(unittest.TestCase):
    def test_dry_run_check_json_and_write_modes(self):
        source = (
            IMPORT
            + "class Example {\n"
            + "  BrokerSseSubscription subscription;\n"
            + "  void close() { subscription.eventSource().cancel(); }\n"
            + "}\n"
        )
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "Example.java"
            path.write_text(source, encoding="utf-8")

            output = io.StringIO()
            with redirect_stdout(output):
                exit_code = migrate.main(["--json", "--check", str(path)])
            findings = json.loads(output.getvalue())

            self.assertEqual(1, exit_code)
            self.assertEqual("SSE000", findings[0]["code"])
            self.assertEqual(source, path.read_text(encoding="utf-8"))

            with redirect_stdout(io.StringIO()):
                self.assertEqual(0, migrate.main(["--write", str(path)]))
            self.assertIn("subscription.close()", path.read_text(encoding="utf-8"))

    def test_missing_input_path_fails_closed(self):
        with redirect_stderr(io.StringIO()):
            exit_code = migrate.main(["--check", "/definitely/missing/source"])

        self.assertEqual(2, exit_code)


if __name__ == "__main__":
    unittest.main()
