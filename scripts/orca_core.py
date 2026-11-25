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
import re
from pathlib import Path

RISKS = frozenset(("routine", "cross_boundary", "critical"))
MANDATORY_SECTIONS = ("safety", "execution")
# Injected scope fragments map to domain and type; routing is supplied by the
# caller from route-task.py, never copied or discovered from the filesystem.
FRAGMENTS = {
    "apps/api": ("backend", "api"),
    "apps/staff-web": ("staff-web", "ui"),
    "apps/patient-mobile": ("patient-mobile", "ui"),
    "db/migrations": ("database", "migration"),
    ".github": ("workflow", "tooling"),
    "scripts": ("tooling", "tooling"),
}


@dataclass(frozen=True)
class TaskContract:
    """A machine-readable task contract used only for offline analysis.

    Inputs: identifiers, objective, acceptance scenarios, affected paths, a
    supported risk level, optional dependency/assumption metadata and the
    analysis-only skills, constraints, tests, definition-of-done and components
    fields, which default to empty because the tracker does not own them and
    do not feed ``readiness()``. Output: ``problems()`` returns field names
    that are missing or invalid, empty when the contract is complete. The
    invariant is that a contract with no problems carries enough information
    to compile a context and score readiness.
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
    skills: tuple = ()
    constraints: tuple = ()
    tests: tuple = ()
    definition_of_done: tuple = ()
    components: tuple = ()

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


def contract_from_task(task, risk):
    """Map one ``parse_tasks()`` dictionary into a TaskContract.

    Inputs are a parsed task dictionary and a supported risk string; the output
    is a contract. A blank id or unknown risk raises ValueError. The five new
    analysis fields stay empty because the tracker does not own them, and the
    requirements string is split once into a sorted tuple. No file is read and
    no task parser is duplicated.
    """
    task_id = str(task.get("id") or "").strip()
    if not task_id:
        raise ValueError("blank task id")
    if risk not in RISKS:
        raise ValueError(f"unknown risk: {risk}")
    raw = task.get("requirements") or ""
    if isinstance(raw, str):
        requirements = tuple(sorted({item.strip() for item in re.split(r"[,\n]", raw)
                                     if item.strip()}))
    elif isinstance(raw, (list, tuple)):
        requirements = tuple(sorted(set(raw)))
    else:
        requirements = ()
    objective = str(task.get("task") or task.get("objective") or "")
    return TaskContract(
        task_id, objective, (), tuple(task.get("scope") or ()), risk,
        requirements=requirements, depends=tuple(task.get("depends") or ()),
    )


def classify(contract, scope, routed, validation):
    """Classify a task deterministically from injected scope and routing data.

    ``scope`` is a tuple of paths, ``routed`` maps each path to specialist names
    from ``route-task.py``, and ``validation`` lists required checks. The result
    carries sorted domains, types, components, expertise and validation, the
    complexity count, the contract risk and clinical/security booleans. An
    incomplete contract, empty scope or empty routed data raises ValueError.
    No repository scan, tracker write or agent launch occurs.
    """
    if contract.problems():
        raise ValueError("incomplete contract")
    if not scope:
        raise ValueError("empty scope")
    if not routed:
        raise ValueError("empty routed")
    domains, types, expertise = set(), set(), set()
    for entry in scope:
        domain, kind = next(
            ((value[0], value[1]) for fragment, value in FRAGMENTS.items() if fragment in entry),
            ("unknown", "tooling"),
        )
        domains.add(domain)
        types.add(kind)
        expertise.update(routed.get(entry, ()))
    clinical = (
        any("clinical" in item.lower() for item in validation)
        or any("physician" in name or "clinical" in name for name in expertise)
    )
    security = (
        any("security" in name for name in expertise)
        or any("auth" in entry or ".github" in entry for entry in scope)
    )
    return {
        "domain": tuple(sorted(domains)),
        "type": tuple(sorted(types)),
        "components": tuple(sorted(scope)),
        "expertise": tuple(sorted(expertise)),
        "validation": tuple(sorted(validation)),
        "complexity": len(scope) + len(contract.acceptance) + len(contract.requirements),
        "risk": contract.risk,
        "clinical": clinical,
        "security": security,
    }


def split_plan(contract, classification, max_parts=8):
    """Split a multi-scope contract into passive scope and integration children.

    Inputs are a complete contract, a ``classify()`` result and a cap that counts
    the integration child. The output is a tuple of child dictionaries, or None
    for a single-scope plan. Each child carries its own id, objective, sliced
    acceptance, scope, inherited metadata, dependencies and kind; no task file,
    dispatch or worker is touched. An incomplete contract, malformed
    classification or cap smaller than the child count raises ValueError.
    """
    required = {
        "domain": tuple, "type": tuple, "components": tuple, "expertise": tuple,
        "validation": tuple, "complexity": int, "risk": str, "clinical": bool,
        "security": bool,
    }
    if contract.problems():
        raise ValueError("incomplete contract")
    if not isinstance(classification, dict) or any(
        key not in classification or not isinstance(classification[key], kind)
        for key, kind in required.items()
    ):
        raise ValueError("invalid classification")
    scopes = tuple(sorted(contract.scope))
    if len(scopes) == 1:
        return None
    if len(scopes) + 1 > max_parts:
        raise ValueError(
            f"max_parts {max_parts} is smaller than {len(scopes)} scopes plus integration"
        )
    inherited = {
        "risk": contract.risk,
        "requirements": contract.requirements,
        "skills": contract.skills,
        "components": classification["components"],
        "expertise": classification["expertise"],
        "validation": classification["validation"],
    }
    children = []
    for index, fragment in enumerate(scopes):
        acceptance = (contract.acceptance[index],) if index < len(contract.acceptance) else ()
        children.append({
            "task_id": f"{contract.task_id}-P{index + 1}",
            "objective": f"{contract.objective} — {fragment}",
            "acceptance": acceptance,
            "scope": (fragment,),
            "depends": (),
            "kind": "scope",
            **inherited,
        })
    children.append({
        "task_id": f"{contract.task_id}-INT",
        "objective": f"Integrate {contract.objective}",
        "acceptance": contract.acceptance,
        "scope": scopes,
        "depends": tuple(child["task_id"] for child in children),
        "kind": "integration",
        **inherited,
    })
    return tuple(children)


def compile_context(contract, sources, budgets):
    """Compile selected text sections under explicit per-section budgets.

    ``sources`` maps a section name to its text; ``budgets`` maps a section to a
    character limit, with ``default`` used when a section has no entry. Mandatory
    sections are never truncated; an optional section with a zero or negative
    budget is kept whole rather than dropped. The returned manifest carries the task id,
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
    # Acceptance and risk are guaranteed by the mandatory gate above, so their
    # points are unconditional; a guard here would be an unreachable branch.
    score = 30
    if contract.objective:
        score += 20
    if contract.scope:
        score += 15
    if contract.requirements:
        score += 15
    if contract.depends:
        score += 10
    if contract.assumptions:
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


def index_sources(files, extractors):
    """Build a deterministic source index from injected text and extractors.

    ``files`` maps path strings to text strings; ``extractors`` maps kind names
    to callables that accept text and return id strings. Each entry is
    ``{"hash": sha1(text)[:12], "kind": first extractor yielding ids or
    "unknown", "ids": sorted unique ids}``. Invalid inputs or a failing
    extractor raise ValueError. No file is opened and no repository is scanned.
    """
    if not isinstance(files, dict):
        raise ValueError("files must be a dict")
    if not isinstance(extractors, dict):
        raise ValueError("extractors must be a dict")
    # Validate before sorting: a mixed-type key would otherwise raise TypeError
    # from comparison rather than the documented ValueError for bad input.
    for path, text in files.items():
        if not isinstance(path, str):
            raise ValueError("path must be a string")
        if not isinstance(text, str):
            raise ValueError("text must be a string")
    for name, extract in extractors.items():
        if not isinstance(name, str):
            raise ValueError("extractor name must be a string")
        if not callable(extract):
            raise ValueError(f"extractor must be callable: {name}")
    result = {}
    for path in sorted(files):
        text = files[path]
        digest = hashlib.sha1(text.encode("utf-8")).hexdigest()[:12]
        kind, ids = "unknown", ()
        for name in sorted(extractors):
            extract = extractors[name]
            try:
                found = extract(text)
            except Exception as exc:
                raise ValueError(f"extractor failed: {name}: {exc}") from exc
            items = tuple(found)  # materialize once: generators are single-pass
            if not all(isinstance(item, str) for item in items):
                raise ValueError(f"extractor ids must be strings: {name}")
            values = tuple(sorted(set(items)))
            if values:
                kind, ids = name, values
                break
        result[path] = {"hash": digest, "kind": kind, "ids": ids}
    return result


def stale_entries(index, files):
    """Return sorted stale, added and removed paths against current revisions.

    ``files`` maps path strings to caller-supplied revision strings; the index
    is the output of ``index_sources``. Comparison uses ``evidence_fresh`` so a
    freshness policy has one owner. No Git or filesystem access occurs.
    """
    stale, removed = [], []
    for path in sorted(index):
        if path not in files:
            removed.append(path)
        elif not evidence_fresh(index[path]["hash"], files[path]):
            stale.append(path)
    return {
        "stale": tuple(sorted(stale)),
        "added": tuple(sorted(set(files).difference(index))),
        "removed": tuple(sorted(removed)),
    }


def impact(changed_paths, index, routed):
    """Map changed paths to affected boundaries without executing any analysis.

    Inputs: an iterable of changed path strings, an ``index_sources`` result and
    a mapping of path to specialist-name strings. Output: a dict of sorted
    tuples for eight non-exclusive categories plus an exclusive ``unmapped``
    complement and a per-path ``decisions`` map so callers can prove no path was
    dropped. Fragments and ids are case-sensitive and matched exactly as
    documented; invalid input types raise ValueError. No file, Git command,
    tracker or agent is touched. ``classify`` uses different signals because it
    reasons about task risk, not about changed path boundaries.
    """
    if isinstance(changed_paths, str):
        raise ValueError("impact: changed_paths must be an iterable of path strings")
    try:
        paths = tuple(changed_paths)  # materialize once: generators are single-pass
    except TypeError as exc:
        raise ValueError("impact: changed_paths must be an iterable of path strings") from exc
    if not isinstance(index, dict):
        raise ValueError("impact: index must be a dict")
    if not isinstance(routed, dict):
        raise ValueError("impact: routed must be a dict")
    for path in paths:
        if not isinstance(path, str):
            raise ValueError("impact: path must be a string")
    for names in routed.values():
        if not isinstance(names, (list, tuple, set, frozenset)) or any(
            not isinstance(name, str) for name in names
        ):
            raise ValueError("impact: routed names must be strings")
    result = {key: set() for key in (
        "consumers", "entities", "web", "mobile", "contracts", "tests",
        "security", "clinical", "unmapped",
    )}
    decisions = {}
    for path in sorted(set(paths)):
        names = tuple(routed.get(path, ()))
        matched = []
        consumers = set(names)
        for entry in index.values():
            if path in entry.get("ids", ()):
                consumers.update(entry["ids"])
        if consumers:
            result["consumers"].update(consumers)
            matched.append("consumers")
        if "db/migrations" in path or index.get(path, {}).get("kind") == "database":
            result["entities"].add(path)
            matched.append("entities")
        if "apps/staff-web" in path:
            result["web"].add(path)
            matched.append("web")
        if "apps/patient-mobile" in path:
            result["mobile"].add(path)
            matched.append("mobile")
        if "contracts/" in path or index.get(path, {}).get("kind") == "contract":
            result["contracts"].add(path)
            matched.append("contracts")
        if "test" in path:
            result["tests"].add(path)
            matched.append("tests")
        if "auth" in path or "security" in path or any("security" in name for name in names):
            result["security"].add(path)
            matched.append("security")
        if "clinical" in path or any(
            "physician" in name or "clinical" in name for name in names
        ):
            result["clinical"].add(path)
            matched.append("clinical")
        if not matched:
            result["unmapped"].add(path)
            matched.append("unmapped")
        decisions[path] = tuple(sorted(matched))
    result["decisions"] = decisions
    return {key: tuple(sorted(value)) if key != "decisions" else value
            for key, value in result.items()}


def save_checkpoint(path, state):
    """Write a JSON state snapshot to ``path`` and return its ``Path`` object.

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
