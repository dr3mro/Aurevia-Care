import shutil
import tempfile
import unittest
from pathlib import Path

from helpers import ROOT, load

docs = load("check-docs")
PREFIXES = {"MSG", "IAM", "ARC"}


class IdTests(unittest.TestCase):
    def test_ranges_and_slashes_expand(self):
        self.assertEqual(docs.mentioned_ids("MSG-01–03 and IAM-01/02", PREFIXES),
                         {"MSG-01", "MSG-02", "MSG-03", "IAM-01", "IAM-02"})

    def test_hyphen_range_and_longer_numbers(self):
        self.assertEqual(docs.mentioned_ids("MSG-01-02", PREFIXES), {"MSG-01", "MSG-02"})
        self.assertEqual(docs.mentioned_ids("ARC-123", PREFIXES), set())

    def test_spec_ids_ignore_appendix(self):
        text = "| IAM-01 | x |\n**ARC-02 · Isolation.**\n## Appendix A\n| IAM-99 | y |\n"
        self.assertEqual(docs.spec_ids(text), {"IAM-01", "ARC-02"})


class RepositoryTests(unittest.TestCase):
    def test_repository_docs_are_consistent(self):
        self.assertEqual(docs.check(ROOT), [])

    def make_repo(self, note):
        tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, tmp)
        (tmp / docs.SPEC).write_text("| IAM-01 | x |\n")
        (tmp / "NOTE.md").write_text(note)
        return tmp

    def test_unknown_id_and_broken_link_are_reported(self):
        errors = docs.check(self.make_repo("See IAM-09 and [x](missing.md) and [y](https://a.b)"))
        self.assertEqual(len(errors), 2)
        self.assertTrue(any("IAM-09" in e for e in errors))
        self.assertTrue(any("missing.md" in e for e in errors))

    def test_valid_links_pass(self):
        errors = docs.check(self.make_repo("IAM-01 [self](NOTE.md#top) [a](#x)"))
        self.assertEqual(errors, [])


if __name__ == "__main__":
    unittest.main()
