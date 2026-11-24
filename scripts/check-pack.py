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

# Worker roles named in docs/WORKFLOW.md and the orchestrator prompt. Each must
# resolve to a real agent definition; keep the mapping in sync with those docs.
ROLE_AGENTS = {
    "Product Manager": "product-manager",
    "Product Owner": "product-owner",
    "Architect": "software-architect",
    "Scrum Master": "scrum-master",
    "Senior Software Engineer": "software-engineer",
    "Tester": "test-engineer",
    "QA": "qa-reviewer",
    "Debugger": "debugger",
    "UI/UX": "ux-reviewer",
    "System Designer": "system-designer",
    "DevSecOps": "devsecops",
    "GitHub Workflow Expert": "github-workflow",
}


# A skill description is what an agent reads to decide whether to load the skill, so it must say
# when to use it, and it must fit the limit of the skill format.
SKILL_DESCRIPTION_LIMIT = 1024
PROCESS_SECTIONS = ("Common rationalizations", "Red flags", "Verification")
# Skills that play a simulated user or plan and track work have no engineering process to
# check; every other skill must carry the process sections.
NO_PROCESS_SECTIONS = {
    "orevia-clinic-assistant", "orevia-laboratory-user", "orevia-patient-user",
    "orevia-pharmacy-user", "orevia-physical-therapy-user", "orevia-radiology-user",
    "orevia-product-manager", "orevia-product-owner", "orevia-scrum-master",
    "orevia-system-designer", "orevia-memory-manager", "orevia-context-optimizer",
}


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


def load_config(path):
    """Return (config, duplicate agent names) parsed from an opencode.json.

    json.loads keeps only the last of duplicate keys, so an object hook records
    repeated names before they are silently collapsed. Only names that are agents
    in this config matter; duplicates in unrelated objects are not agent names.
    """
    repeated = set()

    def object_pairs(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                repeated.add(key)
            result[key] = value
        return result

    config = json.loads(path.read_text(), object_pairs_hook=object_pairs)
    duplicates = [name for name in repeated if name in config.get("agents", {})]
    return config, duplicates


def check(root=ROOT):
    """Return a list of human-readable problems for the pack under root."""
    errors = []
    config, duplicates = load_config(root / ".opencode/opencode.json")
    agents = config["agents"]
    primary = config["default_agent"]
    definitions = {p.stem: p.read_text()
                   for p in (root / ".opencode/agents").glob("*.md")}
    skills = {p.parent.name: p.read_text()
              for p in (root / ".opencode/skills").glob("*/SKILL.md")}

    for name in sorted(set(duplicates)):
        errors.append(f"opencode.json: duplicate agent name {name}")
    for name in sorted(set(definitions) - set(agents)):
        errors.append(f"agent file .opencode/agents/{name}.md is not in opencode.json")
    for name in sorted(set(agents) - set(definitions)):
        errors.append(f"configured agent {name} in opencode.json has no file "
                      f".opencode/agents/{name}.md")
    if primary not in definitions:
        errors.append(f"default_agent {primary} has no definition "
                      f".opencode/agents/{primary}.md")
        return errors
    specialists = set(definitions) - {primary}

    for name, text in sorted(definitions.items()):
        meta = frontmatter(text)
        expected = "primary" if name == primary else "subagent"
        if meta is None:
            errors.append(f"agent .opencode/agents/{name}.md: missing or malformed frontmatter")
            continue
        if meta.get("mode") != expected:
            errors.append(f"agent {name}: mode must be {expected}")
        if not meta.get("description"):
            errors.append(f"agent {name}: description is required")
        if not NAME.fullmatch(name):
            errors.append(f"agent {name}: name must be kebab-case")
        loaded = set(re.findall(r"Load (?:the )?`?(orevia-[a-z0-9-]+)`?", text))
        if not loaded or not loaded <= set(skills):
            errors.append(
                f"agent .opencode/agents/{name}.md must load an existing skill "
                f"(found {sorted(loaded)}, missing {sorted(loaded - set(skills))})")

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

    for skill_dir in sorted((root / ".opencode/skills").iterdir()):
        if skill_dir.is_dir() and not (skill_dir / "SKILL.md").exists():
            errors.append(f"skill .opencode/skills/{skill_dir.name}/SKILL.md is missing")

    for name, text in sorted(skills.items()):
        meta = frontmatter(text)
        if not NAME.fullmatch(name):
            errors.append(f"skill {name}: directory must be kebab-case")
        if meta is None or meta.get("name") != name or not meta.get("description"):
            errors.append(f"skill {name}: frontmatter needs matching name and description")
    for name, text in sorted(skills.items()):
        meta = frontmatter(text) or {}
        description = meta.get("description", "")
        if description and "Use when" not in description:
            errors.append(f"skill {name}: the description must say when to use it (\"Use when ...\")")
        if len(description) > SKILL_DESCRIPTION_LIMIT:
            errors.append(f"skill {name}: the description is over {SKILL_DESCRIPTION_LIMIT} characters")
        if name in NO_PROCESS_SECTIONS:
            continue
        for heading in PROCESS_SECTIONS:
            if not re.search(rf"(?mi)^##\s+{re.escape(heading)}\s*$", text):
                errors.append(f"skill {name}: missing the section \"## {heading}\"")
    # Agent files are short names; each specialist loads the skill of the same
    # name with the orevia- prefix, and the primary loads the delivery skill.
    expected_skills = {f"orevia-{name}" for name in specialists} | {"orevia-delivery"}
    if expected_skills != set(skills):
        errors.append("skills differ from specialists plus orevia-delivery: "
                      f"{sorted(expected_skills ^ set(skills))}")

    workflow = root / "docs/WORKFLOW.md"
    role_sources = definitions.get(primary, "")
    if workflow.exists():
        role_sources += "\n" + workflow.read_text()
    for role, agent in ROLE_AGENTS.items():
        if re.search(r"\b" + re.escape(role) + r"\b", role_sources):
            if agent not in definitions:
                errors.append(
                    f"worker role {role!r} named in docs/WORKFLOW.md or the "
                    f"orchestrator prompt has no agent .opencode/agents/{agent}.md")

    generator = load_generator(root)
    for name, ci in generator.OUTPUTS.items():
        path = root / name
        if not path.exists() or path.read_text() != generator.render(root, ci):
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
