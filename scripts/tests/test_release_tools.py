import os
from pathlib import Path
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


if __name__ == "__main__":
    unittest.main()
