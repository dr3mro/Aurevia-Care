- [-] NOTE-REVISION-PARSING · Accept one canonical revision with trailing prose and validate notes locally
  - agent: orca
  - attempts: 0
  - requirements: OPS-06
  - scope: scripts/swarm_gate.py, scripts/tests/test_swarm_gate.py, scripts/tests/test_quality_gate.py, scripts/quality-gate.sh, tasks/01355000-NOTE-REVISION-PARSING.md
  - details:
    Owner decision: relax the canonical `Tested revision:` extraction so a line with
    exactly one 40-hex SHA plus trailing prose is accepted, while still requiring exactly
    one canonical line, exactly one revision token on it, and the full trusted ancestry
    proof. Add a local `validate-note` subcommand and wire it into the worker's pre-PR
    `quality-gate.sh` so a malformed line is caught before push (PR #1815 root cause:
    `Tested revision: 8b61497c… (`git rev-parse HEAD` …)` extracted no revision).
    Invariants: no threshold, exclusion or ancestry rule weakened; a second SHA on the
    line, a second canonical line, a missing or malformed SHA and a diverged note all
    still fail closed. No workflow change.
    Required plan-review corrections (adopted):
    - Ownership: this branch is based on main 47cfd5d6, where the earlier
      `SWARM-GATE-TASK-EVIDENCE` change (task 01329500) is already present in
      scripts/swarm_gate.py and its tests; no parallel edit is being made. The
      stale `[-]` and `[!]` tracker marks are recorded in the note Findings and
      are not edited here.
    - quality-gate.sh: the note check is proved by a text assertion and a
      behavioural stub test showing a non-zero note check stops the gate before
      check-verdicts.py. The script header names the third mandatory check.
      Worker notes always carry a canonical revision, including docs-only work;
      this check stays offline and does not call the compare API or read a token.
    - CLI contract: `validate-note --note-path PATH` exits 0 with
      `Swarm gate: note revision valid: <sha>`, and 2 with an actionable stderr
      reason for a malformed or unreadable note. `--event-path` is now optional
      at argparse level and guarded inside `evaluate`/`refresh-event`, so a
      missing path returns 2 instead of raising SystemExit.
    - Trust boundary: validate-note is offline, shape-only and advisory. It
      never proves ancestry or marks a check passed; ancestry stays exclusively
      in evaluate() through the trusted compare API.
    - Proving commands: red `python3 -I -m unittest discover -s scripts/tests -p test_swarm_gate.py`
      (3 failures, 5 errors before implementation); green same command and the
      test_quality_gate.py file; `bash -n` and `shellcheck scripts/quality-gate.sh`;
      `REQUIRE_PY_COVERAGE=1 COVERAGE_BASE=47cfd5d6 bash scripts/python-coverage.sh`;
      check-constraints/docs/secrets/wiring; reviewers; exact-SHA CI.

    Evidence pending: record final commands and verdicts after the gates run.
