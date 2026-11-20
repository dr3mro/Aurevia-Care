"""Fast tier skips PostgreSQL tests by JUnit tag, never by class-name pattern."""
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path
import xml.etree.ElementTree as ET

import yaml

from helpers import ROOT

POM = ROOT / "apps/api/pom.xml"
JAVA_TESTS = ROOT / "apps/api/src/test/java"
DELIVERY = ROOT / ".github/workflows/delivery.yml"
QUICK = ROOT / "scripts/quick-check.sh"
FULL_CHECK = ROOT / "apps/api/check.sh"
TAG = '@Tag("integration")'
KNOWN_DB_TESTS = {
    "AuditQueryTest", "PatientRegistrationTest", "SchedulingBookingTest",
    "FacilityIsolationTest", "StaffLifecycleIsolationTest",
    "ConfigurationIsolationTest", "CatalogIsolationTest",
    "ConcurrentTenantIsolationTest",
}
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def fast_commands():
    # Pull requests now run the full tier in delivery.yml; the fast tier survives
    # only in the swarm's local and integrator pre-checks (scripts/quick-check.sh).
    lines = QUICK.read_text(encoding="utf-8").splitlines()
    return [line for line in lines if "mvn " in line and "apps/api/pom.xml" in line]


class FastTierTagTests(unittest.TestCase):
    def test_db_tests_carry_the_integration_tag(self):
        tagged = set()
        for path in sorted(JAVA_TESTS.rglob("*.java")):
            text = path.read_text(encoding="utf-8")
            if "@SpringBootTest" not in text and "jdbc:postgresql" not in text:
                continue
            with self.subTest(test=path.name):
                self.assertIn(TAG, text)
                self.assertIn("import org.junit.jupiter.api.Tag;", text)
            tagged.add(path.stem)
        self.assertTrue(KNOWN_DB_TESTS <= tagged, KNOWN_DB_TESTS - tagged)

    def test_fast_commands_use_the_tag_profile_not_a_name_pattern(self):
        commands = fast_commands()
        self.assertTrue(commands)
        for line in commands:
            with self.subTest(command=line.strip()):
                self.assertIn("fast", line)
                self.assertNotIn("-Dtest", line)

    def test_pom_excludes_integration_only_in_the_fast_profile(self):
        root = ET.parse(POM).getroot()
        profiles = root.find("m:profiles", NS)
        fast = next((p for p in profiles.findall("m:profile", NS)
                     if p.findtext("m:id", namespaces=NS) == "fast"), None)
        self.assertIsNotNone(fast)
        groups = fast.find(".//m:excludedGroups", NS)
        self.assertIsNotNone(groups)
        self.assertEqual("integration", groups.text.strip())
        for element in root.iter():
            if element.tag.endswith("excludedGroups") and element is not groups:
                self.fail("integration excluded outside the fast profile")

    def test_full_tier_still_runs_every_test(self):
        text = FULL_CHECK.read_text(encoding="utf-8")
        self.assertIn("mvn", text)
        self.assertNotIn("fast", text)


if __name__ == "__main__":
    unittest.main()


class FrontendWorkerGateTests(unittest.TestCase):
    def test_coverage_blocks_an_invalid_base_before_checking_changes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "scripts").mkdir()
            script = root / "scripts/coverage-check.sh"
            shutil.copy(ROOT / "scripts/coverage-check.sh", script)
            module = root / "apps/staff-web"
            module.mkdir(parents=True)
            (module / "example.ts").write_text("export {};")
            subprocess.run(["git", "init", "-q", str(root)], check=True)
            subprocess.run(["git", "-C", str(root), "add", "apps/staff-web/example.ts"], check=True)
            result = subprocess.run(["bash", str(script), "invalid-base"], capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("invalid-base", result.stderr)

    def test_frontend_typecheck_failure_is_not_hidden_by_passing_tests(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "scripts").mkdir()
            shutil.copy(ROOT / "scripts/frontend-check.sh", root / "scripts/frontend-check.sh")
            module = root / "apps/patient-mobile"
            module.mkdir(parents=True)
            (module / "package.json").write_text("{}")
            (module / "tsconfig.json").write_text('{"strict": true, "noUncheckedIndexedAccess": true}')
            (module / "transport.ts").write_text("export const request = {};")
            shim = root / "npm"
            shim.write_text('#!/bin/sh\nif [ "$1 $2" = "run typecheck" ] && '
                            '[ "${PWD##*/}" = patient-mobile ]; then exit 37; fi\nexit 0\n')
            shim.chmod(0o755)
            result = subprocess.run(["bash", str(root / "scripts/frontend-check.sh")],
                                    env={**os.environ, "PATH": f"{root}{os.pathsep}{os.environ['PATH']}"},
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 1, result.stdout + result.stderr)

    def test_worker_invokes_the_same_frontend_gate_as_delivery(self):
        delivery = yaml.safe_load(DELIVERY.read_text())["jobs"]["frontend"]
        command = next(step["run"] for step in delivery["steps"] if "run" in step)
        worker = (ROOT / "scripts/worker-check.sh").read_text().splitlines()
        self.assertIn(command, worker)
