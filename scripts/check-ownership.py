#!/usr/bin/env python3
"""Validate declared script ownership and path references without executing code.

The manifest ``scripts/ownership.json`` declares the owner of each script and a
shrink-only list of exceptions. Inputs are a repository root, the manifest and the
current file tree; outputs are sorted, actionable violation lines on stderr and an
exit code (0 clean, 1 violations, 2 bad input). The invariant is that this checker
is passive: no subprocess, network, credential read or repository mutation.
"""
import argparse
import ast
import json
import re
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent
# Isolated mode does not add the script directory to sys.path; the explicit insert
# keeps imports deterministic whether the file is run directly or imported by tests.
sys.path.insert(0, str(ROOT))

SCRIPT_REF = re.compile(
    r"(?<![A-Za-z0-9_.\-/])(?:\./)?(?:scripts|apps|docs|\.opencode|\.github)/"
    r"[^\s'\"`)\]\}]+")
SHELL_REF = re.compile(r"(?:source|\.)\s+(\S+)|python3\s+-I\s+(\S+\.py)|bash\s+(\S+\.sh)")
# Bound passive parser ingestion; checked-in sources are far smaller than this.
MAX_SOURCE_BYTES = 1_000_000

# Exceptions are shrink-only: a change may remove one but never add one. This
# frozen baseline makes a self-certifying new exception fail the validator.
DYNAMIC_HELPERS = frozenset({
    "scripts/taskstore.py", "scripts/check-secrets.py",
    "scripts/check-docs.py", "scripts/check-wiring.py",
})
FROZEN_EXCEPTIONS = frozenset({
    ("scripts/swarm.py", "dynamic-load"),
    ("scripts/check-doc-sync.py", "future-design"),
    ("scripts/gen-skill-registry.py", "future-design"),
    ("scripts/orca_benchmark.py", "future-design"),
    ("scripts/example.py", "future-design"),
})


def resolve_root(start):
    """Find the repository root containing project.json and scripts/.

    ``start`` may be the root, a script directory or an unrelated directory.
    Returns the absolute root. Raises ValueError when no marker is found.
    """
    current = Path(start).resolve()
    for candidate in (current, *current.parents):
        if (candidate / "project.json").is_file() and (candidate / "scripts").is_dir():
            return candidate
    raise ValueError(f"no repository root found from {start}")


def load_manifest(root, manifest_path="scripts/ownership.json"):
    """Read the requested manifest; return its dict or raise ValueError.

    ``manifest_path`` may be absolute or repo-relative. Relative manifests must
    resolve inside the repository and every manifest is size-bounded. A missing,
    malformed or wrongly shaped manifest is a bad-input error, not an ownership
    violation, so callers can exit 2 with an actionable message.
    """
    path = Path(manifest_path)
    trusted_absolute = path.is_absolute()
    if not trusted_absolute:
        path = Path(root) / path
    try:
        resolved = path.resolve()
        if not trusted_absolute and not resolved.is_relative_to(Path(root).resolve()):
            raise ValueError("manifest escapes repository")
        if resolved.stat().st_size > MAX_SOURCE_BYTES:
            raise ValueError("manifest is too large")
        data = json.loads(resolved.read_text(encoding="utf-8"))
    except (OSError, RuntimeError, ValueError) as exc:
        raise ValueError(f"cannot read ownership manifest: {path}") from exc
    if not isinstance(data, dict) or not isinstance(data.get("files"), dict):
        raise ValueError(f"ownership manifest lacks a files object: {path}")
    return data


def classify(rel, manifest):
    """Return the declared owner of a repo-relative script path.

    Raises ValueError for an undeclared path so a new script cannot silently
    escape ownership; the manifest is the only authority.
    """
    owner = manifest["files"].get(rel)
    if owner is None:
        raise ValueError(f"undeclared script: {rel}")
    return owner


def normalize_reference(raw):
    """Strip citation suffixes and canonicalize a repository-relative path."""
    path = re.sub(r":[\d,\-]+$", "", raw).rstrip(".,;:")
    return Path(path).as_posix()


def discover(root):
    """Return sorted repo-relative Python and shell scripts under scripts/."""
    scripts = Path(root) / "scripts"
    return sorted(
        path.relative_to(root).as_posix()
        for path in scripts.rglob("*")
        if (path.is_file() and path.suffix in (".py", ".sh")
                and "tests" not in path.parts)
    )


def parse_python_refs(text):
    """Extract referenced module names and literal script paths from Python text.

    Malformed Python yields an empty set because the checker must not execute or
    crash on untrusted source; a named test covers that path.
    """
    try:
        tree = ast.parse(text)
    except SyntaxError:
        return set()
    refs = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            refs.update(alias.name for alias in node.names)
        elif isinstance(node, ast.ImportFrom) and node.module:
            refs.add(node.module)
        elif isinstance(node, ast.Constant) and isinstance(node.value, str):
            refs.update(parse_text_refs(node.value))
    return refs


def parse_shell_refs(text):
    """Extract sourced files and python/bash script arguments from shell text."""
    refs = set()
    for match in SHELL_REF.finditer(text):
        refs.update(normalize_reference(part) for part in match.groups() if part)
    return refs


def parse_text_refs(text):
    """Extract canonical repo-relative script-like paths from source text."""
    return {normalize_reference(ref) for ref in SCRIPT_REF.findall(text)}


def _mapping(value):
    """Return a mapping value or an empty mapping for malformed input shapes."""
    return value if isinstance(value, dict) else {}


def _mapping_field(container, key, label, problems):
    """Return a mapping field, recording an actionable problem when it is wrong."""
    if key not in container:
        return {}
    value = container[key]
    if not isinstance(value, dict):
        problems.append(f"{label} must be an object")
        return {}
    return value


def _string_refs(value):
    """Extract script references from string values in an env/with mapping."""
    return {
        ref
        for item in _mapping(value).values()
        if isinstance(item, str)
        for ref in parse_text_refs(item)
    }


def package_references(text):
    """Return package script references and parse/shape problems from JSON text.

    Only string-valued ``scripts`` entries can name package-relative executables.
    Malformed JSON and invalid script shapes fail the ownership check explicitly;
    unrelated JSON fields remain prose or metadata and create no references.
    """
    try:
        data = json.loads(text)
    except ValueError:
        return set(), ["invalid package JSON"]
    if not isinstance(data, dict):
        return set(), ["package JSON must be an object"]
    if "scripts" not in data:
        return set(), []
    scripts = data["scripts"]
    if not isinstance(scripts, dict):
        return set(), ["package scripts must be an object"]
    refs = set()
    invalid = False
    for command in scripts.values():
        if isinstance(command, str):
            refs.update(parse_text_refs(command))
        else:
            invalid = True
    problems = ["package script values must be strings"] if invalid else []
    return refs, problems


def working_directory(root, value):
    """Resolve a literal workflow directory under root; dynamic values use root.

    GitHub expressions cannot be evaluated statically, so guessing a directory
    could hide a broken root reference. Root fallback keeps that check fail-closed.
    Candidate existence checks separately reject paths that escape the checkout.
    """
    if not isinstance(value, str) or "${{" in value:
        return Path(root)
    return Path(root) / value


def workflow_references(text, root):
    """Return per-step references and parse/shape problems for one workflow.

    Run steps use their effective step, job or workflow ``working-directory``.
    Step env/with values share that context; job/workflow env values stay at the
    repository root because no single run directory owns them. Invalid YAML and
    wrong-shaped data are reported rather than silently dropping references.
    """
    try:
        data = yaml.safe_load(text)
    except yaml.YAMLError:
        return {}, ["invalid workflow YAML"]
    if not isinstance(data, dict):
        return {}, ["workflow must be an object"]

    problems = []
    entries = {}
    root = Path(root)
    defaults = _mapping_field(data, "defaults", "workflow defaults", problems)
    workflow_run = _mapping_field(defaults, "run", "workflow defaults.run", problems)
    workflow_default = workflow_run.get("working-directory", ".")
    if ("working-directory" in workflow_run
            and not isinstance(workflow_default, str)):
        problems.append("workflow defaults.run working-directory must be a string")
    workflow_env = _mapping_field(data, "env", "workflow env", problems)
    workflow_refs = _string_refs(workflow_env)
    if workflow_refs:
        entries["workflow.env"] = (root, workflow_refs)

    jobs = data.get("jobs")
    if not isinstance(jobs, dict):
        problems.append("workflow jobs must be an object")
        return entries, sorted(problems)
    for job_name, raw_job in jobs.items():
        if not isinstance(raw_job, dict):
            problems.append(f"job {job_name} must be an object")
            continue
        job = raw_job
        job_defaults = _mapping_field(job, "defaults", f"job {job_name} defaults", problems)
        job_run = _mapping_field(
            job_defaults, "run", f"job {job_name} defaults.run", problems)
        job_default = job_run.get("working-directory", workflow_default)
        if not isinstance(job_default, str) and "working-directory" in job_run:
            problems.append(
                f"job {job_name} defaults.run working-directory must be a string")
        job_env = _mapping_field(job, "env", f"job {job_name} env", problems)
        job_refs = _string_refs(job_env)
        if job_refs:
            entries[f"job.{job_name}.env"] = (root, job_refs)

        steps = job.get("steps")
        if "steps" not in job:
            continue
        if not isinstance(steps, list):
            problems.append(f"job {job_name} steps must be a list")
            continue
        for index, raw_step in enumerate(steps):
            label = f"job {job_name} step {index}"
            if not isinstance(raw_step, dict):
                problems.append(f"{label} must be an object")
                continue
            step = raw_step
            run = step.get("run")
            if "run" in step and not isinstance(run, str):
                problems.append(f"{label} run must be a string")
            directory = step.get("working-directory", job_default)
            if "working-directory" in step and not isinstance(directory, str):
                problems.append(f"{label} working-directory must be a string")
            base = working_directory(root, directory)
            step_env = _mapping_field(step, "env", f"{label} env", problems)
            step_with = _mapping_field(step, "with", f"{label} with", problems)
            refs = _string_refs(step_env) | _string_refs(step_with)
            if isinstance(run, str):
                refs.update(parse_text_refs(run))
            if refs:
                entries[f"{job_name}.{index}"] = (base, refs)
    return entries, sorted(problems)


def build_graph(refs):
    """Map each script path to its referenced script paths only.

    ``refs`` maps a path to all references found in it; external modules and
    non-script paths are dropped so the graph stays bounded.
    """
    graph = {}
    for path, targets in refs.items():
        normalized = {normalize_reference(target) for target in targets}
        graph[path] = sorted(normalized & set(refs))
    return graph


def find_cycles(graph):
    """Return sorted cycles in a reference graph, including self-cycles.

    Uses an iterative DFS state map; a node already visiting closes a cycle.
    """
    state = {}
    cycles = set()

    def visit(node):
        state[node] = 1
        for child in graph.get(node, ()):
            if state.get(child) == 1:
                cycles.add((node, child))
            elif child not in state:
                visit(child)
        state[node] = 2

    for node in sorted(graph):
        if node not in state:
            visit(node)
    return sorted(f"{a} -> {b}" for a, b in cycles)


def find_duplicates(files, manifest):
    """Return duplicate basenames not listed in the manifest exceptions.

    ``files`` is a list of repo-relative script paths. A duplicate listed as an
    exception is accepted; adding a new exception is itself a manifest change
    and must be reviewed, so this function only reads the declared list.
    """
    seen = {}
    duplicates = []
    excepted = {(item.get("path"), item.get("rule")) for item in manifest.get("exceptions", [])}
    for path in files:
        name = Path(path).name
        if name in seen and (path, "duplicate") not in excepted:
            duplicates.append(f"duplicate basename: {name}")
        seen[name] = path
    return sorted(set(duplicates))


def _exists_within(root, base, relative):
    """Return whether a resolved candidate exists inside the repository root."""
    try:
        resolved_root = root.resolve()
        candidate = (base / relative).resolve()
        return candidate.is_relative_to(resolved_root) and candidate.exists()
    except (OSError, RuntimeError, ValueError):
        return False


def _read_source(root, path, rel, violations):
    """Read a bounded source inside root, or record why it cannot be inspected."""
    try:
        resolved_root = Path(root).resolve()
        resolved = path.resolve()
        if not resolved.is_relative_to(resolved_root):
            violations.append(f"source escapes repository: {rel}")
            return None
        if resolved.stat().st_size > MAX_SOURCE_BYTES:
            violations.append(f"source is too large: {rel}")
            return None
        return resolved.read_text(encoding="utf-8", errors="replace")
    except (OSError, RuntimeError, ValueError):
        violations.append(f"cannot read source: {rel}")
        return None


def check_references(root, refs, allowed_paths=(), base_dirs=None):
    """Return script paths missing from every source-aware candidate directory.

    ``refs`` may be a bare target collection or a source-to-targets mapping.
    ``base_dirs`` optionally supplies ordered base directories for a source;
    otherwise a source-relative directory is tried before the repository root.
    Documentation citations often append ``:line`` ranges and glob patterns;
    those are stripped or ignored here so only concrete paths are checked.
    """
    root = Path(root)
    missing = set()
    sources = refs.items() if isinstance(refs, dict) else ((None, refs),)
    for source, values in sources:
        targets = set()
        targets.update(values) if isinstance(values, (set, list, tuple)) else targets.add(values)
        configured = (base_dirs or {}).get(source, ())
        if not configured and source:
            configured = (root / Path(source).parent,)
        candidates = (*configured, root)
        for raw in sorted(targets):
            path = normalize_reference(raw)
            if any(char in path for char in "*{}<>\\[]|()"):
                continue
            if Path(path).suffix not in (".py", ".sh"):
                continue
            # Only script paths are in this validator's ownership; workflow and doc
            # links belong to check-docs.py and skill/agent paths to check-pack.py.
            if path in allowed_paths:
                continue
            if path.startswith("scripts/") and not any(
                    _exists_within(root, base, path) for base in candidates):
                missing.add(f"broken reference: {path}")
    return sorted(missing)


def check_modes(files, root, contents=None):
    """Return executable/shebang problems without rereading validated content.

    ``contents`` carries bounded source text from validation. Rejected sources are
    absent and skipped; direct callers use the same confined, bounded reader.
    """
    problems = []
    for rel in files:
        path = Path(root) / rel
        if contents is None:
            content = _read_source(root, path, rel, problems)
            if content is None:
                continue
        else:
            if rel not in contents:
                continue
            content = contents[rel]
        try:
            executable = bool(path.stat().st_mode & 0o111)
        except OSError:
            problems.append(f"cannot inspect script mode: {rel}")
            continue
        shebang = content.startswith("#!")
        if rel.endswith(".sh") and not (shebang and executable):
            problems.append(f"shell script needs shebang and executable bit: {rel}")
        elif rel.endswith(".py") and executable and not shebang:
            problems.append(f"Python script is executable without shebang: {rel}")
    return problems


def check_test_discovery(root):
    """Return duplicate test module names under scripts/tests/."""
    seen = {}
    problems = []
    tests = sorted((Path(root) / "scripts" / "tests").rglob("*.py"))
    for path in tests:
        name = path.name
        if name in seen:
            problems.append(f"duplicate test module: {name}")
        seen[name] = path
    return problems


def check_gate_paths(root):
    """Return missing canonical gate scripts referenced by the policy docs.

    The gate-glob contents themselves belong to `scripts/check.sh` and Delivery;
    this rule only proves the entry scripts exist, so it does not duplicate those
    checks or produce debt for helpers invoked from workflows and agent-run.sh.
    """
    problems = []
    scripts = Path(root) / "scripts"
    for name in ("check.sh", "quick-check.sh", "build-and-test.sh"):
        if not _exists_within(root, scripts, name) or not (scripts / name).is_file():
            problems.append(f"missing gate script: scripts/{name}")
    return problems


def exception_violations(manifest):
    """Return exceptions added beyond the frozen baseline; removals are allowed."""
    declared = {
        (item.get("path"), item.get("rule"))
        for item in manifest.get("exceptions", [])
    }
    return [f"new ownership exception: {path}: {rule}"
            for path, rule in sorted(declared - FROZEN_EXCEPTIONS)]


def dynamic_load_paths(manifest):
    """Return paths whose sibling loads cannot be seen by static parsing."""
    return {
        item.get("path")
        for item in manifest.get("exceptions", [])
        if item.get("rule") == "dynamic-load"
    }


def validate(root, manifest):
    """Return every ownership, reference, mode, test and gate violation."""
    root_path = Path(root)
    violations = list(exception_violations(manifest))
    files = discover(root)
    for rel in files:
        try:
            classify(rel, manifest)
        except ValueError as exc:
            violations.append(str(exc))
    refs = {}
    contents = {}
    for rel in files:
        text = _read_source(root_path, root_path / rel, rel, violations)
        if text is None:
            continue
        contents[rel] = text
        found = parse_python_refs(text) if rel.endswith(".py") else parse_shell_refs(text)
        found.update(parse_text_refs(text))
        # A script naming its own path in a docstring is not a dependency edge,
        # and a Python module does not depend on a shell gate by mentioning it.
        refs[rel] = {
            target for target in found
            if target != rel and not (rel.endswith(".py") and target.endswith(".sh"))
        }
    for rel in dynamic_load_paths(manifest):
        if rel in refs:
            # Only the sibling helpers loaded dynamically by _checker are invisible
            # to static parsing; other references from that script still matter.
            refs[rel] = {
                target for target in refs[rel]
                if target not in DYNAMIC_HELPERS
            }
    external = {}
    base_dirs = {}
    packages = [
        root_path / "package.json",
        *sorted((root_path / "apps").glob("*/package.json")),
    ]
    for package in packages:
        if not package.is_file():
            continue
        rel = package.relative_to(root_path).as_posix()
        text = _read_source(root_path, package, rel, violations)
        if text is None:
            continue
        package_refs, problems = package_references(text)
        violations.extend(f"{rel}: {problem}" for problem in problems)
        if package_refs:
            external[rel] = package_refs
            base_dirs[rel] = (package.parent,)
    for path in (root_path / ".github" / "workflows").rglob("*"):
        if path.is_file() and path.suffix in (".yml", ".yaml"):
            rel = path.relative_to(root_path).as_posix()
            text = _read_source(root_path, path, rel, violations)
            if text is None:
                continue
            entries, problems = workflow_references(text, root_path)
            violations.extend(f"{rel}: {problem}" for problem in problems)
            for step, (base, workflow_refs) in entries.items():
                source = f"{rel}#{step}"
                external[source] = workflow_refs
                base_dirs[source] = (base,)
    for path in (root_path / "docs").rglob("*"):
        if path.is_file() and path.suffix in (".md", ".yml", ".yaml"):
            rel = path.relative_to(root_path).as_posix()
            text = _read_source(root_path, path, rel, violations)
            if text is not None:
                external[rel] = parse_text_refs(text)
    allowed = {item.get("path") for item in manifest.get("exceptions", [])}
    violations.extend(check_references(root, refs, allowed))
    violations.extend(check_references(root, external, allowed, base_dirs))
    violations.extend(find_cycles(build_graph(refs)))
    violations.extend(find_duplicates(files, manifest))
    violations.extend(check_modes(files, root, contents))
    violations.extend(check_test_discovery(root))
    violations.extend(check_gate_paths(root))
    return sorted(set(violations))


def main(argv=None):
    """Parse args, validate the tree and print sorted violations; return an exit code."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", nargs="?", default="scripts/ownership.json")
    args = parser.parse_args(argv)
    try:
        root = resolve_root(ROOT)
        manifest = load_manifest(root, args.manifest)
    except ValueError as exc:
        print(f"check-ownership: {exc}", file=sys.stderr)
        return 2
    violations = validate(root, manifest)
    for line in violations:
        print(f"check-ownership: {line}", file=sys.stderr)
    print(f"check-ownership: {len(violations)} violation(s)", file=sys.stderr)
    return 1 if violations else 0


if __name__ == "__main__":
    sys.exit(main())
