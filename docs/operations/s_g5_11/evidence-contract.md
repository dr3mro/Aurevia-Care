# Evidence contract (ADM-02)

- Requirement: **ADM-02**
- Back to the [S-G5-11 handoff](README.md); see the [queue catalog](queue-catalog.md).

## Artifact schema

An evidence artifact is a JSON object with these fields:

| Field | Content |
|---|---|
| `schema_version` | `"1.0"`; an unsupported version fails closed |
| `kind` | `synthetic_rehearsal` or `operator_attested`; anything else fails closed |
| `source` | Label of the snapshot; an attested artifact may not name a synthetic source |
| `organization_id` | Non-empty active tenant identifier |
| `operators` | Non-empty roster of `{subject, role, active}` records |
| `audit_events` | Non-empty list of OPS-02 audit facts referenced by actions and exports |
| `queues` | Object with exactly the seven ADM-02 queue names, each a list of work items |
| `exports` | List (possibly empty) of scoped, time-bounded, audited export records |

A work item carries `id`, `queue`, `created_at`, `state`, optional `owner` and an
`actions` list. An action carries `type`, `actor`, `reason`, `at` and `audit_id`.
An export carries `scope` (`organization_id` and `queues`), `created_at`,
`expires_at`, `actor` and `audit_id`.

The [conforming fixture](../../../infra/s_g5_11/fixtures/synthetic_ops_queues_evidence.json)
is a complete example. The [violation fixture](../../../infra/s_g5_11/fixtures/violation_ops_queues_evidence.json)
deliberately breaks several fields to demonstrate fail-closed reporting.

## Named checks

`evaluate` returns one named result per check. All checks are reported together;
the verdict is `pass` only when every check passes.

| Check | Enforces |
|---|---|
| `schema_version`, `kind`, `evidence_conflict` | Supported artifact version and evidence class; attested source cannot be synthetic |
| `organization_id` | A non-empty active tenant |
| `queues`, `queues.<name>` | Exact seven-queue vocabulary; each queue is a list |
| `operators` | Roster shape, authorized roles and boolean `active` flag |
| `audit_events` | OPS-02 fields, tenant match and timezone-aware instants |
| `items.structure` | Work-item id, queue tag and creation instant |
| `items.state`, `items.owner` | Known state, justified non-open state, owner when assigned or escalated |
| `items.actions`, `items.reason`, `items.actor` | Typed and correctly scoped actions with reason, active authorized actor and a time not earlier than the item's creation |
| `items.audit` | Every action cites an audit event that corroborates its actor, verb, work item and time |
| `exports.scope`, `exports.expiry`, `exports.audit`, `exports.actor` | Explicit tenant/queue scope, bounded positive TTL, an export audit event by the same actor at creation, and an authorized actor |

## Evidence integrity rules

An audit id alone is not proof: a cited event must describe the very action that
cites it. For an action, the event's `actor`, verb (`ASSIGN`, `RESOLVE`, `RETRY`,
`ESCALATE`), `resource_id` and instant must match the action's actor, type, work
item and time. Instants are compared by value, so `Z` and `+00:00` spellings of
the same moment agree. An export cites an `EXPORT` event by the same actor at the
export's `created_at` instant; an export has no separate resource id to compare.
An action also cannot be timestamped before its work item was created — an
operator cannot act on an item that did not yet exist. Both rules fail closed and
are reported under `items.audit`, `exports.audit` and `items.actions`. Export
expiry is checked only as a bounded positive window at declaration; the offline
evaluator has no clock, so elapsed windows are an operational check, not a
failed check here.

## Commands and results observed here

```
$ python3 -I infra/s_g5_11/ops_queues.py infra/s_g5_11/fixtures/synthetic_ops_queues_evidence.json
ADM-02 operational queues: PASS
  evidence class: rehearsal (final: False)
  ... 25 named checks, all PASS
exit 0
```

```
$ python3 -I infra/s_g5_11/ops_queues.py infra/s_g5_11/fixtures/violation_ops_queues_evidence.json
ADM-02 operational queues: FAIL
  evidence class: operator-attested (final: False)
  [FAIL] evidence_conflict: an operator-attested artifact cannot name a synthetic source
  [FAIL] queues: missing: missing_coverage; unexpected: speculative_queue
  [FAIL] queues.missing_coverage: queue must be a list
  [FAIL] audit_events: an audit event is missing a required OPS-02 field
  [FAIL] items.state: an item has an invalid state or no matching state action: 6 work items inspected
  [FAIL] items.reason: an operator action has no reason: 6 work items inspected
  [FAIL] items.actor: an action names an inactive or unauthorized actor: 6 work items inspected
  [FAIL] items.audit: an action cites an unknown or non-corroborating audit event: 6 work items inspected
  [FAIL] exports.expiry: an export has an unbounded, missing or non-positive expiry
exit 1
```

```
$ bash infra/s_g5_11/check.sh
Ran 103 tests in 0.009s
OK
S-G5-11: ADM-02 evaluator unit tests and fixture checks passed
exit 0
```

Line coverage of the evaluator is 100% under stdlib `trace`
(`python3 -m trace --count --coverdir <dir> infra/s_g5_11/tests/run_coverage.py`,
no executable line uncovered); branch coverage is by exhaustive enumeration of
every conditional in the 103 unit tests, because `coverage.py` is not installed. Evidence source: executed tool commands in this repository.

## What the evaluator proves — and what it cannot

The evaluator proves that a supplied artifact has a complete envelope and that
its fields satisfy the ADM-02 literals. It **cannot** prove that a queue existed
at a site, that an item was actually assigned, that an operator really acted, or
that an export file was actually deleted when it expired. An artifact is a claim
judged against a contract; the claim's truth rests on its provenance and on
human review.

Accordingly:

- `kind: synthetic_rehearsal` yields `final_evidence: false` even when every check
  passes. It demonstrates the harness, not site operation.
- `kind: operator_attested` yields `final_evidence: true` only when every check
  passes and the source is not synthetic — but that flag records what the
  artifact *claims*, not an independent verification.

## Remaining gate

No real queue snapshot, staffed roster or export record is available in this
repository (the container stack is a disposable bootstrap fixture). This story
therefore does **not** claim that ADM-02 is satisfied at any site. The open work
is:

1. Produce a queue snapshot from each enabled site with the tool, version,
   command, UTC timestamp and source SHA recorded.
2. Verify that every action's audit fact exists on the append-only audit chain
   and that the operator roster matches current active membership.
3. Confirm the declared retry scope and operator-role mapping with the site
   owners, and record a human G5 acceptance decision.

A reviewer should treat the delivered synthetic pass as evidence that the check
works, and these three items as the evidence that the queues actually operate.
