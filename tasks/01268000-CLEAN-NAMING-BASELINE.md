- [-] CLEAN-NAMING-BASELINE · Remove stale naming allow-list entries, refresh the bootstrap tooling placeholder and clean local remnants
  - agent: orca
  - requirements: OPS-06
  - scope: scripts/check-names.py, scripts/tests/test_check_names.py, tools/tooling.test.ts
  - delivery: interactive, human-reviewed scoped commit on main; not a swarm auto-merge (docs/SWARM.md:203 protects scripts/ and Dockerfiles)
  - evidence:
    Baseline (tool, 2026-11-24, HEAD 78411794): `python3 -I scripts/check-names.py` exit 0, no output; `python3 -I scripts/check-docs.py` exit 0, no output; `python3 -I scripts/gen-opencode.py --check` exit 0, no output. Working tree clean before claim. Evidence source: executed tool commands.
  - attempts: 0
  - details:
    Repo-wide naming audit (2026-11-23) found a stale `LEGACY` allow-list in
    `scripts/check-names.py` for `contracts/x_0[123]` and `api/x_0[123]` folders
    that no longer exist after REFACTOR-PACKAGES-02. Remove the stale entries so
    a reintroduced story-ID folder is rejected, with a red test first.
    Owner decision 2026-11-24: do NOT change any Dockerfile version text. The
    stale image comments were reverted to HEAD and both files left untouched;
    the directive is recorded in MEMORY.md. SUPERSEDED later the same day: the
    owner chose "Make FROM match comments"; see
    tasks/01268300-PIN-DOCKER-BASE-IMAGES.md and the override row in MEMORY.md.
    Replace the vacuous `tools/tooling.test.ts` bootstrap placeholder with a
    meaningful assertion about the shared toolchain, keeping 100% coverage.
    Do not touch anything under `.agent/` (worker rule and .gitignore:12);
    another worker may own that file. The empty story-ID dirs, `.DS_Store` and
    `__pycache__` are local hygiene only, with no PR evidence: first prove with
    `git ls-files` that none is tracked, then delete the untracked ones.

    Plan review (aurevia-plan-reviewer, 2026-11-24) returned NO-GO with five
    Required findings; all are folded in here:
    1. Interactive/human-reviewed delivery route is stated above.
    2. Removing LEGACY keeps the `legacy` parameter (callers pass three
       positional args at test_check_names.py:16,35 and
       test_check_names_coverage.py:69), changes only the module default to an
       empty tuple, and rewrites the docstring claim that the folders exist.
    3. Ordered steps with proving commands: (a) add red regression test
       `test_repository_default_rejects_former_legacy_paths`, asserting
       `violations(root, ['contracts/x_01/a.yaml'])` returns `['contracts/x_01']`
       with the module default; run `python3 -I -m unittest discover -s scripts/tests`;
       (b) make the three coupled script edits and rerun green;
       (c) `bash scripts/python-coverage.sh`;
       (d) replaced by owner decision: no Dockerfile edits at all;
       (e) replace tools/tooling.test.ts with concrete assertions reading root
       vitest.config.ts and package.json: the 100% thresholds, the
       `tools/**/*.test.ts` include and the four npm scripts, plus a synthetic
       weakened-threshold case that must be rejected; run `npm run test`,
       `npm run typecheck`, `npm run lint`, `npm run format:check` at the root;
       (f) local hygiene only after `git ls-files` proof;
       (g) `bash scripts/quick-check.sh` as the full local gate.
    4. Remnant acceptance corrected as above.
    5. Concrete tooling assertion and its failing case are named in step (e).
    Optional evidence: `git ls-files | grep -E 'x_0[123]'` returns no paths.
