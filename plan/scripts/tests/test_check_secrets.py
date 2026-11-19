import shutil
import tempfile
import unittest
from pathlib import Path

from helpers import ROOT, load

secrets = load("check-secrets")


class SecretTests(unittest.TestCase):
    def scan(self, text):
        tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, tmp)
        (tmp / "sample.txt").write_text(text)
        return [label for _, _, label in secrets.scan(tmp)]

    def test_repository_is_clean(self):
        self.assertEqual(secrets.scan(ROOT), [])

    def test_detects_credentials(self):
        aws = "AKIA" + "A" * 16
        key = "-----BEGIN " + "PRIVATE KEY-----"
        pwd = 'password = "' + "x" * 12 + '"'
        self.assertEqual(self.scan(aws), ["AWS access key id"])
        self.assertEqual(self.scan(key), ["private key"])
        self.assertEqual(self.scan(pwd), ["password assignment"])

    def test_detects_ssn_shape_but_not_reserved_ranges(self):
        self.assertEqual(self.scan("123-45-" + "6789"), ["US social security number"])
        self.assertEqual(self.scan("000-45-" + "6789"), [])
        self.assertEqual(self.scan("123-00-" + "6789"), [])

    def test_marker_allows_a_lookalike_fixture(self):
        self.assertEqual(self.scan("123-45-" + "6789  # synthetic-ok"), [])

    def test_binary_files_are_skipped(self):
        tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, tmp)
        (tmp / "blob.bin").write_bytes(b"\xff\xfe\x00AKIA")
        self.assertEqual(secrets.scan(tmp), [])


if __name__ == "__main__":
    unittest.main()
