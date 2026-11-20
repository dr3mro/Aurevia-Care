"""Task planning, publish and merge logic for the agent swarm (TODO.md).

Every GitHub call goes through the gh CLI and every Git call through git, so
tests replace gh() and git() with fakes. Subcommands print step outputs to
GITHUB_OUTPUT. Runs in trusted steps only (the default-branch checkout), never
inside the agent step.
"""
import argparse
import base64
import datetime
import fnmatch
import json
import math
import os
import random
import re
import subprocess
import sys
import time
from pathlib import Path

MARK = "<!-- swarm-integrator -->"
MAX_FIX = 3
# One story per worker PR: a batch this large cannot be reviewed or verified
# as a single bounded change, and it was the source of the G1 quality failures.
MAX_PR_FILES = 15
# A task a swarm worker has taken this many times without finishing waits for a person.
MAX_ATTEMPTS = 3
BODY_LIMIT = 8000
# Default monthly runner-minute cap for all swarm-related workflows. The guard
# estimates usage from finished run durations and refuses a wave near the cap.
DEFAULT_MONTHLY_MINUTES = 10000
BUDGET_WORKFLOWS = ("swarm.yml", "swarm-integrator.yml",
                    "agent-image.yml", "swarm-images.yml", "fix-pr-gate.yml")
BRANCH = re.compile(r"swarm/[A-Za-z0-9._-]+")
LABELS = {
    "swarm": ("1d76db", "Pull request opened by a swarm worker"),
    "autofix": ("0e8a16", "Automatic repair of a failed main run"),
    "needs-fix": ("d93f0b", "Integration failed; a worker should fix it"),
    "needs-human": ("b60205", "Swarm stopped; a person must decide"),
    "fix-1": ("ededed", "Fix attempt 1"), "fix-2": ("ededed", "Fix attempt 2"),
    "fix-3": ("ededed", "Fix attempt 3"),
}
# Changes here could widen the agents' own permissions or the merge gate, so
# they are never merged automatically.
# Only the repository's own check entry point is protected: it defines the gate every
# worker must pass. Per-module check.sh files (apps/<module>/check.sh, db/check.sh) are
# the canonical test entry points that new modules are required to add, so they must
# remain auto-mergeable. fnmatch's '*' crosses '/', so use the literal root path here.
PROTECTED = [".github/*", ".opencode/*", "opencode.json", "scripts/*",
             "AGENTS.md", "check.sh", "*/check.sh", "compose.yaml", "Dockerfile"]
# Shared trackers are written only by the integrator, one merge at a time, so
# parallel workers never conflict on them.
TRACKERS = ["TODO.md", "MEMORY.md", "WORKSPACE.md", "BUGS.md", "PROJECT.md", "docs/swarm/*"]
# TODO.md marks: [ ] open, [-] taken by an agent, [x] done.
BOXES = {"todo": " ", "taken": "-", "done": "x"}
STATUS = {box: name for name, box in BOXES.items()}
ENTRY = re.compile(r"^- \[([ x-])\] (\S+) · (.*)$")
FIELD = re.compile(r"^  - ([a-z]+):[ \t]*(.*)$")


def gh(*args, check=True):
    """Run a gh CLI command, retrying transient GitHub API failures.

    A 502/503/504 during `gh pr create` is a GitHub-side blip: the branch is
    already pushed, so a retry is safe. Permanent errors (permissions, missing
    refs) are raised immediately with the CLI stderr visible in the job log.
    """
    transient = re.compile(r"\b(?:502|503|504)\b|Bad Gateway|Service Unavailable", re.I)
    for attempt in range(3):
        done = subprocess.run(["gh", *args], capture_output=True, text=True)
        if not (check and done.returncode):
            return done.stdout
        if transient.search(done.stderr or "") and attempt < 2:
            time.sleep(2 ** attempt)
            continue
        # Surface the CLI error; a bare CalledProcessError hides why a label,
        # comment or workflow dispatch was refused.
        if done.stderr:
            print(f"gh {' '.join(str(a) for a in args[:3])}: {done.stderr.strip()}",
                  file=sys.stderr)
        raise subprocess.CalledProcessError(done.returncode, args, done.stdout, done.stderr)
    return done.stdout


def git(*args, check=True):
    done = subprocess.run(["git", *args], capture_output=True, text=True)
    if check and done.returncode:
        # Surface the real git error (for example a ruleset rejection) in the
        # trusted job log; CalledProcessError alone hides stderr from CI.
        print(f"git {args[0] if args else ''} failed: {done.stderr.strip()}", file=sys.stderr)
        raise subprocess.CalledProcessError(done.returncode, args, done.stdout, done.stderr)
    return done.stdout


def authed_git(*args, check=True):
    token = os.environ["GH_TOKEN"]
    header = base64.b64encode(f"x-access-token:{token}".encode()).decode()
    return git("-c", f"http.https://github.com/.extraheader=AUTHORIZATION: basic {header}",
               *args, check=check)


def gh_json(*args):
    return json.loads(gh(*args) or "null")


def report_issue(title, body):
    """Tell a person about a problem without opening a new issue every time.

    The swarm hits the same problem on every wave (main still red, the budget cap
    still reached), and one issue per wave buried the issue list. When an open
    issue with this exact title exists, add a comment stamped with the date and
    time instead; otherwise open one."""
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
    text = f"{MARK}\n**{stamp}**\n\n{body}"
    found = gh_json("issue", "list", "--state", "open", "--search", f"{title} in:title",
                    "--limit", "30", "--json", "number,title") or []
    same = [i for i in found if i.get("title") == title]
    if same:
        number = str(min(i["number"] for i in same))
        gh("issue", "comment", number, "--body", text, check=False)
        return number
    gh("issue", "create", "--title", title, "--body", text, check=False)
    return ""


def repo_slug():
    """Full owner/name used by the REST label endpoints."""
    return os.environ.get("GITHUB_REPOSITORY", "dr3mro/Aurevia-Care")


def add_labels(pr, *names):
    """Add labels through REST; `gh pr edit` needs read:org via GraphQL."""
    fields = [f for name in names for f in ("-f", f"labels[]={name}")]
    gh("api", "--method", "POST", f"repos/{repo_slug()}/issues/{pr}/labels", *fields)


def remove_label(pr, name):
    """Remove one label through REST."""
    gh("api", "--method", "DELETE",
       f"repos/{repo_slug()}/issues/{pr}/labels/{name}", check=False)


def emit(name, value):
    with open(os.environ.get("GITHUB_OUTPUT", os.devnull), "a") as out:
        out.write(f"{name}={value}\n")


def label_names(item):
    return {label["name"] for label in item.get("labels", [])}


def is_repair(pr):
    """True for watchdog repair PRs (autofix label or FIX- title)."""
    info = gh_json("pr", "view", str(pr), "--json", "labels,title")
    return "autofix" in label_names(info) or info.get("title", "").startswith("FIX-")


def run_url():
    repo, run = os.environ.get("GITHUB_REPOSITORY", ""), os.environ.get("GITHUB_RUN_ID", "")
    return f"https://github.com/{repo}/actions/runs/{run}"


def ensure_labels():
    for name, (color, description) in LABELS.items():
        gh("label", "create", name, "--color", color, "--description", description, "--force")


def load_slots(path):
    return json.loads(Path(path).read_text())["slots"]


def slot_config(slots, number):
    """The opencode.json baked into one slot's image. Never holds credentials."""
    slot = next(s for s in slots if s["slot"] == number)
    return {"$schema": "https://opencode.ai/config.json", "model": slot["model"],
            "autoupdate": False, "share": "disabled", **slot.get("config", {})}


# Build states that mean "not finished yet". While any of these is present a
# wave must wait: the images may be mid-replacement on GHCR.
BUILD_PENDING = {"queued", "in_progress", "waiting", "pending", "requested"}


def images_ready(runs, manifests_exist):
    """Decide whether prepared slot images are ready for a swarm wave.

    `runs` is the newest `agent-image.yml` and `swarm-images.yml` run records,
    ordered newest first; each record has `status` and `conclusion`. An empty
    list means Actions kept no history (cleaned, expired or deleted runs).
    `manifests_exist` is True when every slot's `:latest` image is in GHCR.

    Returns `(ready, reason, dispatch)`:

    - a queued/in-progress build blocks the wave (the images may be changing)
      and needs no dispatch;
    - a newest completed `success` allows the wave; a `failure` blocks it with
      no dispatch (a genuinely broken build needs a person, not an hourly retry);
    - otherwise — no history at all, or an inconclusive conclusion such as
      `cancelled`/`skipped` — the registry decides: present images allow the wave,
      missing images block it and request one `agent-image.yml` build.

    History length never decides by itself, so clearing the run list (or a
    cancelled run) cannot pause the swarm while usable images still exist.
    """
    if any((run or {}).get("status") in BUILD_PENDING for run in runs):
        return False, "a prepared-image build is queued or in progress", False
    completed = [run for run in runs if run and run.get("status") == "completed"]
    if completed:
        conclusion = completed[0].get("conclusion")
        if conclusion == "success":
            return True, "the newest prepared-image build succeeded", False
        if conclusion == "failure":
            return False, "the newest prepared-image build did not succeed", False
    # No run history, or a completed run with an inconclusive conclusion: fall
    # back to the registry rather than blocking a wave that has usable images.
    if manifests_exist:
        return True, "slot images are present in the registry", False
    return False, "slot images are missing from the registry", True


def image_runs():
    """Newest build runs from both workflows, flattened newest first.

    Each workflow contributes at most one record. An empty list means Actions
    has no history for either workflow — the case the old shell check treated
    as "not ready" forever.
    """
    runs = []
    for workflow in ("agent-image.yml", "swarm-images.yml"):
        found = gh_json("run", "list", "--workflow", workflow, "--limit", "1",
                        "--json", "status,conclusion,createdAt") or []
        # Tolerate a malformed record rather than crash the plan step (which
        # would fail the job and queue a false repair task).
        runs.extend(r for r in found if isinstance(r, dict))
    return sorted(runs, key=lambda r: r.get("createdAt") or "", reverse=True)


def slot_manifests_exist(slots, repo=None):
    """True when every slot's `:latest` image exists in GHCR.

    Uses the packages API with the plan job's `packages: read` permission rather
    than `docker manifest inspect`, so the plan job needs no Docker login. A 404
    (package never published) returns an empty body and is treated as a missing
    image. Credentials are never written to the repository.
    """
    repo = repo or repo_slug()
    owner, sep, name = repo.partition("/")
    if not (owner and sep and name):
        return False
    for slot in slots:
        package = f"{name}-agent-{slot['slot']}".lower()
        try:
            # check=True lets gh() retry transient 502/503/504 responses; a
            # genuine 404 (package never published) is caught below as missing.
            out = gh("api", f"repos/{owner}/{name}/packages/container/{package}/versions",
                     "--jq", '[.[].metadata.container.tags[]?] | index("latest") != null').strip()
        except subprocess.CalledProcessError:
            return False
        if out != "true":
            return False
    return True


def images_ready_command(slots=None):
    """Gather inputs, decide readiness, dispatch one build if needed, emit outputs.

    Trusted call only (the plan job). The actual decision is the pure
    `images_ready()` function so it can be tested without GitHub or the network.
    """
    runs = image_runs()
    manifests = slot_manifests_exist(load_slots(slots or ".github/swarm/slots.json"))
    ready, reason, dispatch = images_ready(runs, manifests)
    if dispatch:
        gh("workflow", "run", "agent-image.yml", "--ref", "main", check=False)
    print(reason)
    emit("ready", "true" if ready else "false")
    emit("reason", reason)
    emit("dispatch", "true" if dispatch else "false")
    return ready, reason, dispatch


def clip(text):
    return (text or "")[:BODY_LIMIT]


def table_cells(line):
    return [c.strip() for c in line.strip().strip("|").split("|")]


def split_list(value):
    return [v for v in re.split(r"\s*,\s*|\s+", value) if v and v != "-"]


def parse_tasks(text):
    """Read the task entries of TODO.md.

    An entry is `- [ ] ID · title` (`[-]` taken, `[x]` done) followed by indented
    `  - key: value` fields and an optional indented `details:` body."""
    tasks, current, body, in_details = [], None, [], False

    def close():
        if current is not None:
            current["details"] = "\n".join(body).strip()
            current["depends"] = split_list(current["depends"])
            current["aliases"] = split_list(current["aliases"])
            current["scope"] = [d for d in re.split(r"\s*,\s*", current["scope"]) if d and d != "-"]
            tasks.append(current)

    for line in text.splitlines():
        entry = ENTRY.match(line)
        if entry:
            close()
            current = {"id": entry.group(2), "task": entry.group(3), "status": STATUS[entry.group(1)],
                       "requirements": "", "depends": "", "scope": "", "reserved": "", "aliases": "",
                       "pr": "", "agent": "", "evidence": "", "attempts": ""}
            body, in_details = [], False
        elif current is not None and line and not line.startswith(" "):
            close()
            current = None
        elif current is not None:
            field = FIELD.match(line)
            if field and not in_details:
                if field.group(1) == "details":
                    in_details = True
                elif field.group(1) in current:
                    current[field.group(1)] = field.group(2).strip()
            elif in_details:
                body.append(line[4:] if line.startswith("    ") else line.strip())
    close()
    return tasks


MAX_AUDIT_NEW = 5       # findings turned into tasks per daily audit
MAX_AUDIT_OPEN = 10     # no new audit tasks while this many are still open
AUDIT_HEADER = "## Audit findings"


def _norm(text):
    return re.sub(r"[^a-z0-9]+", " ", text.lower()).strip()


def audit_entries(findings, text, today):
    """Validate audit findings and format the new ones as TODO.md entries.

    The audit worker has no token and its JSON is untrusted, so every field is
    checked: bounded lengths, relative scope paths that are not protected files,
    and no duplicate of an existing task title."""
    tasks = parse_tasks(text)
    open_audit = [t for t in tasks if t["id"].startswith("AUD-") and t["status"] != "done"]
    room = min(MAX_AUDIT_NEW, MAX_AUDIT_OPEN - len(open_audit))
    seen = {_norm(t["task"]) for t in tasks}
    existing = {t["id"] for t in tasks}
    entries, number = [], 0
    for item in findings if isinstance(findings, list) else []:
        if len(entries) >= room:
            break
        if not isinstance(item, dict):
            continue
        title, details, scope = item.get("title"), item.get("details"), item.get("scope")
        if not (isinstance(title, str) and 10 <= len(title.strip()) <= 160 and "\n" not in title):
            continue
        if not (isinstance(details, str) and details.strip()) \
                or not (isinstance(scope, list) and 1 <= len(scope) <= 4):
            continue
        if not all(isinstance(d, str) and d and not d.startswith("/") and ".." not in d
                   and "," not in d and not protected_files([d, d.rstrip("/") + "/x"])
                   for d in scope):
            continue
        if _norm(title) in seen:
            continue
        seen.add(_norm(title))
        number += 1
        while f"AUD-{today}-{number:02d}" in existing:
            number += 1
        body = "\n".join(f"    {line}" for line in details.strip()[:2000].splitlines() if line.strip())
        entries.append(
            f"- [ ] AUD-{today}-{number:02d} · {title.strip()}\n"
            f"  - attempts: 0\n  - scope: {', '.join(scope)}\n  - details:\n{body}\n"
            "    Found by the daily audit. Synthetic fixtures only; failing tests first;\n"
            "    stay inside the declared scope; record results in .agent/pr-notes/<story-id>.md.")
    return entries


def add_audit_tasks(findings_path, path="TODO.md", today=None):
    """Append validated audit findings to TODO.md on main for the swarm to take."""
    today = today or datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%d")
    try:
        findings = json.loads(Path(findings_path).read_text())
    except (OSError, ValueError):
        print("No usable audit findings.", file=sys.stderr)
        return 0
    added = []

    def edit(text):
        entries = audit_entries(findings, text, today)
        added[:] = entries
        if not entries:
            return text
        head = "" if AUDIT_HEADER in text else f"\n{AUDIT_HEADER}\n\n"
        return text.rstrip("\n") + "\n" + head + "\n".join(entries) + "\n"
    update_todo(edit, "Daily audit: add findings", path)
    print(f"Added {len(added)} audit task(s).")
    return len(added)


def attempts_of(task):
    value = task.get("attempts", "")
    return int(value) if str(value).isdigit() else 0


def set_task_status(text, task_id, status, pr="", agent="", evidence=""):
    """Change one entry's mark and its agent, pr and evidence fields. Other entries are untouched."""
    lines = text.splitlines()
    start = next((i for i, l in enumerate(lines)
                  if (m := ENTRY.match(l)) and m.group(2) == task_id), None)
    if start is None:
        return text
    lines[start] = f"- [{BOXES[status]}] {task_id} · {ENTRY.match(lines[start]).group(3)}"
    end = start + 1
    while end < len(lines) and (not lines[end] or lines[end].startswith(" ")):
        end += 1
    while end > start + 1 and not lines[end - 1]:
        end -= 1
    drop = {"agent", "pr"} | ({"evidence"} if evidence else set()) | ({"attempts"} if status == "taken" else set())
    kept, in_details, tries = [], False, 0
    for line in lines[start + 1:end]:
        field = FIELD.match(line)
        if field and field.group(1) == "details":
            in_details = True
        if field and not in_details and field.group(1) == "attempts":
            tries = attempts_of({"attempts": field.group(2)})
        if field and not in_details and field.group(1) in drop:
            continue
        kept.append(line)
    fresh = [f"  - {k}: {v}" for k, v in (("agent", agent if status == "taken" else ""),
                                          ("pr", pr if status == "done" else ""),
                                          ("evidence", evidence),
                                          ("attempts", tries + 1 if status == "taken" else 0)) if v]
    lines[start + 1:end] = fresh + kept
    return "\n".join(lines) + "\n"


WORKER_JOB = re.compile(r"^Swarm worker(?: \((?:story|fix), (?P<id>[A-Za-z0-9-]+),)?")


def is_worker_job(name):
    """True for a job of a swarm worker. A matrix job's name carries the task:
    `Swarm worker (story, DLV-02, 0, Title... / Work the task`."""
    return name.startswith("Swarm worker")


def worker_task_id(name):
    """Task id in a worker job name, or '' when the name has none."""
    match = WORKER_JOB.match(name)
    return (match.group("id") or "") if match else ""


def reclaim(text, prs, live=()):
    """Release swarm claims that no pull request backs.

    A claim with only a merged pull request was merged by a person, so it is done;
    one with no pull request, or only closed ones, goes back to open. A claim whose
    worker is still running (`live`: task ids) has no pull request yet and must
    stay: waves overlap, and releasing it started a second worker on the same task."""
    live = set(live)
    for task in parse_tasks(text):
        # `opencode-` claims come from the older dispatch system; with no pull request
        # behind them they would hold their scope (and block repairs) forever.
        if task["status"] != "taken" or not task["agent"].startswith(("swarm", "opencode")):
            continue
        if live & {task["id"], *task["aliases"]}:
            continue
        mine = [p for p in prs if task_id_of(p["headRefName"]) in {task["id"], *task["aliases"]}]
        # An open PR still in progress keeps the claim. A needs-human PR is a
        # failure the swarm gave up on: return the task to the open pool.
        if any(p["state"] == "OPEN" and "needs-human" not in label_names(p) for p in mine):
            continue
        merged = next((p for p in mine if p["state"] == "MERGED"), None)
        text = (set_task_status(text, task["id"], "done", pr=f"#{merged['number']}") if merged
                else set_task_status(text, task["id"], "todo"))
    return text


def release_claim(task_id, reason):
    """Return one swarm claim to the open pool. Trusted caller only."""
    return update_todo(
        lambda text: set_task_status(text, task_id, "todo"),
        f"Release {task_id}: {reason}")


def update_todo(edit, message, path="TODO.md", attempts=8):
    """Apply `edit(text)` to TODO.md on main and push. Reapplied on fresh main if the push races."""
    for attempt in range(attempts):
        text = Path(path).read_text()
        new = edit(text)
        if new == text:
            return False
        Path(path).write_text(new)
        git("add", path)
        # [skip ci] keeps this TODO.md-only commit from triggering another
        # swarm.yml push event (which would spawn a wave for every claim).
        git(*IDENTITY, "commit", "-qm", f"{message} [skip ci]")
        try:
            authed_git("push", "origin", "HEAD:main")
            return True
        except subprocess.CalledProcessError:
            if attempt == attempts - 1:
                raise
            # Ten workers can finish together and all push TODO.md: spread the retries.
            time.sleep(random.uniform(1, 4))
            authed_git("fetch", "origin", "main")
            git("reset", "-q", "--hard", "origin/main")
    return False


def claim_open(text, claims):
    """Mark the still-open tasks in `claims` ({id: agent}) taken.

    Returns `(new_text, granted_ids)`. A task that is already taken or done in
    `text` is left alone and not granted: two plan jobs that read TODO.md a few
    seconds apart (two pushes) must not both start a worker for it.
    """
    status = {t["id"]: t["status"] for t in parse_tasks(text)}
    granted = set()
    for task_id, agent in claims.items():
        if status.get(task_id) == "todo":
            text = set_task_status(text, task_id, "taken", agent=agent)
            granted.add(task_id)
    return text, granted


def prefix(pattern):
    return re.split(r"[*?\[]", pattern)[0].rstrip("/")


def overlaps(a, b):
    """True when two scope lists can touch the same files."""
    for x in a:
        for y in b:
            px, py = prefix(x), prefix(y)
            if px.startswith(py) or py.startswith(px):
                return True
    return False


def task_id_of(branch):
    """Return the task ID from a `swarm/<slug>-<run>` branch.

    IDs may contain several hyphens (S-G1-02, X-01, FIX-444, CW-001), so only
    the trailing numeric run suffix is removed, not the last hyphen segment.
    """
    match = re.fullmatch(r"swarm/([A-Za-z0-9-]+)-\d+", branch)
    return match.group(1) if match else ""


def task_text(task):
    fields = [("Task", task["task"]),
              ("Time budget", "Finish this task within 175 minutes; do not start another task. "
                              "A new worker will take the next one."),
              ("Requirements", task.get("requirements", "")),
              ("Scope (stay inside these paths)", ", ".join(task["scope"])),
              ("Reserved migration version", task.get("reserved", "")),
              ("Read first", "The section 'Instructions for every agent' at the top of TODO.md, then "
                             "AGENTS.md and CODING_STYLE.md."),
              ("Naming rule", "Name every new folder and Java package after its business domain, "
                              "never after a task, story or gate ID; the build fails otherwise.")]
    head = "\n".join(f"{k}: {v}" for k, v in fields if v and v != "-")
    return clip(f"{head}\n\n{task['details']}")


def plan_workers(max_workers, slots, text, open_prs, repair=None):
    """Main repairs, then fixes, then ready tasks whose scope is free."""
    tasks = parse_tasks(text)
    by_id = {t["id"]: t for t in tasks}
    for t in tasks:
        for alias in t["aliases"]:
            by_id.setdefault(alias, t)
    # Scopes of tasks other agents already took stay off limits.
    plan, busy = ([repair] if repair else []), [t["scope"] for t in tasks if t["status"] == "taken"]
    for pr in sorted(open_prs, key=lambda p: p["number"]):
        tid = task_id_of(pr["headRefName"])
        task = by_id.get(tid)
        if task is None and tid.startswith("FIX-"):
            task = {"id": tid, "task": pr.get("title", tid), "scope": [], "details": "",
                    "requirements": "", "reserved": ""}
        if task:
            busy.append(task["scope"])
        if "needs-fix" in label_names(pr) and "needs-human" not in label_names(pr) \
                and not pr["isCrossRepository"] and task:
            notes = [c["body"] for c in pr["comments"] if MARK in c["body"]]
            plan.append({"kind": "fix", "id": task["id"], "number": pr["number"],
                         "branch": pr["headRefName"], "title": task["task"],
                         "body": task_text(task),
                         "fix": clip(notes[-1] if notes else "Integration failed.")})
    in_flight = {task_id_of(p["headRefName"]) for p in open_prs}
    done = {name for t in tasks if t["status"] == "done" for name in (t["id"], *t["aliases"])}
    # Repairs of main come first: a red main blocks every other merge.
    for task in sorted(tasks, key=lambda t: not is_repair_task(t)):
        if task["status"] != "todo" or not task["scope"] or task["id"] in in_flight \
                or attempts_of(task) >= MAX_ATTEMPTS:
            continue
        if not set(task["depends"]) <= done:
            continue
        if any(overlaps(task["scope"], other) for other in busy):
            continue
        busy.append(task["scope"])
        plan.append({"kind": "story", "id": task["id"], "number": 0, "branch": "",
                     "title": task["task"], "body": task_text(task), "fix": ""})
    chosen = plan[:max(0, min(max_workers, len(slots)))]
    return [{**t, "slot": s["slot"], "model": s["model"]} for t, s in zip(chosen, slots)]


def release(task, result, commits, pr=""):
    """Summarize a finished worker. State lives in PRs, so nothing is relabeled."""
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    line = (f"| {task['slot']} | {task['id']} | {task['kind']} | {result} | "
            f"{commits} commit(s) | {('#' + pr) if pr else '-'} |\n")
    if summary:
        with open(summary, "a") as out:
            out.write(line)
    if commits == 0 and task["kind"] == "fix" and result in ("rate_limited", "timeout", "failed"):
        add_labels(task["number"], "needs-fix" if result != "failed" else "needs-human")
        gh("pr", "comment", str(task["number"]), "--body",
           f"Worker {task['slot']} ended with '{result}' and pushed nothing. {run_url()}")


def read(path, default=""):
    try:
        return Path(path).read_text().strip()
    except OSError:
        return default


def publish(task, directory):
    """Push the worker's bundle and open the pull request or update the fix."""
    directory = Path(directory)
    result = read(directory / "result", "failed")
    commits = int(read(directory / "commits", "0") or 0)
    bundle = directory / "result.bundle"
    pr = ""
    if task["kind"] == "fix":
        # A fix worker can outlive its pull request: the integrator may merge or a
        # person may close it while the worker runs, and the branch is deleted.
        # There is nothing to update then; failing the publish only raised noise.
        state = gh("pr", "view", str(task["number"]), "--json", "state", "--jq", ".state",
                   check=False).strip()
        if state in ("MERGED", "CLOSED"):
            print(f"publish: PR #{task['number']} is already {state.lower()}; "
                  f"nothing to push for {task['id']}")
            emit("blocked", "pr-closed")
            return
    if commits > 0 and bundle.exists():
        branch = task["branch"] if task["kind"] == "fix" else read(directory / "branch")
        if not BRANCH.fullmatch(branch) or task_id_of(branch) != task["id"]:
            raise SystemExit(f"refusing unexpected branch name {branch!r}")
        if task["kind"] == "fix":
            authed_git("fetch", "origin", branch)
        # The publish job reuses one checkout for every slot. Recreate the
        # local ref so a second bundle cannot fail as a non-fast-forward.
        git("update-ref", "-d", "refs/heads/swarm-incoming", check=False)
        git("fetch", str(bundle), "HEAD:refs/heads/swarm-incoming")
        changed = git("diff", "--name-only", "HEAD", "refs/heads/swarm-incoming").split()
        # GITHUB_TOKEN is a GitHub App installation token and cannot create or
        # update .github/workflows/*; there is no `workflows` permissions scope.
        # Fail closed instead of crashing the publish loop for every later slot.
        if (any(f.startswith(".github/workflows/") for f in changed)
                and os.environ.get("SWARM_WORKFLOW_PUSH") != "true"):
            if task["kind"] == "story":
                release_claim(task["id"], "worker changed workflow files; use SWARM_TOKEN with workflow scope")
            print(f"publish: {task['id']} changed .github/workflows/*; not pushing "
                  "(GitHub App token cannot update workflow files)", file=sys.stderr)
            emit("blocked", "workflow-files")
            return
        authed_git("push", "origin", f"swarm-incoming:refs/heads/{branch}")
        checks = read(directory / "checks", "skipped")
        merged = read(directory / "merge", "skipped")
        # Work is always published, even when the worker did not finish: a
        # draft pull request keeps it reviewable and lets a fix worker continue.
        incomplete = result != "ok" or checks != "passed" or merged == "conflict"
        if task["kind"] == "story":
            notes = read(directory / "pr-notes.md", "No notes were written.")
            status = ""
            if incomplete:
                why = [f"agent result: {result}", f"local checks: {checks}"]
                if merged == "conflict":
                    why.append("merge with main left unresolved conflicts")
                status = ("**Draft: the worker did not finish cleanly** (" + "; ".join(why) +
                          "). Its work is saved here so nothing is lost; a fix worker or a "
                          "person continues from this branch.\n\n")
            body = (f"{status}Task: {task['id']}\n\nSwarm worker {task['slot']}. Local checks: {checks}. "
                    f"Agent result: {result}.\n\n{clip(notes)}")
            repair = task["id"].startswith("FIX-")
            if repair:
                body = ("**Automatic repair of a failed run on main.** The integrator merges it "
                        "once the fast gate passes, including changes to workflow, script or agent "
                        "files.\n\n" + body)
            try:
                gh("pr", "create", "--base", os.environ.get("BASE_BRANCH", "main"), "--head", branch,
                   "--title", f"{task['id']}: {task['title']}", "--body", body, "--label", "swarm",
                   *(["--label", "autofix"] if repair else []),
                   *(["--draft"] if incomplete and not repair else []))
                pr = gh("pr", "view", branch, "--json", "number", "--jq", ".number").strip()
            except subprocess.CalledProcessError:
                # The branch is pushed, so the work is safe; make sure a person can find it.
                report_issue(f"{task['id']}: worker branch pushed without a pull request",
                             f"Branch `{branch}` holds the worker's commits (result: {result}, "
                             f"checks: {checks}) but the pull request could not be opened. "
                             f"Open one from the branch. {run_url()}")
                pr = ""
        else:
            pr = str(task["number"])
            gh("pr", "comment", pr, "--body",
               f"Worker {task['slot']} pushed a fix (local checks: {checks}). The integrator will retest.")
    release(task, result, 1 if pr else 0, pr)
    if task["kind"] == "story" and (result != "ok" or not pr):
        # A failed worker, or one that published nothing, must not keep a claim.
        release_claim(task["id"], f"result={result}, pr={pr or 'none'}")
    emit("pr", pr)


def protected_files(files):
    return [f for f in files if any(fnmatch.fnmatch(f, pattern) for pattern in PROTECTED)]


def fail(pr, reason, log_text=""):
    """Send a pull request back to the workers, or stop after MAX_FIX attempts."""
    info = gh_json("pr", "view", str(pr), "--json", "labels")
    attempts = len([n for n in label_names(info) if re.fullmatch(r"fix-\d", n)])
    tail = "\n".join(log_text.splitlines()[-80:])[-6000:].replace("```", "'''")
    detail = f"\n\n```text\n{tail}\n```" if tail else ""
    if attempts >= MAX_FIX:
        add_labels(pr, "needs-human")
        remove_label(pr, "needs-fix")
        gh("pr", "comment", str(pr), "--body",
           f"{MARK}\nIntegration still fails after {MAX_FIX} fix attempts: {reason}. "
           f"A person must take over.{detail}")
        return False
    attempt = attempts + 1
    add_labels(pr, "needs-fix", f"fix-{attempt}")
    gh("pr", "comment", str(pr), "--body",
       f"{MARK}\n{reason}. Fix attempt {attempt} of {MAX_FIX}. Reproduce with "
       f"`scripts/check.sh` on the merge of main and this branch.{detail}")
    gh("workflow", "run", "swarm.yml", "-f", "workers=1", check=False)
    return True


def main_is_red(workflow="delivery.yml"):
    """True when the newest finished run of the delivery workflow on main failed."""
    runs = gh_json("run", "list", "--workflow", workflow, "--branch", "main", "--status", "completed",
                   "--limit", "1", "--json", "conclusion") or []
    return bool(runs) and runs[0]["conclusion"] == "failure"


def plan_integration(red=False):
    """Pull requests ready to integrate, oldest first. While main is red only
    repair pull requests (FIX-, DLV-) may merge, so ordinary work is not blamed for it."""
    prs = gh_json("pr", "list", "--state", "open", "--label", "swarm", "--limit", "50",
                  "--json", "number,title,labels,isCrossRepository")
    blocked = {"needs-fix", "needs-human"}
    return [p["number"] for p in sorted(prs, key=lambda p: p["number"])
            if not p["isCrossRepository"] and not blocked & label_names(p)
            and (not red or p.get("title", "").startswith(REPAIR_PREFIXES))]


def only_worker_failures(run):
    """True when an Agent swarm run failed only in its worker jobs.

    A worker that times out or is rate limited is routine and its task is retried
    by the planner; main is not broken, so it must not start a repair that blocks
    the whole swarm. Failures in planning, integration or the loop still count."""
    if run.get("name") != "Agent swarm":
        return False
    failed = [j["name"] for j in run.get("jobs", []) if j.get("conclusion") == "failure"]
    return bool(failed) and all(is_worker_job(name) for name in failed)


def repair_task(run_id):
    """Build the worker task that repairs a failed run on main."""
    run = gh_json("run", "view", str(run_id), "--json", "name,headBranch,headSha,conclusion,url,jobs")
    if run["headBranch"] != "main" or run["conclusion"] != "failure":
        return None
    if only_worker_failures(run):
        return None
    log = gh("run", "view", str(run_id), "--log-failed", check=False)
    tail = "\n".join(log.splitlines()[-150:])[-5000:].replace("```", "'''")
    body = (f"Repair the failed '{run['name']}' run on main ({run['url']}, commit {run['headSha'][:12]}).\n"
            "Reproduce it with the failing commands. When it is a real defect, write a failing test "
            "first, then make the smallest fix. When it is a transient or runner problem, say so in "
            ".agent/pr-notes.md and change nothing.\n\nFailing log tail:\n```text\n" + tail + "\n```")
    return {"kind": "story", "id": f"FIX-{run_id}", "number": 0, "branch": "",
            "title": f"Repair main: {run['name']} failed", "body": clip(body), "fix": ""}


REPAIR_HEADER = "## Repairs (automatic)"
# Tasks whose id starts with one of these repair main: planned first, and while
# one is open no second automatic repair is added (it would repeat the work).
REPAIR_PREFIXES = ("FIX-", "DLV-")


def is_repair_task(task):
    return task["id"].startswith(REPAIR_PREFIXES)


def repair_entry(task):
    """TODO.md entry for a repair. Scope `*` overlaps every other task, so while a
    repair is taken the swarm works on nothing else."""
    body = "\n".join(f"    {line}" for line in task["body"].splitlines() if line.strip())
    return (f"- [ ] {task['id']} · {task['title']}\n"
            f"  - attempts: 0\n  - scope: *\n  - details:\n{body}\n"
            "    Repair PRs may touch workflow and script files. Reproduce first, fix the cause,\n"
            "    never weaken, skip or delete a test; record results in .agent/pr-notes/<story-id>.md.")


def add_repair_task(run_id, path="TODO.md"):
    """Turn a failed run on main into a repair task in TODO.md. Returns True when added.

    Bounded like the old watchdog: nothing while a repair pull request or task is
    still open, and an issue for a person after three repairs in a day."""
    start, why = repair_gate(run_id)
    print(why)
    if not start:
        return False
    task = repair_task(run_id)
    if task is None:
        print("The run is not a failed run on main; nothing to repair.")
        return False
    added = []

    def edit(text):
        if any(is_repair_task(t) and t["status"] != "done" for t in parse_tasks(text)):
            print("A repair task is already open.")
            return text
        head = "" if REPAIR_HEADER in text else f"\n{REPAIR_HEADER}\n\n"
        added.append(task["id"])
        return text.rstrip("\n") + "\n" + head + repair_entry(task) + "\n"
    update_todo(edit, f"Repair task for run {run_id}", path)
    return bool(added)


REPAIR_WORKFLOWS = ("Delivery", "Agent swarm", "Agent image", "Swarm slot images")


def failing_runs_on_main():
    """Ids of the pipeline workflows' newest finished run on main, failures only.

    Only the newest finished run of each workflow counts: a later green run means
    that workflow is healthy again. Cancelled and skipped runs say nothing either
    way and are passed over. The result is ordered newest first."""
    runs = gh_json("run", "list", "--branch", "main", "--limit", "60",
                   "--json", "databaseId,name,status,conclusion,createdAt") or []
    seen, failing = set(), []
    for run in sorted(runs, key=lambda r: r["createdAt"], reverse=True):
        if run["name"] not in REPAIR_WORKFLOWS or run["name"] in seen:
            continue
        if run["status"] != "completed" or run["conclusion"] not in ("success", "failure"):
            continue
        seen.add(run["name"])
        if run["conclusion"] == "failure":
            failing.append(run["databaseId"])
    return failing


def add_latest_repair_task(path="TODO.md"):
    """Queue a repair for the newest failing pipeline run on main. Returns True when added.

    The scheduler calls this at the start of every plan, so a red main becomes a
    repair task without a separate workflow run: no watchdog fires per failure and
    nothing piles up in the Actions list. A run that already has its FIX- entry
    (open or done) is not queued again, and a swarm run that failed only in
    workers is routine and skipped, exactly as in add_repair_task."""
    text = Path(path).read_text()
    for run_id in failing_runs_on_main():
        if f"FIX-{run_id}" in text:
            continue
        run = gh_json("run", "view", str(run_id), "--json", "name,jobs")
        if only_worker_failures(run):
            continue
        return add_repair_task(run_id, path)
    print("No failing pipeline run on main needs a repair task.")
    return False


MAX_ACTIVE_WORKERS = 20  # workers running at once across every wave


WAITING_WAVE_MAX_AGE_MIN = 30


def waiting_waves():
    """Agent swarm runs that exist but have not started: queued, pending or waiting.

    Every wave plans against current TODO.md state, so one waiting wave already
    covers any work the others would take. Starting more only fills the queue."""
    now = datetime.datetime.now(datetime.timezone.utc)
    total = 0
    for status in ("queued", "pending", "waiting", "requested"):
        out = gh("run", "list", "--workflow", "swarm.yml", "--status", status, "--limit", "50",
                 "--json", "createdAt", "-q", ".[].createdAt", check=False)
        for stamp in out.split():
            created = datetime.datetime.fromisoformat(stamp.replace("Z", "+00:00"))
            # Runs stuck for hours are zombies from an older concurrency group; counting
            # them would stop the loop from ever starting another wave.
            if now - created < datetime.timedelta(minutes=WAITING_WAVE_MAX_AGE_MIN):
                total += 1
    return total


def live_workers():
    """One entry per worker running or queued in any unfinished Agent swarm run.

    The entry is the worker's task id (a worker's Work and Publish jobs count once;
    a name without a task id counts as its own entry). Waves may overlap, so this
    both bounds the combined worker cap and tells reclaim which claims are alive.
    Returns [] when the API call fails (the per-wave cap and the budget gate still
    apply)."""
    runs = gh("run", "list", "--workflow", "swarm.yml", "--limit", "30",
              "--json", "databaseId,status", "-q",
              '.[] | select(.status != "completed") | .databaseId', check=False)
    live = []
    for run_id in runs.split():
        out = gh("api", f"repos/{repo_slug()}/actions/runs/{run_id}/jobs?per_page=100", "--paginate",
                 "--jq", '.jobs[] | select(.status != "completed") | .name', check=False)
        keys = {worker_task_id(n) or n for n in out.splitlines() if is_worker_job(n)}
        live.extend(sorted(keys))
    return live


def active_workers():
    """Number of workers running or queued across all waves right now."""
    return len(live_workers())


def rotate_slots(slots, busy):
    """Start after the slots already in use so overlapping waves spread over accounts."""
    if not slots:
        return slots
    start = busy % len(slots)
    return slots[start:] + slots[:start]


WORKER_MINUTES = 190  # one worker: 175 min of agent work, merge, bundle and publish


def run_job_minutes(run_id):
    """Billed minutes of one run: every job rounds up to whole minutes.

    Parallel workers bill side by side, so wall time alone would undercount a
    wave of ten by a factor of ten. Returns None when the API call fails."""
    out = gh("api", f"repos/{repo_slug()}/actions/runs/{run_id}/jobs?per_page=100", "--paginate",
             "--jq", '.jobs[] | select(.started_at and .completed_at) | [.started_at, .completed_at] | @tsv',
             check=False)
    if not out.strip():
        return None
    total = 0.0
    for line in out.splitlines():
        a, b = line.split("\t")
        seconds = (datetime.datetime.fromisoformat(b.replace("Z", "+00:00"))
                   - datetime.datetime.fromisoformat(a.replace("Z", "+00:00"))).total_seconds()
        total += max(1, math.ceil(seconds / 60))
    return total


def affordable_workers(used, cap=DEFAULT_MONTHLY_MINUTES):
    """How many full-length workers fit under 90 percent of the monthly cap (at least one)."""
    return max(1, int((cap * 0.9 - used) // WORKER_MINUTES))


def monthly_minutes(now=None):
    """Estimate billed minutes this month from finished swarm workflow runs.

    GitHub bills each job as whole minutes with a one-minute minimum; run-level
    start/end times are the closest available proxy. This is an estimate, not a
    billing figure, and is used only for a conservative safety guard."""
    now = now or datetime.datetime.now(datetime.timezone.utc)
    month_start = now.replace(day=1, hour=0, minute=0, second=0, microsecond=0)
    total = 0.0
    for workflow in BUDGET_WORKFLOWS:
        runs = gh_json("run", "list", "--workflow", workflow, "--status", "completed",
                       "--limit", "100", "--json", "createdAt,updatedAt,databaseId") or []
        for run in runs:
            start = datetime.datetime.fromisoformat(run["createdAt"].replace("Z", "+00:00"))
            if start < month_start:
                continue
            end = datetime.datetime.fromisoformat(run["updatedAt"].replace("Z", "+00:00"))
            minutes = max(1.0, (end - start).total_seconds() / 60.0)
            if workflow == "swarm.yml" and run.get("databaseId"):
                minutes = max(minutes, run_job_minutes(run["databaseId"]) or 0)
            total += minutes
    return total


def repo_is_public():
    """Standard GitHub-hosted runners are free and unlimited in a public repository."""
    out = gh("api", f"repos/{repo_slug()}", "--jq", ".private", check=False).strip()
    return out == "false"


def budget_gate(cap=DEFAULT_MONTHLY_MINUTES, now=None, used=None):
    """Refuse a wave when estimated monthly minutes are near the cap."""
    used = monthly_minutes(now) if used is None else used
    if used < cap * 0.9:
        return True, f"Estimated monthly swarm minutes {used:.0f} of {cap}: starting."
    report_issue("Swarm monthly Actions budget near its cap",
                 f"Estimated swarm minutes this month: {used:.0f} of {cap}. "
                 "Waves are paused until the cap resets; a person may raise the cap "
                 "or stop the swarm. No new wave was started.")
    return False, f"Estimated monthly swarm minutes {used:.0f} of {cap}: pausing waves."


def repair_gate(run_id, now=None):
    """Decide whether to start a repair. Stops after three tries in a day."""
    prs = gh_json("pr", "list", "--state", "all", "--label", "swarm", "--limit", "100",
                  "--json", "number,title,state,createdAt") or []
    repairs = [p for p in prs if p["title"].startswith("FIX-")]
    if any(p["state"] == "OPEN" for p in repairs):
        return False, "A repair pull request is already open."
    since = (now or datetime.datetime.now(datetime.timezone.utc)) - datetime.timedelta(hours=24)
    recent = [p for p in repairs
              if datetime.datetime.fromisoformat(p["createdAt"].replace("Z", "+00:00")) > since]
    if len(recent) >= MAX_FIX:
        report_issue("Main is red and automatic repair gave up",
                     "Three repair attempts in 24 hours did not turn main green. "
                     f"Latest failing run: {run_id}. A person must take over.")
        return False, "Repair limit reached; reported in the open issue."
    return True, "Starting a repair."


def guard(pr, repair=False):
    """Return the files that stop an automatic merge and label accordingly.

    Ordinary PRs never auto-merge workflow, agent or script changes. Watchdog
    repairs are the exception: they must be able to fix the pipeline itself,
    and they still pass the fast gate before the integrator merges them.
    """
    files = gh("pr", "diff", str(pr), "--name-only").split()
    touched = protected_files(files)
    if touched and not repair:
        add_labels(pr, "needs-human")
        gh("pr", "comment", str(pr), "--body",
           f"{MARK}\nThis pull request changes workflow, agent or script files, which are never "
           f"merged automatically: {', '.join(touched[:10])}. Please review it.")
        return touched
    trackers = [f for f in files if any(fnmatch.fnmatch(f, pattern) for pattern in TRACKERS)]
    if trackers:
        fail(pr, "Shared trackers must not be edited by workers; revert " + ", ".join(trackers[:10])
             + " and put the evidence in .agent/pr-notes.md")
        return trackers
    if len(files) > MAX_PR_FILES:
        add_labels(pr, "needs-human")
        gh("pr", "comment", str(pr), "--body",
           f"{MARK}\nThis pull request changes {len(files)} files; the limit is {MAX_PR_FILES}. "
           "Split it into one bounded story per pull request. A person must decide how to split it.")
        return files
    return []


IDENTITY = ["-c", "user.name=" + os.environ.get("SWARM_GIT_NAME", "swarm-integrator"),
            "-c", "user.email=" + os.environ.get("SWARM_GIT_EMAIL", "swarm@users.noreply.github.com")]


def merge_local(pr):
    """Merge the pull request into the checked-out main with a merge commit.

    Returns (tip sha, task id, conflicting files). A conflict aborts the merge."""
    info = gh_json("pr", "view", str(pr), "--json", "headRefName,title")
    authed_git("fetch", "origin", f"pull/{pr}/head:pr-{pr}")
    done = subprocess.run(["git", *IDENTITY, "merge", "--no-ff", "-m",
                           f"Merge {info['headRefName']} (PR #{pr})", f"pr-{pr}"],
                          capture_output=True, text=True)
    if done.returncode:
        files = git("diff", "--name-only", "--diff-filter=U", check=False).split()
        git("merge", "--abort", check=False)
        return None, task_id_of(info["headRefName"]), files
    return git("rev-parse", f"pr-{pr}").strip(), task_id_of(info["headRefName"]), []


def record_merge(pr, task_id, sha, gate="scripts/quick-check.sh"):
    """Record one already-gated merge in the shared trackers. No commit or push."""
    info = gh_json("pr", "view", str(pr), "--json", "headRefOid,body")
    if info["headRefOid"] != sha:
        raise SystemExit("the branch changed after it was tested; it will be retested")
    todo = Path("TODO.md")
    tasks = {t["id"]: t for t in parse_tasks(todo.read_text())}
    title = tasks.get(task_id, {}).get("task", task_id)
    Path("docs/swarm").mkdir(parents=True, exist_ok=True)
    notes = Path(f"docs/swarm/{task_id}.md")
    notes.write_text(f"# {task_id} · {title}\n\nPR #{pr}, tested branch tip {sha}.\n\n"
                     f"{clip(info['body'])}\n")
    evidence = (f"PR #{pr}; integrator ran {gate} on the merge result (tool evidence); "
                f"Delivery re-verifies main after the push; notes in {notes}; "
                "simulated review only")
    todo.write_text(set_task_status(todo.read_text(), task_id, "done", pr=f"#{pr}", evidence=evidence))
    return f"Merged to main by the integrator after {gate} passed on the merge result."


def dispatch_delivery():
    """Re-run Delivery on main so a merge (including a repair) is verified and a still-red main wakes the watchdog."""
    gh("workflow", "run", "delivery.yml", "--ref", "main", check=False)


def continue_swarm(text, prs):
    """True when another wave has work and nothing is waiting for the integrator,
    which starts the next wave itself after it merges."""
    ready = plan_workers(1, [{"slot": 1, "model": ""}], text, prs)
    waiting = [p for p in prs if not p["isCrossRepository"]
               and not {"needs-fix", "needs-human"} & label_names(p)]
    return bool(ready) and not waiting


REPAIR_GATE = "scripts/repair-gate.sh"


def gate_for(pr, default_gate):
    """(gate script, extra environment) for one pull request.

    Repairs (FIX- and DLV- tasks) use the repair gate, which judges the integration
    tier on the classes the task names; everything else keeps the full gate."""
    info = gh_json("pr", "view", str(pr), "--json", "title,labels") or {}
    title = info.get("title", "")
    if not (title.startswith(REPAIR_PREFIXES) or "autofix" in label_names(info)):
        return default_gate, {}
    story = task_id_of_title(title)
    task = next((t for t in parse_tasks(Path("TODO.md").read_text()) if t["id"] == story), None)
    body = f"{task['task']}\n{task['details']}" if task else ""
    return REPAIR_GATE, {"STORY_ID": story, "STORY_BODY": body}


def task_id_of_title(title):
    """`DLV-02: Repair ...` -> `DLV-02`."""
    match = re.match(r"([A-Za-z0-9-]+):", title)
    return match.group(1) if match else ""


def run_gate(gate, extra_env=None):
    """Run the fast gate on the current merge. Returns (ok, log tail)."""
    # Agent-written code is already merged locally at this point. Strip every
    # credential from the gate subprocess so it cannot read or leak a token.
    env = {k: v for k, v in os.environ.items()
           if k not in ("GH_TOKEN", "GITHUB_TOKEN", "SWARM_TOKEN")}
    env.update(extra_env or {})
    done = subprocess.run(["bash", gate], capture_output=True, text=True, env=env)
    log = (done.stdout + "\n" + done.stderr).strip()
    return done.returncode == 0, log


def integrate_batch(queue, gate="scripts/quick-check.sh"):
    """Merge every ready PR in one job, gate each, then push main once.

    One cache/Java setup serves the whole batch. A PR whose gate fails is sent
    back to the workers and its merge is rolled back before the next PR, so a
    bad PR never blocks the others. Returns the number of merged PRs."""
    merged, conflicted, gates = [], [], {}
    for pr in queue:
        blocked = guard(pr, is_repair(pr))
        if blocked:
            continue
        sha, task_id, conflicts = merge_local(pr)
        if sha is None:
            reason = (f"Merge conflict with main in {', '.join(conflicts[:10]) or 'unknown files'}; "
                      "merge main into the branch and resolve it")
            # Give the automatic resolver a few tries before the PR goes back to the workers.
            tries = resolve_attempts(pr)
            if tries < MAX_RESOLVE:
                conflicted.append(pr)
                # Counted when queued, so a resolver that never runs cannot loop forever.
                gh("pr", "comment", str(pr), "--body", f"{RESOLVE_MARK}\nMerge conflict with main; "
                   f"automatic resolution queued ({tries + 1} of {MAX_RESOLVE}). {reason}", check=False)
            else:
                fail(pr, reason)
            continue
        head = gh_json("pr", "view", str(pr), "--json", "headRefOid")["headRefOid"]
        if head != sha:
            fail(pr, "the branch changed after planning; it will be retested")
            git("merge", "--abort", check=False)
            continue
        pr_gate, pr_env = gate_for(pr, gate)
        ok, log = run_gate(pr_gate, pr_env)
        if not ok:
            fail(pr, f"Merge gate ({pr_gate}) failed on the merge of main and this branch", log)
            git("reset", "--hard", "HEAD~1", check=False)
            continue
        gates[pr] = (pr_gate, pr_env)
        merged.append((pr, task_id, sha))
    emit("conflicted", json.dumps(conflicted))
    if not merged:
        emit("merged", 0)
        return 0
    comments = [record_merge(pr, task_id, sha, gates[pr][0]) for pr, task_id, sha in merged]
    files = ["TODO.md"] + [f"docs/swarm/{t}.md" for _, t, _ in merged]
    git("add", *files)
    git(*IDENTITY, "commit", "-qm", f"Integrate {len(merged)} swarm pull request(s)")
    # A full gate can take many minutes; concurrent [skip ci] tracker commits
    # can advance main meanwhile. Integrators are serialized by their
    # concurrency group, so the only new commits are tracker-only. Move HEAD
    # onto fresh main with --soft (keeping the already-gated tree staged),
    # adopt the fresh shared trackers, re-apply our merge records, re-gate and
    # retry. No commit replay means no committer identity or rebase conflicts.
    for attempt in range(3):
        try:
            authed_git("push", "origin", "HEAD:main")
            break
        except subprocess.CalledProcessError:
            if attempt == 2:
                raise
            authed_git("fetch", "origin", "main")
            git("reset", "--soft", "origin/main")
            git("checkout", "origin/main", "--", "TODO.md", "docs/swarm", check=False)
            for pr, task_id, sha in merged:
                record_merge(pr, task_id, sha, gates[pr][0])
            git("add", *files)
            git(*IDENTITY, "commit", "-qm", "Integrate swarm PRs (reconciled)",
                "--allow-empty", check=False)
            # Re-gate with the strictest gate the batch used: the full one if any PR needed it.
            regate = gate if any(g == gate for g, _ in gates.values()) else REPAIR_GATE
            renv = next((e for g, e in gates.values() if g == regate), {})
            ok, log = run_gate(regate, renv)
            if not ok:
                raise subprocess.CalledProcessError(1, ("gate", regate), "", log)
    for (pr, _, _), comment in zip(merged, comments):
        gh("pr", "comment", str(pr), "--body", comment)
    dispatch_delivery()
    emit("merged", len(merged))
    return len(merged)


MAX_RESOLVE = 3
RESOLVE_MARK = "<!-- swarm-auto-resolve -->"


def resolve_attempts(pr):
    """How many times the automatic conflict resolver already tried this PR."""
    info = gh_json("pr", "view", str(pr), "--json", "comments") or {}
    return sum(RESOLVE_MARK in c.get("body", "") for c in info.get("comments", []))


def resolve_publish(directory):
    """Push every conflict resolution that worked and send the rest back to the workers.

    Trusted step in a fresh runner: `directory/<pr>` holds the outcome the token-less
    resolver wrote and `directory/<pr>.bundle` its merge. Returns pushed PR numbers."""
    pushed, failed = [], []
    for outcome_file in sorted(Path(directory).glob("*")):
        pr = outcome_file.name
        if not pr.isdigit():
            continue
        outcome = outcome_file.read_text().strip()
        info = gh_json("pr", "view", pr, "--json", "headRefName,isCrossRepository")
        branch = info["headRefName"]
        gh("pr", "comment", pr, "--body", f"Automatic conflict resolution result: {outcome}. "
           f"{run_url()}", check=False)
        bundle = Path(directory) / f"{pr}.bundle"
        if outcome == "merged" and bundle.exists():
            git("update-ref", "-d", f"refs/heads/resolve-{pr}", check=False)
            try:
                git("fetch", str(bundle), f"resolve-{pr}:refs/heads/resolve-{pr}")
            except subprocess.CalledProcessError:
                outcome = "the resolved merge could not be read"
        elif outcome == "merged":
            outcome = "the resolved merge was not saved"
        if outcome == "merged" and not info["isCrossRepository"] and BRANCH.fullmatch(branch):
            try:
                authed_git("push", "origin", f"resolve-{pr}:refs/heads/{branch}")
                pushed.append(pr)
                continue
            except subprocess.CalledProcessError:
                outcome = "the branch moved while the conflict was being resolved"
        failed.append(pr)
        fail(int(pr), f"Merge conflict with main could not be resolved automatically ({outcome})")
    if pushed:
        # The resolved branches are mergeable now: run the integrator again.
        gh("workflow", "run", "swarm-integrator.yml", "--ref", "main", check=False)
    emit("pushed", json.dumps(pushed))
    emit("failed", json.dumps(failed))
    return pushed


def finalize(pr, task_id, sha):
    """Record the merge in the shared trackers and publish main. Trusted step."""
    comment = record_merge(pr, task_id, sha)
    git("add", "TODO.md", f"docs/swarm/{task_id}.md")
    git(*IDENTITY, "commit", "-qm", f"Record {task_id} as merged (PR #{pr})")
    authed_git("push", "origin", "HEAD:main")
    gh("pr", "comment", str(pr), "--body", comment)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="cmd", required=True)
    for name in ("ensure-labels", "plan-integration", "slot-matrix"):
        sub.add_parser(name)
    p = sub.add_parser("images-ready")
    p.add_argument("--slots", default=".github/swarm/slots.json")
    p = sub.add_parser("budget-gate")
    p.add_argument("--cap", type=int, default=DEFAULT_MONTHLY_MINUTES)
    p = sub.add_parser("integrate-batch")
    p.add_argument("--queue", required=True, help="JSON array of pull request numbers")
    p.add_argument("--gate", default="scripts/quick-check.sh")
    p = sub.add_parser("resolve-publish")
    p.add_argument("--dir", required=True)
    p = sub.add_parser("slot-config")
    p.add_argument("--slot", type=int, required=True)
    p.add_argument("--slots", default=".github/swarm/slots.json")
    p = sub.add_parser("plan-workers")
    p.add_argument("--max", type=int, default=10)
    p.add_argument("--slots", default=".github/swarm/slots.json")
    p.add_argument("--file", default="TODO.md")
    p.add_argument("--repair-run", default="")
    p = sub.add_parser("continue")
    p.add_argument("--file", default="TODO.md")
    p = sub.add_parser("add-audit-tasks")
    p.add_argument("--findings", required=True)
    p.add_argument("--file", default="TODO.md")
    p = sub.add_parser("repair-gate")
    p.add_argument("--run", required=True)
    p = sub.add_parser("add-repair-task")
    p.add_argument("--run", default="")
    p.add_argument("--latest", action="store_true",
                   help="repair the newest failing pipeline run on main")
    p.add_argument("--file", default="TODO.md")
    for name in ("release", "publish"):
        p = sub.add_parser(name)
        p.add_argument("--task", required=True, help="task JSON")
        if name == "release":
            p.add_argument("--result", default="failed")
            p.add_argument("--commits", type=int, default=0)
        if name == "publish":
            p.add_argument("--dir", required=True)
    for name in ("guard", "merge-local", "finalize", "fail"):
        p = sub.add_parser(name)
        p.add_argument("--pr", type=int, required=True)
        if name == "guard":
            p.add_argument("--repair", action="store_true")
        if name == "finalize":
            p.add_argument("--task-id", required=True)
            p.add_argument("--sha", required=True)
        if name == "fail":
            p.add_argument("--reason", required=True)
            p.add_argument("--log", default="")
    a = parser.parse_args(argv)
    if a.cmd == "ensure-labels":
        ensure_labels()
    elif a.cmd == "images-ready":
        images_ready_command(a.slots)
    elif a.cmd == "slot-matrix":
        emit("matrix", json.dumps({"slot": [s["slot"] for s in load_slots(".github/swarm/slots.json")]},
                                  separators=(",", ":")))
    elif a.cmd == "slot-config":
        print(json.dumps(slot_config(load_slots(a.slots), a.slot), separators=(",", ":")))
    elif a.cmd == "plan-workers":
        prs = gh_json("pr", "list", "--state", "open", "--label", "swarm", "--limit", "100",
                      "--json", "number,title,headRefName,isCrossRepository,labels,comments")
        repair = repair_task(a.repair_run) if a.repair_run else None
        everything = gh_json("pr", "list", "--state", "all", "--label", "swarm", "--limit", "200",
                             "--json", "number,headRefName,state,labels") or []
        live = live_workers()
        text = reclaim(Path(a.file).read_text(), everything, live)
        busy = len(live)
        room = max(0, MAX_ACTIVE_WORKERS - busy)
        print(f"{busy} worker(s) already running; room for {room} of {MAX_ACTIVE_WORKERS}.")
        include = plan_workers(min(a.max, room), rotate_slots(load_slots(a.slots), busy),
                               text, prs, repair)
        claims = {t["id"]: f"swarm-{t['slot']}" for t in include if t["kind"] == "story"}

        granted = set()

        def mark(current):
            # Re-evaluated on every push retry against the newest TODO.md, so a
            # task another plan job claimed meanwhile is not started twice.
            current = reclaim(current, everything, live)
            current, won = claim_open(current, claims)
            granted.clear()
            granted.update(won)
            return current
        update_todo(mark, f"Take {len(claims)} task(s) for the swarm", a.file)
        include = [t for t in include if t["kind"] != "story" or t["id"] in granted]
        emit("matrix", json.dumps({"include": include}, separators=(",", ":")))
        emit("count", len(include))
    elif a.cmd == "continue":
        prs = gh_json("pr", "list", "--state", "open", "--label", "swarm", "--limit", "100",
                      "--json", "number,title,headRefName,isCrossRepository,labels,comments") or []
        go = continue_swarm(Path(a.file).read_text(), prs)
        if go and waiting_waves():
            go = False
            print("A wave is already waiting; not queueing another.")
        print("Starting the next wave." if go else "No further wave to start now.")
        if go:
            gh("workflow", "run", "swarm.yml", "--ref", "main", check=False)
    elif a.cmd == "release":
        release(json.loads(a.task), a.result, a.commits)
    elif a.cmd == "publish":
        publish(json.loads(a.task), a.dir)
    elif a.cmd == "add-audit-tasks":
        add_audit_tasks(a.findings, a.file)
    elif a.cmd == "add-repair-task":
        if not a.run and not a.latest:
            sys.exit("add-repair-task needs --run <id> or --latest")
        added = add_latest_repair_task(a.file) if a.latest else add_repair_task(a.run, a.file)
        emit("added", "true" if added else "false")
    elif a.cmd == "repair-gate":
        start, why = repair_gate(a.run)
        print(why)
        emit("start", "true" if start else "false")
    elif a.cmd == "plan-integration":
        queue = plan_integration(main_is_red())
        emit("queue", json.dumps(queue))
        emit("count", len(queue))
    elif a.cmd == "budget-gate":
        if repo_is_public():
            # No minutes budget to protect: the estimate only ever paused a free swarm.
            print("Public repository: runner minutes are free; the budget gate is off.")
            emit("start", "true")
            emit("workers", MAX_ACTIVE_WORKERS)
            return 0
        used = monthly_minutes()
        allowed, why = budget_gate(a.cap, used=used)
        print(why)
        emit("start", "true" if allowed else "false")
        # A wave never starts more workers than the remaining budget can pay for.
        emit("workers", affordable_workers(used, a.cap))
    elif a.cmd == "integrate-batch":
        integrate_batch(json.loads(a.queue), a.gate)
    elif a.cmd == "resolve-publish":
        resolve_publish(a.dir)
    elif a.cmd == "guard":
        emit("blocked", "true" if guard(a.pr, a.repair) else "false")
    elif a.cmd == "merge-local":
        sha, task_id, files = merge_local(a.pr)
        if sha is None:
            fail(a.pr, f"Merge conflict with main in {', '.join(files[:10]) or 'unknown files'}; "
                       "merge main into the branch and resolve it")
        emit("conflict", "true" if sha is None else "false")
        emit("sha", sha or "")
        emit("task", task_id)
    elif a.cmd == "finalize":
        finalize(a.pr, a.task_id, a.sha)
    elif a.cmd == "fail":
        fail(a.pr, a.reason, read(a.log))
    return 0


if __name__ == "__main__":
    sys.exit(main())
