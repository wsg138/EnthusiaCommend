"""Checks that rate limiting retries never conceal a failed build."""

import unittest
from types import SimpleNamespace
from unittest.mock import patch

import maven_retry


class MavenRetryTest(unittest.TestCase):
    limited = SimpleNamespace(
        returncode=1,
        stdout="[ERROR] Could not transfer artifact plugin:pom:1 from/to central: status code: 429\n",
    )

    @patch("maven_retry.time.sleep")
    @patch("maven_retry.subprocess.run")
    def test_retries_transfer_and_returns_success(self, execute, sleep):
        execute.side_effect = [self.limited, SimpleNamespace(returncode=0, stdout="Success\n")]
        self.assertEqual(0, maven_retry.run("verify"))
        self.assertEqual(2, execute.call_count)
        sleep.assert_called_once_with(20)

    @patch("maven_retry.time.sleep")
    @patch("maven_retry.subprocess.run")
    def test_test_failure_is_not_retried(self, execute, sleep):
        execute.return_value = SimpleNamespace(returncode=7, stdout="[ERROR] Tests failed: HTTP 429\n")
        self.assertEqual(7, maven_retry.run("verify"))
        execute.assert_called_once()
        sleep.assert_not_called()

    @patch("maven_retry.time.sleep")
    @patch("maven_retry.subprocess.run")
    def test_persistent_rate_limit_still_fails(self, execute, sleep):
        execute.return_value = self.limited
        self.assertEqual(1, maven_retry.run("pmd"))
        self.assertEqual(3, execute.call_count)
        self.assertEqual(2, sleep.call_count)

    @patch("maven_retry.subprocess.run")
    def test_arbitrary_commands_are_rejected(self, execute):
        with self.assertRaises(ValueError):
            maven_retry.run("arbitrary-command")
        execute.assert_not_called()
