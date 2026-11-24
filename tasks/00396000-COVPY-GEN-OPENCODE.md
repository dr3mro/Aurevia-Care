- [x] COVPY-GEN-OPENCODE · Script tests: reach 100% line and branch coverage of `scripts/gen-opencode.py`
  - evidence: PR #671 merged to main (reconciled 2026-11-23)
  - pr: #671
  - attempts: 1
  - requirements: OPS-06
  - scope: scripts/tests/test_gen_opencode_coverage.py
  - depends: WQUALITY-05
  - details:
    Historical brief. Use the recorded evidence and PR to locate the delivered change before starting a follow-up.
    Current reference points to verify against that delivery are `scripts/gen-opencode.py`.

    1. Read `scripts/gen-opencode.py` completely and the existing tests for it under `scripts/tests`.
    2. Measure first: `pip install --break-system-packages coverage` if it is missing, then `python3 -m coverage run --branch --source=scripts -m unittest discover -s scripts/tests` and `python3 -m coverage report -m --include=scripts/gen-opencode.py`. The lines listed below were uncovered on 2026-11-21; other tasks may have changed the file since, so use the report, not the list. Uncovered then: lines 124-128, 133.
    3. Write only new test code in the single new test file named in `scope`: no change to the script under test, to any existing test, and no `# pragma: no cover`. Test behaviour through the script's public functions and command line with temporary directories, mocked `gh` and subprocess calls and synthetic data; never call the network. Cover every line and every branch (both outcomes of each `if`, each `except`, each loop with zero and many items). If a line is unreachable by any input, do not edit the script: list the line and why under `## Why this is not finished` so a person can remove it.
    4. Run `python3 -I -m unittest discover -s scripts/tests` and then the coverage report; paste the final report line for `scripts/gen-opencode.py` (it must read 100%) under `## Coverage` in your notes.
    If you cannot finish because something you need is missing, write what is missing under `## Why this is not finished` in your notes and stop; do not guess.
    Before you start read the "Instructions for every agent" in tasks/_head.md. Finish with the check in step 6 there and paste the result into `docs/pr/notes/<task-id>.md`. Use synthetic data only; do NOT add a dependency or change files outside `scope`.

