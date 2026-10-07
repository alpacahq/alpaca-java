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

    def test_rewrites_explicitly_typed_local_receiver(self):
        source = (
            IMPORT
            + "class Example {\n"
            + "  void close() {\n"
            + "    BrokerSseSubscription subscription = create();\n"
            + "    subscription.eventSource().cancel();\n"
            + "  }\n"
            + "}\n"
        )

        result = migrate.analyze_text(source)

        self.assertIn("subscription.close();", result.text)
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
                + "class Example extends ExternalBase {\n"
                + "  void close(java.util.List<BrokerSseSubscription> values) {\n"
                + "    for (BrokerSseSubscription subscription : values)\n"
                + "      run(subscription);\n"
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
            (
                IMPORT
                + "class Example {\n"
                + "  BrokerSseSubscription subscription;\n"
                + "  void close() { subscription.eventSource().cancel(); }\n"
                + "}\n"
            ),
            (
                IMPORT
                + "class One { BrokerSseSubscription subscription; }\n"
                + "class Two extends ExternalBase {\n"
                + "  void close() { subscription.eventSource().cancel(); }\n"
                + "}\n"
            ),
            (
                IMPORT
                + "class Example extends ExternalBase {\n"
                + "  void keep(BrokerSseSubscription subscription) {}\n"
                + "  void close() { subscription.eventSource().cancel(); }\n"
                + "}\n"
            ),
            (
                IMPORT
                + "class Example extends ExternalBase {\n"
                + "  void close() {\n"
                + "    try (BrokerSseSubscription subscription = create()) { run(); }\n"
                + "    subscription.eventSource().cancel();\n"
                + "  }\n"
                + "}\n"
            ),
            (
                IMPORT
                + "class Example {\n"
                + "  void consume() {\n"
                + "    use((BrokerSseSubscription subscription) -> "
                + "subscription.eventSource().cancel());\n"
                + "  }\n"
                + "}\n"
            ),
            (
                IMPORT
                + "class Example {\n"
                + "  void consume() {\n"
                + "    use((BrokerSseSubscription subscription) -> {\n"
                + "      subscription.eventSource().cancel();\n"
                + "    });\n"
                + "  }\n"
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

    def test_reports_same_named_type_parameter_without_rewriting(self):
        declarations = (
            "BrokerSseSubscription",
            "BrokerSseSubscription extends LegacySubscription",
            "@TypeUse BrokerSseSubscription extends LegacySubscription",
            "@com.acme.TypeUse BrokerSseSubscription extends LegacySubscription",
            '@com.acme.TypeUse(value = Nested.value("x")) '
            "BrokerSseSubscription extends LegacySubscription",
            "@com.acme.TypeUse(value = 1 > 0, types = {A.class, B.class}) "
            "BrokerSseSubscription extends LegacySubscription",
        )

        for declaration in declarations:
            with self.subTest(declaration=declaration):
                source = (
                    IMPORT
                    + f"class Example<{declaration}> {{\n"
                    + "  void close(BrokerSseSubscription subscription) {\n"
                    + "    subscription.eventSource().cancel();\n"
                    + "  }\n"
                    + "}\n"
                )
                result = migrate.analyze_text(source)

                self.assertEqual(source, result.text)
                self.assertIn("SSE001", [finding.code for finding in result.findings])

    def test_unicode_escape_disables_automatic_rewrites(self):
        source = (
            IMPORT
            + "class Example {\n"
            + '  String marker = "\\u0041";\n'
            + "  void close(BrokerSseSubscription subscription) {\n"
            + "    subscription.eventSource().cancel();\n"
            + "  }\n"
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

    def test_ignores_cancel_chain_inside_text_block_with_escaped_triple_quotes(self):
        source = (
            IMPORT
            + "class Example {\n"
            + "  void close(BrokerSseSubscription subscription) {\n"
            + '    String text = """\n'
            + '      \\"""\n'
            + "      subscription.eventSource().cancel();\n"
            + '      \\"""\n'
            + '      """;\n'
            + "  }\n"
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

    def test_reports_corrected_and_legacy_generated_sse_variants(self):
        methods = (
            "subscribeToAccountStatusSSE",
            "subscribeToAccountStatusSSECall",
            "subscribeToAccountStatusSSEWithHttpInfo",
            "subscribeToAccountStatusSSEAsync",
            "suscribeToAccountStatusSSE",
            "suscribeToAccountStatusSSECall",
            "suscribeToAccountStatusSSEWithHttpInfo",
            "suscribeToAccountStatusSSEAsync",
            "getV1EventsNtaCall",
            "getAccountActivityEventAsync",
        )

        for method in methods:
            with self.subTest(method=method):
                source = f"class Example {{ void open(EventsApi api) {{ api.{method}(); }} }}"
                result = migrate.analyze_text(source)
                self.assertEqual(["SSE006"], [finding.code for finding in result.findings])

    def test_reports_generated_sse_method_references(self):
        methods = (
            "subscribeToActivitiesSSE",
            "subscribeToCorporateActionsSSECall",
            "subscribeToAccountStatusSSEAsync",
            "suscribeToAccountStatusSSEWithHttpInfo",
            "getV1EventsNtaCall",
            "getAccountActivityEventAsync",
        )

        for method in methods:
            with self.subTest(method=method):
                source = (
                    "class Example { GeneratedOperation bind(EventsApi api) { "
                    f"return api::{method};"
                    " } }"
                )
                result = migrate.analyze_text(source)
                self.assertEqual(["SSE006"], [finding.code for finding in result.findings])
                self.assertIn("method usage", result.findings[0].message)

    def test_reports_every_generated_sse_usage(self):
        source = """
            class Example {
              void open(EventsApi first, EventsApi second) {
                first.subscribeToActivitiesSSE(null, null, null, null);
                second.getV1EventsNtaCall();
              }
            }
        """

        result = migrate.analyze_text(source)

        self.assertEqual(["SSE006", "SSE006"], [finding.code for finding in result.findings])
        self.assertEqual([4, 5], [finding.line for finding in result.findings])

    def test_reports_unqualified_generated_sse_calls(self):
        sources = (
            """
                class Example extends EventsApi {
                  void open() {
                    subscribeToActivitiesSSE(null, null, null, null);
                  }
                }
            """,
            """
                class Example extends
                    markets.alpaca.client.openapi.data.api.CorporateActionsApi {
                  void open() {
                    subscribeToCorporateActionsSSE(null, null, null);
                  }
                }
            """,
            """
                class Base extends EventsApi {}
                class Example extends Base {
                  void open() {
                    subscribeToActivitiesSSE(null, null, null, null);
                  }
                }
            """,
            """
                class Example {
                  Object api = new EventsApi() {
                    void open() {
                      subscribeToActivitiesSSE(null, null, null, null);
                    }
                  };
                }
            """,
        )

        for source in sources:
            with self.subTest(source=source):
                result = migrate.analyze_text(source)
                self.assertEqual(
                    ["SSE006"], [finding.code for finding in result.findings]
                )
                self.assertIn("unqualified generated", result.findings[0].message)

    def test_reports_event_source_direct_cast_and_both_identity_directions(self):
        source = """
            class Example {
              BrokerSseSubscription subscription;
              EventSource source;
              void compare() {
                Object cast = (okhttp3.sse.EventSource) subscription.eventSource();
                boolean left = subscription.eventSource() == source;
                boolean right = source != subscription.eventSource();
              }
            }
        """

        result = migrate.analyze_text(source)

        self.assertGreaterEqual(
            [finding.code for finding in result.findings].count("SSE002"), 3
        )

    def test_reports_chained_event_source_identity_without_type_token(self):
        source = """
            class Example {
              BrokerSseSubscription subscription;
              void compare(Object expected, java.util.List<Holder> subscriptions) {
                boolean same = expected == subscriptions.get(0).eventSource();
              }
            }
        """

        result = migrate.analyze_text(source)

        self.assertIn("SSE002", [finding.code for finding in result.findings])

    def test_ignores_single_event_call_on_handwritten_broker_client(self):
        sources = (
            """
                import markets.alpaca.client.broker.sse.BrokerEventsSseClient;
                class Example {
                  void fetch(BrokerEventsSseClient client) {
                    client.getAccountActivityEventAsync(null, "event");
                  }
                }
            """,
            """
                class Example {
                  void fetch(
                      markets.alpaca.client.broker.sse.BrokerEventsSseClient client) {
                    client.getAccountActivityEventAsync(null, "event");
                  }
                }
            """,
            """
                import markets.alpaca.client.broker.sse.BrokerEventsSseClient;
                class Example {
                  void fetch() {
                    client.getAccountActivityEventAsync(null, "event");
                  }
                  private BrokerEventsSseClient client;
                }
            """,
            """
                import markets.alpaca.client.broker.sse.BrokerEventsSseClient;
                class Example {
                  GeneratedOperation bind(BrokerEventsSseClient client) {
                    return client::getAccountActivityEventAsync;
                  }
                }
            """,
            """
                import markets.alpaca.client.broker.sse.BrokerEventsSseClient;
                class Example {
                  private BrokerEventsSseClient client;
                  void fetch() {
                    this.client.getAccountActivityEventAsync(null, "event");
                  }
                  GeneratedOperation bind() {
                    return this.client::getAccountActivityEventAsync;
                  }
                }
            """,
            """
                import markets.alpaca.client.broker.sse.BrokerEventsSseClient;
                class Example {
                  private BrokerEventsSseClient client;
                  void fetch(BrokerEventsSseClient client) {
                    this.client.getAccountActivityEventAsync(null, "event");
                  }
                }
            """,
        )

        for source in sources:
            with self.subTest(source=source):
                self.assertEqual((), migrate.analyze_text(source).findings)

    def test_reports_ambiguous_handwritten_client_lookalike(self):
        sources = (
            """
                class BrokerEventsSseClient {}
                class Example {
                  void fetch(BrokerEventsSseClient client) {
                    client.getAccountActivityEventAsync(null, "event");
                  }
                }
            """,
            """
                class BrokerEventsSseClient {}
                class Example {
                  GeneratedOperation bind(BrokerEventsSseClient client) {
                    return client::getAccountActivityEventAsync;
                  }
                }
            """,
        )

        for source in sources:
            with self.subTest(source=source):
                result = migrate.analyze_text(source)
                self.assertEqual(
                    ["SSE006"], [finding.code for finding in result.findings]
                )

    def test_reports_shadowing_handwritten_client_type_parameter(self):
        declarations = (
            "BrokerEventsSseClient extends AccountsApi",
            "@com.acme.TypeUse BrokerEventsSseClient extends AccountsApi",
            '@com.acme.TypeUse(value = Nested.value("x")) '
            "BrokerEventsSseClient extends AccountsApi",
            "@com.acme.TypeUse(value = 1 > 0, types = {A.class, B.class}) "
            "BrokerEventsSseClient extends AccountsApi",
        )

        for declaration in declarations:
            with self.subTest(declaration=declaration):
                source = f"""
                    import markets.alpaca.client.broker.sse.BrokerEventsSseClient;
                    class Example<{declaration}> {{
                      void fetch(BrokerEventsSseClient client) {{
                        client.getAccountActivityEventAsync(null, "event");
                      }}
                    }}
                """

                result = migrate.analyze_text(source)

                self.assertEqual(
                    ["SSE006"], [finding.code for finding in result.findings]
                )

    def test_reports_qualified_handwritten_client_receiver(self):
        source = """
            import markets.alpaca.client.broker.sse.BrokerEventsSseClient;
            class Example {
              private BrokerEventsSseClient client;
              GeneratedOperation bind(Example other) {
                other.client.getAccountActivityEventAsync(null, "event");
                return other.client::getAccountActivityEventAsync;
              }
            }
        """

        result = migrate.analyze_text(source)

        self.assertEqual(["SSE006", "SSE006"], [finding.code for finding in result.findings])

    def test_reports_this_receiver_without_matching_handwritten_field(self):
        sources = (
            """
                import markets.alpaca.client.broker.sse.BrokerEventsSseClient;
                class Base {
                  protected AccountsApi client;
                }
                class Example extends Base {
                  void fetch(BrokerEventsSseClient client) {
                    this.client.getAccountActivityEventAsync(null, "event");
                  }
                }
            """,
            """
                import markets.alpaca.client.broker.sse.BrokerEventsSseClient;
                class Example {
                  private AccountsApi client;
                  void fetch(BrokerEventsSseClient client) {
                    this.client.getAccountActivityEventAsync(null, "event");
                  }
                }
            """,
        )

        for source in sources:
            with self.subTest(source=source):
                result = migrate.analyze_text(source)
                self.assertEqual(
                    ["SSE006"], [finding.code for finding in result.findings]
                )

    def test_reports_secondary_generated_declarator_inside_anonymous_class(self):
        source = """
            class Example {
              AccountsApi generated, client;
              Runnable task = new Runnable() {
                public void run() {
                  client.getAccountActivityEventAsync(null, "event");
                }
              };
            }
        """

        result = migrate.analyze_text(source)

        self.assertEqual(["SSE006"], [finding.code for finding in result.findings])

    def test_this_receiver_uses_anonymous_class_field_type(self):
        generated_source = """
            import markets.alpaca.client.broker.sse.BrokerEventsSseClient;
            class Example {
              BrokerEventsSseClient client;
              Runnable task = new Runnable() {
                AccountsApi client;
                public void run() {
                  this.client.getAccountActivityEventAsync(null, "event");
                }
              };
            }
        """
        handwritten_source = """
            import markets.alpaca.client.broker.sse.BrokerEventsSseClient;
            class Example {
              AccountsApi client;
              Runnable task = register(new Runnable() {
                BrokerEventsSseClient client;
                public void run() {
                  this.client.getAccountActivityEventAsync(null, "event");
                }
              });
            }
        """

        result = migrate.analyze_text(generated_source)

        self.assertEqual(["SSE006"], [finding.code for finding in result.findings])
        self.assertEqual((), migrate.analyze_text(handwritten_source).findings)

    def test_initializer_argument_is_not_treated_as_secondary_declarator(self):
        source = """
            import markets.alpaca.client.broker.sse.BrokerEventsSseClient;
            class Base {
              protected AccountsApi client;
            }
            class Example extends Base {
              BrokerEventsSseClient adapter = createAdapter("value", client);
              void fetch() {
                client.getAccountActivityEventAsync(null, "event");
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
            + "  void close(BrokerSseSubscription subscription) {\n"
            + "    subscription.eventSource().cancel();\n"
            + "  }\n"
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

    def test_write_preserves_crlf_line_endings(self):
        source = (
            IMPORT
            + "class Example {\n"
            + "  void close(BrokerSseSubscription subscription) {\n"
            + "    subscription.eventSource().cancel();\n"
            + "  }\n"
            + "}\n"
        ).replace("\n", "\r\n")
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "Example.java"
            path.write_bytes(source.encode("utf-8"))

            with redirect_stdout(io.StringIO()):
                self.assertEqual(0, migrate.main(["--write", str(path)]))

            rewritten = path.read_bytes()
            self.assertIn(b"subscription.close();\r\n", rewritten)
            self.assertNotIn(b"\n", rewritten.replace(b"\r\n", b""))

    def test_scans_checkout_nested_beneath_build_directory(self):
        source = (
            IMPORT
            + "class Example {\n"
            + "  void close(BrokerSseSubscription subscription) {\n"
            + "    subscription.eventSource().cancel();\n"
            + "  }\n"
            + "}\n"
        )
        with tempfile.TemporaryDirectory() as directory:
            checkout = Path(directory) / "build" / "checkout"
            path = checkout / "src" / "Example.java"
            path.parent.mkdir(parents=True)
            path.write_text(source, encoding="utf-8")

            output = io.StringIO()
            with redirect_stdout(output):
                exit_code = migrate.main(["--check", "--json", str(checkout)])

            self.assertEqual(1, exit_code)
            self.assertEqual(["SSE000"], [item["code"] for item in json.loads(output.getvalue())])

    def test_missing_input_path_fails_closed(self):
        with redirect_stderr(io.StringIO()):
            exit_code = migrate.main(["--check", "/definitely/missing/source"])

        self.assertEqual(2, exit_code)


if __name__ == "__main__":
    unittest.main()
