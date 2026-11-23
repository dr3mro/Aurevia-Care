import json
import tempfile
import unittest
from pathlib import Path

from helpers import load

wiring = load("check-wiring")
PKG = "apps/api/src/main/java/com/x"


class CheckWiringTests(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.addCleanup(lambda: __import__("shutil").rmtree(self.root))
        (self.root / "scripts").mkdir()
        (self.root / "db/migrations").mkdir(parents=True)

    def java(self, name, body, folder=PKG):
        path = self.root / folder / f"{name}.java"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body)

    def run_gate(self, *args):
        return wiring.main(list(args), root=self.root)

    def keys(self, rule):
        return wiring.findings(self.root)[rule]

    def test_clean_tree_passes(self):
        self.java("WidgetService", "public class WidgetService {}")
        self.java("WidgetController", "public class WidgetController { WidgetService s; }")
        self.assertEqual(self.run_gate(), 0)

    def test_service_without_a_caller_is_flagged(self):
        self.java("OrphanService", "public final class OrphanService {}")
        self.assertEqual(self.keys("unreferenced-service"), [f"{PKG}/OrphanService.java"])
        self.assertEqual(self.run_gate(), 1)

    def test_service_with_a_caller_is_not_flagged(self):
        self.java("UsedService", "public class UsedService {}")
        self.java("Caller", "class Caller { UsedService s; }")
        self.assertEqual(self.keys("unreferenced-service"), [])

    def test_in_memory_class_in_main_is_flagged(self):
        self.java("InMemoryThingStore", "class InMemoryThingStore {}")
        self.assertEqual(self.keys("inmemory-in-main"), [f"{PKG}/InMemoryThingStore.java"])

    def test_store_without_jdbc_implementation_is_flagged_and_with_one_is_not(self):
        self.java("ThingStore", "public interface ThingStore {}")
        self.java("InMemoryThingStore", "class InMemoryThingStore implements ThingStore {}")
        self.assertEqual(self.keys("store-without-durable"), [f"{PKG}/ThingStore.java"])
        self.java("JdbcThingStore", "class JdbcThingStore implements ThingStore {}")
        self.assertEqual(self.keys("store-without-durable"), [])

    def test_controller_that_builds_its_own_service_is_flagged(self):
        self.java("ThingController", "class ThingController { ThingController() { this(new ThingService()); } }")
        self.java("ThingService", "class ThingService {}")
        self.assertEqual(self.keys("controller-builds"), [f"{PKG}/ThingController.java"])

    def test_spotbugs_suppression_is_flagged(self):
        self.java("Annotated", "@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(value = \"X\")\nclass Annotated {}")
        self.java("Imported", "import x.SuppressFBWarnings;\nclass Imported {}")
        self.assertEqual(self.keys("fb-suppression"), [f"{PKG}/Annotated.java"])

    def test_requirement_id_names_are_flagged_in_classes_and_tables(self):
        self.java("Xch03Service", "class Xch03Service {}")
        self.java("G3EvidenceService", "class G3EvidenceService {}")
        self.java("GatewayService", "class GatewayService {}")
        (self.root / "db/migrations/V1__a.sql").write_text(
            "create table public.xch03_order (id int);\nCREATE TABLE patient (id int);")
        keys = self.keys("requirement-id-name")
        self.assertEqual(keys, [f"{PKG}/G3EvidenceService.java", f"{PKG}/Xch03Service.java",
                                "db/migrations/V1__a.sql:xch03_order"])

    def test_baseline_tolerates_existing_findings_but_not_new_ones(self):
        self.java("OldService", "class OldService {}")
        self.assertEqual(self.run_gate("--write-baseline"), 0)
        self.assertEqual(self.run_gate(), 0)
        self.java("NewService", "class NewService {}")
        self.assertEqual(self.run_gate(), 1)

    def test_stale_baseline_line_only_prints_a_note(self):
        (self.root / wiring.BASELINE).write_text(json.dumps({"inmemory-in-main": ["gone.java"]}))
        self.assertEqual(self.run_gate(), 0)
        new, stale = wiring.compare(wiring.findings(self.root), wiring.load_baseline(self.root))
        self.assertEqual((new, stale), ([], [("inmemory-in-main", "gone.java")]))

    def test_fail_on_stale_returns_one_for_a_fixed_baseline_finding(self):
        # A baseline entry whose finding is gone is normally just a note; the
        # --fail-on-stale mode must treat it as a failure so debt cannot linger.
        (self.root / wiring.BASELINE).write_text(json.dumps({"inmemory-in-main": ["gone.java"]}))
        self.assertEqual(self.run_gate("--fail-on-stale"), 1)

    def test_fail_on_stale_passes_when_no_finding_is_stale(self):
        self.assertEqual(self.run_gate("--fail-on-stale"), 0)

    def test_missing_source_tree_is_an_empty_clean_result(self):
        self.assertEqual(self.run_gate(), 0)

    def test_the_real_repository_has_no_new_findings(self):
        from helpers import ROOT
        self.assertEqual(wiring.main([], root=ROOT), 0)


if __name__ == "__main__":
    unittest.main()
