#!/usr/bin/env python3
"""Generate .opencode/opencode.json and .opencode/opencode.ci.json from the agent inventory in .opencode/agents.

The committed file is generated so the 33 permission blocks cannot drift.
Usage: gen-opencode.py [--check | --check-runtime]. Without a flag the file is
rewritten. With --check it exits 1 when a committed file differs from the
generated one. With --check-runtime it validates the CI runtime profile against
the unattended-swarm security policy, independently of the generated config.
"""
import argparse
import fnmatch
import json
import re
import subprocess
import sys
from pathlib import Path

# ``python3 -I`` omits the script directory from sys.path, so import the sibling
# environment helper explicitly (the same shim scripts/swarm.py uses).
sys.path.insert(0, str(Path(__file__).resolve().parent))
from process_env import child_environment

ROOT = Path(__file__).resolve().parents[1]
PRIMARY = "orca"

# Project instructions loaded into every agent session. Every Markdown document in
# .opencode/setup is mandatory: AGENTS.md, CODING_STYLE.md and CONSTRAINTS.md are the
# working rules and the written quality bar. README-SETUP.md and docs/WORKFLOW.md add
# setup and review routing. The specification, trackers, architecture decisions,
# swarm guide and module READMEs remain task-specific and are read on demand.
INSTRUCTIONS = [
    ".opencode/setup/AGENTS.md",
    ".opencode/setup/CODING_STYLE.md",
    ".opencode/setup/CONSTRAINTS.md",
    ".opencode/config/README-SETUP.md",
    "docs/WORKFLOW.md",
]

# Environment files may hold credentials. Rules are evaluated last match wins,
# so the example file is re-allowed after the deny rules.
SECRET_READ_RULES = [
    {"action": "read", "resource": "*.env", "effect": "deny"},
    {"action": "read", "resource": "*.env.*", "effect": "deny"},
    {"action": "read", "resource": "*.env.example", "effect": "allow"},
]

# The orchestrator runs scoped shell work without prompts by user directive
# (2026-11-18). Hard safety gates stay as explicit deny rules below: force push,
# reset/clean, --no-verify and .env reads are never allowed. CI uses the same
# allow-by-default shell with a larger deny list for publication commands.
SHELL_ALLOW = [
    "git status*", "git diff*", "git log*", "git show*", "git branch*",
    "git rev-parse*", "git ls-files*", "git add*", "git commit*",
    "scripts/check.sh*", "scripts/verify.sh*", "scripts/smoke-image.sh*",
    "scripts/docs.sh*", "python3 scripts/*", "python3 -I scripts/*",
]
# AGENTS.md forbids force-pushing and bypassing protections. These denies apply
# to both the local and CI profiles, on top of the broad shell allow rule.
SHELL_DENY = [
    "git push*--force*", "git push -f*", "git push* -f *", "git push* -f",
    "git push*--delete*", "git reset --hard*", "git clean*",
    "git commit*--no-verify*", "git push*--no-verify*",
    # Extra hard denies (user directive 2026-11-19): no privilege escalation,
    # recursive deletion, download-to-shell pipelines, or remote ref surgery.
    "sudo*", "rm -rf /*", "rm -rf ~*",
    "curl*|*sh*", "wget*|*sh*",
    "git push*:*", "git remote set-url*",
]


def rule(action, resource, effect):
    return {"action": action, "resource": resource, "effect": effect}


# Unattended runs cannot answer prompts, so the CI profile allows shell by
# default and removes what could leak or publish. The workflow gives the agent
# step no GitHub token and performs push and pull request creation itself.
CI_SHELL_DENY = [
    "gh *", "git push*", "git tag*", "git remote*", "git config*",
    "curl*", "wget*", "ssh*", "scp*", "nc *", "sudo*", "docker*",
]


def build(names, ci=False):
    """Return the configuration dictionary for the sorted agent names."""
    specialists = sorted(set(names) - {PRIMARY})
    primary = [
        rule("external_directory", "*", "deny"),
        rule("shell", "*", "allow"),
        *[rule("shell", pattern, "allow") for pattern in SHELL_ALLOW],
        *[rule("shell", pattern, "deny")
          for pattern in SHELL_DENY + (CI_SHELL_DENY if ci else [])],
        rule("subagent", "*", "deny"),
        *[rule("subagent", name, "allow") for name in specialists],
        *SECRET_READ_RULES,
    ]
    agents = {PRIMARY: {"permissions": primary}}
    for name in specialists:
        agents[name] = {"permissions": [
            rule("*", "*", "deny"),
            rule("read", "*", "allow"),
            *SECRET_READ_RULES,
            rule("glob", "*", "allow"),
            rule("grep", "*", "allow"),
            rule("skill", "*", "allow"),
        ]}
    return {
        "$schema": "https://opencode.ai/config.json",
        "default_agent": PRIMARY,
        # Load the repository's durable working rules and documentation before
        # any task. AGENTS.md points at trackers and the delivery skill; the
        # docs globs carry workflow, code style, architecture and API conventions.
        "instructions": INSTRUCTIONS,
        "agents": agents,
    }


OUTPUTS = {".opencode/opencode.json": False, ".opencode/opencode.ci.json": True}


def git_output(root, args):
    """Return stdout of a read-only Git command, or raise on failure.

    The sanitized environment prevents inherited ``GIT_*`` routing variables from
    redirecting the read to a different repository or index. No output is logged:
    config bytes and Git stderr can carry credentials.
    """
    return subprocess.run(
        ["git", "-C", str(root), *args], capture_output=True, text=True, check=True,
        env=child_environment(read_only=True),
    ).stdout


def read_generated(root=ROOT, name=""):
    """Return the authoritative text of one generated config file.

    Worktree bytes are authoritative in the normal developer loop, so local
    regeneration before commit is not reported as drift. When Git marks the file
    skip-worktree, an unattended worker has overlaid the CI runtime profile over
    it and the committed blob is authoritative instead. A scratch tree without a
    Git repository falls back to worktree bytes; a trusted read that cannot be
    completed returns ``None`` so callers fail closed.
    """
    path = Path(root) / name
    try:
        flags = git_output(root, ["ls-files", "-v", "-z", "--", name])
    except subprocess.CalledProcessError as error:
        # Only a tree with no Git metadata may fall back to worktree bytes. A
        # corrupt index or other fatal Git failure in a real repository must
        # fail closed instead of validating an overlaid CI profile as truth.
        if error.returncode != 128 or (Path(root) / ".git").exists():
            return None
        flags = ""  # Synthetic fixtures are copied without a repository.
    except OSError:
        flags = ""  # Git binary unavailable outside a worker image.
    for entry in flags.split("\0"):
        if entry.startswith("S "):
            try:
                return git_output(root, ["show", f"HEAD:{name}"])
            except (OSError, subprocess.CalledProcessError):
                return None
    return path.read_text() if path.exists() else None


# The CI profile is copied over opencode.json in unattended workers, so its policy
# needs its own gate. check-pack validates the generated local config for drift;
# check-runtime validates the profile the workers actually load (OPS-06).
RUNTIME_PROFILE = ".opencode/opencode.ci.json"
CATCH_ALL_DENY = {"action": "*", "resource": "*", "effect": "deny"}
BASH_ACTIONS = frozenset({"shell", "bash"})
WRITE_ACTIONS = frozenset({"write", "edit", "patch"})
READ_ONLY_ACTIONS = frozenset({"read", "glob", "grep", "skill"})
# Representative files prove whether a write rule can reach a protected tree.
PROTECTED_FILES = (
    ".github/workflows/delivery.yml",
    ".opencode/config/MEMORY.md",
    ".opencode/agents/example.md",
)
SECRET_PATTERNS = (
    ("api key/token variable",
     re.compile(r"\b[A-Z][A-Z0-9_]*_(?:KEY|TOKEN|SECRET|PASSWORD|CREDENTIAL)\b")),
    ("GitHub token", re.compile(r"gh[pousr]_[A-Za-z0-9]{16,}")),
    ("GitHub fine-grained token", re.compile(r"github_pat_[A-Za-z0-9_]{16,}")),
    ("service API key", re.compile(r"sk-[A-Za-z0-9]{16,}")),
    ("AWS access key", re.compile(r"AKIA[0-9A-Z]{16}")),
)


def render(root=ROOT, ci=False):
    names = [p.stem for p in (root / ".opencode/agents").glob("*.md")]
    return json.dumps(build(names, ci), indent=2) + "\n"


def _read_json(path, label, violations):
    """Load a policy file, recording a violation when it is missing or malformed."""
    try:
        return json.loads(path.read_text())
    except (OSError, ValueError):
        violations.append(f"{label}: {path} is missing or not valid JSON")
        return None


def check_runtime(root=ROOT):
    """Return the policy violations in the CI runtime profile under ``root``.

    An empty list means the profile is safe for unattended swarm workers. The
    profile is judged on its own: a fresh generated config does not excuse a
    runtime profile that grants write access or carries a credential. Model
    selection is supplied at run time through ``SWARM_MODEL`` and is not a
    runtime-profile policy input.
    """
    violations = []
    profile = _read_json(root / RUNTIME_PROFILE, "runtime profile", violations)
    if profile is None:
        return violations

    for path in profile.get("instructions", []):
        if not (root / path).is_file():
            violations.append(f"runtime profile: instruction file {path} does not exist")

    for name, config in profile.get("agents", {}).items():
        permissions = config.get("permissions", [])
        reviewer = CATCH_ALL_DENY in permissions
        bash_rules = [perm for perm in permissions if perm.get("action") in BASH_ACTIONS]
        if reviewer:
            if any(perm.get("effect") == "allow"
                   and perm.get("action") not in READ_ONLY_ACTIONS for perm in permissions):
                violations.append(f"agent {name}: read-only reviewer has edit permission")
        elif not bash_rules:
            violations.append(f"agent {name}: editing agent has no bash permission list")
        for perm in permissions:
            if perm.get("action") in WRITE_ACTIONS and perm.get("effect") == "allow":
                for protected in PROTECTED_FILES:
                    if fnmatch.fnmatchcase(protected, perm.get("resource", "")):
                        violations.append(
                            f"agent {name}: write permission targets {protected} "
                            f"via {perm.get('resource', '')}")
        for label, pattern in SECRET_PATTERNS:
            if pattern.search(json.dumps(config)):
                violations.append(f"agent {name}: contains a {label}")
    for label, pattern in SECRET_PATTERNS:
        if pattern.search(json.dumps({k: v for k, v in profile.items() if k != "agents"})):
            violations.append(f"runtime profile: contains a {label}")
    return violations


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--check-runtime", action="store_true", dest="check_runtime")
    args = parser.parse_args(argv)
    if args.check_runtime:
        violations = check_runtime(ROOT)
        for violation in violations:
            print(violation, file=sys.stderr)
        return 1 if violations else 0
    status = 0
    for name, ci in OUTPUTS.items():
        target = ROOT / name
        text = render(ROOT, ci)
        if args.check:
            authoritative = read_generated(ROOT, name)
            if authoritative is None or authoritative != text:
                print(f"{name} is out of date: run scripts/gen-opencode.py",
                      file=sys.stderr)
                status = 1
        else:
            target.write_text(text)
    return status


if __name__ == "__main__":
    sys.exit(main())
