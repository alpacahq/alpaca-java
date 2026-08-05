import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

from scripts import release_tools


class ReleaseTagTest(unittest.TestCase):
    def test_valid_tag_returns_version(self):
        self.assertEqual(release_tools.parse_release_tag("v1.2.3"), "1.2.3")
        self.assertEqual(release_tools.parse_release_tag("v0.0.0"), "0.0.0")

    def test_huge_components_are_supported(self):
        huge = "9" * 5001
        self.assertEqual(
            release_tools.parse_release_tag(f"v{huge}.2.3"), f"{huge}.2.3"
        )

    def test_invalid_tags_are_rejected(self):
        for tag in (
            "1.2.3",
            "v01.2.3",
            "v1.02.3",
            "v1.2.03",
            "v1.2",
            "v1.2.3-SNAPSHOT",
            "v1.2.3;echo unsafe",
            f"v0{'9' * 5001}.2.3",
        ):
            with self.subTest(tag=tag):
                with self.assertRaises(release_tools.ReleaseToolError):
                    release_tools.parse_release_tag(tag)


class RecoveryPomTest(unittest.TestCase):
    def setUp(self):
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        self.pom = Path(self.temporary_directory.name) / "release.pom"

    def validate(self):
        return release_tools.validate_recovery_pom(
            self.pom,
            group="markets.alpaca",
            artifact="alpaca-java",
            version="1.2.3",
        )

    def test_valid_namespaced_pom(self):
        self.pom.write_text(
            """<?xml version="1.0"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>markets.alpaca</groupId>
  <artifactId>alpaca-java</artifactId>
  <version>1.2.3</version>
</project>
""",
            encoding="utf-8",
        )
        self.assertEqual(
            self.validate(),
            {
                "groupId": "markets.alpaca",
                "artifactId": "alpaca-java",
                "version": "1.2.3",
            },
        )

    def test_valid_utf8_bom_pom(self):
        self.pom.write_bytes(
            release_tools._UTF8_BOM
            + (
                "<project><groupId>markets.alpaca</groupId>"
                "<artifactId>alpaca-java</artifactId>"
                "<version>1.2.3</version></project>"
            ).encode("utf-8")
        )
        self.assertEqual(self.validate()["version"], "1.2.3")

    def test_oversize_pom_is_rejected_before_parsing(self):
        self.pom.write_bytes(b" " * (release_tools.MAX_RECOVERY_POM_BYTES + 1))
        with self.assertRaisesRegex(
            release_tools.ReleaseToolError, "exceeds .* bytes"
        ):
            self.validate()

    def test_malformed_pom_is_rejected(self):
        self.pom.write_text("<project>", encoding="utf-8")
        with self.assertRaises(release_tools.ReleaseToolError):
            self.validate()

    def test_doctype_is_rejected(self):
        self.pom.write_text(
            """<!DOCTYPE project [<!ENTITY x "markets.alpaca">]>
<project><groupId>&x;</groupId><artifactId>alpaca-java</artifactId>
<version>1.2.3</version></project>
""",
            encoding="utf-8",
        )
        with self.assertRaises(release_tools.ReleaseToolError):
            self.validate()

    def test_doctype_text_in_non_declaration_content_is_accepted(self):
        for content in (
            "<!-- <!DOCTYPE project> -->",
            "<notes><![CDATA[<!ENTITY x \"markets.alpaca\">]]></notes>",
            "<?release-note <!DOCTYPE project>?>",
        ):
            with self.subTest(content=content):
                self.pom.write_text(
                    "<project>"
                    + content
                    + "<groupId>markets.alpaca</groupId>"
                    "<artifactId>alpaca-java</artifactId>"
                    "<version>1.2.3</version></project>",
                    encoding="utf-8",
                )
                self.assertEqual(self.validate()["version"], "1.2.3")

    def test_utf16_entity_bypass_is_rejected_before_elementtree(self):
        document = """<?xml version="1.0" encoding="UTF-16"?>
<!DOCTYPE project [<!ENTITY group "markets.alpaca">]>
<project><groupId>&group;</groupId><artifactId>alpaca-java</artifactId>
<version>1.2.3</version></project>
"""
        self.pom.write_bytes(document.encode("utf-16"))
        with mock.patch.object(release_tools.ET, "fromstring") as parse:
            with self.assertRaisesRegex(
                release_tools.ReleaseToolError, "unsupported UTF-16 or UTF-32"
            ):
                self.validate()
            parse.assert_not_called()

    def test_utf32_entity_bypass_is_rejected_before_elementtree(self):
        document = """<?xml version="1.0" encoding="UTF-32"?>
<!DOCTYPE project [<!ENTITY group "markets.alpaca">]>
<project><groupId>&group;</groupId><artifactId>alpaca-java</artifactId>
<version>1.2.3</version></project>
"""
        self.pom.write_bytes(document.encode("utf-32"))
        with mock.patch.object(release_tools.ET, "fromstring") as parse:
            with self.assertRaisesRegex(
                release_tools.ReleaseToolError, "unsupported UTF-16 or UTF-32"
            ):
                self.validate()
            parse.assert_not_called()

    def test_mixed_case_doctype_and_entity_declarations_are_rejected(self):
        for declaration in (
            '<!DoCtYpE project [<!ENTITY x "markets.alpaca">]>',
            '<!EnTiTy x "markets.alpaca">',
        ):
            with self.subTest(declaration=declaration):
                self.pom.write_text(
                    declaration
                    + "<project><groupId>markets.alpaca</groupId>"
                    "<artifactId>alpaca-java</artifactId>"
                    "<version>1.2.3</version></project>",
                    encoding="utf-8",
                )
                with self.assertRaisesRegex(
                    release_tools.ReleaseToolError, "DOCTYPE or ENTITY"
                ):
                    self.validate()

    def test_internal_entity_expansion_is_rejected(self):
        self.pom.write_text(
            '<!DOCTYPE project [<!ENTITY group "markets.alpaca">]>'
            "<project><groupId>&group;</groupId>"
            "<artifactId>alpaca-java</artifactId>"
            "<version>1.2.3</version></project>",
            encoding="utf-8",
        )
        with self.assertRaisesRegex(
            release_tools.ReleaseToolError, "DOCTYPE or ENTITY"
        ):
            self.validate()

    def test_invalid_utf8_is_rejected(self):
        self.pom.write_bytes(b"<project>\xff</project>")
        with self.assertRaisesRegex(release_tools.ReleaseToolError, "valid UTF-8"):
            self.validate()

    def test_declared_non_utf8_encoding_is_rejected(self):
        self.pom.write_text(
            '<?xml version="1.0" encoding="ISO-8859-1"?>'
            "<project><groupId>markets.alpaca</groupId>"
            "<artifactId>alpaca-java</artifactId>"
            "<version>1.2.3</version></project>",
            encoding="utf-8",
        )
        with self.assertRaisesRegex(
            release_tools.ReleaseToolError, "unsupported encoding"
        ):
            self.validate()

    def test_missing_pom_file_is_rejected(self):
        with self.assertRaises(release_tools.ReleaseToolError):
            self.validate()

    def test_missing_coordinate_is_rejected(self):
        self.pom.write_text(
            "<project><groupId>markets.alpaca</groupId>"
            "<version>1.2.3</version></project>",
            encoding="utf-8",
        )
        with self.assertRaises(release_tools.ReleaseToolError):
            self.validate()

    def test_duplicate_coordinate_is_rejected(self):
        self.pom.write_text(
            "<project><groupId>markets.alpaca</groupId>"
            "<groupId>markets.alpaca</groupId>"
            "<artifactId>alpaca-java</artifactId>"
            "<version>1.2.3</version></project>",
            encoding="utf-8",
        )
        with self.assertRaises(release_tools.ReleaseToolError):
            self.validate()

    def test_mismatched_coordinate_is_rejected(self):
        self.pom.write_text(
            "<project><groupId>markets.alpaca</groupId>"
            "<artifactId>other</artifactId>"
            "<version>1.2.3</version></project>",
            encoding="utf-8",
        )
        with self.assertRaises(release_tools.ReleaseToolError):
            self.validate()


class VersionUpdateTest(unittest.TestCase):
    def setUp(self):
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        self.properties = Path(self.temporary_directory.name) / "gradle.properties"

    def update(self, release="1.2.3"):
        return release_tools.update_next_snapshot(self.properties, release)

    def test_behind_updates_to_next_patch(self):
        self.properties.write_text("version=1.2.3-SNAPSHOT\n", encoding="utf-8")
        result = self.update()
        self.assertEqual(result.status, "behind")
        self.assertTrue(result.changed)
        self.assertEqual(
            self.properties.read_text(encoding="utf-8"),
            "version=1.2.4-SNAPSHOT\n",
        )

    def test_utf8_bom_and_crlf_are_preserved(self):
        self.properties.write_bytes(
            release_tools._UTF8_BOM + b"version=1.2.3-SNAPSHOT\r\n"
        )

        result = self.update()

        self.assertEqual(result.status, "behind")
        self.assertTrue(result.changed)
        self.assertEqual(
            self.properties.read_bytes(),
            release_tools._UTF8_BOM + b"version=1.2.4-SNAPSHOT\r\n",
        )

    def test_invalid_utf8_is_rejected_as_release_tool_error(self):
        self.properties.write_bytes(b"version=1.2.3-SNAPSHOT\xff\n")

        with self.assertRaisesRegex(
            release_tools.ReleaseToolError, "not valid UTF-8"
        ):
            self.update()

    def test_read_snapshot_version_accepts_utf8_bom(self):
        self.properties.write_bytes(
            release_tools._UTF8_BOM + b"version=1.2.4-SNAPSHOT\r\n"
        )

        self.assertEqual(
            release_tools.read_snapshot_version(self.properties),
            "1.2.4-SNAPSHOT",
        )

    def test_equal_is_noop(self):
        original = "version=1.2.4-SNAPSHOT\n"
        self.properties.write_text(original, encoding="utf-8")
        result = self.update()
        self.assertEqual(result.status, "equal")
        self.assertFalse(result.changed)
        self.assertEqual(self.properties.read_text(encoding="utf-8"), original)

    def test_ahead_is_noop(self):
        original = "version=2.0.0-SNAPSHOT\n"
        self.properties.write_text(original, encoding="utf-8")
        result = self.update()
        self.assertEqual(result.status, "ahead")
        self.assertFalse(result.changed)
        self.assertEqual(self.properties.read_text(encoding="utf-8"), original)

    def test_malformed_effective_version_is_rejected(self):
        self.properties.write_text("version=not-semver\n", encoding="utf-8")
        with self.assertRaises(release_tools.ReleaseToolError):
            self.update()

    def test_effective_last_version_entry_is_updated_only(self):
        self.properties.write_text(
            "version=9.9.9-SNAPSHOT\nother=value\n version = 1.2.3-SNAPSHOT \n",
            encoding="utf-8",
        )
        result = self.update()
        self.assertTrue(result.changed)
        self.assertEqual(
            self.properties.read_text(encoding="utf-8"),
            "version=9.9.9-SNAPSHOT\nother=value\nversion=1.2.4-SNAPSHOT\n",
        )

    def test_huge_patch_increment_carries_without_integer_conversion(self):
        huge = "9" * 5001
        incremented = "1" + ("0" * 5001)
        self.properties.write_text("version=0.0.0-SNAPSHOT\n", encoding="utf-8")
        result = self.update(f"1.2.{huge}")
        self.assertEqual(result.version, f"1.2.{incremented}-SNAPSHOT")
        self.assertEqual(result.status, "behind")

    def test_huge_equal_ahead_and_behind_comparisons(self):
        huge = "9" * 5001
        next_patch = "1" + ("0" * 5001)
        ahead_patch = "1" + ("0" * 5000) + "1"
        release = f"1.2.{huge}"

        cases = (
            (f"1.2.{next_patch}-SNAPSHOT", "equal", False),
            (f"1.2.{ahead_patch}-SNAPSHOT", "ahead", False),
            (f"1.2.{huge}-SNAPSHOT", "behind", True),
        )
        for current, expected_status, expected_changed in cases:
            with self.subTest(status=expected_status):
                self.properties.write_text(
                    f"version={current}\n", encoding="utf-8"
                )
                result = self.update(release)
                self.assertEqual(result.status, expected_status)
                self.assertEqual(result.changed, expected_changed)

    def test_malformed_release_version_is_rejected(self):
        self.properties.write_text("version=1.2.4-SNAPSHOT\n", encoding="utf-8")
        with self.assertRaises(release_tools.ReleaseToolError):
            self.update("01.2.3")

    def test_huge_leading_zero_versions_are_rejected(self):
        huge_with_leading_zero = "0" + ("9" * 5001)
        self.properties.write_text(
            f"version=1.2.{huge_with_leading_zero}-SNAPSHOT\n",
            encoding="utf-8",
        )
        with self.assertRaises(release_tools.ReleaseToolError):
            self.update("1.2.3")

        self.properties.write_text("version=1.2.4-SNAPSHOT\n", encoding="utf-8")
        with self.assertRaises(release_tools.ReleaseToolError):
            self.update(f"1.2.{huge_with_leading_zero}")


class ReleaseToolsCliTest(unittest.TestCase):
    def setUp(self):
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        self.root = Path(self.temporary_directory.name)
        self.script = Path(release_tools.__file__).resolve()
        self.environment = {
            **os.environ,
            "PYTHONDONTWRITEBYTECODE": "1",
        }

    def run_cli(self, *arguments):
        return subprocess.run(
            [sys.executable, str(self.script), *arguments],
            cwd=self.root,
            env=self.environment,
            capture_output=True,
            text=True,
            check=False,
        )

    def test_parse_tag_exit_codes_and_streams(self):
        success = self.run_cli("parse-tag", "v1.2.3")
        self.assertEqual(success.returncode, 0)
        self.assertEqual(success.stdout, "1.2.3\n")
        self.assertEqual(success.stderr, "")

        failure = self.run_cli("parse-tag", "v01.2.3")
        self.assertEqual(failure.returncode, 2)
        self.assertEqual(failure.stdout, "")
        self.assertIn("release tool error:", failure.stderr)

    def test_validate_pom_exit_codes_and_streams(self):
        pom = self.root / "release.pom"
        pom.write_text(
            "<project><groupId>markets.alpaca</groupId>"
            "<artifactId>alpaca-java</artifactId>"
            "<version>1.2.3</version></project>",
            encoding="utf-8",
        )
        arguments = (
            "validate-pom",
            "--pom",
            str(pom),
            "--group",
            "markets.alpaca",
            "--artifact",
            "alpaca-java",
            "--version",
            "1.2.3",
        )
        success = self.run_cli(*arguments)
        self.assertEqual(success.returncode, 0)
        self.assertEqual(success.stdout, "")
        self.assertEqual(success.stderr, "")

        pom.write_text("<project>", encoding="utf-8")
        failure = self.run_cli(*arguments)
        self.assertEqual(failure.returncode, 2)
        self.assertEqual(failure.stdout, "")
        self.assertIn("malformed XML", failure.stderr)

    def test_update_version_github_output_and_failure(self):
        properties = self.root / "gradle.properties"
        github_output = self.root / "github-output"
        properties.write_text("version=1.2.3-SNAPSHOT\n", encoding="utf-8")
        arguments = (
            "update-version",
            "--properties",
            str(properties),
            "--release-version",
            "1.2.3",
            "--github-output",
            str(github_output),
        )
        success = self.run_cli(*arguments)
        self.assertEqual(success.returncode, 0)
        self.assertEqual(success.stdout, "")
        self.assertEqual(success.stderr, "")
        self.assertEqual(
            github_output.read_text(encoding="utf-8"),
            "version=1.2.4-SNAPSHOT\nshould_push=true\nstatus=behind\n",
        )
        self.assertEqual(
            properties.read_text(encoding="utf-8"),
            "version=1.2.4-SNAPSHOT\n",
        )

        properties.write_text("version=malformed\n", encoding="utf-8")
        failure = self.run_cli(*arguments)
        self.assertEqual(failure.returncode, 2)
        self.assertEqual(failure.stdout, "")
        self.assertIn("not a semantic SNAPSHOT", failure.stderr)

    def test_update_version_invalid_utf8_uses_cli_error_contract(self):
        properties = self.root / "gradle.properties"
        properties.write_bytes(b"version=1.2.3-SNAPSHOT\xff\n")

        result = self.run_cli(
            "update-version",
            "--properties",
            str(properties),
            "--release-version",
            "1.2.3",
        )

        self.assertEqual(result.returncode, 2)
        self.assertEqual(result.stdout, "")
        self.assertIn("release tool error:", result.stderr)
        self.assertIn("not valid UTF-8", result.stderr)

    def test_read_version_accepts_bom_and_rejects_release_version(self):
        properties = self.root / "gradle.properties"
        properties.write_bytes(
            release_tools._UTF8_BOM + b"version=1.2.4-SNAPSHOT\r\n"
        )

        success = self.run_cli("read-version", "--properties", str(properties))

        self.assertEqual(success.returncode, 0)
        self.assertEqual(success.stdout, "1.2.4-SNAPSHOT\n")
        self.assertEqual(success.stderr, "")

        properties.write_text("version=1.2.4\n", encoding="utf-8")
        failure = self.run_cli("read-version", "--properties", str(properties))
        self.assertEqual(failure.returncode, 2)
        self.assertEqual(failure.stdout, "")
        self.assertIn("not a semantic SNAPSHOT", failure.stderr)

    def test_check_main_freshness_github_output_and_fail_closed(self):
        commit = "a" * 40
        github_output = self.root / "github-output"
        success = self.run_cli(
            "check-main-freshness",
            "--commit",
            commit,
            "--remote-main",
            commit,
            "--github-output",
            str(github_output),
        )
        self.assertEqual(success.returncode, 0)
        self.assertEqual(success.stdout, "")
        self.assertEqual(success.stderr, "")
        self.assertEqual(
            github_output.read_text(encoding="utf-8"),
            "should_publish=true\n",
        )

        stale = self.run_cli(
            "check-main-freshness",
            "--commit",
            commit,
            "--remote-main",
            "b" * 40,
        )
        self.assertEqual(stale.returncode, 0)
        self.assertEqual(stale.stdout, "should_publish=false\n")

        failure = self.run_cli(
            "check-main-freshness",
            "--commit",
            commit,
            "--remote-main",
            "b" * 40,
            "--fail-if-stale",
        )
        self.assertEqual(failure.returncode, 2)
        self.assertEqual(failure.stdout, "")
        self.assertIn("release tool error:", failure.stderr)
        self.assertIn("stale", failure.stderr)

    def test_extract_changelog_cli_writes_notes_and_source(self):
        changelog = self.root / "CHANGELOG.md"
        changelog.write_text(
            "## [Unreleased]\n\n- Pending.\n\n## [1.2.3] - 2026-01-02\n\n- Shipped.\n",
            encoding="utf-8",
        )
        notes = self.root / "notes.md"
        github_output = self.root / "github-output"

        success = self.run_cli(
            "extract-changelog",
            "--changelog",
            str(changelog),
            "--version",
            "1.2.3",
            "--allow-unreleased",
            "--output",
            str(notes),
            "--github-output",
            str(github_output),
        )
        self.assertEqual(success.returncode, 0)
        self.assertEqual(success.stdout, "")
        self.assertEqual(success.stderr, "")
        self.assertEqual(notes.read_text(encoding="utf-8"), "- Shipped.\n")
        self.assertEqual(
            github_output.read_text(encoding="utf-8"), "source=version\n"
        )

        fallback = self.run_cli(
            "extract-changelog",
            "--changelog",
            str(changelog),
            "--version",
            "9.9.9",
            "--allow-unreleased",
        )
        self.assertEqual(fallback.returncode, 0)
        self.assertEqual(fallback.stdout, "- Pending.\n")

        failure = self.run_cli(
            "extract-changelog",
            "--changelog",
            str(changelog),
            "--version",
            "9.9.9",
        )
        self.assertEqual(failure.returncode, 2)
        self.assertEqual(failure.stdout, "")
        self.assertIn("release tool error:", failure.stderr)

    def test_promote_changelog_cli_reports_status(self):
        changelog = self.root / "CHANGELOG.md"
        changelog.write_text(
            "## [Unreleased]\n\n- Pending.\n\n## [1.0.0] - 2025-01-01\n\n- Old.\n",
            encoding="utf-8",
        )
        expected = self.root / "expected.md"
        expected.write_text("- Pending.\n", encoding="utf-8")
        github_output = self.root / "github-output"

        arguments = (
            "promote-changelog",
            "--changelog",
            str(changelog),
            "--version",
            "1.2.3",
            "--date",
            "2026-08-05",
            "--expected-body",
            str(expected),
            "--github-output",
            str(github_output),
        )
        success = self.run_cli(*arguments)
        self.assertEqual(success.returncode, 0)
        self.assertEqual(success.stdout, "")
        self.assertEqual(success.stderr, "")
        self.assertEqual(
            github_output.read_text(encoding="utf-8"),
            "changelog_status=promoted\nchanged=true\n",
        )
        self.assertIn(
            "## [1.2.3] - 2026-08-05", changelog.read_text(encoding="utf-8")
        )

        github_output.unlink()
        repeated = self.run_cli(*arguments)
        self.assertEqual(repeated.returncode, 0)
        self.assertEqual(
            github_output.read_text(encoding="utf-8"),
            "changelog_status=unchanged\nchanged=false\n",
        )

        expected.write_text("- Notes that were actually published.\n", encoding="utf-8")
        github_output.unlink()
        diverged = self.run_cli(*arguments)
        self.assertEqual(diverged.returncode, 0)
        self.assertEqual(
            github_output.read_text(encoding="utf-8"),
            "changelog_status=diverged\nchanged=false\n",
        )

        failure = self.run_cli(
            "promote-changelog",
            "--changelog",
            str(changelog),
            "--version",
            "2.0.0",
            "--date",
            "2026-08-05",
        )
        self.assertEqual(failure.returncode, 2)
        self.assertEqual(failure.stdout, "")
        self.assertIn("release tool error:", failure.stderr)

    def test_prepare_frozen_specs_cli(self):
        source = self.root / "build" / "specs"
        for api in ("broker", "data", "trading"):
            directory = source / api
            directory.mkdir(parents=True)
            (directory / "openapi.yaml").write_text(f"{api}\n", encoding="utf-8")

        output = self.root / "frozen"
        success = self.run_cli(
            "prepare-frozen-specs",
            "--source-root",
            str(source),
            "--output-dir",
            str(output),
        )
        self.assertEqual(success.returncode, 0)
        self.assertEqual(success.stdout, f"{output}\n")
        self.assertEqual(success.stderr, "")
        for api in ("broker", "data", "trading"):
            spec = output / api / "openapi.yaml"
            self.assertEqual(spec.read_text(encoding="utf-8"), f"{api}\n")
            self.assertFalse(os.access(spec, os.W_OK))


class MainFreshnessTest(unittest.TestCase):
    def test_current_commit_publishes(self):
        commit = "a" * 40
        self.assertEqual(
            release_tools.evaluate_main_freshness(commit, commit, fail_if_stale=False),
            "publish",
        )

    def test_stale_commit_skips_by_default(self):
        self.assertEqual(
            release_tools.evaluate_main_freshness(
                "a" * 40, "b" * 40, fail_if_stale=False
            ),
            "skip",
        )

    def test_stale_commit_fails_when_required(self):
        with self.assertRaises(release_tools.ReleaseToolError):
            release_tools.evaluate_main_freshness(
                "a" * 40, "b" * 40, fail_if_stale=True
            )

    def test_invalid_shas_are_rejected(self):
        for commit, remote in (
            ("short", "a" * 40),
            ("a" * 40, "short"),
            ("g" * 40, "a" * 40),
            ("A" * 39 + "g", "a" * 40),
        ):
            with self.subTest(commit=commit, remote=remote):
                with self.assertRaises(release_tools.ReleaseToolError):
                    release_tools.evaluate_main_freshness(
                        commit, remote, fail_if_stale=False
                    )


class PrepareFrozenSpecsTest(unittest.TestCase):
    def setUp(self):
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        self.root = Path(self.temporary_directory.name)
        self.source = self.root / "build" / "specs"
        for api in ("broker", "data", "trading"):
            directory = self.source / api
            directory.mkdir(parents=True)
            (directory / "openapi.yaml").write_text(f"{api}-spec\n", encoding="utf-8")

    def test_copies_specs_read_only(self):
        output = self.root / "frozen"
        result = release_tools.prepare_frozen_specs(self.source, output)
        self.assertEqual(result, output)
        for api in ("broker", "data", "trading"):
            spec = output / api / "openapi.yaml"
            self.assertEqual(spec.read_text(encoding="utf-8"), f"{api}-spec\n")
            self.assertEqual(stat.S_IMODE(spec.stat().st_mode), 0o444)
            self.assertEqual(
                stat.S_IMODE((output / api).stat().st_mode),
                0o555,
            )
        self.assertEqual(stat.S_IMODE(output.stat().st_mode), 0o555)

    def test_empty_spec_is_rejected(self):
        (self.source / "data" / "openapi.yaml").write_text("", encoding="utf-8")
        with self.assertRaises(release_tools.ReleaseToolError):
            release_tools.prepare_frozen_specs(self.source, self.root / "frozen")

    def test_missing_spec_is_rejected(self):
        (self.source / "trading" / "openapi.yaml").unlink()
        with self.assertRaises(release_tools.ReleaseToolError):
            release_tools.prepare_frozen_specs(self.source, self.root / "frozen")


SAMPLE_CHANGELOG = """# Changelog

## [Unreleased]

### Added
- Pending feature.

## [1.2.3] - 2026-01-02

### Fixed
- Important fix.
- Second bullet.

## [1.2.2] - 2025-12-01

### Changed
- Older change.
"""


class ExtractChangelogTest(unittest.TestCase):
    def setUp(self):
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        self.changelog = Path(self.temporary_directory.name) / "CHANGELOG.md"
        self.changelog.write_text(SAMPLE_CHANGELOG, encoding="utf-8")

    def test_extracts_version_section_body(self):
        result = release_tools.extract_changelog(
            self.changelog, "1.2.3", allow_unreleased=False
        )
        self.assertEqual(result.source, "version")
        self.assertEqual(
            result.body,
            "### Fixed\n- Important fix.\n- Second bullet.",
        )

    def test_does_not_leak_adjacent_sections(self):
        result = release_tools.extract_changelog(
            self.changelog, "1.2.3", allow_unreleased=True
        )
        self.assertNotIn("Pending feature", result.body)
        self.assertNotIn("Older change", result.body)
        self.assertNotIn("## [", result.body)

    def test_prefers_version_over_unreleased(self):
        result = release_tools.extract_changelog(
            self.changelog, "1.2.3", allow_unreleased=True
        )
        self.assertEqual(result.source, "version")
        self.assertIn("Important fix", result.body)

    def test_falls_back_to_unreleased_when_allowed(self):
        result = release_tools.extract_changelog(
            self.changelog, "9.9.9", allow_unreleased=True
        )
        self.assertEqual(result.source, "unreleased")
        self.assertEqual(result.body, "### Added\n- Pending feature.")

    def test_rejects_missing_version_without_fallback(self):
        with self.assertRaises(release_tools.ReleaseToolError):
            release_tools.extract_changelog(
                self.changelog, "9.9.9", allow_unreleased=False
            )

    def test_rejects_empty_unreleased_fallback(self):
        self.changelog.write_text(
            "## [Unreleased]\n\n## [1.0.0] - 2026-01-01\n\n### Added\n- Ship it.\n",
            encoding="utf-8",
        )
        with self.assertRaises(release_tools.ReleaseToolError):
            release_tools.extract_changelog(
                self.changelog, "2.0.0", allow_unreleased=True
            )

    def test_rejects_empty_version_section(self):
        self.changelog.write_text(
            "## [Unreleased]\n\n## [1.0.0] - 2026-01-01\n\n",
            encoding="utf-8",
        )
        with self.assertRaises(release_tools.ReleaseToolError):
            release_tools.extract_changelog(
                self.changelog, "1.0.0", allow_unreleased=True
            )

    def test_empty_version_section_never_falls_back_to_unreleased(self):
        self.changelog.write_text(
            "## [Unreleased]\n\n- pending\n\n## [1.0.0] - 2026-01-01\n\n",
            encoding="utf-8",
        )
        with self.assertRaises(release_tools.ReleaseToolError):
            release_tools.extract_changelog(
                self.changelog, "1.0.0", allow_unreleased=True
            )


class PromoteChangelogTest(unittest.TestCase):
    def setUp(self):
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        self.changelog = Path(self.temporary_directory.name) / "CHANGELOG.md"

    def test_promotes_unreleased_when_version_missing(self):
        self.changelog.write_text(
            "# Changelog\n\n## [Unreleased]\n\n### Added\n- Feature.\n\n"
            "## [1.0.0] - 2025-01-01\n\n### Fixed\n- Old.\n",
            encoding="utf-8",
        )
        result = release_tools.promote_changelog(
            self.changelog, "1.2.3", release_date="2026-08-05"
        )
        self.assertEqual(result.status, "promoted")
        self.assertTrue(result.changed)
        text = self.changelog.read_text(encoding="utf-8")
        self.assertIn("## [Unreleased]\n\n## [1.2.3] - 2026-08-05\n", text)
        self.assertIn("### Added\n- Feature.\n", text)
        self.assertIn("## [1.0.0] - 2025-01-01\n", text)
        version = release_tools.extract_changelog(
            self.changelog, "1.2.3", allow_unreleased=False
        )
        self.assertEqual(version.body, "### Added\n- Feature.")
        with self.assertRaises(release_tools.ReleaseToolError):
            release_tools.extract_changelog(
                self.changelog, "9.9.9", allow_unreleased=True
            )

    def test_leaves_changelog_when_version_exists(self):
        original = SAMPLE_CHANGELOG
        self.changelog.write_text(original, encoding="utf-8")
        result = release_tools.promote_changelog(
            self.changelog, "1.2.3", release_date="2026-08-05"
        )
        self.assertEqual(result.status, "unchanged")
        self.assertFalse(result.changed)
        self.assertEqual(self.changelog.read_text(encoding="utf-8"), original)

    def test_fails_when_nothing_to_promote(self):
        self.changelog.write_text(
            "## [Unreleased]\n\n## [1.0.0] - 2025-01-01\n\n### Fixed\n- Old.\n",
            encoding="utf-8",
        )
        with self.assertRaises(release_tools.ReleaseToolError):
            release_tools.promote_changelog(
                self.changelog, "2.0.0", release_date="2026-08-05"
            )

    def test_fails_when_version_section_is_empty(self):
        self.changelog.write_text(
            "## [Unreleased]\n\n- pending\n\n## [1.2.3] - 2026-01-01\n\n",
            encoding="utf-8",
        )
        with self.assertRaises(release_tools.ReleaseToolError):
            release_tools.promote_changelog(
                self.changelog, "1.2.3", release_date="2026-08-05"
            )

    def test_promotes_when_unreleased_matches_expected_body(self):
        self.changelog.write_text(
            "## [Unreleased]\n\n### Added\n- Feature.\n\n## [1.0.0] - 2025-01-01\n\n- Old.\n",
            encoding="utf-8",
        )
        result = release_tools.promote_changelog(
            self.changelog,
            "1.2.3",
            release_date="2026-08-05",
            expected_body="### Added\n- Feature.\n",
        )
        self.assertEqual(result.status, "promoted")
        self.assertIn(
            "## [1.2.3] - 2026-08-05",
            self.changelog.read_text(encoding="utf-8"),
        )

    def test_existing_version_section_matching_expected_body_is_unchanged(self):
        self.changelog.write_text(
            "## [Unreleased]\n\n## [1.2.3] - 2026-08-05\n\n### Added\n- Feature.\n",
            encoding="utf-8",
        )
        result = release_tools.promote_changelog(
            self.changelog,
            "1.2.3",
            release_date="2026-08-05",
            expected_body="### Added\n- Feature.",
        )
        self.assertEqual(result.status, "unchanged")
        self.assertFalse(result.changed)

    def test_existing_version_section_differing_from_published_notes_diverges(self):
        original = (
            "## [Unreleased]\n\n## [1.2.3] - 2026-08-05\n\n### Added\n- Rewritten later.\n"
        )
        self.changelog.write_text(original, encoding="utf-8")
        result = release_tools.promote_changelog(
            self.changelog,
            "1.2.3",
            release_date="2026-08-05",
            expected_body="### Added\n- Feature.",
        )
        self.assertEqual(result.status, "diverged")
        self.assertFalse(result.changed)
        self.assertEqual(self.changelog.read_text(encoding="utf-8"), original)

    def test_fails_when_unreleased_drifted_from_expected_body(self):
        original = (
            "## [Unreleased]\n\n### Added\n- Feature.\n- Landed after the tag.\n\n"
            "## [1.0.0] - 2025-01-01\n\n- Old.\n"
        )
        self.changelog.write_text(original, encoding="utf-8")
        with self.assertRaises(release_tools.ReleaseToolError):
            release_tools.promote_changelog(
                self.changelog,
                "1.2.3",
                release_date="2026-08-05",
                expected_body="### Added\n- Feature.",
            )
        self.assertEqual(self.changelog.read_text(encoding="utf-8"), original)


class ComposeGithubReleaseNotesTest(unittest.TestCase):
    def test_appends_full_changelog_link_only(self):
        curated = "### Fixed\n- Important fix."
        generated = (
            "## What's Changed\n"
            "* chore(deps): bump foo by @dependabot in https://example/pull/1\n"
            "* fix: Important fix by @dev in https://example/pull/2\n"
            "\n"
            "**Full Changelog**: https://github.com/alpacahq/alpaca-java/compare/v1.2.2...v1.2.3"
        )
        self.assertEqual(
            release_tools.compose_github_release_notes(curated, generated),
            "### Fixed\n- Important fix.\n\n"
            "**Full Changelog**: https://github.com/alpacahq/alpaca-java/compare/v1.2.2...v1.2.3\n",
        )

    def test_omits_link_when_generate_notes_has_none(self):
        self.assertEqual(
            release_tools.compose_github_release_notes("### Added\n- Feature.", ""),
            "### Added\n- Feature.\n",
        )


if __name__ == "__main__":
    unittest.main()
