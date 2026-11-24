- [-] CLEAN-PATH-REFERENCES · Repair stale path strings and broken relative links left by the config and operations-folder moves
  - agent: orca
  - requirements: OPS-06
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
    docs/swarm/, tasks/ and .agent/pr-notes/ are deliberately left untouched.
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
