#!/usr/bin/env python3
"""Pick the specialists, skills and starting files for a task from its scope.

The worker prompt used to tell every task to read every Markdown file. This script
turns the task scope into a short routing block instead: which read-only senior
specialists to consult, which skills to load and which directories to read first.
The task advisor refines it into a brief; the worker prompt falls back to it alone.

Usage: route-task.py [--scope "a, b"] [--title T] [--requirements "X-01, Y-02"] [--body TEXT]
Prints Markdown. Names that do not exist in .opencode are dropped.
"""
import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

# (path fragment, specialists). A scope entry matches when it contains the fragment.
ROUTES = (
    ("db/migrations", ("senior-database",)),
    ("apps/api", ("senior-backend", "senior-java-build")),
    ("auth", ("senior-spring-security",)),
    ("iam", ("senior-spring-security",)),
    ("security", ("senior-spring-security",)),
    ("tenant", ("senior-spring-security",)),
    ("apps/staff-web", ("senior-react", "senior-typescript")),
    ("apps/patient-mobile", ("senior-mobile", "senior-typescript")),
    ("contracts/", ("senior-integrations",)),
    ("integration", ("senior-integrations",)),
    ("infra/", ("senior-cloud-iac",)),
    (".github/workflows", ("github-workflow",)),
    ("scripts/", ("senior-shell",)),
)
# Python tooling needs the Python specialist, not only the shell one.
PYTHON_SUFFIX = ".py"
# Always consulted: the test plan and the independent reviews.
ALWAYS = ("context-optimizer", "test-engineer", "plan-reviewer", "code-reviewer",
          "coverage-auditor", "devsecops")


def split(text):
    """Comma separated list into trimmed non-empty items."""
    return [item.strip() for item in (text or "").split(",") if item.strip()]


def field(body, name):
    """Read a task field from either task-file or worker-body text.

    Task files use ``  - scope: a, b`` while the worker brief produced by
    ``task_text()`` uses ``Scope (stay inside these paths): a, b``. Both must be
    parsed or specialist routing silently receives an empty scope.
    """
    pattern = rf"(?mi)^\s*-?\s*{re.escape(name)}\b(?:\s*\([^)]*\))?\s*:[ \t]*(.*)$"
    match = re.search(pattern, body or "")
    return match.group(1).strip() if match else ""


def pick(scope):
    """Specialists for the scope, in a stable order and without duplicates."""
    names = []
    for entry in scope:
        for fragment, specialists in ROUTES:
            if fragment in entry:
                names.extend(specialists)
        if entry.endswith(PYTHON_SUFFIX):
            names.append("senior-python")
    return list(dict.fromkeys(names))


def existing(names, root, kind="agents"):
    """Keep only the agents or skills that exist in this repository.

    Agent files are bare today, while skill directories carry the product prefix
    except for system skills. ORCA-081 renames skills atomically, so both forms
    are accepted during the transition. Inputs are candidate short names, a
    repository root and the resource kind; the output is the existing subset in
    input order. No exception is raised for a missing name.
    """
    def has(name):
        if kind == "skills":
            base = root / ".opencode/skills"
            return ((base / name / "SKILL.md").is_file()
                    or (base / f"aurevia-{name}" / "SKILL.md").is_file())
        base = root / ".opencode/agents"
        return ((base / f"{name}.md").is_file()
                or (base / f"aurevia-{name}.md").is_file())
    return [n for n in names if has(n)]


def render(scope, requirements, title, root=ROOT):
    """Markdown routing block for a task."""
    specialists = existing(pick(scope) + list(ALWAYS), root)
    skills = existing(["delivery"] + specialists, root, "skills")
    dirs = [e for e in scope if (root / e).exists()]
    lines = ["## Routing", f"Task: {title or 'untitled'}",
             "Consult these read-only specialists (files are .opencode/agents/<name>.md): "
             + ", ".join(specialists),
             "Load these skills before you start (under .opencode/skills/<name>/SKILL.md; "
             "most are currently prefixed): "
             + ", ".join(skills),
             "Read first: AGENTS.md, CODING_STYLE.md, the requirement IDs " + (", ".join(requirements) or "(none)")
             + " in .opencode/config/PROJECT_V1_SPEC.md, and these paths in scope: " + (", ".join(dirs) or "(new files only)")]
    return "\n".join(lines) + "\n"


def main(argv=None, root=ROOT):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scope", default="")
    parser.add_argument("--title", default="")
    parser.add_argument("--requirements", default="")
    parser.add_argument("--body", default="", help="task text; scope and requirements are read from it")
    args = parser.parse_args(argv)
    scope = split(args.scope or field(args.body, "scope"))
    requirements = split(args.requirements or field(args.body, "requirements"))
    sys.stdout.write(render(scope, requirements, args.title, root))
    return 0


if __name__ == "__main__":
    sys.exit(main())
