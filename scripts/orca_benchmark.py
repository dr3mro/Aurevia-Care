"""Passive benchmark measurements for the ORCA refactor.

This is a sibling of ``scripts/orca_core.py`` under the owner's single offline
core ruling: it measures deterministic repository facts and never launches an
agent, reads a clock, calls a token counter, touches the network or invokes a
repository command. Inputs are local paths and plain values; outputs are plain
dictionaries and integers. Unavailable metrics are returned as the text
``"unavailable"``, never as estimates, and percentage targets are labelled as
targets rather than measurements.
"""
import ast
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_PROFILE = ROOT / ".opencode" / "opencode.json"

UNAVAILABLE_METRICS = (
    "tokens", "duration_seconds", "agent_invocations", "tool_calls",
    "duplicate_reads", "skill_activation", "live_first_attempt",
)
TARGETS = {
    "context_reduction": 40,
    "agent_invocation_reduction": 30,
    "duration_reduction": 25,
    "repair_cycle_reduction": 50,
    "first_attempt_success": 90,
}


class EvidenceSource:
    """Allowed provenance labels; an unavailable metric carries its own label."""

    TOOL = "tool"
    REVIEWER = "reviewer"
    SIMULATED_USER = "simulated-user"
    HUMAN = "human"
    HUMAN_PARTICIPANT = "human"
    UNAVAILABLE = "unavailable"


SOURCES = frozenset((
    EvidenceSource.TOOL, EvidenceSource.REVIEWER, EvidenceSource.SIMULATED_USER,
    EvidenceSource.HUMAN, EvidenceSource.UNAVAILABLE,
))


def count_files(root, pattern):
    """Count files matching ``pattern`` under ``root``; zero when none match."""
    return len(list(Path(root).glob(pattern)))


def count_bytes(paths):
    """Sum the byte sizes of the given files; an empty list sums to zero."""
    return sum(path.stat().st_size for path in paths)


def doc_load_count(profile=DEFAULT_PROFILE):
    """Return the number of globally loaded instruction documents.

    The value is counted from the generated config, not hardcoded, so a new
    mandatory setup document is reflected. Missing or malformed input raises
    ValueError with an actionable message.
    """
    try:
        data = json.loads(Path(profile).read_text(encoding="utf-8"))
        instructions = data["instructions"]
    except (OSError, ValueError, KeyError, TypeError) as exc:
        raise ValueError(f"cannot read instruction profile: {profile}") from exc
    if not isinstance(instructions, list):
        raise ValueError(f"instruction profile has no instruction list: {profile}")
    return len(instructions)


def count_tests(path):
    """Count top-level and class-nested test functions in a Python file.

    Non-test definitions are ignored; invalid source yields zero so the checker
    never executes untrusted text.
    """
    try:
        tree = ast.parse(Path(path).read_text(encoding="utf-8"))
    except (OSError, SyntaxError):
        return 0
    return sum(
        1 for node in ast.walk(tree)
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))
        and node.name.startswith("test_")
    )


def count_agents(root):
    """Count agent Markdown files under ``.opencode/agents``."""
    return len(list((Path(root) / ".opencode" / "agents").glob("*.md")))


def count_skills(root):
    """Count discoverable SKILL.md files under ``.opencode/skills``."""
    return len(list((Path(root) / ".opencode" / "skills").glob("*/SKILL.md")))


def measure(root=ROOT, profile=DEFAULT_PROFILE):
    """Return deterministic repository counts and a list of unavailable metrics.

    Only integer facts and the textual unavailable list are returned. No ratio,
    duration, token count or live execution result is inferred.
    """
    return {
        "files": count_files(Path(root) / "scripts", "*.py"),
        "bytes": count_bytes([
            *list((Path(root) / ".opencode" / "setup").glob("*.md")),
            *list((Path(root) / ".opencode" / "agents").glob("*.md")),
        ]),
        "doc_loads": doc_load_count(profile),
        "tests": count_tests(Path(root) / "scripts" / "tests" / "test_orca_core.py"),
        "agents": count_agents(root),
        "skills": count_skills(root),
        "unavailable": list(UNAVAILABLE_METRICS),
    }


def ratio(numerator, denominator):
    """Store a numerator, denominator and value together; zero denominator is None."""
    if denominator == 0:
        return {"numerator": numerator, "denominator": denominator, "value": None}
    return {"numerator": numerator, "denominator": denominator,
            "value": numerator / denominator}


def record(task_id, metric, value, source, kind="measurement"):
    """Create one evidence row with explicit provenance.

    Unknown sources and blank metrics raise ValueError. Denied metrics are
    returned with the text ``"unavailable"`` regardless of the supplied value.
    Percentage targets require ``kind="target"`` so they can never be mistaken
    for a measured result. Rows are never averaged across sources.
    """
    if source not in SOURCES:
        raise ValueError(f"unknown source: {source}")
    if not metric.strip():
        raise ValueError("blank metric")
    if metric in UNAVAILABLE_METRICS:
        return {"task_id": task_id, "metric": metric, "value": "unavailable",
                "source": EvidenceSource.UNAVAILABLE, "kind": "unavailable"}
    if metric in TARGETS and kind != "target":
        raise ValueError(f"metric {metric} is a target; pass kind='target'")
    return {"task_id": task_id, "metric": metric, "value": value,
            "source": source, "kind": kind}


def first_attempt(row):
    """Whether a row proves first-attempt success, failing closed on missing data."""
    acceptance = row.get("acceptance_passed")
    if acceptance is not True:
        return False
    if "repair_cycles" not in row:
        return False
    return row["repair_cycles"] == 0
