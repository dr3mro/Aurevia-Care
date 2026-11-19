"""Generate opencode.json from the agent inventory in .opencode/agents.

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

# Local, reversible commands the delivery loop runs constantly. Everything else
# in the shell stays on "ask", so pushes, tags, gh and installs keep a human gate.
SHELL_ALLOW = [
    "git status*", "git diff*", "git log*", "git show*", "git branch*",
    "git rev-parse*", "git ls-files*", "git add*", "git commit*",
    "scripts/check.sh*", "scripts/verify.sh*", "scripts/smoke-image.sh*",
    "scripts/docs.sh*", "python3 scripts/*", "python3 -I scripts/*",
]
# AGENTS.md forbids force-pushing and bypassing protections.
SHELL_DENY = [
    "git push*--force*", "git push -f*", "git push* -f *", "git push* -f",
    "git push*--delete*", "git reset --hard*", "git clean*",
    "git commit*--no-verify*", "git push*--no-verify*",
]


def rule(action, resource, effect):
    return {"action": action, "resource": resource, "effect": effect}


def build(names):
    """Return the configuration dictionary for the sorted agent names."""
    specialists = sorted(set(names) - {PRIMARY})
    primary = [
        rule("external_directory", "*", "deny"),
        rule("shell", "*", "ask"),
        *[rule("shell", pattern, "allow") for pattern in SHELL_ALLOW],
        *[rule("shell", pattern, "deny") for pattern in SHELL_DENY],
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


def render(root=ROOT):
    names = [p.stem for p in (root / ".opencode/agents").glob("*.md")]
    return json.dumps(build(names), indent=2) + "\n"


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args(argv)
    target = ROOT / "opencode.json"
    text = render()
    if args.check:
        if not target.exists() or target.read_text() != text:
            print("opencode.json is out of date: run scripts/gen-opencode.py",
                  file=sys.stderr)
            return 1
        return 0
    target.write_text(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
