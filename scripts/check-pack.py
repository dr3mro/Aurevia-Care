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
    "aurevia-clinic-assistant", "aurevia-laboratory-user", "aurevia-patient-user",
    "aurevia-pharmacy-user", "aurevia-physical-therapy-user", "aurevia-radiology-user",
    "aurevia-product-manager", "aurevia-product-owner", "aurevia-scrum-master",
    "aurevia-system-designer", "aurevia-memory-manager", "aurevia-context-optimizer",
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


def system_skill(text):
    """Return whether a skill is supplied by the runtime, not owned by the project.

    OpenCode may materialise its built-in skills into ``.opencode/skills`` (for
    example ``run-workflow``). They carry a ``system`` namespace and are rewritten
    by the runtime, so the project's process sections and skill inventory do not
    apply to them. Their description is still checked: agents select skills by it.
    """
    meta = frontmatter(text)
    if meta is None:
        return False
    return meta.get("namespace", "") == "system"


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
        loaded = set(re.findall(r"Load (?:the )?`?(aurevia-[a-z0-9-]+)`?", text))
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

    # Invocations name agents by their file ID. A skill id such as
    # ``aurevia-product-owner`` makes ``opencode run --agent`` fail instantly, and
    # an ask that uses an aurevia- name tells a worker to delegate to a skill.
    invocation = re.compile(r"(?:--agent|run_agent)\s+([a-z][a-z0-9-]*)")
    skill_delegation = re.compile(r"\bask\s+`?(aurevia-[a-z0-9-]+)`?", re.I)
    sources = []
    for folder, patterns in ((".github/workflows", ("*.yml",)),
                             ("scripts", ("*.sh", "*.py"))):
        for pattern in patterns:
            sources.extend(sorted((root / folder).glob(pattern)))
    for path in sources:
        text = path.read_text(encoding="utf-8")
        for match in invocation.finditer(text):
            name = match.group(1)
            if name not in definitions:
                line = text.count("\n", 0, match.start()) + 1
                errors.append(f"{path.relative_to(root)}:{line}: agent {name!r} has no "
                              f"file .opencode/agents/{name}.md")
        for match in skill_delegation.finditer(text):
            line = text.count("\n", 0, match.start()) + 1
            errors.append(f"{path.relative_to(root)}:{line}: ask {match.group(1)} "
                          "names a skill; use the agent file ID")

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
        if name in NO_PROCESS_SECTIONS or system_skill(text):
            continue
        for heading in PROCESS_SECTIONS:
            if not re.search(rf"(?mi)^##\s+{re.escape(heading)}\s*$", text):
                errors.append(f"skill {name}: missing the section \"## {heading}\"")
    # Agent files are short names; each specialist loads the skill of the same
    # name with the aurevia- prefix, and the primary loads the delivery skill.
    # Runtime-supplied skills are not part of that project inventory.
    expected_skills = {f"aurevia-{name}" for name in specialists} | {"aurevia-delivery"}
    project_skills = {name: text for name, text in skills.items()
                      if not system_skill(text)}
    if expected_skills != set(project_skills):
        errors.append("skills differ from specialists plus aurevia-delivery: "
                      f"{sorted(expected_skills ^ set(project_skills))}")

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
        # An unattended worker overlays the CI profile on a skip-worktree config,
        # so the committed blob is authoritative there; both profiles are still
        # generated from the same inventory and neither check is skipped.
        if generator.read_generated(root, name) != generator.render(root, ci):
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
