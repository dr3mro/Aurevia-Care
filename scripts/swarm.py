"""Task planning, publish and merge logic for the agent swarm (CLOUDWORKERS.md).

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
import os
import re
import subprocess
import sys
from pathlib import Path

MARK = "<!-- swarm-integrator -->"
MAX_FIX = 3
BODY_LIMIT = 8000
# Default monthly runner-minute cap for all swarm-related workflows. The guard
# estimates usage from finished run durations and refuses a wave near the cap.
DEFAULT_MONTHLY_MINUTES = 1500
BUDGET_WORKFLOWS = ("swarm.yml", "swarm-integrator.yml", "heavy-check.yml",
                    "agent-image.yml", "swarm-images.yml", "main-watchdog.yml")
BRANCH = re.compile(r"swarm/[A-Za-z0-9._-]+")
LABELS = {
    "swarm": ("1d76db", "Pull request opened by a swarm worker"),
    "autofix": ("0e8a16", "Automatic repair of a failed main run; a person reviews and merges it"),
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
TRACKERS = ["CLOUDWORKERS.md", "PROGRESS.md", "MEMORY.md", "WORKSPACE.md", "TODO.md",
            "BUGS.md", "PROJECT.md", "docs/swarm/*"]


def gh(*args, check=True):
    done = subprocess.run(["gh", *args], capture_output=True, text=True)
    if check and done.returncode:
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


def emit(name, value):
    with open(os.environ.get("GITHUB_OUTPUT", os.devnull), "a") as out:
        out.write(f"{name}={value}\n")


def label_names(item):
    return {label["name"] for label in item.get("labels", [])}


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


def clip(text):
    return (text or "")[:BODY_LIMIT]


def table_cells(line):
    return [c.strip() for c in line.strip().strip("|").split("|")]


def parse_tasks(text):
    """Read the task table and the ### sections of CLOUDWORKERS.md."""
    lines = text.splitlines()
    header = next((i for i, l in enumerate(lines)
                   if l.startswith("|") and "id" in l.lower() and "status" in l.lower()), None)
    if header is None:
        return []
    names = [c.lower() for c in table_cells(lines[header])]
    sections = {m.group(1): m.group(2).strip() for m in
                re.finditer(r"(?ms)^### (\S+)[^\n]*\n(.*?)(?=^#{2,3} |\Z)", text)}
    tasks = []
    for line in lines[header + 2:]:
        if not line.startswith("|"):
            break
        row = dict(zip(names, table_cells(line)))
        row["depends"] = [d for d in re.split(r"[,\s]+", row.get("depends", "")) if d and d != "-"]
        row["scope"] = [d for d in re.split(r"\s*,\s*", row.get("scope", "")) if d and d != "-"]
        row["details"] = sections.get(row["id"], "")
        tasks.append(row)
    return tasks


def set_task_done(text, task_id, pr):
    out = []
    header = None
    for line in text.splitlines():
        if line.startswith("|") and header is None and "status" in line.lower():
            header = [c.lower() for c in table_cells(line)]
        elif header and line.startswith("|") and table_cells(line)[0] == task_id:
            cells = table_cells(line)
            cells[header.index("status")] = "done"
            if "pr" in header:
                cells[header.index("pr")] = f"#{pr}"
            line = "| " + " | ".join(cells) + " |"
        out.append(line)
    return "\n".join(out) + "\n"


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
    match = re.fullmatch(r"swarm/([A-Za-z0-9]+-\d+)-[A-Za-z0-9._-]*", branch)
    return match.group(1) if match else ""


def task_text(task):
    fields = [("Task", task["task"]), ("Requirements", task.get("requirements", "")),
              ("Scope (stay inside these paths)", ", ".join(task["scope"])),
              ("Reserved migration version", task.get("reserved", ""))]
    head = "\n".join(f"{k}: {v}" for k, v in fields if v and v != "-")
    return clip(f"{head}\n\n{task['details']}")


def plan_workers(max_workers, slots, text, open_prs, repair=None):
    """Main repairs, then fixes, then ready tasks whose scope is free."""
    tasks = parse_tasks(text)
    by_id = {t["id"]: t for t in tasks}
    plan, busy = ([repair] if repair else []), []
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
    done = {t["id"] for t in tasks if t.get("status", "").lower() == "done"}
    for task in tasks:
        if task.get("status", "").lower() != "todo" or task["id"] in in_flight:
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
        gh("pr", "edit", str(task["number"]), "--add-label",
           "needs-fix" if result != "failed" else "needs-human")
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
    if commits > 0 and bundle.exists():
        branch = task["branch"] if task["kind"] == "fix" else read(directory / "branch")
        if not BRANCH.fullmatch(branch) or task_id_of(branch) != task["id"]:
            raise SystemExit(f"refusing unexpected branch name {branch!r}")
        if task["kind"] == "fix":
            authed_git("fetch", "origin", branch)
        git("fetch", str(bundle), "HEAD:refs/heads/swarm-incoming")
        authed_git("push", "origin", f"swarm-incoming:refs/heads/{branch}")
        checks = read(directory / "checks", "skipped")
        if task["kind"] == "story":
            notes = read(directory / "pr-notes.md", "No notes were written.")
            body = (f"Task: {task['id']}\n\nSwarm worker {task['slot']}. Local checks: {checks}. "
                    f"Agent result: {result}.\n\n{clip(notes)}")
            repair = task["id"].startswith("FIX-")
            if repair:
                body = ("**Automatic repair of a failed run on main.** Review the diff and merge it "
                        "to turn main green; it is never merged automatically.\n\n" + body)
            gh("pr", "create", "--base", os.environ.get("BASE_BRANCH", "main"), "--head", branch,
               "--title", f"{task['id']}: {task['title']}", "--body", body, "--label", "swarm",
               *(["--label", "autofix"] if repair else []),
               *(["--draft"] if checks != "passed" and not repair else []))
            pr = gh("pr", "view", branch, "--json", "number", "--jq", ".number").strip()
        else:
            pr = str(task["number"])
            gh("pr", "comment", pr, "--body",
               f"Worker {task['slot']} pushed a fix (local checks: {checks}). The integrator will retest.")
    release(task, result, 1 if pr else 0, pr)
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
        gh("pr", "edit", str(pr), "--add-label", "needs-human", "--remove-label", "needs-fix")
        gh("pr", "comment", str(pr), "--body",
           f"{MARK}\nIntegration still fails after {MAX_FIX} fix attempts: {reason}. "
           f"A person must take over.{detail}")
        return False
    attempt = attempts + 1
    gh("pr", "edit", str(pr), "--add-label", "needs-fix", "--add-label", f"fix-{attempt}")
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
    """Pull requests ready to integrate, oldest first. Repair pull requests (FIX-)
    are never merged here: a person reviews and merges them. While main is red
    nothing else merges, so ordinary work is not blamed for it."""
    prs = gh_json("pr", "list", "--state", "open", "--label", "swarm", "--limit", "50",
                  "--json", "number,title,labels,isCrossRepository")
    blocked = {"needs-fix", "needs-human"}
    return [p["number"] for p in sorted(prs, key=lambda p: p["number"])
            if not p["isCrossRepository"] and not blocked & label_names(p)
            and not p.get("title", "").startswith("FIX-") and not red]


def repair_task(run_id):
    """Build the worker task that repairs a failed run on main."""
    run = gh_json("run", "view", str(run_id), "--json", "name,headBranch,headSha,conclusion,url")
    if run["headBranch"] != "main" or run["conclusion"] != "failure":
        return None
    log = gh("run", "view", str(run_id), "--log-failed", check=False)
    tail = "\n".join(log.splitlines()[-150:])[-5000:].replace("```", "'''")
    body = (f"Repair the failed '{run['name']}' run on main ({run['url']}, commit {run['headSha'][:12]}).\n"
            "Reproduce it with the failing commands. When it is a real defect, write a failing test "
            "first, then make the smallest fix. When it is a transient or runner problem, say so in "
            ".agent/pr-notes.md and change nothing.\n\nFailing log tail:\n```text\n" + tail + "\n```")
    return {"kind": "story", "id": f"FIX-{run_id}", "number": 0, "branch": "",
            "title": f"Repair main: {run['name']} failed", "body": clip(body), "fix": ""}


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
                       "--limit", "100", "--json", "createdAt,updatedAt") or []
        for run in runs:
            start = datetime.datetime.fromisoformat(run["createdAt"].replace("Z", "+00:00"))
            if start < month_start:
                continue
            end = datetime.datetime.fromisoformat(run["updatedAt"].replace("Z", "+00:00"))
            minutes = max(1.0, (end - start).total_seconds() / 60.0)
            total += minutes
    return total


def budget_gate(cap=DEFAULT_MONTHLY_MINUTES, now=None):
    """Refuse a wave when estimated monthly minutes are near the cap."""
    used = monthly_minutes(now)
    if used < cap * 0.9:
        return True, f"Estimated monthly swarm minutes {used:.0f} of {cap}: starting."
    gh("issue", "create", "--title", "Swarm monthly Actions budget near its cap",
       "--body", (f"{MARK}\nEstimated swarm minutes this month: {used:.0f} of {cap}. "
                  "Waves are paused until the cap resets; a person may raise the cap "
                  "or stop the swarm. No new wave was started."), check=False)
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
        gh("issue", "create", "--title", "Main is red and automatic repair gave up",
           "--body", f"{MARK}\nThree repair attempts in 24 hours did not turn main green. "
                     f"Latest failing run: {run_id}. A person must take over.", check=False)
        return False, "Repair limit reached; opened an issue."
    return True, "Starting a repair."


def guard(pr):
    """Return the files that stop an automatic merge and label accordingly."""
    files = gh("pr", "diff", str(pr), "--name-only").split()
    touched = protected_files(files)
    if touched:
        gh("pr", "edit", str(pr), "--add-label", "needs-human")
        gh("pr", "comment", str(pr), "--body",
           f"{MARK}\nThis pull request changes workflow, agent or script files, which are never "
           f"merged automatically: {', '.join(touched[:10])}. Please review it.")
        return touched
    trackers = [f for f in files if any(fnmatch.fnmatch(f, pattern) for pattern in TRACKERS)]
    if trackers:
        fail(pr, "Shared trackers must not be edited by workers; revert " + ", ".join(trackers[:10])
             + " and put the evidence in .agent/pr-notes.md")
    return trackers


IDENTITY = ["-c", "user.name=swarm-integrator", "-c", "user.email=swarm@users.noreply.github.com"]


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
    cloud = Path("CLOUDWORKERS.md")
    tasks = {t["id"]: t for t in parse_tasks(cloud.read_text())}
    title = tasks.get(task_id, {}).get("task", task_id)
    reqs = tasks.get(task_id, {}).get("requirements", "")
    Path("docs/swarm").mkdir(parents=True, exist_ok=True)
    notes = Path(f"docs/swarm/{task_id}.md")
    notes.write_text(f"# {task_id} · {title}\n\nPR #{pr}, tested branch tip {sha}.\n\n"
                     f"{clip(info['body'])}\n")
    cloud.write_text(set_task_done(cloud.read_text(), task_id, pr))
    progress = Path("PROGRESS.md")
    row = (f"| {task_id} · {title} | Done (swarm, merged) | {reqs or '-'} | PR #{pr}; integrator ran "
           f"{gate} on the merge result (tool evidence); heavy checks run on main in heavy-check.yml; "
           f"notes in {notes} | Merged by the integrator. Simulated review only. Next: CLOUDWORKERS.md |")
    text = progress.read_text()
    progress.write_text(text + ("" if text.endswith("\n") else "\n") + row + "\n")
    return f"Merged to main by the integrator after {gate} passed on the merge result."


def dispatch_heavy():
    """Trigger the once-per-batch heavy verification on main."""
    gh("workflow", "run", "heavy-check.yml", "--ref", "main", check=False)


def run_gate(gate):
    """Run the fast gate on the current merge. Returns (ok, log tail)."""
    # Agent-written code is already merged locally at this point. Strip every
    # credential from the gate subprocess so it cannot read or leak a token.
    env = {k: v for k, v in os.environ.items()
           if k not in ("GH_TOKEN", "GITHUB_TOKEN", "SWARM_TOKEN")}
    done = subprocess.run(["bash", gate], capture_output=True, text=True, env=env)
    log = (done.stdout + "\n" + done.stderr).strip()
    return done.returncode == 0, log


def integrate_batch(queue, gate="scripts/quick-check.sh"):
    """Merge every ready PR in one job, gate each, then push main once.

    One cache/Java setup serves the whole batch. A PR whose gate fails is sent
    back to the workers and its merge is rolled back before the next PR, so a
    bad PR never blocks the others. Returns the number of merged PRs."""
    merged = []
    for pr in queue:
        blocked = guard(pr)
        if blocked:
            continue
        sha, task_id, conflicts = merge_local(pr)
        if sha is None:
            fail(pr, f"Merge conflict with main in {', '.join(conflicts[:10]) or 'unknown files'}; "
                     "merge main into the branch and resolve it")
            continue
        head = gh_json("pr", "view", str(pr), "--json", "headRefOid")["headRefOid"]
        if head != sha:
            fail(pr, "the branch changed after planning; it will be retested")
            git("merge", "--abort", check=False)
            continue
        ok, log = run_gate(gate)
        if not ok:
            fail(pr, f"Fast merge gate ({gate}) failed on the merge of main and this branch", log)
            git("reset", "--hard", "HEAD~1", check=False)
            continue
        merged.append((pr, task_id, sha))
    if not merged:
        emit("merged", 0)
        return 0
    comments = [record_merge(pr, task_id, sha, gate) for pr, task_id, sha in merged]
    files = ["CLOUDWORKERS.md", "PROGRESS.md"] + [f"docs/swarm/{t}.md" for _, t, _ in merged]
    git("add", *files)
    git(*IDENTITY, "commit", "-qm", f"Integrate {len(merged)} swarm pull request(s)")
    authed_git("push", "origin", "HEAD:main")
    for (pr, _, _), comment in zip(merged, comments):
        gh("pr", "comment", str(pr), "--body", comment)
    dispatch_heavy()
    emit("merged", len(merged))
    return len(merged)


def finalize(pr, task_id, sha):
    """Record the merge in the shared trackers and publish main. Trusted step."""
    comment = record_merge(pr, task_id, sha)
    git("add", "CLOUDWORKERS.md", "PROGRESS.md", f"docs/swarm/{task_id}.md")
    git(*IDENTITY, "commit", "-qm", f"Record {task_id} as merged (PR #{pr})")
    authed_git("push", "origin", "HEAD:main")
    gh("pr", "comment", str(pr), "--body", comment)
    dispatch_heavy()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="cmd", required=True)
    for name in ("ensure-labels", "plan-integration", "slot-matrix"):
        sub.add_parser(name)
    p = sub.add_parser("budget-gate")
    p.add_argument("--cap", type=int, default=DEFAULT_MONTHLY_MINUTES)
    p = sub.add_parser("integrate-batch")
    p.add_argument("--queue", required=True, help="JSON array of pull request numbers")
    p.add_argument("--gate", default="scripts/quick-check.sh")
    p = sub.add_parser("slot-config")
    p.add_argument("--slot", type=int, required=True)
    p.add_argument("--slots", default=".github/swarm/slots.json")
    p = sub.add_parser("plan-workers")
    p.add_argument("--max", type=int, default=10)
    p.add_argument("--slots", default=".github/swarm/slots.json")
    p.add_argument("--file", default="CLOUDWORKERS.md")
    p.add_argument("--repair-run", default="")
    p = sub.add_parser("repair-gate")
    p.add_argument("--run", required=True)
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
        if name == "finalize":
            p.add_argument("--task-id", required=True)
            p.add_argument("--sha", required=True)
        if name == "fail":
            p.add_argument("--reason", required=True)
            p.add_argument("--log", default="")
    a = parser.parse_args(argv)
    if a.cmd == "ensure-labels":
        ensure_labels()
    elif a.cmd == "slot-matrix":
        emit("matrix", json.dumps({"slot": [s["slot"] for s in load_slots(".github/swarm/slots.json")]},
                                  separators=(",", ":")))
    elif a.cmd == "slot-config":
        print(json.dumps(slot_config(load_slots(a.slots), a.slot), separators=(",", ":")))
    elif a.cmd == "plan-workers":
        prs = gh_json("pr", "list", "--state", "open", "--label", "swarm", "--limit", "100",
                      "--json", "number,title,headRefName,isCrossRepository,labels,comments")
        repair = repair_task(a.repair_run) if a.repair_run else None
        include = plan_workers(a.max, load_slots(a.slots), Path(a.file).read_text(), prs, repair)
        emit("matrix", json.dumps({"include": include}, separators=(",", ":")))
        emit("count", len(include))
    elif a.cmd == "release":
        release(json.loads(a.task), a.result, a.commits)
    elif a.cmd == "publish":
        publish(json.loads(a.task), a.dir)
    elif a.cmd == "repair-gate":
        start, why = repair_gate(a.run)
        print(why)
        emit("start", "true" if start else "false")
    elif a.cmd == "plan-integration":
        queue = plan_integration(main_is_red())
        emit("queue", json.dumps(queue))
        emit("count", len(queue))
    elif a.cmd == "budget-gate":
        allowed, why = budget_gate(a.cap)
        print(why)
        emit("start", "true" if allowed else "false")
    elif a.cmd == "integrate-batch":
        integrate_batch(json.loads(a.queue), a.gate)
    elif a.cmd == "guard":
        emit("blocked", "true" if guard(a.pr) else "false")
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
