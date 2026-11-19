"""Generate opencode.json and .github/opencode.ci.json from the agent inventory in .opencode/agents.

The committed file is generated so the 33 permission blocks cannot drift.
Usage: gen-opencode.py [--check]. Without --check the file is rewritten.
With --check it exits 1 when the committed file differs from the generated one.
"""
import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PRIMARY = "smartclinic-orchestrator"

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
        "agents": agents,
    }


OUTPUTS = {"opencode.json": False, ".github/opencode.ci.json": True}


def render(root=ROOT, ci=False):
    names = [p.stem for p in (root / ".opencode/agents").glob("*.md")]
    return json.dumps(build(names, ci), indent=2) + "\n"


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args(argv)
    status = 0
    for name, ci in OUTPUTS.items():
        target = ROOT / name
        text = render(ROOT, ci)
        if args.check:
            if not target.exists() or target.read_text() != text:
                print(f"{name} is out of date: run scripts/gen-opencode.py",
                      file=sys.stderr)
                status = 1
        else:
            target.write_text(text)
    return status


if __name__ == "__main__":
    sys.exit(main())
