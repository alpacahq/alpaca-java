#!/usr/bin/env python3
"""Tests for scripts/adopt_openapi.py orchestration helpers."""

from __future__ import annotations

import contextlib
from pathlib import Path
import tempfile
import unittest
from unittest import mock

from scripts import adopt_openapi


@contextlib.contextmanager
def _adopt_workspace():
    """Temporary repo root holding pinned specs and adopt candidates for all APIs."""
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        for api in adopt_openapi.APIS:
            pinned = root / "specs" / api / "openapi.yaml"
            pinned.parent.mkdir(parents=True)
            pinned.write_text(f"pinned {api}\n", encoding="utf-8")
            candidate = root / "build" / "specs-adopt" / api / "openapi.yaml"
            candidate.parent.mkdir(parents=True)
            candidate.write_text(f"upstream {api}\n", encoding="utf-8")
        yield root


def _patched_root(root: Path):
    return mock.patch.multiple(
        adopt_openapi,
        ROOT=root,
        PIN_BACKUP_ROOT=root / "build" / "specs-pin-backup",
    )


class ResolveAdoptExitCodeTests(unittest.TestCase):
    def test_no_changes_is_success(self):
        self.assertEqual(
            adopt_openapi.resolve_adopt_exit_code(
                dry_run=False,
                yes=True,
                allow_breaking=False,
                any_changes=False,
                any_breaking=False,
            ),
            0,
        )

    def test_dry_run_succeeds_even_when_breaking(self):
        self.assertEqual(
            adopt_openapi.resolve_adopt_exit_code(
                dry_run=True,
                yes=False,
                allow_breaking=False,
                any_changes=True,
                any_breaking=True,
            ),
            0,
        )

    def test_breaking_without_allow_returns_two(self):
        self.assertEqual(
            adopt_openapi.resolve_adopt_exit_code(
                dry_run=False,
                yes=True,
                allow_breaking=False,
                any_changes=True,
                any_breaking=True,
            ),
            2,
        )

    def test_breaking_with_allow_proceeds(self):
        self.assertEqual(
            adopt_openapi.resolve_adopt_exit_code(
                dry_run=False,
                yes=True,
                allow_breaking=True,
                any_changes=True,
                any_breaking=True,
            ),
            0,
        )

    def test_additive_without_yes_returns_one(self):
        self.assertEqual(
            adopt_openapi.resolve_adopt_exit_code(
                dry_run=False,
                yes=False,
                allow_breaking=False,
                any_changes=True,
                any_breaking=False,
            ),
            1,
        )

    def test_additive_with_yes_proceeds(self):
        self.assertEqual(
            adopt_openapi.resolve_adopt_exit_code(
                dry_run=False,
                yes=True,
                allow_breaking=False,
                any_changes=True,
                any_breaking=False,
            ),
            0,
        )


class UpstreamUrlsAndDownloadTests(unittest.TestCase):
    def test_load_upstream_defaults_matches_shared_json(self):
        urls = adopt_openapi.load_upstream_defaults()
        self.assertEqual(
            set(urls),
            {"broker", "data", "trading"},
        )
        for value in urls.values():
            self.assertTrue(value.startswith("https://"))

    def test_download_rejects_http_error(self):
        from pathlib import Path
        from tempfile import TemporaryDirectory
        from unittest import mock
        import urllib.error

        with TemporaryDirectory() as tmp:
            destination = Path(tmp) / "spec.json"
            with mock.patch(
                "urllib.request.urlopen",
                side_effect=urllib.error.HTTPError(
                    url="https://example.test/openapi.json",
                    code=503,
                    msg="Unavailable",
                    hdrs=None,
                    fp=None,
                ),
            ):
                with self.assertRaises(SystemExit) as ctx:
                    adopt_openapi._download(
                        "https://example.test/openapi.json",
                        destination,
                    )
            self.assertIn("HTTP 503", str(ctx.exception))
            self.assertFalse(destination.exists())


def _additive_load_spec(path):
    """Pinned vs candidate specs differing only by an added schema."""
    schemas = {"Order": {"type": "object", "properties": {}}}
    if "specs-adopt" in str(path):
        schemas["Asset"] = {"type": "object", "properties": {}}
    return {"openapi": "3.0.3", "paths": {}, "components": {"schemas": schemas}}


class PinBackupTests(unittest.TestCase):
    def test_restore_pins_undoes_a_pin_write(self):
        with _adopt_workspace() as root:
            with _patched_root(root):
                adopt_openapi.write_pins(root / "build" / "specs-adopt")
                self.assertEqual(
                    (root / "specs" / "broker" / "openapi.yaml").read_text(encoding="utf-8"),
                    "upstream broker\n",
                )
                adopt_openapi.restore_pins()

            for api in adopt_openapi.APIS:
                self.assertEqual(
                    (root / "specs" / api / "openapi.yaml").read_text(encoding="utf-8"),
                    f"pinned {api}\n",
                )
            self.assertFalse((root / "build" / "specs-pin-backup").exists())

    def test_restore_pins_without_backup_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            with mock.patch.object(adopt_openapi, "PIN_BACKUP_ROOT", Path(tmp)):
                with self.assertRaises(SystemExit) as ctx:
                    adopt_openapi.restore_pins()
        self.assertIn("No pin backup", str(ctx.exception))

    def test_write_pins_leaves_no_partial_update(self):
        with _adopt_workspace() as root:
            # A missing candidate fails the write part way through the three APIs.
            (root / "build" / "specs-adopt" / "trading" / "openapi.yaml").unlink()
            with _patched_root(root):
                with self.assertRaises(OSError):
                    adopt_openapi.write_pins(root / "build" / "specs-adopt")

            for api in adopt_openapi.APIS:
                self.assertEqual(
                    (root / "specs" / api / "openapi.yaml").read_text(encoding="utf-8"),
                    f"pinned {api}\n",
                )
            # Pins never advanced, so nothing may claim there is an adopt to undo.
            self.assertFalse((root / "build" / "specs-pin-backup").exists())

    def _adopt(self):
        return adopt_openapi.adopt(
            dry_run=False,
            yes=True,
            allow_breaking=False,
            skip_generate=False,
            skip_fetch=True,
            skip_preprocess=True,
        )

    def test_adopt_restores_pins_and_regenerates_when_generate_fails(self):
        import subprocess

        commands: list[list[str]] = []

        def fail_the_first_run(command):
            commands.append(command)
            if len(commands) == 1:
                raise subprocess.CalledProcessError(1, "gradlew")

        with _adopt_workspace() as root:
            with _patched_root(root), mock.patch.object(
                adopt_openapi.openapi_tools, "load_spec", side_effect=_additive_load_spec
            ), mock.patch.object(adopt_openapi, "_run", side_effect=fail_the_first_run):
                with self.assertRaises(SystemExit) as ctx:
                    self._adopt()

            # Both the initial regenerate and recovery must force pin paths so env /
            # local.properties redirects cannot bypass the pins just written (or restored).
            pin_args = [
                f"-P{api}Spec={root / 'specs' / api / 'openapi.yaml'}"
                for api in adopt_openapi.APIS
            ]
            self.assertEqual(commands[0][1:], ["generateApis", "test"] + pin_args)
            self.assertEqual(commands[1][1:], ["clearOpenApiPinBackup"] + pin_args)
            self.assertIn("pins and generated sources were restored", str(ctx.exception))
            for api in adopt_openapi.APIS:
                self.assertEqual(
                    (root / "specs" / api / "openapi.yaml").read_text(encoding="utf-8"),
                    f"pinned {api}\n",
                )
            self.assertFalse((root / "build" / "specs-pin-backup").exists())

    def test_adopt_reports_when_regenerating_restored_pins_also_fails(self):
        import subprocess

        with _adopt_workspace() as root:
            with _patched_root(root), mock.patch.object(
                adopt_openapi.openapi_tools, "load_spec", side_effect=_additive_load_spec
            ), mock.patch.object(
                adopt_openapi,
                "_run",
                side_effect=subprocess.CalledProcessError(1, "gradlew"),
            ):
                with self.assertRaises(SystemExit) as ctx:
                    self._adopt()

            self.assertIn("regenerating from them failed too", str(ctx.exception))
            for api in adopt_openapi.APIS:
                self.assertEqual(
                    (root / "specs" / api / "openapi.yaml").read_text(encoding="utf-8"),
                    f"pinned {api}\n",
                )

    def test_adopt_keeps_pins_when_only_the_nested_tests_fail(self):
        import subprocess

        def generate_then_fail(_command):
            # Nested clearOpenApiPinBackup drops the backup before `test` runs.
            adopt_openapi.clear_pin_backup()
            raise subprocess.CalledProcessError(1, "gradlew")

        with _adopt_workspace() as root:
            with _patched_root(root), mock.patch.object(
                adopt_openapi.openapi_tools, "load_spec", side_effect=_additive_load_spec
            ), mock.patch.object(adopt_openapi, "_run", side_effect=generate_then_fail):
                with self.assertRaises(SystemExit) as ctx:
                    self._adopt()

            # Generated sources already match the new pins; rewinding would create drift.
            self.assertIn("revert both with git", str(ctx.exception))
            for api in adopt_openapi.APIS:
                self.assertEqual(
                    (root / "specs" / api / "openapi.yaml").read_text(encoding="utf-8"),
                    f"upstream {api}\n",
                )

    def test_successful_adopt_clears_backup_and_forces_pins(self):
        commands: list[list[str]] = []

        with _adopt_workspace() as root:
            with _patched_root(root), mock.patch.object(
                adopt_openapi.openapi_tools, "load_spec", side_effect=_additive_load_spec
            ), mock.patch.object(
                adopt_openapi, "_run", side_effect=lambda command: commands.append(command)
            ):
                self.assertEqual(self._adopt(), 0)
                # Successful nested generate+test is followed by clear_pin_backup().
                self.assertFalse(adopt_openapi.pin_backup_pending())

            self.assertEqual(
                commands[0][1:],
                ["generateApis", "test"]
                + [
                    f"-P{api}Spec={root / 'specs' / api / 'openapi.yaml'}"
                    for api in adopt_openapi.APIS
                ],
            )
            for api in adopt_openapi.APIS:
                self.assertEqual(
                    (root / "specs" / api / "openapi.yaml").read_text(encoding="utf-8"),
                    f"upstream {api}\n",
                )


if __name__ == "__main__":
    unittest.main()
