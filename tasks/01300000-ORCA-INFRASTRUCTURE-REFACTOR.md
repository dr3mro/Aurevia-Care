- [!] ORCA-INFRASTRUCTURE-REFACTOR · Coordinated ORCA infrastructure audit, refactoring and verification
  - aliases: 01300000-ORCA-INFRASTRUCTURE-REFACTOR
  - requirements: ORCA-001..ORCA-150, AG-01..AG-35, WF-01..WF-35 (source: `orca-refactor.md`)
  - scope: .opencode, .github, scripts, tasks/01300000-ORCA-INFRASTRUCTURE-REFACTOR.md, docs, .opencode/config
  - depends: -
  - details:
    Incomplete: 13 of 16 child stories are done; ORCA-REVIEWER-PROVENANCE and ORCA-TRUSTED-REVIEW-EVIDENCE are incomplete and blocked on trusted Git/GitHub execution, while ORCA-SKILL-PREFIX-REMOVAL is not started despite the owner's remove-prefix decision. Remaining units also need staff-web/Java/CI authority or a non-competing ORCA-080 replacement. Reopen to `[ ]` only when another attempt is authorized.
    Historical evidence (not current completion evidence): full suite `Ran 1920 tests in 126.800s`, `OK`; `bash scripts/python-coverage.sh` reported every changed script line covered; all six policy checks exited 0; four reviewer verdicts were GO per child story. Evidence source: tool commands and read-only reviewers. No commit, push or PR was made in that session.
    Incremental, independently verifiable stages A–N from `orca-refactor.md` section 19. Do not attempt all requirements in one change. Preserve useful functionality, Git safety, branch protection, quality gates and clinical safety. Every disposition must cite tool/reviewer/simulated-user/human evidence and its source type. This file is the high-level incomplete parent checkpoint; the authoritative stage checklist and 150-capability disposition tables remain in `tasks/01279000-WORKFLOW-AUDIT.md` rather than 150 duplicate tasks.
