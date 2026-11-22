"""Tests for scripts/route-task.py."""
import io
import runpy
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
from helpers import load  # noqa: E402

route = load("route-task")


def make_root(names, dirs=(), skills=()):
    root = Path(tempfile.mkdtemp())
    (root / ".opencode/agents").mkdir(parents=True)
    for name in names:
        (root / ".opencode/agents" / f"smartclinic-{name}.md").write_text("x")
    for name in skills:
        (root / ".opencode/skills" / f"smartclinic-{name}").mkdir(parents=True)
        (root / ".opencode/skills" / f"smartclinic-{name}" / "SKILL.md").write_text("x")
    for d in dirs:
        (root / d).mkdir(parents=True)
    return root


class RouteTests(unittest.TestCase):
    def test_split_trims_and_drops_empty_items(self):
        self.assertEqual(route.split(" a, ,b "), ["a", "b"])
        self.assertEqual(route.split(""), [])
        self.assertEqual(route.split(None), [])

    def test_pick_routes_by_path_and_removes_duplicates(self):
        got = route.pick(["apps/api/src/iam", "apps/api/x", "scripts/tool.py", "db/migrations"])
        self.assertEqual(got, ["senior-backend", "senior-java-build", "senior-spring-security",
                               "senior-shell", "senior-python", "senior-database"])

    def test_pick_covers_the_frontends_and_contracts(self):
        self.assertEqual(route.pick(["apps/staff-web/src"]), ["senior-react", "senior-typescript"])
        self.assertEqual(route.pick(["apps/patient-mobile/a"]), ["senior-mobile", "senior-typescript"])
        self.assertEqual(route.pick(["contracts/openapi.yaml", "infra/x", ".github/workflows/a.yml"]),
                         ["senior-integrations", "senior-cloud-iac", "github-workflow"])

    def test_pick_of_an_unknown_path_is_empty(self):
        self.assertEqual(route.pick(["docs/x.md"]), [])

    def test_existing_drops_missing_agents(self):
        root = make_root(["senior-backend"], skills=["delivery"])
        self.assertEqual(route.existing(["senior-backend", "ghost"], root), ["senior-backend"])
        self.assertEqual(route.existing(["delivery", "ghost"], root, "skills"), ["delivery"])

    def test_render_lists_specialists_skills_and_existing_paths(self):
        root = make_root(["senior-backend", "code-reviewer"], dirs=["apps/api"],
                         skills=["delivery", "senior-backend", "code-reviewer"])
        text = route.render(["apps/api", "apps/new"], ["MSG-02"], "Title", root)
        self.assertIn("Task: Title", text)
        self.assertIn("senior-backend, code-reviewer", text)
        self.assertIn("delivery, senior-backend, code-reviewer", text)
        self.assertIn("MSG-02", text)
        self.assertIn("paths in scope: apps/api\n", text)

    def test_render_without_details_uses_placeholders(self):
        text = route.render([], [], "", make_root([]))
        self.assertIn("Task: untitled", text)
        self.assertIn("(none)", text)
        self.assertIn("(new files only)", text)

    def test_field_reads_a_task_line(self):
        body = "- [ ] X\n  - scope: apps/api, db/migrations\n  - requirements: A-01\n"
        self.assertEqual(route.field(body, "scope"), "apps/api, db/migrations")
        self.assertEqual(route.field(body, "requirements"), "A-01")
        self.assertEqual(route.field("none", "scope"), "")
        self.assertEqual(route.field(None, "scope"), "")

    def test_main_reads_scope_and_requirements_from_the_body(self):
        out = io.StringIO()
        body = "  - scope: docs/x.md\n  - requirements: Z-9\n"
        with redirect_stdout(out):
            route.main(["--body", body], make_root([], dirs=["docs/x.md"]))
        self.assertIn("Z-9", out.getvalue())
        self.assertIn("paths in scope: docs/x.md", out.getvalue())

    def test_main_prints_the_block(self):
        out = io.StringIO()
        with redirect_stdout(out):
            code = route.main(["--scope", "docs/x.md", "--title", "T"], make_root([]))
        self.assertEqual(code, 0)
        self.assertIn("Task: T", out.getvalue())

    def test_command_line_entry_point(self):
        script = str(Path(__file__).resolve().parents[1] / "route-task.py")
        out = io.StringIO()
        with mock.patch.object(sys, "argv", [script, "--scope", "docs/x.md"]), \
                redirect_stdout(out), self.assertRaises(SystemExit) as cm:
            runpy.run_path(script, run_name="__main__")
        self.assertEqual(cm.exception.code, 0)
        self.assertIn("## Routing", out.getvalue())


if __name__ == "__main__":
    unittest.main()
