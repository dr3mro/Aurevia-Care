"""Load the hyphen-named tooling scripts as modules for testing."""
import importlib.util
import os
import shutil
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

# The suite runs inside swarm workers and CI jobs whose environment already carries
# the task being worked (BRANCH, STORY_*, SWARM, MODE...) and GitHub context and
# tokens. The tests build their own environment, so none of that may leak in:
# a worker inheriting BRANCH or BRANCH_PREFIX failed about half of test_agent_run.
_AMBIENT_EXACT = {
    "BASE_BRANCH", "BRANCH", "BRANCH_PREFIX", "CHECK_SCRIPT", "CONFLICTED", "EVENT_NAME",
    "FIX_COMMENT", "ISSUE_NUMBER", "MERGE_ATTEMPTS", "MODE", "PR_NUMBER",
    "REQUIRE_POSTGRES", "REVIEWERS", "SCHEDULE", "SEGMENT", "SEGMENTS", "SYNC_ONLY",
    "AGENT_TIMEOUT", "AUDIT_FOCUS", "AUREVIA_PG_ADMIN", "NVD_API_KEY",
}
_AMBIENT_PREFIXES = ("GITHUB_", "GH_", "RUNNER_", "SWARM", "PUSH_", "OPENCODE", "STORY_",
                     "GIT_")


def scrub_ambient_environment():
    """Drop worker, CI and git context variables from this process's environment."""
    for name in list(os.environ):
        if name in _AMBIENT_EXACT or name.startswith(_AMBIENT_PREFIXES):
            del os.environ[name]


scrub_ambient_environment()


def load(name):
    spec = importlib.util.spec_from_file_location(
        name.replace("-", "_"), ROOT / "scripts" / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def copy_pack():
    """Copy the files the pack checks use into a scratch repository."""
    tmp = Path(tempfile.mkdtemp())
    (tmp / ".github").mkdir()
    (tmp / ".opencode").mkdir()
    for folder in ("agents", "skills", "config", "setup"):
        shutil.copytree(ROOT / ".opencode" / folder, tmp / ".opencode" / folder,
                        ignore=shutil.ignore_patterns("__pycache__", "tests"))
    for name in (".opencode/opencode.json", ".opencode/config/PROJECT_V1_SPEC.md",
                 ".opencode/opencode.ci.json"):
        shutil.copy(ROOT / name, tmp / name)
    for folder in ("scripts", "tasks"):
        shutil.copytree(ROOT / folder, tmp / folder,
                        ignore=shutil.ignore_patterns("__pycache__", "tests"))
    return tmp
