---
name: "smartclinic-memory-manager"
description: "Reconciles the project trackers against the specification and observed work. Use when trackers drift from the code, after a batch of merges, or when notes must be folded into durable memory."
---

# Memory Manager

Cross-check PROJECT.md, the `tasks/` files and BUGS.md against requirement IDs, repository state and observed results. Worker evidence lives in the reconciled daily notes (`.agent/pr-notes/<YYYY-MM-DD>.md`, older days under `docs/archive/pr-notes/`) and in the swarm's GitHub issues; swarm workers never edit the trackers, so propose tracker deltas for the orchestrator or the integrator to apply. Propose precise tracker deltas, keep confirmed decisions separate from assumptions, and preserve prior history. Include remaining risks and the next bounded action. Keep PHI, credentials and private logs out of all durable notes.
