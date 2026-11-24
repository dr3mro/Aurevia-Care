---
name: "aurevia-context-optimizer"
description: "Identifies the next bounded repository context and the authoritative evidence for it. Use when a task is large or unclear and the agent needs to decide which files and requirement IDs to read first."
---

# Context Optimizer

Read AGENTS.md, current specification headings and the relevant tracker rows, the earlier findings in the prompt when there are any, and the "Lessons for workers" of the newest `docs/pr/reports/<YYYY-MM-DD>.md`. Locate actual entry points with targeted search and bounded reads. Separate implemented, partial, planned and unknown behavior. Return requirement IDs, exact file paths and sections, last verified baseline, and the smallest reads or checks needed. Flag conflicting or stale records. Return a compact briefing without edits.

## Context hierarchy

Curate what the agent sees from most persistent to most transient. Load the smallest
layer that answers the question; do not pull the whole repository into context.

1. **Rules** — `AGENTS.md`, `CODING_STYLE.md`, `CONSTRAINTS.md`, `docs/WORKFLOW.md`.
   Always in effect; state only what governs the current task.
2. **Spec and architecture** — `PROJECT_V1_SPEC.md` by requirement ID, relevant
   `docs/decisions/`, the module README under `apps/`, `contracts/` or `db/`.
   Load per task, not wholesale.
3. **Relevant source** — entry points found by targeted search, the files in the
   task's declared `scope`, and their direct tests. Bounded reads; cite path and line.
4. **Evidence** — existing task notes, tracker rows, executed check results and
   reviewer verdicts. Prefer the newest authoritative record; flag contradictions.
5. **Conversation** — earlier findings in the prompt and the newest daily notes'
   "Lessons for workers". Compacted, never repeated verbatim unless it changes the plan.

Return the briefing in this order, marking which layer each item came from. A briefing
that mixes layers invites the agent to treat a stale note as current evidence.
