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

ROOT = Path(__file__).resolve().parent
# Isolated mode does not add the script directory to sys.path; the explicit insert
# keeps imports deterministic whether the file is run directly or imported by tests.
sys.path.insert(0, str(ROOT))

SCRIPT_REF = re.compile(r"(?:scripts|apps|docs|\.opencode|\.github)/[^\s'\"`)\]\}]+")
SHELL_REF = re.compile(r"(?:source|\.)\s+(\S+)|python3\s+-I\s+(\S+\.py)|bash\s+(\S+\.sh)")

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

    ``manifest_path`` may be absolute or repo-relative. A missing, malformed or
    wrongly shaped manifest is a bad-input error, not an ownership violation, so
    callers can exit 2 with an actionable message instead of a traceback.
    """
    path = Path(manifest_path)
    if not path.is_absolute():
        path = Path(root) / path
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
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


def discover(root):
    """Return sorted repo-relative Python and shell scripts under scripts/."""
    scripts = Path(root) / "scripts"
    return sorted(
        path.relative_to(root).as_posix()
        for path in scripts.rglob("*")
        if path.is_file() and path.suffix in (".py", ".sh") and "tests" not in path.parts
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
            refs.update(SCRIPT_REF.findall(node.value))
    return refs


def parse_shell_refs(text):
    """Extract sourced files and python/bash script arguments from shell text."""
    refs = set()
    for match in SHELL_REF.finditer(text):
        refs.update(part for part in match.groups() if part)
    return refs


def parse_text_refs(text):
    """Extract repo-relative script-like paths from workflow or Markdown text."""
    return set(SCRIPT_REF.findall(text))


def build_graph(refs):
    """Map each script path to its referenced script paths only.

    ``refs`` maps a path to all references found in it; external modules and
    non-script paths are dropped so the graph stays bounded.
    """
    graph = {}
    for path, targets in refs.items():
        graph[path] = sorted(target for target in targets if target in refs)
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


def app_local_script_exists(root, path):
    """Return whether a quoted `scripts/...` path resolves under an app.

    Logs copied into documentation often quote a command run from an app
    directory, where `scripts/foo.sh` names that app's scripts directory rather
    than the repository root. Inputs are the root and a repo-style script path;
    the output is True only when a real file exists under `apps/*/scripts`. The
    invariant is that this widens documentation text only; script and workflow
    references stay root-only.
    """
    relative = path[len("scripts/"):]
    # A doc may quote `..` or a glob metacharacter; neither is a concrete script.
    if ".." in relative.split("/"):
        return False
    for directory in (Path(root) / "apps").glob("*/scripts"):
        if (directory / relative).is_file():
            return True
    return False


def check_references(root, refs, allowed_paths=(), allow_app_local=False):
    """Return referenced paths that do not exist under the repository root.

    Documentation citations often append ``:line`` ranges and glob patterns;
    those are stripped or ignored here so only concrete paths are checked.
    ``allow_app_local`` is set for documentation text, where a quoted command
    may run from an app directory with its own ``scripts/``. Workflow steps run
    from the repository root and keep the strict root-only rule.
    """
    targets = set()
    for values in refs.values() if isinstance(refs, dict) else refs:
        targets.update(values) if isinstance(values, (set, list, tuple)) else targets.add(values)
    missing = []
    for raw in sorted(targets):
        path = re.sub(r":[\d,\-]+$", "", raw).rstrip(".,;:")
        if any(char in path for char in "*{}<>\\[]|()"):
            continue
        if Path(path).suffix not in (".py", ".sh"):
            continue
        # Only script paths are in this validator's ownership; workflow and doc
        # links belong to check-docs.py and skill/agent paths to check-pack.py.
        if path in allowed_paths:
            continue
        if path.startswith("scripts/") and not (Path(root) / path).exists():
            if not (allow_app_local and app_local_script_exists(root, path)):
                missing.append(f"broken reference: {path}")
    return missing


def check_modes(files, root):
    """Return executable/shebang problems for shell and Python scripts."""
    problems = []
    for rel in files:
        path = Path(root) / rel
        content = path.read_text(encoding="utf-8", errors="replace")
        shebang = content.startswith("#!")
        executable = bool(path.stat().st_mode & 0o111)
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
    for name in ("check.sh", "quick-check.sh", "build-and-test.sh"):
        if not (Path(root) / "scripts" / name).is_file():
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
    violations = list(exception_violations(manifest))
    files = discover(root)
    for rel in files:
        try:
            classify(rel, manifest)
        except ValueError as exc:
            violations.append(str(exc))
    refs = {}
    for rel in files:
        text = (Path(root) / rel).read_text(encoding="utf-8", errors="replace")
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
    workflow_refs = {}
    doc_refs = {}
    for base, collected in ((".github/workflows", workflow_refs), ("docs", doc_refs)):
        for path in (Path(root) / base).rglob("*"):
            if path.is_file() and path.suffix in (".yml", ".yaml", ".md"):
                collected[path.relative_to(root).as_posix()] = parse_text_refs(
                    path.read_text(encoding="utf-8", errors="replace")
                )
    allowed = {item.get("path") for item in manifest.get("exceptions", [])}
    violations.extend(check_references(root, refs, allowed))
    violations.extend(check_references(root, workflow_refs, allowed))
    # A workflow runs from the repository root; only copied docs can be app-cwd.
    violations.extend(check_references(root, doc_refs, allowed, allow_app_local=True))
    violations.extend(find_cycles(build_graph(refs)))
    violations.extend(find_duplicates(files, manifest))
    violations.extend(check_modes(files, root))
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
