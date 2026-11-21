import curses, json, re, shutil, subprocess, sys, threading, time
from datetime import datetime, timezone

INTERVAL, RECENT, ONCE = int(sys.argv[1]), int(sys.argv[2]), sys.argv[3] == "1"
FAILED = ("failure", "timed_out")
FIELDS = "databaseId,workflowName,conclusion,status,headSha,headBranch,event,createdAt,startedAt,updatedAt,url"
TEXT = ("repo", "todo", "archive")  # plain-text results; everything else is JSON
CALLS = {
    "repo": ["repo", "view", "--json", "nameWithOwner", "-q", ".nameWithOwner"],
    "issues": ["issue", "list", "--state", "open", "--limit", "40", "--json", "number,title,labels,createdAt"],
    "prs": ["pr", "list", "--state", "open", "--limit", "40", "--json", "number,title,isDraft,labels,headRefName,createdAt"],
    "merged": ["pr", "list", "--state", "merged", "--limit", "50", "--json", "number,title,mergedAt"],
    "closed": ["pr", "list", "--state", "closed", "--limit", "30", "--json", "number,title,closedAt,mergedAt"],
    "runs": ["run", "list", "--limit", "100", "--json", FIELDS],
    "todo": ["api", "repos/{owner}/{repo}/contents/TODO.md", "-H", "Accept: application/vnd.github.raw"],
    "archive": ["api", "repos/{owner}/{repo}/contents/docs/archive/TODO-DONE.md", "-H", "Accept: application/vnd.github.raw"],
}


# ---- data ---------------------------------------------------------------------------------
def spawn(args):
    return subprocess.Popen(["gh", *args], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)


def collect(procs, text_keys=()):
    data = {}
    for k, p in procs.items():
        try:
            out, _ = p.communicate(timeout=60)
            data[k] = out if k in text_keys else json.loads(out or "[]")
        except Exception:
            p.kill()
            data[k] = None  # None: keep the previous value
    return data


def fetch():
    data = collect({k: spawn(a) for k, a in CALLS.items()}, TEXT)
    if data.get("repo"):
        data["repo"] = data["repo"].strip()
    # Second wave: job detail for the runs that are live or recently failed.
    runs = data.get("runs") or []
    now = datetime.now(timezone.utc)
    want = [r["databaseId"] for r in runs if r.get("status") != "completed"][:6]
    want += [r["databaseId"] for r in runs if r.get("status") == "completed" and state(r) in FAILED
             and r.get("updatedAt") and (now - ts(r["updatedAt"])).total_seconds() < 86400][:6]
    jobs = collect({i: spawn(["run", "view", str(i), "--json", "jobs"]) for i in want})
    data["jobs"] = {i: (j or {}).get("jobs", []) for i, j in jobs.items() if isinstance(j, dict) or j is None}
    return data


def blank(k):
    return "" if k in TEXT else {} if k == "jobs" else []


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
                   "title": m.group(3), "agent": ""}
            tasks.append(cur)
        elif cur is not None and line.startswith("  - agent:"):
            cur["agent"] = line.split(":", 1)[1].strip()
        elif cur is not None and line and not line.startswith(" "):
            cur = None
    return tasks


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
        if not items:
            rows = [[("(none)", "dim")]]
        elif len(items) <= show[i]:
            rows = list(items)
        else:
            rows = items[: show[i] - 1] + [[(f"... +{len(items) - show[i] + 1} more", "dim")]]
        lines += box(p["title"], p["total"], p["col"], p["border"], rows, iw, 2 + len(rows) + pad[i])
    return lines


def compose(data, W, H, alarm_ids=frozenset(), busy=False):
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
    todo = parse_tasks(data.get("todo"))
    n_done = sum(1 for t in todo if t["status"] == "done") + len(re.findall(r"^- \[x\] ", data.get("archive") or "", re.M))
    n_open = sum(1 for t in todo if t["status"] == "todo")
    taken = [t for t in todo if t["status"] == "taken"]
    n_all = n_done + n_open + len(taken)
    hot = sum(1 for x in issues + prs for l in x.get("labels", []) if l["name"] == "needs-human")
    day_ok = (len(runs_day) - len(failed_day)) / len(runs_day) if runs_day else 1.0

    # ---- top: title bar, stat cards, progress lines
    stamp = datetime.now().strftime("%a %b %d  %H:%M:%S") + ("  *" if busy else "   ")
    title = f" {data['repo'] or 'repo'}  ·  swarm monitor"
    top = [[(title + " " * max(W - 2 - len(title) - len(stamp) - 1, 1) + stamp + " ", "bar")]]
    cards = [("MAIN", f"{msym} {mstate}", "b_" + mcol, age(main["updatedAt"]) if main else "-"),
             ("RUNNING", str(len(live)), "b_yellow" if live else "gray", "active runs"),
             ("MERGED 24h", str(len(merged_day)), "b_green" if merged_day else "gray", "pull requests"),
             ("FAILED 24h", str(len(failed_day)), "b_red" if failed_day else "b_green", f"of {len(runs_day)} runs"),
             ("CLOSED 24h", str(len(closed_day)), "b_yellow" if closed_day else "gray", "not merged"),
             ("MEDIAN RUN", span(median), "b_cyan", f"{len(durs)} runs"),
             ("NEEDS HUMAN", str(hot), "b_red" if hot else "b_green", "issues, PRs")]
    if (H is None or H >= 24) and W >= 121:
        spare = W - 2 - len(cards) * 17  # share leftover width out so the cards reach the right edge
        rows = [[], [], [], []]
        for n, (label, value, style, sub) in enumerate(cards):
            w = 16 + spare // len(cards) + (1 if n < spare % len(cards) else 0)
            inner = w - 2
            rows[0] += [("╭" + f"─ {label} ".ljust(inner, "─") + "╮ ", "gray")]
            rows[1] += [("│", "gray"), ((" " + value).ljust(inner)[:inner], style), ("│ ", "gray")]
            rows[2] += [("│", "gray"), ((" " + sub).ljust(inner)[:inner], "dim"), ("│ ", "gray")]
            rows[3] += [("╰" + "─" * inner + "╯ ", "gray")]
        top += [[(" ", "")] + r for r in rows]
    else:
        line = [(" ", "")]
        for label, value, style, sub in cards:
            line += [(label.lower() + " ", "gray"), (value, style), ("   ", "")]
        top.append(line)
    # ---- pipeline card: two meter rows on one grid (label, bar, figures, flush-right extras)
    def bar(share, color, n=22):
        full = round(share * n)  # single-width box-drawing glyphs only, so every terminal draws the same width
        return [("━" * full, color), ("╌" * (n - full), "gray")]

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
    nxt = next((t for t in todo if t["status"] == "todo"), None)
    if todo:
        share = n_done / n_all if n_all else 0.0
        meters.append(meter("tasks", share, "cyan", f"{n_done}/{n_all} done ({share * 100:.0f}%)",
                            [(f"{len(taken)} taken", "b_yellow"), (f"   {n_open} open", "gray")],
                            [(f"pace {pace:.1f}/h  ETA ", "gray"), (eta, "b_cyan")],
                            [(f"   next: {nxt['id']} {nxt['title']}", "", True)] if nxt else []))
    if H is None or H >= 24:
        top += box("PIPELINE", "", "cyan", "gray", meters, W - 6, len(meters) + 2)
    else:
        top += [[(" ", "")] + m for m in meters]

    # ---- columns
    cw = (W - 2) // 2
    iw = cw - 4

    def run_row(r, running):
        s = state(r)
        sym, col = SYM.get(s, ("•", "gray"))
        start, end = ts(r.get("startedAt") or r.get("createdAt")), ts(r.get("updatedAt"))
        if running:
            when = ("run " if s == "in_progress" else "wait ") + span((now - start).total_seconds())
        else:
            when = (span((end - start).total_seconds()) + " · " if start and end else "") + (age(r["updatedAt"]) if end else "")
        name_style = "fail" if r["databaseId"] in alarm_ids else ("" if s != "failure" else "red")
        return cells(iw, (sym, "fail" if r["databaseId"] in alarm_ids else col, 1),
                     (r.get("workflowName", ""), name_style, None),
                     (mid(r.get("headBranch") or "", 14), "gray", 14), (when, "dim", 20, "r"))

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

    def with_detail(r, running):
        rows = [run_row(r, running)]
        js = jobs.get(r["databaseId"]) or []
        text = job_summary(js, now) if running else ""
        if js and text:
            rows.append(detail(text, "dim"))
        return rows

    flat = lambda rs, running: [row for r in rs for row in with_detail(r, running)]
    labels = lambda x: ",".join(l["name"] for l in x.get("labels", []) if l["name"] in ("needs-human", "needs-fix"))
    left = [dict(title="In Progress", total=len(live), col="yellow", border="yellow" if live else "gray", prio=0,
                 items=flat(live, True)),
            dict(title="Recent Runs", total=len(done), col="cyan", border="gray", prio=2,
                 items=[run_row(r, False) for r in done[:RECENT]]),
            dict(title="Failed 24h", total=len(failed_day), col="red", border="red" if failed_day else "gray", prio=1,
                 items=[row for r in failed_day for row in failed_rows(r)])]
    right = [dict(title="Swarm Tasks Taken", total=len(taken), col="cyan", border="gray", prio=2,
                  items=[cells(iw, (">", "yellow", 1), (t["agent"] or "-", "gray", 9), (t["id"], "", 18), (t["title"], "", None))
                         for t in taken]),
             dict(title="Merged PRs 24h", total=len(merged_day), col="green", border="gray", prio=3,
                  items=[cells(iw, ("+", "green", 1), (f"#{p['number']}", "", 5), (p["title"], "", None),
                               (age(p["mergedAt"]), "dim", 10, "r")) for p in merged_day]),
             dict(title="Open PRs", total=len(prs), col="blue", border="gray", prio=0,
                  items=[cells(iw, ("d" if p.get("isDraft") else "o", "yellow" if p.get("isDraft") else "green", 1),
                               (f"#{p['number']}", "", 5), (p["title"], "", None), (labels(p), "red", 11)) for p in prs]),
             dict(title="Open Issues", total=len(issues), col="magenta", border="gray", prio=1,
                  items=[cells(iw, ("o", "red" if any(l["name"] == "needs-human" for l in i.get("labels", [])) else "magenta", 1),
                               (f"#{i['number']}", "", 5), (i["title"], "", None), (age(i["createdAt"]), "dim", 10, "r"))
                         for i in issues])]
    body = None if H is None else max(H - len(top) - 1, 0)
    return top, fit_col(left, body, iw), fit_col(right, body, iw), cw


# ---- once: one static frame ---------------------------------------------------------------------
ANSI = {"": "0", "title": "1;36", "bar": "1;7;36", "fail": "1;31", "green": "32", "red": "31", "yellow": "33",
        "blue": "34", "magenta": "35", "cyan": "36", "gray": "90", "dim": "2", "b_green": "1;32", "b_red": "1;31",
        "b_yellow": "1;33", "b_cyan": "1;36", "b_blue": "1;34", "b_magenta": "1;35", "b_gray": "1;90", "b_white": "1"}


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
def beep():
    for cmd in (["afplay", "/System/Library/Sounds/Glass.aiff"], ["paplay", "/usr/share/sounds/freedesktop/stereo/complete.oga"]):
        if shutil.which(cmd[0]):
            subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            return
    curses.beep()


def tui(stdscr):
    curses.curs_set(0)
    curses.use_default_colors()
    base = {"green": curses.COLOR_GREEN, "red": curses.COLOR_RED, "yellow": curses.COLOR_YELLOW,
            "blue": curses.COLOR_BLUE, "magenta": curses.COLOR_MAGENTA, "cyan": curses.COLOR_CYAN,
            "gray": 8 if curses.COLORS >= 16 else curses.COLOR_WHITE, "white": -1}
    pair = {}
    for n, (name, fg) in enumerate(base.items(), 1):
        curses.init_pair(n, fg, -1)
        pair[name] = curses.color_pair(n)
    attr = {"": 0, "dim": pair["gray"], "title": pair["cyan"] | curses.A_BOLD,
            "bar": pair["cyan"] | curses.A_BOLD | curses.A_REVERSE, "fail": pair["red"] | curses.A_BOLD}
    for name in base:
        attr[name] = pair[name]
        attr["b_" + name] = pair[name] | curses.A_BOLD

    st = {"data": {k: blank(k) for k in [*CALLS, "jobs"]}, "busy": False, "at": 0.0}
    lock = threading.Lock()

    def refresh():
        fresh = fetch()
        with lock:
            for k, v in fresh.items():
                if v is not None:
                    st["data"][k] = v
            st["busy"], st["at"] = False, time.time()

    def kick():
        with lock:
            if st["busy"]:
                return
            st["busy"] = True
        threading.Thread(target=refresh, daemon=True).start()

    seen, alarm, checked_at, last = None, None, 0.0, None
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
        if st["at"] and time.time() - st["at"] >= INTERVAL:
            kick()
        with lock:
            data = {k: (v if v is not None else blank(k)) for k, v in st["data"].items()}
            busy, at = st["busy"], st["at"]
        now = time.time()
        if at and at != checked_at:  # new data: look for runs that failed since the last look
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
        if alarm and now >= alarm["next"]:
            beep()
            alarm["next"] = now + 5
        H, W = stdscr.getmaxyx()
        phase = int(now * 3) % 2 if alarm else 0
        sig = (H, W, at, busy, phase, bool(alarm), int(now // 5))
        if sig == last:
            continue
        last = sig
        stdscr.erase()
        if not at:
            stdscr.addstr(0, 1, "Loading...")
            stdscr.refresh()
            continue
        ids = alarm["ids"] if alarm else frozenset()
        top, left, right, cw = compose(data, max(W, 80), H, ids, busy)

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
            msg = f" !! NEW FAILED RUN: {alarm['text']}  -  press ANY KEY to silence the alarm (beeps every 5s) "
            a = pair["red"] | curses.A_BOLD | (curses.A_REVERSE if phase else 0)
            stdscr.addstr(H - 1, 0, msg.ljust(W - 1)[: W - 1], a)
        else:
            status = "refreshing..." if busy else f"updated {span(time.time() - at)} ago"
            foot = f" q quit · r refresh · every {INTERVAL}s · {status}"
            stdscr.addstr(H - 1, 0, foot[: W - 1], attr["dim"])
        stdscr.refresh()


if ONCE:
    once()
else:
    curses.wrapper(tui)
