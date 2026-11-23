- [x] COVPY-MONITOR-TUI · Script tests: monitor tool coverage, tui part (to 100% together with the other three parts)
  - pr: #420
  - attempts: 1
  - requirements: OPS-06
  - scope: scripts/tests/test_monitor_tui_coverage.py
  - details:
    Historical brief. Use the recorded evidence and PR to locate the delivered change before starting a follow-up.
    Current reference points to verify against that delivery are `scripts/monitor.py`, `scripts/monitor.sh`.

    1. Read `scripts/monitor.py` and `scripts/monitor.sh`. The monitor has no tests today (0% of 606 statements). Four tasks split it; this one covers the curses loop: `tui`, `part`, `refresh`, the loading screen, key handling, the clock and the alarm beeps; fake `curses` with a stub screen object (roughly lines 600-829). Another task owns each other part, so cover only your part's lines.
    2. Measure first: `pip install --break-system-packages coverage` if it is missing, then `python3 -m coverage run --branch --source=scripts -m unittest discover -s scripts/tests` and `python3 -m coverage report -m --include=scripts/monitor.py`. The lines listed below were uncovered on 2026-11-21; other tasks may have changed the file since, so use the report, not the list. Because four test files share the module, report the lines of your part that are covered by your file alone: run your file alone with `python3 -I -m coverage run --branch --source=scripts -m unittest scripts/tests/test_monitor_tui_coverage.py`-style discovery (`discover -s scripts/tests -p "test_monitor_tui_coverage.py"`).
    3. Write only new test code in the single new test file named in `scope`: no change to the script under test, to any existing test, and no `# pragma: no cover`. Test behaviour through the script's public functions and command line with temporary directories, mocked `gh` and subprocess calls and synthetic data; never call the network. Cover every line and every branch (both outcomes of each `if`, each `except`, each loop with zero and many items). If a line is unreachable by any input, do not edit the script: list the line and why under `## Why this is not finished` so a person can remove it. Fake the `gh` calls with canned JSON, fake time, and give curses a stub window that records `addstr` calls; never start a real terminal.
    4. Paste the coverage lines for your part under `## Coverage` in your notes.
    If you cannot finish because something you need is missing, write what is missing under `## Why this is not finished` in your notes and stop; do not guess.
    Before you start read the "Instructions for every agent" in tasks/_head.md. Finish with the check in step 6 there and paste the result into `.agent/pr-notes/<task-id>.md`. Use synthetic data only; do NOT add a dependency or change files outside `scope`.

