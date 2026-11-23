import curses, json, os, re, shutil, subprocess, sys, tempfile, threading, time
from datetime import datetime, timedelta, timezone

INTERVAL, RECENT, ONCE = int(sys.argv[1]), int(sys.argv[2]), sys.argv[3] == "1"
AUTOSTART = int(sys.argv[4]) if len(sys.argv) > 4 else 0  # minutes between automatic wave starts; 0 = off
LIVE = ("queued", "in_progress", "waiting", "pending", "requested")
FAILED = ("failure", "timed_out")
FIELDS = "databaseId,workflowName,displayTitle,conclusion,status,headSha,headBranch,event,createdAt,startedAt,updatedAt,url"
TEXT = ("repo", "todo", "archive", "total", "commits")  # plain-text results; everything else is JSON
# Sections that change slowly are re-read every SLOW_EVERY seconds, not on every refresh, and the jobs of a
# finished run never change, so they are looked up once. This keeps the GitHub rate limit for other work.
SLOW = ("archive", "total", "closed", "merged", "commits", "runs_more")
SLOW_EVERY = 300
CACHE = {"jobs": {}, "slow_at": 0.0}
CALLS = {
    "repo": ["repo", "view", "--json", "nameWithOwner", "-q", ".nameWithOwner"],
    "issues": ["issue", "list", "--state", "open", "--limit", "300", "--json", "number,title,labels,createdAt"],
    "prs": ["pr", "list", "--state", "open", "--limit", "300", "--json", "number,title,isDraft,labels,headRefName,createdAt"],
    "merged": ["pr", "list", "--state", "merged", "--limit", "300", "--search", "merged:>={since}", "--json", "number,title,mergedAt"],
    "closed": ["pr", "list", "--state", "closed", "--limit", "300", "--search", "closed:>={since}", "--json", "number,title,closedAt,mergedAt"],
    "runs": ["run", "list", "--limit", "100", "--json", FIELDS],  # first page: shows quickly
    "runs_more": ["run", "list", "--limit", "300", "--json", FIELDS],  # three pages, merged in when it arrives
    "total": ["api", "graphql", "-F", "o={owner}", "-F", "n={repo}", "-q", ".data.repository.defaultBranchRef.target.history.totalCount",
              "-f", "query=query($o:String!,$n:String!){repository(owner:$o,name:$n){defaultBranchRef{target{... on Commit{history{totalCount}}}}}}"],
    # The tasks are one file each under tasks/ (TODO.md is not in git). This call lists only "name oid" lines;
    # todo_text() then reads the files it has not seen yet, 100 per call, and keeps the rest in a local cache.
    "todo": ["api", "graphql", "-F", "o={owner}", "-F", "n={repo}", "-q",
             '.data.repository.t.entries[]? | select(.name | test("^[0-9].*[.]md$")) | "\\(.name) \\(.oid)"',
             "-f", 'query=query($o:String!,$n:String!){repository(owner:$o,name:$n){t:object(expression:"HEAD:tasks"){... on Tree{entries{name oid}}}}}'],
    # Finished tasks: one file each under tasks/done, plus the older archive file.
    "archive": ["api", "graphql", "-F", "o={owner}", "-F", "n={repo}", "-q",
                '[(.data.repository.d.entries // [])[].object.text, .data.repository.a.text] | map(select(. != null)) | join("\n")',
                "-f", 'query=query($o:String!,$n:String!){repository(owner:$o,name:$n){d:object(expression:"HEAD:tasks/done"){... on Tree{entries{object{... on Blob{text}}}}} a:object(expression:"HEAD:docs/archive/TODO-DONE.md"){... on Blob{text}}}}'],
}


TASK_CACHE = os.path.join(tempfile.gettempdir(), "swarm-monitor-tasks.json")
TASK_BATCH = 100


def read_blobs(owner, repo, oids):
    """Text of the given blobs, one GraphQL call (aliases a0, a1, ...). A blob that fails is left out."""
    body = " ".join(f'a{i}:object(oid:"{o}"){{... on Blob{{text}}}}' for i, o in enumerate(oids))
    query = f'query($o:String!,$n:String!){{repository(owner:$o,name:$n){{{body}}}}}'
    got = collect({"x": spawn(["api", "graphql", "-F", f"o={owner}", "-F", f"n={repo}", "-f", f"query={query}"])})["x"]
    repo_data = ((got or {}).get("data") or {}).get("repository") or {}
    return {o: (repo_data.get(f"a{i}") or {}).get("text") for i, o in enumerate(oids)}


def todo_text(listing):
    """All task files as one text, in file-name order. Files never change once their oid is known, so only
    new or edited ones are read (100 per call, a few calls at once); everything else comes from the cache."""
    entries = [ln.split(" ") for ln in (listing or "").splitlines() if ln.count(" ") == 1]
    if "repo" not in CACHE:  # this section runs beside the repo lookup, so it asks for the name itself
        CACHE["repo"] = (collect({"x": spawn(["repo", "view", "--json", "nameWithOwner", "-q", ".nameWithOwner"])}, ("x",))["x"] or "").strip()
    owner, _, repo = CACHE["repo"].partition("/")
    if not repo:
        CACHE.pop("repo")
        return None
    try:
        with open(TASK_CACHE) as f:
            cache = json.load(f)
    except (OSError, ValueError):
        cache = {}
    missing = [o for _, o in entries if o not in cache]
    batches = [missing[i:i + TASK_BATCH] for i in range(0, len(missing), TASK_BATCH)]
    for i in range(0, len(batches), 4):
        threads, results = [], []
        for b in batches[i:i + 4]:
            t = threading.Thread(target=lambda b=b: results.append(read_blobs(owner, repo, b)), daemon=True)
            t.start()
            threads.append(t)
        for t in threads:
            t.join()
        for r in results:
            cache.update({o: text for o, text in r.items() if text is not None})
    if any(o not in cache for _, o in entries):
        return None  # a batch failed: keep the previous value rather than show a partial list
    keep = {o: cache[o] for _, o in entries}
    try:
        with open(TASK_CACHE, "w") as f:
            json.dump(keep, f)
    except OSError:
        pass
    return "".join(keep[o] for _, o in sorted(entries))


# ---- data ---------------------------------------------------------------------------------
def spawn(args):
    return subprocess.Popen(["gh", *args], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)


def collect(procs, text_keys=()):
    data = {}
    for k, p in procs.items():
        try:
            out, _ = p.communicate(timeout=180)
            data[k] = out if k in text_keys else json.loads(out or "[]")
        except Exception:
            p.kill()
            data[k] = None  # None: keep the previous value
    return data


TIMING_LOG = os.path.join(tempfile.gettempdir(), "swarm-monitor-timing.log")


def log_timing(rows, total):
    """Append one block per refresh: how long each section took, slowest first (to find the bottleneck)."""
    try:
        with open(TIMING_LOG, "a") as f:
            f.write(f"--- {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}  refresh took {total:.1f}s\n")
            for name, secs, note in sorted(rows, key=lambda r: -r[1]):
                f.write(f"{secs:7.2f}s  {name:<12} {note}\n")
    except OSError:
        pass


def merge_runs(a, b):
    """Runs of the last 24 hours plus the newest ones of any age, one entry per run, newest first."""
    seen = {r["databaseId"]: r for r in [*b, *a]}
    return sorted(seen.values(), key=lambda r: r.get("createdAt") or "", reverse=True)


def fetch(emit=None, full=True):
    """Load every section in parallel. Each section is handed to `emit(part)` the moment it is ready, so a
    screen can fill in piece by piece; the merged result is also returned (a section that failed is None)."""
    since = (datetime.now(timezone.utc) - timedelta(hours=24)).strftime("%Y-%m-%dT%H:%M:%SZ")
    full = full or time.time() - CACHE["slow_at"] >= SLOW_EVERY
    if full:
        CACHE["slow_at"] = time.time()
    calls = {k: [a.replace("{since}", since) for a in v] for k, v in CALLS.items() if full or k not in SLOW}
    # One line per commit ("sha parent"), newest first; --paginate so a busy day is not capped at one page of 100.
    if full:
        calls["commits"] = ["api", f"repos/{{owner}}/{{repo}}/commits?since={since}&per_page=100", "--paginate", "-q",
                            ".[] | \"\\(.sha) \\(.parents[0].sha // \"\")\""]
    out, guard, timings, began, claimed = {}, threading.Lock(), [], time.monotonic(), set()

    def put(part):
        with guard:
            out.update(part)
        if emit:
            emit(part)

    def timed(name, t0, note=""):
        with guard:
            timings.append((name, time.monotonic() - t0, note))

    def load(k, args):
        t0 = time.monotonic()
        val = collect({k: spawn(args)}, (*TEXT, "commits"))[k]
        timed(k, t0, "FAILED or timed out" if val is None else f"{len(val)} {'chars' if isinstance(val, str) else 'items'}")
        if val is None:
            return put({k: None}) if k not in ("commits", "runs") else None
        if k == "repo":
            val = val.strip()
        if k == "todo":
            val = todo_text(val)
            if val is None:
                return put({k: None})
        if k == "commits":
            commits = [{"sha": f[0], "parent": f[1] if len(f) > 1 else ""}
                       for f in (ln.split(" ") for ln in val.split("\n") if ln.strip())]
            churn = {"commits": len(commits), "files": 0, "add": 0, "del": 0}
            if commits and commits[-1]["parent"]:
                t1 = time.monotonic()
                got = collect({"x": spawn(["api", f"repos/{{owner}}/{{repo}}/compare/{commits[-1]['parent']}...{commits[0]['sha']}", "-q",
                                           "{files: (.files | length), add: ([.files[].additions] | add // 0), del: ([.files[].deletions] | add // 0)}"])})["x"]
                timed("compare", t1, "what the day's commits changed")
                churn = dict(got, commits=len(commits)) if isinstance(got, dict) else None
            return put({"churn": churn})
        if k in ("runs", "runs_more"):  # the quick first page shows at once; the full list merges in when it lands
            with guard:
                merged = merge_runs(val, out.get("runs") or [])
            put({"runs": merged})
            return add_jobs(merged)
        put({k: val})

    def add_jobs(runs):
        """Job detail for runs that are live or recently failed, only for runs not looked up yet."""
        now = datetime.now(timezone.utc)
        want = [r["databaseId"] for r in runs if r.get("status") != "completed"]
        want += [r["databaseId"] for r in runs if r.get("status") == "completed" and state(r) in FAILED
                 and r.get("updatedAt") and (now - ts(r["updatedAt"])).total_seconds() < 86400]
        done = {r["databaseId"] for r in runs if r.get("status") == "completed"}
        with guard:
            # Drop stale job lists for any run that is live again, so a re-run is not
            # masked by a cached completed-run snapshot.
            for i in [r["databaseId"] for r in runs if r.get("status") != "completed"]:
                CACHE["jobs"].pop(i, None)
            cached = dict(CACHE["jobs"])
            want = [i for i in dict.fromkeys(want) if i not in claimed and i not in cached]
            claimed.update(want)
        if not want and out.get("jobs") is not None:
            return
        t1 = time.monotonic()
        jobs = collect({i: spawn(["run", "view", str(i), "--json", "jobs"]) for i in want})
        timed("jobs", t1, f"{len(want)} run view calls")
        with guard:
            have = dict(out.get("jobs") or {})
        found = {i: (j or {}).get("jobs", []) for i, j in jobs.items() if isinstance(j, dict) or j is None}
        CACHE["jobs"].update({i: j for i, j in found.items() if i in done})
        have = {**cached, **have, **found}
        put({"jobs": have})

    # Start order follows the measured cost, cheapest first (timing log: issues, repo, prs, total, archive,
    # todo, closed, merged, commits, then the runs and their job lookups), so the quick boxes appear first.
    order = ["issues", "repo", "prs", "total", "archive", "todo", "closed", "merged", "commits", "runs", "runs_more"]
    threads = [threading.Thread(target=load, args=(k, calls[k]), daemon=True)
               for k in sorted(calls, key=lambda k: order.index(k) if k in order else len(order))]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    log_timing(timings, time.monotonic() - began)
    return out


def blank(k):
    return "" if k in TEXT else {} if k in ("jobs", "churn") else []


def ts(v):
    return datetime.fromisoformat(v.replace("Z", "+00:00")) if v else None


def span(sec):
    sec = max(int(sec), 0)
    if sec >= 86400:
        return f"{sec // 86400}d{sec % 86400 // 3600:02d}h"
    if sec >= 3600:
        return f"{sec // 3600}h{sec % 3600 // 60:02d}m"
    return f"{sec // 60}m{sec % 60:02d}s"


def clip(text, n):
    text = str(text)
    return text if len(text) <= n else text[: max(n - 3, 0)] + "..."


def mid(text, n):
    """Shorten from the middle so both ends of a branch name stay readable."""
    text = str(text)
    if len(text) <= n:
        return text
    keep = max(n - 1, 2)
    return text[: keep - keep // 2] + "…" + text[len(text) - keep // 2:]


def failure_parts(jobs):
    """(failing job, first failed step) of a run, with the noisy 'Run ' prefix of step names removed."""
    for j in jobs:
        if j.get("conclusion") in FAILED:
            steps = [st.get("name", "") for st in j.get("steps", []) if st.get("conclusion") in FAILED]
            step = steps[0] if steps else ""
            return j.get("name", ""), step[4:] if step.startswith("Run ") else step
    return "", ""


def state(r):
    return r.get("conclusion") or r.get("status") or ""


# Plain ASCII symbols on purpose: many terminals draw symbols like a check or a triangle two
# cells wide, which shifts a row past its column. Box-drawing characters are single width.
SYM = {"success": ("+", "green"), "failure": ("x", "red"), "timed_out": ("x", "red"),
       "in_progress": (">", "yellow"), "queued": ("~", "yellow"), "waiting": ("~", "yellow"),
       "pending": ("~", "yellow"), "requested": ("~", "yellow"), "cancelled": ("-", "gray"),
       "skipped": ("-", "gray"), "action_required": ("!", "red")}

ENTRY = re.compile(r"^- \[([ x-])\] (\S+) · (.*)$")


def parse_tasks(text):
    """TODO.md entries as dicts with status (todo, taken, done), id, title and agent."""
    tasks, cur = [], None
    for line in (text or "").splitlines():
        m = ENTRY.match(line)
        if m:
            cur = {"status": {" ": "todo", "-": "taken", "x": "done"}[m.group(1)], "id": m.group(2),
                   "title": m.group(3), "agent": "", "priority": ""}
            tasks.append(cur)
        elif cur is not None and line.startswith("  - agent:"):
            cur["agent"] = line.split(":", 1)[1].strip()
        elif cur is not None and line.startswith("  - priority:"):
            cur["priority"] = line.split(":", 1)[1].strip().lower()
        elif cur is not None and line and not line.startswith(" "):
            cur = None
    return tasks


LOW_PREFIXES = ("QAD-", "DSO-", "AUD-")  # same rule as priority_of in swarm.py
PRIORITY_RANK = {"high": 0, "normal": 1, "low": 2}


def priority_of(task):
    value = task.get("priority", "")
    if value in PRIORITY_RANK:
        return value
    if "HIGH PRIORITY" in task["title"]:
        return "high"
    return "low" if task["id"].startswith(LOW_PREFIXES) else "normal"


WORKER_CAP = 20  # the most workers the swarm runs at once (MAX_ACTIVE_WORKERS in swarm.py)
WORKER_JOB = re.compile(r"^Swarm worker(?: \((?:story|fix), (?P<id>[A-Za-z0-9-]+),)?")


def worker_counts(live, jobs):
    """(running, queued) workers across the live Agent swarm runs.

    A worker is one task: its Work and Publish jobs count once, as swarm.py's live_workers does.
    Running means a job of the worker is in progress; queued means it has jobs but none running."""
    running, queued = set(), set()
    for run in live:
        run_jobs = jobs.get(run.get("databaseId"))
        if not run_jobs:
            # Jobs not loaded yet: count the run itself as one running worker so
            # the dashboard never reports 0/20 while a wave is actually live.
            # Only "Agent swarm" runs spawn workers, so only those count.
            if run.get("workflowName") == "Agent swarm":
                running.add((run.get("databaseId"), run.get("databaseId")))
            continue
        for job in run_jobs:
            match = WORKER_JOB.match(job.get("name") or "")
            if not match or job.get("status") == "completed":
                continue
            key = (run.get("databaseId"), match.group("id") or job.get("name"))
            (running if job.get("status") == "in_progress" else queued).add(key)
    # If no jobs loaded at all, fall back to counting live Agent swarm runs.
    if not jobs and live:
        running = {(r.get("databaseId"), r.get("databaseId")) for r in live
                   if r.get("workflowName") == "Agent swarm"}
        queued = set()
    return len(running), len(queued - running)


def job_summary(jobs, now):
    """One short line about a live run's jobs: what runs, what waits, how many finished."""
    running = [j for j in jobs if j.get("status") == "in_progress"]
    queued = sum(1 for j in jobs if j.get("status") in ("queued", "waiting", "pending"))
    finished = sum(1 for j in jobs if j.get("status") == "completed")
    bad = [j for j in jobs if j.get("conclusion") in FAILED]
    parts = []
    for j in running[:3]:
        t = ts(j.get("startedAt"))
        parts.append(f"{j.get('name', '')} {span((now - t).total_seconds()) if t else ''}".strip())
    if len(running) > 3:
        parts.append(f"+{len(running) - 3} running")
    if queued:
        parts.append(f"{queued} queued")
    parts.append(f"{finished}/{len(jobs)} done")
    if bad:
        parts.append(f"{len(bad)} failed")
    return " · ".join(parts)


def failure_detail(jobs):
    """Failing job and its first failed step, e.g. 'Delivery tooling tests / Run python3 ...'."""
    for j in jobs:
        if j.get("conclusion") in FAILED:
            steps = [st.get("name", "") for st in j.get("steps", []) if st.get("conclusion") in FAILED]
            return f"{j.get('name', '')} / {steps[0]}" if steps else j.get("name", "")
    return ""


# ---- layout: every line is a list of (text, style) segments -----------------------------------
def cells(width, *spec):
    """One row of exactly `width` characters. spec: (text, style, width[, 'r']); width None takes the rest."""
    fixed = sum(c[2] for c in spec if c[2] is not None) + len(spec) - 1
    flex = max(width - fixed, 4)
    row = []
    for i, c in enumerate(spec):
        w = c[2] if c[2] is not None else flex
        text = clip(c[0], w)
        row.append((text.rjust(w) if len(c) > 3 else text.ljust(w), c[1]))
        if i < len(spec) - 1:
            row.append((" ", ""))
    return row


def clipline(line, n):
    out, used = [], 0
    for text, style in line:
        if used >= n:
            break
        text = text[: n - used]
        out.append((text, style))
        used += len(text)
    return out, used


def box(title, badge, col, border, rows, iw, height):
    """A rounded card of exactly `height` lines; rows are clipped or padded to the inside width."""
    border = "line" if border == "gray" else border
    t, b = f" {title} ", f" {badge} " if badge != "" else ""
    fill = max(iw - len(t) - len(b), 0)
    lines = [[("╭─", border), (t[: iw], col), ("─" * fill, border), (b, "b_" + col), ("─╮", border)]]
    for i in range(height - 2):
        row, used = clipline(rows[i], iw) if i < len(rows) else ([], 0)
        lines.append([("│ ", border)] + row + [(" " * (iw - used), ""), (" │", border)])
    lines.append([("╰" + "─" * (iw + 2) + "╯", border)])
    return lines


def fit_col(panels, avail, iw):
    """Cards for one column filling exactly `avail` lines (None: natural height). Items are shared
    out one line at a time in priority order; spare lines become padding, so no gap is left empty."""
    n = len(panels)
    if avail is None:
        show, pad = [len(p["items"]) for p in panels], [0] * n
    else:
        show = [min(len(p["items"]), 2) for p in panels]
        free = avail - 2 * n - sum(max(k, 1) for k in show)
        order = [i for i in sorted(range(n), key=lambda i: panels[i]["prio"])]
        while free > 0:
            gave = False
            for i in order:
                if show[i] < len(panels[i]["items"]) and free > 0:
                    show[i] += 1
                    free -= 1
                    gave = True
            if not gave:
                break
        pad = [0] * n
        while free > 0:
            for i in order:
                if free > 0:
                    pad[i] += 1
                    free -= 1
    lines = []
    for i, p in enumerate(panels):
        items = p["items"]
        if p.get("loading"):
            rows, p = [[("loading…", "dim")]], dict(p, total="…")
        elif not items:
            rows = [[("(none)", "dim")]]
        elif len(items) <= show[i]:
            rows = list(items)
        else:
            rows = items[: show[i] - 1] + [[(f"... +{len(items) - show[i] + 1} more", "dim")]]
        lines += box(p["title"], p["total"], p["col"], p["border"], rows, iw, 2 + len(rows) + pad[i])
    return lines


def compose(data, W, H, alarm_ids=frozenset(), busy=False, loading=frozenset()):
    """Return (top_lines, left_lines, right_lines, column_width) for a W x H screen (H None: natural)."""
    now = datetime.now(timezone.utc)
    runs, issues, prs = data["runs"], data["issues"], data["prs"]
    merged, closed = data["merged"], data["closed"]
    age = lambda v: span((now - ts(v)).total_seconds()) + " ago" if ts(v) else ""
    day = lambda v: bool(ts(v)) and (now - ts(v)).total_seconds() < 86400

    live = sorted([r for r in runs if r.get("status") != "completed"], key=lambda r: r.get("createdAt") or "")
    done = [r for r in runs if r.get("status") == "completed"]
    last20 = done[:20]
    rate = sum(1 for r in last20 if state(r) == "success") / len(last20) if last20 else 0.0
    durs = sorted((ts(r["updatedAt"]) - ts(r.get("startedAt") or r["createdAt"])).total_seconds()
                  for r in done[:30] if r.get("updatedAt"))
    median = durs[len(durs) // 2] if durs else 0
    runs_day = [r for r in done if day(r.get("updatedAt"))]
    failed_day = [r for r in runs_day if state(r) in FAILED]
    merged_day = [p for p in merged if day(p.get("mergedAt"))]
    closed_day = [p for p in closed if not p.get("mergedAt") and day(p.get("closedAt"))]
    main = next((r for r in done if r.get("workflowName") == "Delivery" and r.get("headBranch") == "main"), None)
    mstate = state(main) if main else "unknown"
    msym, mcol = SYM.get(mstate, ("•", "gray"))
    wave = next((r for r in runs if r.get("workflowName") == "Agent swarm"), None)
    jobs = data.get("jobs") or {}
    churn = data.get("churn") or {}
    todo = parse_tasks(data.get("todo"))
    n_done = sum(1 for t in todo if t["status"] == "done") + len(re.findall(r"^- \[x\] ", data.get("archive") or "", re.M))
    n_open = sum(1 for t in todo if t["status"] == "todo")
    taken = [t for t in todo if t["status"] == "taken"]
    busy_workers, queued_workers = worker_counts(live, jobs)
    n_all = n_done + n_open + len(taken)
    hot = sum(1 for x in issues + prs for l in x.get("labels", []) if l["name"] == "needs-human")
    day_ok = (len(runs_day) - len(failed_day)) / len(runs_day) if runs_day else 1.0

    # ---- top: title bar, stat cards, progress lines
    stamp = datetime.now().strftime("%a %b %d  %H:%M:%S") + ("  *" if busy else "   ")
    title = f" {data['repo'] or 'repo'}  ·  swarm monitor"
    top = [[(title + " " * max(W - 2 - len(title) - len(stamp) - 1, 1) + stamp + " ", "bar")]]
    cards = [("MAIN", f"{msym} {mstate}", "b_" + mcol, age(main["updatedAt"]) if main else "-"),
             ("WORKERS", f"{busy_workers}/{WORKER_CAP}", "b_yellow" if busy_workers else "gray",
              f"running, {queued_workers} queued" if queued_workers else "running"),
             ("MERGED 24h", str(len(merged_day)), "b_green" if merged_day else "gray", "pull requests"),
             ("FAILED 24h", str(len(failed_day)), "b_red" if failed_day else "b_green", f"of {len(runs_day)} runs"),
             ("CLOSED 24h", str(len(closed_day)), "b_yellow" if closed_day else "gray", "not merged"),
             ("COMMITS", (data.get("total") or "").strip() or "-", "b_blue" if data.get("total") else "gray", "all time"),
             ("COMMITS LAST 24H", str(churn.get("commits", "-")), "b_blue" if churn else "gray",
              f"+{churn.get('add', 0)} -{churn.get('del', 0)} {churn.get('files', 0)}f" if churn else "no data"),
             ("MEDIAN RUN", span(median), "b_cyan", f"{len(durs)} runs"),
             ("NEEDS HUMAN", str(hot), "b_red" if hot else "b_green", "issues, PRs")]
    waits = {"MAIN": {"runs"}, "WORKERS": {"runs", "jobs"}, "MERGED 24h": {"merged"}, "FAILED 24h": {"runs"}, "CLOSED 24h": {"closed"},
             "COMMITS": {"total"}, "COMMITS LAST 24H": {"churn"}, "MEDIAN RUN": {"runs"}, "NEEDS HUMAN": {"issues", "prs"}}
    cards = [(c[0], "…", "gray", "loading") if waits.get(c[0], set()) & loading else c for c in cards]

    def base(c):  # a card is at least 16 wide and grows when its label, value or caption needs more
        sub = c[3] if isinstance(c[3], str) else "".join(t for t, _ in c[3])
        return max(16, len(c[0]) + 5, len(sub) + 3, len(c[1]) + 3)

    for extra in ("CLOSED 24h", "MEDIAN RUN"):  # too narrow for every card: drop the least useful ones first
        if W - 2 < sum(base(c) + 1 for c in cards):
            cards = [c for c in cards if c[0] != extra]
    if (H is None or H >= 24) and W - 2 >= sum(base(c) + 1 for c in cards):
        spare = W - 2 - sum(base(c) + 1 for c in cards)  # share leftover width out so the cards reach the right edge
        rows = [[], [], [], []]
        for n, (label, value, style, sub) in enumerate(cards):
            w = base(cards[n]) + spare // len(cards) + (1 if n < spare % len(cards) else 0)
            inner = w - 2
            rows[0] += [("╭" + f"─ {label} ".ljust(inner, "─") + "╮ ", "line")]
            rows[1] += [("│", "line"), ((" " + value).ljust(inner)[:inner], style), ("│ ", "line")]
            segs = sub if isinstance(sub, list) else [(sub, "dim")]
            segs = [(" ", "")] + segs
            used = sum(len(t) for t, _ in segs)
            rows[2] += [("│", "line")] + clipline(segs, inner)[0] + [(" " * max(inner - used, 0), ""), ("│ ", "line")]
            rows[3] += [("╰" + "─" * inner + "╯ ", "line")]
        top += [[(" ", "")] + r for r in rows]
    else:
        line = [(" ", "")]
        for label, value, style, sub in cards:
            line += [(label.lower() + " ", "gray"), (value, style), ("   ", "")]
        top.append(line)
    # ---- pipeline card: two meter rows on one grid (label, bar, figures, flush-right extras)
    def bar(share, color, n=22):
        full = round(share * n)  # single-width box-drawing glyphs only, so every terminal draws the same width
        return [("━" * full, color), ("╌" * (n - full), "line")]

    def meter(label, share, color, first, second, right, extra=()):
        row = [(f"{label:<6}", "gray")] + bar(share, color) + [("  ", "")]
        row += [(f"{first:<20}", "")] + second
        size = sum(len(t) for t, _ in right)
        for text, style, *soft in extra:  # fill the gap in the middle; whole items only, unless marked soft
            room = iw_top - sum(len(t) for t, _ in row) - size - 3
            if len(text) > room and not (soft and room >= 12):
                break
            row.append((clip(text, room), style))
        gap = iw_top - sum(len(t) for t, _ in row) - size
        return row + [(" " * gap, "")] + right if gap >= 2 else row

    iw_top = W - 6
    hist = [("┃", "green" if state(r) == "success" else "red" if state(r) in FAILED else "gray") for r in done[:30][::-1]]
    pace = sum(1 for p in merged if ts(p.get("mergedAt")) and (now - ts(p["mergedAt"])).total_seconds() < 21600) / 6
    pending = n_open + len(taken)
    eta = span(pending / pace * 3600) if pace else "-"
    meters = [meter("runs", rate, "green", f"{rate * 100:.0f}% of last {len(last20)}",
                    [("24h ok ", "gray"), (f"{day_ok * 100:.0f}%", "b_green" if day_ok >= 0.8 else "b_red"),
                     (f"   last wave {age(wave['createdAt']) if wave else '-'}", "gray")],
                    [("last 30  ", "gray")] + hist,
                    [(f"   issues {len(issues)}", "magenta" if issues else "gray"),
                     (f"   PRs {len(prs)} ({sum(1 for p in prs if p.get('isDraft'))} draft)", "gray"),
                     (f"   median {span(median)}", "gray"),
                     (f"   {len(failed_day)} failed / {len(runs_day)} runs 24h", "red" if failed_day else "gray")])]
    load = {}
    for t in taken:
        load[t["agent"] or "-"] = load.get(t["agent"] or "-", 0) + 1
    nxt = next((t for t in todo if t["status"] == "todo"), None)
    if todo:
        share = n_done / n_all if n_all else 0.0
        meters.append(meter("tasks", share, "cyan", f"{n_done}/{n_all} done ({share * 100:.0f}%)",
                            [(f"{len(taken)} taken", "b_yellow"), (f"   {n_open} open", "gray")],
                            [(f"pace {pace:.1f}/h  ETA ", "gray"), (eta, "b_cyan")],
                            [(f"   {len(merged_day)} merged 24h", "green" if merged_day else "gray"),
                             (f"   {sum(1 for x in prs if any(l['name'] == 'needs-fix' for l in x.get('labels', [])))} needs-fix", "gray"),
                             ("   " + "  ".join(f"{a}:{c}" for a, c in sorted(load.items())) if load else "", "magenta")]))
    if {"runs"} & loading:
        meters = [[("runs  ", "gray"), ("loading…", "dim")]] + ([[("tasks ", "gray"), ("loading…", "dim")]] if {"todo"} & loading else [])
    elif {"todo"} & loading:
        meters.append([("tasks ", "gray"), ("loading…", "dim")])
    if H is None or H >= 24:
        top += box("PIPELINE", "", "cyan", "gray", meters, W - 6, len(meters) + 2)
    else:
        top += [[(" ", "")] + m for m in meters]

    # ---- columns
    cw = (W - 2) // 2
    iw = cw - 4

    def trigger_of(r):
        return {"workflow_run": "chained", "workflow_dispatch": "manual", "pull_request": "PR",
                "workflow_call": "called"}.get(r.get("event"), r.get("event") or "")

    def natural(r):
        start, end = ts(r.get("startedAt") or r.get("createdAt")), ts(r.get("updatedAt"))
        took = span((end - start).total_seconds()) if start and end else ""
        return [r.get("workflowName", ""), mid(r.get("headBranch") or "", 24), trigger_of(r),
                (r.get("headSha") or "")[:7], took, (age(r["updatedAt"]) if end else "")]

    wide_w = [max([len(natural(r)[i]) for r in done[:RECENT] + live] or [1]) for i in range(6)]
    spare = max(iw - 2 - sum(wide_w), 5)
    gaps = [spare // 5 + (1 if i < spare % 5 else 0) for i in range(5)]  # near-equal gaps between the six columns

    def run_row(r, running):
        s = state(r)
        sym, col = SYM.get(s, ("•", "gray"))
        start, end = ts(r.get("startedAt") or r.get("createdAt")), ts(r.get("updatedAt"))
        if running:
            when = ("run " if s == "in_progress" else "wait ") + span((now - start).total_seconds())
        else:
            when = (span((end - start).total_seconds()) + " · " if start and end else "") + (age(r["updatedAt"]) if end else "")
        name_style = "fail" if r["databaseId"] in alarm_ids else ("" if s != "failure" else "red")
        mark = (sym, "fail" if r["databaseId"] in alarm_ids else col, 1)
        branch = (mid(r.get("headBranch") or "", 14), "gray", 14)
        if iw >= 70:  # wide card: columns keep their natural width and the spare space becomes even gaps
            took, _, ago = when.partition(" · ")
            if not ago:
                took, ago = "", when
            cols = [(r.get("workflowName", ""), name_style), (mid(r.get("headBranch") or "", 24), "gray"),
                    (trigger_of(r), "magenta"), ((r.get("headSha") or "")[:7], "blue"), (took, "dim"), (ago, "dim")]
            row = [(mark[0], mark[1])]
            for i, (text, style) in enumerate(cols):
                row += [(" " * gaps[i - 1], "")] if i else [(" ", "")]
                row.append((text.rjust(wide_w[i]) if i >= 4 else text.ljust(wide_w[i]), style))
            return row
        return cells(iw, mark, (r.get("workflowName", ""), name_style, None), branch, (when, "dim", 20, "r"))

    def detail(text, col):
        return [("  - ", "dim"), (clip(text, iw - 4), col)]

    def failed_rows(r):
        """Two lines per failure: what failed and when, then the failing job and step underneath."""
        alarm = r["databaseId"] in alarm_ids
        start, end = ts(r.get("startedAt") or r.get("createdAt")), ts(r.get("updatedAt"))
        ran = span((end - start).total_seconds()) if start and end else ""
        rows = [cells(iw, ("x", "fail" if alarm else "b_red", 1), (r.get("workflowName", ""), "fail" if alarm else "b_white", None),
                      (mid(r.get("headBranch") or "", 14), "gray", 14), (f"ran {ran}", "dim", 10, "r"),
                      (age(r["updatedAt"]) if end else "", "red", 12, "r"))]
        job, step = failure_parts(jobs.get(r["databaseId"]) or [])
        if job:
            room = iw - 4 - len(job) - 3
            tail = [(" › ", "dim"), (clip(step, room), "gray")] if step and room > 6 else []
            rows.append([("  └ ", "dim"), (clip(job, iw - 4), "red")] + tail)
        return rows

    titles = {t["id"]: t["title"] for t in todo}
    task_id = re.compile(r"\b[A-Z]+-(?:\d{8}-)?\d+\b")

    shown = []

    def trigger_name(r):
        return trigger_of(r)

    def generic_rows(r, js):
        """Two lines in the same style as a swarm worker: the workflow and its commit title, then
        branch, trigger, commit, the job that runs now with its step, and how long it has run."""
        live_jobs = [j for j in js if j.get("status") == "in_progress"]
        job = live_jobs[0] if live_jobs else None
        step = next((st.get("name", "") for st in (job or {}).get("steps", []) if st.get("status") == "in_progress"), "")
        t0 = ts((job or {}).get("startedAt") or r.get("startedAt") or r.get("createdAt"))
        took = ("run " if job else "wait ") + span((now - t0).total_seconds()) if t0 else ""
        title = r.get("displayTitle") or ""
        title = "" if title == r.get("workflowName") else title
        name = r.get("workflowName", "")
        head = [("  > " if job else "  ~ ", "b_yellow"), (name + "  ", "b_cyan"),
                (clip(title, max(iw - 6 - len(name), 4)), "b_white")]
        parts = [(r.get("headBranch") or "", "magenta"), (trigger_name(r), "magenta"),
                 ((r.get("headSha") or "")[:7], "blue"),
                 (took, "green"), (": ".join(x for x in ((job or {}).get("name", ""), step) if x), "yellow")]
        if len(live_jobs) > 1:
            parts.insert(4, (f"+{len(live_jobs) - 1} more jobs", "gray"))
        line = [("    ", "")]
        for text, style in [x for x in parts if x[0]]:
            if len(line) > 1:
                line.append((" · ", "gray"))
            line.append((text, style))
        return [head, clipline(line, iw)[0]]

    def with_detail(r, running):
        """A live run gets the same two-line look as every other live run, workers and pipelines alike."""
        if not running:
            return [run_row(r, running)]
        rows = []
        js = jobs.get(r["databaseId"]) or []
        live_jobs = [j for j in js if j.get("status") == "in_progress"]
        for j in live_jobs[:3]:
            m = task_id.search(j.get("name", ""))
            if not m:
                continue
            step = next((st.get("name", "") for st in j.get("steps", []) if st.get("status") == "in_progress"), "")
            t0 = ts(j.get("startedAt"))
            took = span((now - t0).total_seconds()) if t0 else ""
            agent = next((t["agent"] for t in todo if t["id"] == m.group(0) and t["agent"]), "")
            title = titles.get(m.group(0), "(not in TODO.md)")
            rows.append([("  > ", "b_yellow"), (m.group(0) + "  ", "b_cyan"),
                         (clip(title, iw - 6 - len(m.group(0))), "b_white")])
            line = [("    ", "")]
            for text, style in [x for x in [(agent, "magenta"), (step, "yellow"), (took, "green")] if x[0]]:
                if len(line) > 1:
                    line.append((" · ", "gray"))
                line.append((text, style))
            rows.append(clipline(line, iw)[0])
        if not rows:
            rows = generic_rows(r, js)  # no task id in the job names: a pipeline run such as Delivery
        if shown:
            rows.insert(0, [("┄" * iw, "gray")])  # divider between live runs
        shown.append(1)
        return rows

    flat = lambda rs, running: [row for r in rs for row in with_detail(r, running)]
    labels = lambda x: ",".join(l["name"] for l in x.get("labels", []) if l["name"] in ("needs-human", "needs-fix"))
    left = [dict(title="In Progress", total=len(live), col="yellow", border="yellow" if live else "gray", prio=0,
                 items=flat(live, True)),
            dict(title="Recent Runs", total=len(done), col="cyan", border="gray", prio=2,
                 items=[run_row(r, False) for r in done[:RECENT]]),
            dict(title="Failed 24h", total=len(failed_day), col="red", border="red" if failed_day else "gray", prio=1,
                 items=[row for r in failed_day for row in failed_rows(r)])]
    prefix = re.compile(r"^([A-Z]+-(?:\d{8}-)?\d+)(:)(.*)$")

    def merged_row(p):
        """Merged PR line: number in blue, task ID in teal, bot bumps in lavender, the rest in plain text."""
        room = iw - 1 - 1 - 5 - 1 - 1 - 10
        title = clip(p["title"], room)
        m = prefix.match(title)
        if m:
            body = [(m.group(1), "b_cyan"), (m.group(2), "gray"), (m.group(3), "")]
        elif title.startswith("Bump "):
            body = [("Bump ", "magenta"), (title[5:], "dim")]
        else:
            body = [(title, "")]
        used = sum(len(t) for t, _ in body)
        return ([("+", "green"), (" ", ""), (f"#{p['number']}".ljust(5), "blue"), (" ", "")] + body
                + [(" " * (room - used + 1), ""), (age(p["mergedAt"]).rjust(10), "dim")])

    # The id column is as wide as the longest id on screen, at most ID_MAX: one very long id used to
    # pad every row and push the titles to the right edge, where they were cut off.
    ID_MAX = 20
    idw = min(max([len(t["id"]) for t in taken[:12]] or [8]), ID_MAX)
    queue = sorted((t for t in todo if t["status"] == "todo"), key=lambda t: PRIORITY_RANK[priority_of(t)])
    mark = {"high": ("H", "b_red"), "low": ("L", "dim")}
    qw = min(max([len(t["id"]) for t in queue[:12]] or [8]), ID_MAX)
    right = [dict(title="Swarm Tasks Taken", total=len(taken), col="cyan", border="gray", prio=2,
                  items=[cells(iw, (">", "yellow", 1), (t["agent"] or "-", "magenta", 9), (t["id"], "b_cyan", idw), (t["title"], "", None))
                         for t in taken]),
             dict(title="Next Up", total=len(queue), col="blue", border="gray", prio=1,
                  items=[cells(iw, mark.get(priority_of(t), ("-", "gray")) + (1,), (t["id"], "b_cyan", qw), (t["title"], "", None))
                         for t in queue[:12]]),
             dict(title="Merged PRs 24h", total=len(merged_day), col="green", border="gray", prio=3,
                  items=[merged_row(p) for p in merged_day]),
             dict(title="Open PRs", total=len(prs), col="blue", border="gray", prio=0,
                  items=[cells(iw, ("d" if p.get("isDraft") else "o", "yellow" if p.get("isDraft") else "green", 1),
                               (f"#{p['number']}", "", 5), (p["title"], "", None), (labels(p), "red", 11)) for p in prs]),
             dict(title="Open Issues", total=len(issues), col="magenta", border="gray", prio=1,
                  items=[cells(iw, ("o", "red" if any(l["name"] == "needs-human" for l in i.get("labels", [])) else "magenta", 1),
                               (f"#{i['number']}", "", 5), (i["title"], "", None), (age(i["createdAt"]), "dim", 10, "r"))
                         for i in issues])]
    for panel, keys in ((left[0], {"runs", "jobs"}), (left[1], {"runs"}), (left[2], {"runs", "jobs"}), (right[0], {"todo", "archive"}),
                        (right[1], {"todo"}), (right[2], {"merged"}), (right[3], {"prs"}), (right[4], {"issues"})):
        if keys & loading:
            panel["loading"] = True
    body = None if H is None else max(H - len(top) - 1, 0)
    right = [right[i] for i in (3, 4, 0, 1, 2)]  # open PRs and issues first
    return top, fit_col(left, body, iw), fit_col(right, body, iw), cw


# ---- once: one static frame ---------------------------------------------------------------------
# Dusty, low-saturation 256-colour palette: soft text, one quiet border grey, calm accents.
THEME = {"white": 252, "gray": 245, "line": 240, "green": 108, "red": 174, "yellow": 180, "blue": 109,
         "magenta": 139, "cyan": 109}
BAR_FG, BAR_BG = 253, 66  # title bar: light text on dusty teal


def ansi_code(style):
    if style == "":
        return f"38;5;{THEME['white']}"
    if style == "dim":
        return f"38;5;{THEME['gray']}"
    if style == "bar":
        return f"1;38;5;{BAR_FG};48;5;{BAR_BG}"
    if style == "fail":
        return f"1;38;5;{THEME['red']}"
    bold, name = ("1;", style[2:]) if style.startswith("b_") else ("", style)
    return f"{bold}38;5;{THEME.get(name, THEME['white'])}"


class AnsiMap(dict):
    def get(self, style, default="0"):
        return ansi_code(style)


ANSI = AnsiMap()


def once():
    W = max(100, min(shutil.get_terminal_size().columns, 160))
    data = fetch()
    for k in data:
        if data[k] is None:
            data[k] = blank(k)
    top, left, right, cw = compose(data, W, None)
    emit = lambda line: "".join(f"\033[{ANSI.get(s, '0')}m{t}\033[0m" for t, s in line)
    print("\n".join(emit(l) for l in top))
    for i in range(max(len(left), len(right))):
        a = left[i] if i < len(left) else []
        b = right[i] if i < len(right) else []
        used = clipline(a, cw)[1]
        print(emit(clipline(a, cw)[0]) + " " * (cw - used) + " " + emit(clipline(b, cw)[0]))


# ---- live TUI ---------------------------------------------------------------------------------------
def swarm_idle(data):
    """True when no Agent swarm run is live and TODO.md still has open tasks (None until both are known)."""
    runs, todo = data.get("runs"), data.get("todo")
    if not runs or not todo:
        return None
    if any(r.get("workflowName") == "Agent swarm" and r.get("status") in LIVE for r in runs):
        return False
    return any(t["status"] == "todo" for t in parse_tasks(todo))


def start_swarm():
    """Dispatch one Agent swarm wave. Returns an empty string on success, else the error text."""
    p = subprocess.run(["gh", "workflow", "run", "swarm.yml", "-f", "now=true"], capture_output=True, text=True)
    return "" if p.returncode == 0 else (p.stderr.strip().splitlines() or ["gh failed"])[-1]


def beep():
    for cmd in (["afplay", "/System/Library/Sounds/Glass.aiff"], ["paplay", "/usr/share/sounds/freedesktop/stereo/complete.oga"]):
        if shutil.which(cmd[0]):
            subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            return
    curses.beep()


def tui(stdscr):
    curses.curs_set(0)
    curses.use_default_colors()
    rich = curses.COLORS >= 256
    base = dict(THEME) if rich else {"green": curses.COLOR_GREEN, "red": curses.COLOR_RED, "yellow": curses.COLOR_YELLOW,
                                      "blue": curses.COLOR_BLUE, "magenta": curses.COLOR_MAGENTA, "cyan": curses.COLOR_CYAN,
                                      "gray": 8 if curses.COLORS >= 16 else curses.COLOR_WHITE, "line": 8 if curses.COLORS >= 16
                                      else curses.COLOR_WHITE, "white": -1}
    pair = {}
    for n, (name, fg) in enumerate(base.items(), 1):
        curses.init_pair(n, fg, -1)
        pair[name] = curses.color_pair(n)
    curses.init_pair(40, BAR_FG if rich else curses.COLOR_BLACK, BAR_BG if rich else curses.COLOR_CYAN)
    attr = {"": pair["white"], "dim": pair["gray"], "title": pair["cyan"] | curses.A_BOLD,
            "bar": curses.color_pair(40) | curses.A_BOLD, "fail": pair["red"] | curses.A_BOLD}
    for name in base:
        attr[name] = pair[name]
        attr["b_" + name] = pair[name] | curses.A_BOLD

    st = {"data": {k: blank(k) for k in [*CALLS, "jobs", "churn"]}, "busy": False, "at": 0.0, "loaded": set()}
    lock = threading.Lock()

    def part(got):  # a section arrived: show it now
        with lock:
            for k, v in got.items():
                if v is not None:
                    st["data"][k] = v
                    st["loaded"].add(k)
            st["at"] = time.time()

    def refresh():
        fetch(part, full=False)
        with lock:
            st["busy"] = False
            st["loaded"] |= set(st["data"])  # a section that failed shows empty instead of loading forever

    def kick():
        with lock:
            if st["busy"]:
                return
            st["busy"] = True
        threading.Thread(target=refresh, daemon=True).start()

    seen, alarm, checked_at, last = None, None, 0.0, None
    started_at, started_note, muted = 0.0, "", False
    autostart = bool(AUTOSTART)
    autostart_wait = AUTOSTART or 10
    stdscr.timeout(200)
    kick()
    while True:
        key = stdscr.getch()
        if key not in (-1, curses.KEY_RESIZE):
            if alarm:
                alarm = None  # any key silences the alarm and does nothing else
            elif key in (ord("q"), ord("Q"), 27):
                return
            elif key in (ord("r"), ord("R")):
                kick()
            elif key in (ord("m"), ord("M")):
                muted = not muted  # the alarm still shows; only the sound stops
            elif key in (ord("s"), ord("S")):
                autostart = not autostart
        if st["at"] and time.time() - st["at"] >= INTERVAL:
            kick()
        with lock:
            data = {k: (v if v is not None else blank(k)) for k, v in st["data"].items()}
            busy, at = st["busy"], st["at"]
            loading = frozenset(k for k in st["data"] if k not in st["loaded"]) - {"repo", "runs_more"}
            if len(st["loaded"]) * 4 >= len(st["data"]) * 3:
                st["ready"] = True  # three quarters of the sections are in: draw the dashboard from now on
            ready = st.get("ready", False)
        now = time.time()
        if at and at != checked_at and "runs" in st["loaded"]:  # new data: look for runs that failed since the last look
            checked_at = at
            failed_now = {r["databaseId"]: r for r in data["runs"]
                          if r.get("status") == "completed" and state(r) in FAILED}
            if seen is not None:
                fresh = [r for i, r in failed_now.items() if i not in seen]
                if fresh:
                    text = f"{fresh[0].get('workflowName', '')} {fresh[0].get('headBranch') or ''}".strip()
                    text += f" (+{len(fresh) - 1} more)" if len(fresh) > 1 else ""
                    ids = (alarm["ids"] if alarm else set()) | {r["databaseId"] for r in fresh}
                    alarm = {"ids": ids, "text": text, "next": now}
            seen = set(failed_now) | (seen or set())
            if autostart and swarm_idle(data) and now - started_at >= autostart_wait * 60:
                err = start_swarm()
                started_at = now
                started_note = (f"swarm start failed: {err}" if err else
                                f"swarm was idle, started a wave at {datetime.now().strftime('%H:%M')}")
        if alarm and now >= alarm["next"]:
            if not muted:
                beep()
            alarm["next"] = now + 5
        H, W = stdscr.getmaxyx()
        phase = int(now * 3) % 2 if alarm else 0
        sig = (H, W, at, busy, phase, bool(alarm), int(now // 5), started_note, len(loading), ready, muted, autostart)
        if sig == last:
            continue
        last = sig
        stdscr.erase()
        if not ready:  # plain black screen until 75% of the sections have loaded
            stdscr.addstr(0, 1, "Loading...")
            stdscr.refresh()
            continue
        ids = alarm["ids"] if alarm else frozenset()
        top, left, right, cw = compose(data, max(W, 80), H, ids, busy, loading)

        def put(y, x, line, limit):
            for text, style in clipline(line, limit)[0]:
                a = attr.get(style, 0)
                if style == "fail" and phase:
                    a = pair["red"] | curses.A_REVERSE | curses.A_BOLD  # flicker
                try:
                    stdscr.addstr(y, x, text, a)
                except curses.error:
                    pass
                x += len(text)

        for y, line in enumerate(top):
            put(y, 0, line, W - 1)
        for i in range(max(len(left), len(right))):
            y = len(top) + i
            if y >= H - 1:
                break
            if i < len(left):
                put(y, 0, left[i], cw)
            if i < len(right):
                put(y, cw + 1, right[i], min(cw, W - cw - 2))
        if alarm:
            msg = f" !! NEW FAILED RUN: {alarm['text']}  -  press ANY KEY to silence the alarm ({'muted' if muted else 'beeps every 5s'}) "
            a = pair["red"] | curses.A_BOLD | (curses.A_REVERSE if phase else 0)
            stdscr.addstr(H - 1, 0, msg.ljust(W - 1)[: W - 1], a)
        else:
            status = (f"loading {len(st['data']) - len(loading)}/{len(st['data'])}..." if loading else "refreshing..." if busy
                      else f"updated {span(time.time() - at)} ago")
            auto_status = f"on ({autostart_wait}m)" if autostart else "off"
            foot = f" q quit · r refresh · m {'unmute' if muted else 'mute'}{' (MUTED)' if muted else ''} · s auto-start {auto_status}"
            if autostart and started_note:
                foot += f" · {started_note}"
            foot += f" · every {INTERVAL}s · {status}"
            stdscr.addstr(H - 1, 0, foot[: W - 1], attr["dim"])
        stdscr.refresh()


if ONCE:
    once()
else:
    curses.wrapper(tui)
