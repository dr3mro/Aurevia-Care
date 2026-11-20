"""Validate the OpenCode role inventory, skills and delegation configuration.

Every problem is reported, not just the first one, so a fix can be made in one
pass. Exit status is 1 when any problem is found.
"""
import importlib.util
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
NAME = re.compile(r"[a-z0-9]+(?:-[a-z0-9]+)*")
FRONTMATTER = re.compile(r"\A---\n(.*?)\n---\n", re.S)


def frontmatter(text):
    """Return the simple key/value pairs of a leading YAML block, or None."""
    match = FRONTMATTER.match(text)
    if not match:
        return None
    pairs = {}
    for line in match.group(1).splitlines():
        key, sep, value = line.partition(":")
        if not sep:
            return None
        pairs[key.strip()] = value.strip().strip('"')
    return pairs


def load_generator(root):
    spec = importlib.util.spec_from_file_location(
        "gen_opencode", root / "scripts/gen-opencode.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def check(root=ROOT):
    """Return a list of human-readable problems for the pack under root."""
    errors = []
    config = json.loads((root / "opencode.json").read_text())
    agents = config["agents"]
    primary = config["default_agent"]
    definitions = {p.stem: p.read_text()
                   for p in (root / ".opencode/agents").glob("*.md")}
    skills = {p.parent.name: p.read_text()
              for p in (root / ".opencode/skills").glob("*/SKILL.md")}

    if set(agents) != set(definitions):
        errors.append("opencode.json and .opencode/agents differ: "
                      f"{sorted(set(agents) ^ set(definitions))}")
    if primary not in definitions:
        errors.append(f"default_agent {primary} has no definition")
        return errors
    specialists = set(definitions) - {primary}

    for name, text in sorted(definitions.items()):
        meta = frontmatter(text)
        expected = "primary" if name == primary else "subagent"
        if meta is None:
            errors.append(f"agent {name}: missing or malformed frontmatter")
            continue
        if meta.get("mode") != expected:
            errors.append(f"agent {name}: mode must be {expected}")
        if not meta.get("description"):
            errors.append(f"agent {name}: description is required")
        if not NAME.fullmatch(name):
            errors.append(f"agent {name}: name must be kebab-case")
        loaded = set(re.findall(r"Load (?:the )?`?(smartclinic-[a-z0-9-]+)`?", text))
        role_skill = "smartclinic-delivery" if name == primary else name
        if role_skill not in loaded or not loaded <= set(skills):
            errors.append(f"agent {name}: must load {role_skill} and only existing skills, "
                          f"found {sorted(loaded)}")

    allowed = {r["resource"] for r in agents[primary]["permissions"]
               if r["action"] == "subagent" and r["effect"] == "allow"}
    if allowed != specialists:
        errors.append("orchestrator subagent allow list differs from "
                      f"specialists: {sorted(allowed ^ specialists)}")
    for name in sorted(specialists & set(agents)):
        rules = agents[name]["permissions"]
        if rules[0] != {"action": "*", "resource": "*", "effect": "deny"}:
            errors.append(f"agent {name}: first rule must deny everything")
        extra = {r["action"] for r in rules[1:]} - {"read", "grep", "glob", "skill"}
        if extra:
            errors.append(f"agent {name}: specialists are read-only, found {sorted(extra)}")

    for name, text in sorted(skills.items()):
        meta = frontmatter(text)
        if not NAME.fullmatch(name):
            errors.append(f"skill {name}: directory must be kebab-case")
        if meta is None or meta.get("name") != name or not meta.get("description"):
            errors.append(f"skill {name}: frontmatter needs matching name and description")
    if specialists | {"smartclinic-delivery"} != set(skills):
        errors.append("skills differ from specialists plus smartclinic-delivery: "
                      f"{sorted((specialists | {'smartclinic-delivery'}) ^ set(skills))}")

    generator = load_generator(root)
    for name, ci in generator.OUTPUTS.items():
        if generator.profile_source(root, name) != generator.render(root, ci):
            errors.append(f"{name} is stale: run scripts/gen-opencode.py")
    return errors


def main():
    errors = check()
    for error in errors:
        print(f"check-pack: {error}", file=sys.stderr)
    if errors:
        return 1
    count = len(list((ROOT / ".opencode/agents").glob("*.md")))
    print(f"Delivery tooling validated: {count} agents, {count} skills")
    return 0


if __name__ == "__main__":
    sys.exit(main())
