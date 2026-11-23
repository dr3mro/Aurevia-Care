"""Fail when agent-written API code is finished on paper but not wired or durable.

JaCoCo at 100% passes for a service nobody calls and for a store that forgets its data
on restart, so line coverage cannot prove a story is done. This gate checks the shape
of apps/api/src/main instead:

  unreferenced-service     a *Service class that no other main class mentions
  inmemory-in-main         an InMemory* class in production source (belongs in src/test)
  store-without-durable    a *Store interface with no Jdbc* implementation
  controller-builds        a controller that does `new ...Service/Store(...)` itself
  fb-suppression           a file that adds @SuppressFBWarnings (fix the code; the central
                           exclude file is human-reviewed)
  requirement-id-name      a Java class or SQL table named after a requirement ID
                           (Xch03Service, xch03_order, G3EvidenceService)

Existing findings are recorded in scripts/wiring-baseline.json and are tolerated; the
gate fails only on NEW ones, so the debt cannot grow while it is paid down. When you fix
one, delete its line from the baseline (a stale line only prints a note).

Usage: check-wiring.py [--write-baseline]   (write-baseline needs a person's review)
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASELINE = "scripts/wiring-baseline.json"
MAIN = "apps/api/src/main/java"
MIGRATIONS = "db/migrations"
RULES = ("unreferenced-service", "inmemory-in-main", "store-without-durable",
         "controller-builds", "fb-suppression", "requirement-id-name")

ID_CLASS = re.compile(r"^(Xch|Rx|Arc|Ops|Iam|Pt|Msg|Pat|Rad|Lab|Fin)\d{2}[A-Z]|^G\d+[A-Z]")
ID_TABLE = re.compile(r"^(xch|rx|arc|ops|iam|pt|msg|pat|rad|lab|fin|g)\d+_")
CREATE_TABLE = re.compile(r"create\s+table\s+(?:if\s+not\s+exists\s+)?(?:public\.)?(\w+)", re.I)
NEW_COLLABORATOR = re.compile(r"\bnew\s+\w*(?:Service|Store)\s*\(")


def java_files(root):
    base = Path(root) / MAIN
    return sorted(base.rglob("*.java")) if base.is_dir() else []


def rel(root, path):
    return str(Path(path).relative_to(root))


def findings(root):
    """Return {rule: sorted list of stable keys} for the repository at root."""
    root = Path(root)
    files = java_files(root)
    text = {f: f.read_text(errors="ignore") for f in files}
    found = {rule: set() for rule in RULES}
    for f, body in text.items():
        name = f.stem
        key = rel(root, f)
        if name.startswith("InMemory"):
            found["inmemory-in-main"].add(key)
        if name.endswith("Service") and re.search(r"\b(?:class|record)\s+%s\b" % name, body):
            others = (b for g, b in text.items() if g != f)
            if not any(re.search(r"\b%s\b" % name, b) for b in others):
                found["unreferenced-service"].add(key)
        if name.endswith("Store") and re.search(r"\binterface\s+%s\b" % name, body):
            durable = any(re.search(r"implements[^{]*\b%s\b" % name, b) and g.stem.startswith("Jdbc")
                          for g, b in text.items())
            if not durable:
                found["store-without-durable"].add(key)
        if name.endswith("Controller") and NEW_COLLABORATOR.search(body):
            found["controller-builds"].add(key)
        if re.search(r"@(?:[\w.]*\.)?SuppressFBWarnings\b", body):
            found["fb-suppression"].add(key)
        if ID_CLASS.match(name):
            found["requirement-id-name"].add(key)
    migrations = Path(root) / MIGRATIONS
    for sql in sorted(migrations.glob("V*.sql")) if migrations.is_dir() else []:
        for table in CREATE_TABLE.findall(sql.read_text(errors="ignore")):
            if ID_TABLE.match(table):
                found["requirement-id-name"].add(f"{rel(root, sql)}:{table}")
    return {rule: sorted(keys) for rule, keys in found.items()}


def load_baseline(root):
    path = Path(root) / BASELINE
    if not path.is_file():
        return {rule: [] for rule in RULES}
    data = json.loads(path.read_text())
    return {rule: list(data.get(rule, [])) for rule in RULES}


def compare(current, baseline):
    """Return (new findings, stale baseline lines) as lists of (rule, key)."""
    new, stale = [], []
    for rule in RULES:
        allowed = set(baseline[rule])
        new += [(rule, key) for key in current[rule] if key not in allowed]
        stale += [(rule, key) for key in baseline[rule] if key not in set(current[rule])]
    return new, stale


ADVICE = {
    "unreferenced-service": "wire it into a controller or bean, or the feature is not done",
    "inmemory-in-main": "production code must not hold data in memory; add a Jdbc store and a migration, keep fakes in src/test",
    "store-without-durable": "add a Jdbc implementation with a migration (RLS + FORCE RLS) and an integration test",
    "controller-builds": "inject the service with Spring instead of constructing it in the controller",
    "fb-suppression": "do not add @SuppressFBWarnings; fix the code (the exclude file is human-reviewed)",
    "requirement-id-name": "name it after the business concept, not the requirement ID",
}


def main(argv=None, root=ROOT):
    args = sys.argv[1:] if argv is None else argv
    current = findings(root)
    if "--write-baseline" in args:
        (Path(root) / BASELINE).write_text(json.dumps(current, indent=2, sort_keys=True) + "\n")
        print(f"wrote {BASELINE}: " + ", ".join(f"{r}={len(current[r])}" for r in RULES))
        return 0
    new, stale = compare(current, load_baseline(root))
    for rule, key in stale:
        print(f"note: {rule}: {key} is fixed; delete it from {BASELINE}", file=sys.stderr)
    for rule, key in new:
        print(f"FAIL {rule}: {key}\n     {ADVICE[rule]}", file=sys.stderr)
    if "--fail-on-stale" in args and stale:
        return 1
    return 1 if new else 0


if __name__ == "__main__":
    sys.exit(main())
