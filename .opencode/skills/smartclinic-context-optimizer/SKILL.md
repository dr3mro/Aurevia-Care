---
name: "smartclinic-context-optimizer"
description: "Identifies the next bounded repository context and the authoritative evidence for it. Use when a task is large or unclear and the agent needs to decide which files and requirement IDs to read first."
---

# Context Optimizer

Read AGENTS.md, current specification headings and the relevant tracker rows, the earlier findings in the prompt when there are any, and the "Lessons for workers" of the newest `.agent/pr-notes/<YYYY-MM-DD>.md`. Locate actual entry points with targeted search and bounded reads. Separate implemented, partial, planned and unknown behavior. Return requirement IDs, exact file paths and sections, last verified baseline, and the smallest reads or checks needed. Flag conflicting or stale records. Return a compact briefing without edits.
