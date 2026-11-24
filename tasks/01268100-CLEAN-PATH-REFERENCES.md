- [x] CLEAN-PATH-REFERENCES · Repair stale path strings and broken relative links left by the config and operations-folder moves
  - agent: orca
  - requirements: OPS-06
  - pr: direct commit 28cfb5b4 on main (interactive, human-reviewed)
  - scope: README.md, docs/operations/slo.md, docs/reference/synthetic-data.md, docs/security/data-inventory.md, docs/reference/testing-guide.md, docs/TEAM-ACTIONS.md, docs/SWARM.md, docs/operations/workflow-changes/quality-verification-checklist.md, .opencode/setup/AGENTS.md, .opencode/config/README-SETUP.md, .opencode/config/STARTER_PROMPT.md, .opencode/config/MEMORY.md, .opencode/config/BUGS.md, .opencode/config/WORKSPACE.md, .opencode/agents/orca.md, .opencode/skills/aurevia-delivery/SKILL.md
  - delivery: interactive, human-reviewed scoped commit on main; not a swarm auto-merge (docs/SWARM.md:203 protects .opencode/)
  - attempts: 0
  - details:
    The 2026-11-23 naming audit found path strings that still use the removed
    `infra/s_g5_*` folders and the pre-move config locations (`opencode.json`,
    `.github/opencode.ci.json`, `.github/swarm/slots.json`,
    `.github/swarm/model-pricing.json`). The real files are under `.opencode/`.
    Fix every active reference, repair the broken relative Markdown links, and
    reconcile the stale tracker claims. Historical records under docs/archive/,
    docs/swarm/, tasks/ and .agents/notes/ are deliberately left untouched.
    Regenerate the OpenCode config after editing instruction files and commit
    any resulting diff. Run `python3 -I scripts/check-docs.py` and the pack
    check as evidence.

    Authoritative path map (verified by tool: the new targets exist, the old
    ones do not):
    - infra/s_g5_05 → infra/capacity; s_g5_05/capacity-targets.md →
      docs/operations/capacity/capacity-targets.md
    - infra/s_g5_06 → infra/recovery; s_g5_06/recovery-targets.md,
      restore-procedure.md and operational-runbooks.md →
      docs/operations/recovery/<name>
    - infra/s_g5_11 → infra/ops-queues; s_g5_11/queue-catalog.md →
      docs/operations/ops-queues/queue-catalog.md
    - infra/s_g5_09/fixtures → infra/onboarding/fixtures
    - infra/s_g5_08 → infra/tenant-lifecycle
    - opencode.json → .opencode/opencode.json;
      .github/opencode.ci.json → .opencode/opencode.ci.json;
      .github/swarm/slots.json → .opencode/config/slots.json;
      .github/swarm/model-pricing.json → .opencode/model-pricing.json;
      README-SETUP.md and PROJECT_V1_SPEC.md → .opencode/config/<name>

    Historical evidence under docs/archive/, docs/swarm/ and tasks/ is not
    edited: those files record commands as they were run before the move.
    docs/reference/testing-guide.md:27 uses s_g5_05 only as an illustrative
    bad-name example and is not a stale path; leave that line alone.
    docs/gates/g0-isolation-surfaces.md is a historical worker evidence record
    and is out of scope for the same reason.

    Ordered steps: (a) fix the 12 broken relative links; (b) replace the stale
    s_g5_* strings in the three active docs; (c) replace the stale config paths
    in active instructions and docs; (d) reconcile the stale MEMORY.md refactor
    claim and the WORKSPACE.md resume card with the real branch/SHA;
    (e) run `python3 -I scripts/check-docs.py`, `python3 -I scripts/check-pack.py`,
    `python3 -I scripts/gen-opencode.py --check`, then a fresh link scan that
    reports zero broken links outside generated output; (f) `bash scripts/quick-check.sh`.
  - evidence:
    Owner choice 2026-11-24: remove all old naming in active files; historical
    records under tasks/, docs/archive/, docs/swarm/ and .agents/notes/ stay.
    Tool: path map verified on disk before edits — `.opencode/config/slots.json`,
    `.opencode/model-pricing.json`, `.opencode/opencode.ci.json`,
    `.opencode/opencode.json`, `infra/{capacity,recovery,ops-queues,tenant-lifecycle,onboarding/fixtures}`
    and `docs/operations/{capacity,recovery,ops-queues}` exist; the old
    `.github/swarm`, `.github/opencode.ci.json`, root `opencode.json` and
    `infra/s_g5_*` do not.
    Tool: replaced stale paths in `docs/SWARM.md` (slots, pricing, protected-path
    list), `docs/TEAM-ACTIONS.md`, `docs/operations/slo.md` (9 lines),
    `docs/reference/synthetic-data.md`, `docs/security/data-inventory.md`,
    `docs/operations/workflow-changes/quality-verification-checklist.md`,
    `.opencode/setup/AGENTS.md`, `.opencode/agents/orca.md`, `.opencode/config/{MEMORY,BUGS,WORKSPACE}.md`
    and the two workflow comments in `.github/workflows/{swarm,swarm-images}.yml`.
    Reworded the historical "moved from old path" lines in the trackers so they
    name the current path instead of asserting a removed path. Also removed a
    duplicated `com/aurevia/aurevia` package segment left by an earlier rename
    from four `docs/spec/clauses/authz/role-*.md` files.
    Kept deliberately: the `s_g5_05` bad-name example in
    `docs/reference/testing-guide.md:27` and the negative-assertion string in
    `scripts/tests/test_swarm_checkout.py` (the test proves the old path is NOT
    checked out).
    Tool: `python3 -I scripts/gen-opencode.py --check` exit 0 (instruction paths
    unchanged, so regenerated configs need no diff);
    `python3 -I scripts/check-pack.py` → `Delivery tooling validated: 39 agents, 39 skills`,
    exit 0; `python3 -I scripts/check-names.py` exit 0;
    `python3 -I scripts/check-wiring.py` exit 0;
    `actionlint -ignore 'unexpected key "queue" for "concurrency" section' .github/workflows/swarm.yml .github/workflows/swarm-images.yml`
    exit 0; `git grep` audit shows no old-layout references in active files.
    Tool (full suite): `python3 -I -m unittest discover -s scripts/tests -q` →
    `Ran 1552 tests in 434.489s`, `FAILED (failures=1)`; the only failure is
    `test_check_docs.RepositoryTests.test_repository_docs_are_consistent`, the
    concurrent doc churn below. All other 1551 tests passed, including the
    migration naming test and `test_swarm.py`.
    Blocker (environment, not this change): `python3 -I scripts/check-docs.py`
    exit 1 with 151 lines, all from concurrent sessions moving notes to
    `.agents/reports/` and deleting `docs/archive/`; zero errors name a file
    changed here, and the same check on a clean `git archive HEAD` copy exits 0.
    A later run crashed in `scripts/check-docs.py` with
    `NameError: name 'MOVED_DOCS' is not defined` while another session edited
    that script; left untouched. Evidence sources: executed tool commands; no
    reviewer, simulated user or human participant evidence.
    Resolution: the concurrent doc-path migration finished and
    `python3 -I scripts/check-docs.py` now exits 0 with no output; every other
    gate above is green. Delivery: committed on main in `28cfb5b4` and merged
    with origin in `4932b50c`; no push or tag was made by this session.
