"""Fast tier skips PostgreSQL tests by JUnit tag, never by class-name pattern."""
import unittest
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
    steps = yaml.safe_load(DELIVERY.read_text(encoding="utf-8"))["jobs"]["quick"]["steps"]
    lines = [line for step in steps for line in (step.get("run") or "").splitlines()]
    lines += QUICK.read_text(encoding="utf-8").splitlines()
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
