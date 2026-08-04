#!/usr/bin/env python3
"""Tests for scripts/adopt_openapi.py orchestration helpers."""

from __future__ import annotations

import unittest

from scripts import adopt_openapi


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


if __name__ == "__main__":
    unittest.main()
