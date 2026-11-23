- [x] COVPY-SWARM-WORKERS · Script tests: swarm.py coverage, workers part (to 100% together with the other parts)
  - evidence: merged on main 352ae2ae (#906)
  - agent: swarm-9
  - attempts: 3
  - requirements: OPS-06
  - scope: scripts/tests/test_swarm_workers_coverage.py
  - depends: WFAUDIT-02, WFAUDIT-06, WQUALITY-01, WQUALITY-02
  - details:
    Historical brief. Use the recorded evidence and PR to locate the delivered change before starting a follow-up.
    Current reference points to verify against that delivery are `scripts/swarm.py`.

    1. Read `scripts/swarm.py` and `scripts/tests/test_swarm.py`, `test_swarm_issues.py`, `test_swarm_monitor.py` for the fixtures (`FakeGh` and friends). Five tasks split the file; this one covers lines 1250-1850: `plan_workers`, publish, instruction reconciler, notes files and repair planning. Cover only the uncovered lines in that range.
    2. Measure first: `pip install --break-system-packages coverage` if it is missing, then `python3 -m coverage run --branch --source=scripts -m unittest discover -s scripts/tests` and `python3 -m coverage report -m --include=scripts/swarm.py`. The lines listed below were uncovered on 2026-11-21; other tasks may have changed the file since, so use the report, not the list.
    3. Write only new test code in the single new test file named in `scope`: no change to the script under test, to any existing test, and no `# pragma: no cover`. Test behaviour through the script's public functions and command line with temporary directories, mocked `gh` and subprocess calls and synthetic data; never call the network. Cover every line and every branch (both outcomes of each `if`, each `except`, each loop with zero and many items). If a line is unreachable by any input, do not edit the script: list the line and why under `## Why this is not finished` so a person can remove it. Reuse the existing fake `gh` approach by copying the small helper into your file (do not import from another test file).
    4. Paste the coverage lines of your range under `## Coverage` in your notes.
    If you cannot finish because something you need is missing, write what is missing under `## Why this is not finished` in your notes and stop; do not guess.
    Before you start read the "Instructions for every agent" in tasks/_head.md. Finish with the check in step 6 there and paste the result into `.agent/pr-notes/<task-id>.md`. Use synthetic data only; do NOT add a dependency or change files outside `scope`.

