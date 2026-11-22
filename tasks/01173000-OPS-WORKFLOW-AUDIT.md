- [ ] OPS-WORKFLOW-AUDIT · Remediate swarm integration, repair, release and handoff findings (OPS-06).
  - requirements: OPS-06
  - scope: docs/WORKFLOW.md, docs/operations/workflow-changes
  - evidence: Local changes cover pinned-commit replay, repair registration and retry eligibility, exact-SHA release verification, dispatch cleanup, draft notes and memory proposals, issue variable propagation, and the story reading prompt. The synthetic Git replay and all seven release-filter cases passed. Tool results: 412 Python tooling tests passed, clean Maven verification passed, PostgreSQL checks passed and the developer reference built. GitHub verification is pending. No repository commit or push was made by request.
  - details:
    Pending: isolate candidate validation from publication credentials, publish immutable slot-image generations with last-good recovery, fix the missing local architecture tag on first release, reject unknown worker inventory, replace raw log tails with structured diagnostics, validate generated Markdown, and reconcile the remaining runbook policy. Verify affected workflows on GitHub only after delivery is authorized.

