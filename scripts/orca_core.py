"""Offline analysis helpers for ORCA contracts, context budgets and checkpoints.

This module is deliberately passive: it reads task data and returns decisions or
local JSON files. It never launches an agent, never mutates swarm state and does
not replace native OpenCode, ``scripts/swarm.py``, ``scripts/route-task.py`` or
``scripts/agent-run.sh`` as the runtime authority for routing, scheduling and
lifecycle. Inputs are synthetic task data and local paths; outputs are plain
dictionaries and paths. Errors are ValueError for invalid analysis input and the
standard file exceptions for missing or corrupt checkpoints.
"""

from dataclasses import dataclass
import hashlib
import json
from pathlib import Path

RISKS = frozenset(("routine", "cross_boundary", "critical"))
MANDATORY_SECTIONS = ("safety", "execution")


@dataclass(frozen=True)
class TaskContract:
    """A machine-readable task contract used only for offline analysis.

    Inputs: identifiers, objective, acceptance scenarios, affected paths, a
    supported risk level and optional dependency/assumption metadata. Output:
    ``problems()`` returns field names that are missing or invalid, empty when
    the contract is complete. The invariant is that a contract with no problems
    carries enough information to compile a context and score readiness.
    """

    task_id: str
    objective: str
    acceptance: tuple
    scope: tuple
    risk: str
    requirements: tuple = ()
    depends: tuple = ()
    exclusions: tuple = ()
    assumptions: tuple = ()

    def problems(self):
        """Return the required field names that are absent or invalid."""
        found = []
        if not self.task_id:
            found.append("task_id")
        if not self.objective:
            found.append("objective")
        if not self.acceptance:
            found.append("acceptance")
        if not self.scope:
            found.append("scope")
        if self.risk not in RISKS:
            found.append("risk")
        return found


def compile_context(contract, sources, budgets):
    """Compile selected text sections under explicit per-section budgets.

    ``sources`` maps a section name to its text; ``budgets`` maps a section to a
    character limit, with ``default`` used when a section has no entry. Mandatory
    sections are never truncated. The returned manifest carries the task id,
    selected content, ordered section names and a stable content hash; it is
    analysis output, not a runtime context handed to an agent.
    """
    selected = {}
    instructions = []
    references = []
    for name in dict.fromkeys((*MANDATORY_SECTIONS, *sorted(sources))):
        if name not in sources:
            continue
        text = sources[name]
        if name in MANDATORY_SECTIONS:
            part = text
            instructions.append(name)
        else:
            limit = budgets.get(name, budgets.get("default", 0))
            part = text if limit <= 0 else text[:limit]
            references.append(name)
        selected[name] = part
    manifest = {"task_id": contract.task_id, "instructions": instructions,
                "references": references, "content": selected}
    manifest["hash"] = hashlib.sha1(
        json.dumps(manifest, sort_keys=True).encode("utf-8")
    ).hexdigest()[:12]
    return manifest


def readiness(contract):
    """Score preparation from 0 to 100, failing closed on missing acceptance.

    A valid acceptance set and risk are mandatory. Remaining points come from
    the objective, scope, requirement ids, dependencies and recorded
    assumptions. Returns an integer score; no side effects.
    """
    if not contract.acceptance or contract.risk not in RISKS:
        return 0
    score = 0
    if contract.objective:
        score += 20
    if contract.acceptance:
        score += 20
    if contract.scope:
        score += 15
    if contract.requirements:
        score += 15
    if contract.depends:
        score += 10
    if contract.assumptions:
        score += 10
    if contract.risk in RISKS:
        score += 10
    return score


def preflight(contract, tools, instructions, skills):
    """List missing inputs for an offline task, without executing any check.

    Inputs are sets of available tool names, instruction paths and skill names.
    Returns human-readable problems; an empty list means the analysis inputs
    are present. Runtime enforcement remains the job of the delivery gates.
    """
    problems = []
    if not contract.task_id:
        problems.append("missing task id")
    for tool in ("python3", "opencode"):
        if tool not in tools:
            problems.append(f"missing tool: {tool}")
    for path in ("AGENTS.md", "WORKFLOW.md"):
        if path not in instructions:
            problems.append(f"missing instruction: {path}")
    for skill in ("delivery", "code-reviewer"):
        if skill not in skills:
            problems.append(f"missing skill: {skill}")
    return problems


class Blackboard:
    """Task-scoped, in-memory findings with explicit provenance.

    ``publish`` takes a task id, publishing agent, repository revision, evidence
    text, a confidence in [0, 1] and a fact/assumption flag. It returns the
    stored entry and rejects blank evidence or an invalid confidence.
    ``entries`` optionally filters by task id. Nothing is persisted.
    """

    def __init__(self):
        self._entries = []

    def publish(self, task_id, agent, revision, evidence, confidence=1.0, fact=True):
        """Store one attributed finding and return its dictionary entry."""
        if not evidence.strip():
            raise ValueError("evidence must not be blank")
        if not 0 <= confidence <= 1:
            raise ValueError("confidence must be between 0 and 1")
        entry = {"task_id": task_id, "agent": agent, "revision": revision,
                 "evidence": evidence, "confidence": confidence, "fact": fact}
        self._entries.append(entry)
        return entry

    def entries(self, task_id=None):
        """Return all entries, or only those published for one task."""
        if task_id is None:
            return list(self._entries)
        return [entry for entry in self._entries if entry["task_id"] == task_id]


def evidence_fresh(evidence_revision, current_revision):
    """Whether evidence bound to one revision still matches the current one."""
    return evidence_revision == current_revision


def save_checkpoint(path, state):
    """Write a JSON state snapshot to ``path`` and return the resolved path.

    Missing parent directories are created. ``state`` must be JSON-serialisable;
    no repository or agent state is read or written.
    """
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(state, sort_keys=True), encoding="utf-8")
    return target


def load_checkpoint(path):
    """Read a JSON state snapshot, raising the standard file or JSON errors."""
    return json.loads(Path(path).read_text(encoding="utf-8"))
