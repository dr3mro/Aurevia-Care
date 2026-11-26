"""Tests for the developer-reference source-link rewrite.

The generated site keeps Doxygen's own links and the TypeDoc sub-sites intact;
only repository-relative links copied from the module guides become permanent
GitHub permalinks. Directory targets use /tree/, files use /blob/. All fixtures
are synthetic and no network is used.
"""
import io
import runpy
import sys
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from tempfile import TemporaryDirectory

from helpers import ROOT, load

links = load("link_developer_reference")
SCRIPT = ROOT / "scripts" / "link_developer_reference.py"
REPOSITORY = "dr3mro/Aurevia-Care"
REF = "61d49ef1fad002da3f5ffd62af3c364408b4e88d"


def href(path, depth=3, quotes='"'):
    """Build a generated-site href for a repository-relative source path."""
    return f"href={quotes}{'../' * depth}{path}{quotes}"


def blob(path):
    """Build the expected permanent GitHub blob URL for a source path."""
    return f"https://github.com/{REPOSITORY}/blob/{REF}/{path}"


def tree(path):
    """Build the expected permanent GitHub tree URL for a source directory."""
    return f"https://github.com/{REPOSITORY}/tree/{REF}/{path}"


class RewriteTests(unittest.TestCase):
    """The href contract: rewrite only repository-relative source links."""

    def test_rewrite_java_link_keeps_surrounding_markup(self):
        path = "apps/api/src/main/java/com/aurevia/catalog/CatalogService.java"
        with TemporaryDirectory() as tmp:
            text = f'<a {href(path)}>CatalogService.java</a>'
            result, count, unresolved = links.rewrite_links(
                text, REPOSITORY, REF, tmp)
        self.assertEqual(count, 1)
        self.assertEqual(unresolved, ())
        self.assertEqual(
            result, f'<a href="{blob(path)}">CatalogService.java</a>')

    def test_rewrite_supported_repository_roots_at_both_depths(self):
        roots = (
            ("apps", "apps/api/src/main/java/com/aurevia/web/CatalogController.java"),
            ("db", "db/migrations/V6__catalog_fold.sql"),
            ("contracts", "contracts/openapi.yaml"),
            ("scripts", "scripts/wiring-baseline.json"),
            ("infra", "infra/app/start.sh"),
            (".opencode", ".opencode/config/PROJECT_V1_SPEC.md"),
            ("docs", "docs/reference/maintainer.md"),
        )
        with TemporaryDirectory() as tmp:
            for root, path in roots:
                for depth in (1, 2, 3):
                    with self.subTest(root=root, depth=depth):
                        result, count, unresolved = links.rewrite_links(
                            href(path, depth=depth), REPOSITORY, REF, tmp)
                        self.assertEqual((count, unresolved), (1, ()))
                        self.assertEqual(result, f'href="{blob(path)}"')

    def test_directory_target_uses_tree_segment(self):
        path = "apps/api/src/main/java/com/aurevia/exchange"
        with TemporaryDirectory() as tmp:
            (Path(tmp) / path).mkdir(parents=True)
            for target in (path, path + "/"):
                with self.subTest(target=target):
                    result, count, unresolved = links.rewrite_links(
                        href(target, depth=2), REPOSITORY, REF, tmp)
                    self.assertEqual((count, unresolved), (1, ()))
                    self.assertEqual(result, f'href="{tree(path)}"')

    def test_preserves_query_and_fragment_byte_for_byte(self):
        target = "apps/api/src/main/java/Example.java?raw=1&amp;b=2#L12"
        with TemporaryDirectory() as tmp:
            result, count, _ = links.rewrite_links(
                href(target), REPOSITORY, REF, tmp)
        self.assertEqual(count, 1)
        self.assertEqual(
            result,
            f'href="{blob("apps/api/src/main/java/Example.java")}?raw=1&amp;b=2#L12"',
        )

    def test_leaves_generated_internal_and_absolute_links_untouched(self):
        untouched = (
            'href="md_docs_2reference_2maintainer.html"',
            'href="_catalog_service_8java_source.html"',
            'href="tabs.css"',
            'href="search/search.js"',
            'href="#things-to-know"',
            'href="https://example.test/apps/x.java"',
            'href="../assets/app.css"',
            'href="../../opencode/config.md"',
        )
        with TemporaryDirectory() as tmp:
            for text in untouched:
                with self.subTest(text=text):
                    result, count, unresolved = links.rewrite_links(
                        text, REPOSITORY, REF, tmp)
                    self.assertEqual((result, count, unresolved), (text, 0, ()))

    def test_accepts_single_quotes_and_spaces_around_equals(self):
        with TemporaryDirectory() as tmp:
            for text in (
                href("apps/x.java", quotes="'"),
                "href = '../../../apps/x.java'",
            ):
                with self.subTest(text=text):
                    result, count, unresolved = links.rewrite_links(
                        text, REPOSITORY, REF, tmp)
                    self.assertEqual(count, 1)
                    self.assertEqual(unresolved, ())
                    self.assertIn(blob("apps/x.java"), result)
                    self.assertNotIn("../../../", result)

    def test_rejects_traversal_and_empty_segments(self):
        with TemporaryDirectory() as tmp:
            for path in ("apps/../db/x.sql", "apps//x.java", "apps/./x.java",
                         "apps/x\\y.java"):
                with self.subTest(path=path):
                    text = href(path)
                    result, count, unresolved = links.rewrite_links(
                        text, REPOSITORY, REF, tmp)
                    self.assertEqual((result, count), (text, 0))
                    self.assertEqual(unresolved, ((1, text[6:-1]),))

    def test_rewrite_is_idempotent(self):
        target = href("db/migrations/V1__tenant_schema.sql")
        with TemporaryDirectory() as tmp:
            once, first, _ = links.rewrite_links(target, REPOSITORY, REF, tmp)
            twice, second, _ = links.rewrite_links(once, REPOSITORY, REF, tmp)
        self.assertEqual(first, 1)
        self.assertEqual((twice, second), (once, 0))

    def test_unresolved_links_report_line_numbers(self):
        text = "\n".join((
            f"<p>{href('apps/x.java')}</p>",
            "no link here",
            f"<p>{href('apps/../db/x.sql')}</p>",
            f"<p>{href('db/x.sql')}</p>",
        ))
        self.assertEqual(
            links.unresolved_links(text),
            ((1, "../../../apps/x.java"), (3, "../../../apps/../db/x.sql"),
             (4, "../../../db/x.sql")),
        )


class SiteTests(unittest.TestCase):
    """The file boundary: only root-level generated HTML is considered."""

    def test_process_site_rewrites_root_pages_only(self):
        with TemporaryDirectory() as tmp:
            site = Path(tmp) / "site"
            repo = Path(tmp) / "repo"
            site.mkdir()
            repo.mkdir()
            root_page = site / "md_docs_2reference_2modules_2api-catalog.html"
            nested = site / "staff-web" / "index.html"
            nested.parent.mkdir()
            original = href("apps/api/src/main/java/Example.java")
            root_page.write_text(f"<p>{original}</p>", encoding="utf-8")
            nested.write_text(f"<p>{original}</p>", encoding="utf-8")
            total, changed, unresolved = links.process_site(
                site, REPOSITORY, REF, repo)
            self.assertEqual((total, changed, unresolved), (1, 1, ()))
            self.assertIn(blob("apps/api/src/main/java/Example.java"),
                          root_page.read_text(encoding="utf-8"))
            self.assertIn(original, nested.read_text(encoding="utf-8"))

    def test_process_site_ignores_non_html_and_is_sorted(self):
        with TemporaryDirectory() as tmp:
            site = Path(tmp) / "site"
            site.mkdir()
            (site / "notes.txt").write_text(href("apps/x.java"), encoding="utf-8")
            (site / "b.html").write_text(href("apps/b.java"), encoding="utf-8")
            (site / "a.html").write_text(href("apps/a.java"), encoding="utf-8")
            total, changed, unresolved = links.process_site(
                site, REPOSITORY, REF, tmp)
            self.assertEqual((total, changed, unresolved), (2, 2, ()))
            self.assertIn(blob("apps/a.java"),
                          (site / "a.html").read_text(encoding="utf-8"))
            self.assertEqual((site / "notes.txt").read_text(encoding="utf-8"),
                             href("apps/x.java"))

    def test_process_site_on_empty_directory(self):
        with TemporaryDirectory() as tmp:
            self.assertEqual(
                links.process_site(tmp, REPOSITORY, REF, tmp), (0, 0, ()))

    def test_write_mode_leaves_a_clean_page_untouched(self):
        with TemporaryDirectory() as tmp:
            page = Path(tmp) / "clean.html"
            original = 'href="md_docs_2reference_2index.html"'
            page.write_text(original, encoding="utf-8")
            self.assertEqual(
                links.process_site(tmp, REPOSITORY, REF, tmp), (0, 0, ()))
            self.assertEqual(page.read_text(encoding="utf-8"), original)

    def test_check_mode_reports_without_writing(self):
        with TemporaryDirectory() as tmp:
            page = Path(tmp) / "page.html"
            page.write_text(href("apps/x.java"), encoding="utf-8")
            total, changed, unresolved = links.process_site(
                tmp, REPOSITORY, REF, tmp, check=True)
            self.assertEqual((total, changed), (0, 0))
            self.assertEqual(unresolved, (("page.html", 1, "../../../apps/x.java"),))
            self.assertEqual(page.read_text(encoding="utf-8"),
                             href("apps/x.java"))


class ArgTests(unittest.TestCase):
    """The command-line trust boundary rejects values that could form bad URLs."""

    def args(self, repository=REPOSITORY, ref=REF):
        return ["--site", "site", "--repo-root", ".", "--repository", repository,
                "--ref", ref]

    def test_parse_args_valid(self):
        args = links.parse_args(self.args())
        self.assertEqual((args.site.as_posix(), args.repo_root.as_posix(),
                          args.repository, args.ref, args.check),
                         ("site", ".", REPOSITORY, REF, False))
        self.assertTrue(links.parse_args(self.args() + ["--check"]).check)

    def test_parse_args_rejects_bad_repository(self):
        for value in ("dr3mro", "-owner/repo", "owner/re po"):
            with self.subTest(value=value):
                with redirect_stderr(io.StringIO()):
                    with self.assertRaises(SystemExit) as raised:
                        links.parse_args(self.args(repository=value))
                self.assertEqual(raised.exception.code, 2)

    def test_parse_args_rejects_bad_ref(self):
        for value in ("main", "../main", "ref#1", ""):
            with self.subTest(value=value):
                with redirect_stderr(io.StringIO()):
                    with self.assertRaises(SystemExit) as raised:
                        links.parse_args(self.args(ref=value))
                self.assertEqual(raised.exception.code, 2)


class MainTests(unittest.TestCase):
    """The CLI reports actionable errors and maps outcomes to exit codes."""

    def main_args(self, site, check=False):
        args = ["--site", str(site), "--repo-root", str(site), "--repository",
                REPOSITORY, "--ref", REF]
        return args + (["--check"] if check else [])

    def test_main_rejects_missing_site(self):
        with TemporaryDirectory() as tmp:
            missing = Path(tmp) / "absent"
            stderr = io.StringIO()
            with redirect_stderr(stderr):
                status = links.main(self.main_args(missing))
            self.assertEqual(status, 1)
            self.assertIn("--site is not a directory", stderr.getvalue())

    def test_main_rewrite_writes_and_summaries(self):
        with TemporaryDirectory() as tmp:
            page = Path(tmp) / "page.html"
            page.write_text(href("apps/x.java"), encoding="utf-8")
            stdout = io.StringIO()
            with redirect_stdout(stdout):
                status = links.main(self.main_args(tmp))
            self.assertEqual(status, 0)
            self.assertEqual(stdout.getvalue(),
                             "Rewrote 1 link(s) in 1 file(s).\n")
            self.assertIn(blob("apps/x.java"), page.read_text(encoding="utf-8"))

    def test_main_check_clean_returns_zero(self):
        with TemporaryDirectory() as tmp:
            page = Path(tmp) / "page.html"
            page.write_text('href="md_docs_2reference_2index.html"', encoding="utf-8")
            stdout = io.StringIO()
            with redirect_stdout(stdout):
                status = links.main(self.main_args(tmp, check=True))
            self.assertEqual(status, 0)
            self.assertEqual(stdout.getvalue(),
                             "No unresolved repository-relative links.\n")

    def test_main_check_with_unresolved_returns_one(self):
        with TemporaryDirectory() as tmp:
            page = Path(tmp) / "page.html"
            page.write_text(href("apps/x.java"), encoding="utf-8")
            stderr = io.StringIO()
            with redirect_stderr(stderr):
                status = links.main(self.main_args(tmp, check=True))
            self.assertEqual(status, 1)
            self.assertEqual(
                stderr.getvalue(),
                "page.html:1: unresolved repository-relative link: ../../../apps/x.java\n",
            )

    def test_main_write_mode_reports_unsafe_link_after_rewriting_safe_ones(self):
        with TemporaryDirectory() as tmp:
            page = Path(tmp) / "page.html"
            page.write_text("\n".join((href("apps/x.java"),
                                       href("apps/../db/x.sql"))), encoding="utf-8")
            stderr = io.StringIO()
            with redirect_stderr(stderr):
                status = links.main(self.main_args(tmp))
            self.assertEqual(status, 1)
            self.assertIn(
                "page.html:2: unresolved repository-relative link: ../../../apps/../db/x.sql",
                stderr.getvalue(),
            )
            self.assertIn(blob("apps/x.java"), page.read_text(encoding="utf-8"))

    def test_module_guard_exits_with_main_status(self):
        with TemporaryDirectory() as tmp:
            page = Path(tmp) / "page.html"
            page.write_text(href("apps/x.java"), encoding="utf-8")
            stdout = io.StringIO()
            previous = sys.argv
            sys.argv = [str(SCRIPT)] + self.main_args(tmp)
            try:
                with redirect_stdout(stdout):
                    with self.assertRaises(SystemExit) as raised:
                        runpy.run_path(str(SCRIPT), run_name="__main__")
            finally:
                sys.argv = previous
            self.assertEqual(raised.exception.code, 0)
            self.assertEqual(stdout.getvalue(),
                             "Rewrote 1 link(s) in 1 file(s).\n")


if __name__ == "__main__":
    unittest.main()
