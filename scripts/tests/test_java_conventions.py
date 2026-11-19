"""Guard the Java conventions that SpotBugs and Doxygen cannot be trusted to learn.

Both defects in the G1 slice (mutable record lists and unresolved {@link}
targets) had to be fixed after CI caught them. These checks make the conventions
cheap and deterministic so a new record or Javadoc link cannot regress.
"""
import re
import unittest

from helpers import ROOT

MAIN = ROOT / "apps/api/src/main/java"


def java_files():
    return sorted(MAIN.rglob("*.java"))


class JavaConventionTest(unittest.TestCase):
    def test_list_bearing_records_copy_on_store_and_read(self):
        checked = 0
        for path in java_files():
            text = path.read_text(encoding="utf-8")
            if not re.search(r"public record \w+\([^)]*\bList<", text, re.S):
                continue
            with self.subTest(record=path.name):
                copies = text.count("List.copyOf(")
                self.assertGreaterEqual(copies, 2)
            checked += 1
        self.assertGreater(checked, 0)

    def test_javadoc_links_are_fully_qualified(self):
        """Doxygen only resolves fully qualified targets; others fail the docs gate."""
        for path in java_files():
            text = path.read_text(encoding="utf-8")
            for target in re.findall(r"\{@link\s+([^}]+)\}", text):
                with self.subTest(file=path.name, target=target):
                    self.assertIn(".", target.split("#")[0])


if __name__ == "__main__":
    unittest.main()
