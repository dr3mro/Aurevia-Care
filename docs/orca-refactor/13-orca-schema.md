# ORCA task contract and context manifest schema

Analysis-only documentation. This records the shape of the passive helpers in
[`orca_core.py`](../../scripts/orca_core.py); it does not create a runtime
context, enforce a gate or dispatch an agent. Evidence source: tool reads of
the helper implementations and a synthetic worked example checked by
`scripts/tests/test_orca_schema_doc.py`.

## Task contract fields

`TaskContract` is a frozen value object used only for offline analysis.
Required fields are `task_id`, `objective`, `acceptance`, `scope` and `risk`;
optional fields are `requirements`, `depends`, `exclusions`, `assumptions`,
`skills`, `constraints`, `tests`, `definition_of_done` and `components`, all
defaulting to empty tuples. `problems()` returns the names of missing or
invalid required fields, and an empty result means the contract has enough
information to compile a context and score readiness.

## Manifest fields

`compile_context(contract, sources, budgets)` selects text sections under
per-section character budgets and returns a manifest. The field names, one per
line, are:

```text
content
hash
instructions
references
task_id
```

`task_id` comes from the contract. `instructions` lists mandatory section
names in declaration order; `references` lists optional section names in
sorted order. `content` maps each selected name to its text. `hash` is the
first twelve hex characters of the SHA-1 of the JSON-serialised manifest with
sorted keys, so the same inputs always produce the same hash.

Mandatory sections (`safety`, `execution`) are never truncated, even with a
tiny budget. An optional section is truncated when its resolved budget is
positive and kept whole when that budget is zero or negative. A section with
no entry of its own uses the `default` budget, so an absent entry can still be
truncated when the default is positive. This is a character budget for analysis text,
not the row budget used by `scripts/orca_memory.py`, where zero deliberately
keeps only mandatory evidence rows.

## Worked manifest artifact

The following artifact is generated from a synthetic contract (`SYN-014`,
objective `Verify the synthetic context schema`), four synthetic source
sections and budgets `{"notes": 10, "default": 0}`. No real repository path,
user data or credential appears in it.

```json
{
  "content": {
    "execution": "SYNTHETIC EXECUTION RULE",
    "notes": "SYNTHETIC ",
    "safety": "SYNTHETIC SAFETY RULE",
    "spec": "SYNTHETIC SPEC EXCERPT"
  },
  "hash": "03ba43b7f756",
  "instructions": [
    "safety",
    "execution"
  ],
  "references": [
    "notes",
    "spec"
  ],
  "task_id": "SYN-014"
}
```

## Preflight problems

`preflight(contract, tools, instructions, skills)` lists missing analysis
inputs without running any check. For the contract above with available tools
`{"python3"}`, instructions `{"AGENTS.md"}` and skills `{"delivery"}`, the
exact problems are:

```text
missing tool: opencode
missing instruction: WORKFLOW.md
missing skill: code-reviewer
```

An empty list means the named analysis inputs are present. Runtime enforcement
of tools, instructions and skills stays with the delivery gates; this helper
only reports what is missing.

## Scope and limits

This document closes the schema and artifact gap for deliverables 14, 18, 19
and 20. It does not deliver the context compiler runtime, a task manifest
writer, preflight gate enforcement, task dispatch or persistent memory. Those
units remain separate stories with their own evidence and owner decisions.
