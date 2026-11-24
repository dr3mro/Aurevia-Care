#!/usr/bin/env python3
"""Unit tests for the offline command-contract drift gate (PRD-06, ARC-03).

Every fixture is a copy of the committed synthetic artifacts with one
authority or precondition regression injected; no patient data, credential
or live endpoint is used. The tests are discovered explicitly with
``python3 -I -m unittest discover -s contracts/openapi -p 'test_*.py' -q``.
"""
import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check  # noqa: E402

GENERATED = Path(__file__).resolve().parent / "generated"


class DriftGateTests(unittest.TestCase):
    """The gate must reject authority leakage and per-command regressions."""

    def setUp(self) -> None:
        """Copy the committed artifacts into an isolated fixture directory."""
        self._tmp = tempfile.TemporaryDirectory()
        self.directory = Path(self._tmp.name) / "generated"
        shutil.copytree(GENERATED, self.directory)
        self.manifest = json.loads((self.directory / "manifest.json").read_text(encoding="utf-8"))
        self.contract = (self.directory / "openapi.yaml").read_text(encoding="utf-8")
        self.client = (self.directory / "client.ts").read_text(encoding="utf-8")

    def tearDown(self) -> None:
        """Remove the fixture directory."""
        self._tmp.cleanup()

    def write_client(self, text: str) -> None:
        """Write a fixture client and refresh only its checksum in the manifest."""
        (self.directory / "client.ts").write_text(text, encoding="utf-8")
        self.manifest["clientSha256"] = check.canonical_checksum(text)
        (self.directory / "manifest.json").write_text(
            json.dumps(self.manifest), encoding="utf-8"
        )

    def test_committed_artifacts_pass_the_gate(self) -> None:
        """The generated artifacts in the repository are internally consistent."""
        self.assertEqual(check.check(GENERATED), 0)

    def test_authority_field_variants_are_rejected(self) -> None:
        """Renamed or capitalised authority declarations cannot slip through."""
        for injected in (
            "  actor: string,\n",
            "  userRole: string,\n",
            "  Role : string,\n",
            "  grantedScopes: string,\n",
            "  claimedPermissions: string,\n",
        ):
            with self.subTest(injected=injected):
                text = self.client.replace(
                    "  idempotencyKey: string,\n",
                    injected + "  idempotencyKey: string,\n",
                    1,
                )
                self.write_client(text)
                errors, _ = check.verify(self.directory)
                self.assertTrue(
                    any("forbidden authority" in error for error in errors),
                    f"gate accepted {injected!r}: {errors}",
                )

    def test_authority_header_is_rejected(self) -> None:
        """A client-supplied authority header is forbidden even when typed elsewhere."""
        text = self.client.replace(
            "      'X-Api-Version': 'v1',\n",
            "      'X-Actor': 'admin',\n",
            1,
        )
        self.assertNotEqual(text, self.client)
        self.write_client(text)
        errors, _ = check.verify(self.directory)
        self.assertTrue(
            any("forbidden authority header" in error for error in errors),
            f"gate accepted an authority header: {errors}",
        )

    def test_path_encoding_is_verified_per_function(self) -> None:
        """One unencoded path parameter fails even when other functions encode it."""
        text = self.client.replace(
            "'/api/v1/orders/' + encodeURIComponent(id) + '/accept'",
            "'/api/v1/orders/' + id + '/accept'",
        )
        self.assertNotEqual(text, self.client)
        self.write_client(text)
        errors, _ = check.verify(self.directory)
        self.assertTrue(
            any("acceptOrder" in error for error in errors),
            f"gate did not attribute the encoding regression: {errors}",
        )

    def test_every_function_requires_preconditions(self) -> None:
        """A command function that drops Idempotency-Key or If-Match is rejected."""
        text = self.client.replace("  ifMatch: string,\n", "  ifMatch?: string,\n", 1)
        self.assertNotEqual(text, self.client)
        self.write_client(text)
        errors, _ = check.verify(self.directory)
        self.assertTrue(
            any("precondition" in error for error in errors),
            f"gate accepted a missing precondition: {errors}",
        )

    def test_contract_drift_is_detected(self) -> None:
        """A changed contract with a stale manifest checksum still fails."""
        (self.directory / "openapi.yaml").write_text("drifted", encoding="utf-8")
        errors, _ = check.verify(self.directory)
        self.assertTrue(any("contractSha256 drift" in error for error in errors), errors)

    def test_missing_artifact_fails_closed(self) -> None:
        """A missing artifact never passes the gate."""
        (self.directory / "manifest.json").unlink()
        errors, _ = check.verify(self.directory)
        self.assertTrue(errors)


if __name__ == "__main__":
    unittest.main()
