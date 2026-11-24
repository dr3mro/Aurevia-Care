# ADM-02 operational queues

- Requirement: **ADM-02**
- Status: the queue contract, the operator-action rules, the export envelope and
  a fail-closed evidence evaluator are delivered. Real site queue snapshots and
  operator attestations are **not** produced here and remain an open G5 gate.
- Audience: operations, tenant administrators, privacy auditors, engineering and
  G5 acceptance reviewers.

This directory is the engineering handoff for ADM-02. It states which
operational queues must exist, which actions an authorized operator may take and
with what evidence, how an evidence artifact is judged, and — deliberately —
what this repository cannot prove.

## Requirement mapping

| Requirement | Specification clause | Handled by |
|---|---|---|
| ADM-02 | Operational queues for unsigned encounters, unreconciled reports, unacknowledged critical findings, rejected exchanges, document failures, missing coverage and revoked accounts; authorized operators assign, resolve, retry or escalate with reason; exports use explicit scope, expiry and audit | [Queue catalog](queue-catalog.md), [Evidence contract](evidence-contract.md) |
| OPS-02 | Audit of reads, disclosures, mutations and administration with actor, purpose, scope, time, action, resource, correlation and outcome; append-only | [Queue catalog](queue-catalog.md) (audit facts on every action and export) |
| IAM-01, IAM-02 | Active membership and required permission on every write; revocation effective on the next protected request | [Queue catalog](queue-catalog.md) (operator roster and revocation) |

The approved clause itself is in the [product specification](../../../.opencode/config/PROJECT_V1_SPEC.md).
No new threshold or requirement ID is introduced by this handoff.

Supporting queue-to-requirement links (unreconciled reports → XCH-04,
unacknowledged critical findings → XCH-05, rejected exchanges → XCH-03,
unsigned encounters → CLN-05, document failures → ARC-06, missing coverage →
XCH-05 and ADM-01, revoked accounts → IAM-02) describe where a work item
originates. They do not create new ADM-02 requirements.

## Three evidence tiers

| Tier | What it is | What it proves | What it does not prove |
|---|---|---|---|
| Contract | The literals and rules encoded in the [evaluator](../../../infra/ops-queues/ops_queues.py) | Fidelity of the encoding and fail-closed validation | That any queue was staffed or any action really happened |
| Synthetic rehearsal | A seeded artifact run by [`check.sh`](../../../infra/ops-queues/check.sh) in this repository | That the evaluator accepts a conforming snapshot and rejects breaches | Real queues, operators, exports or response times; no site action was observed |
| Operator-attested | An artifact attested by an authorized site operator, produced outside this repository | The artifact's own claim of completeness at a site | Independent verification of the claim or human G5 acceptance |

A passing synthetic artifact is explicitly labelled `final_evidence: false`. The
evaluator never promotes a rehearsal to attested evidence; an attested artifact
that names a synthetic source is rejected as a conflict.

## Document set

| Document | Covers |
|---|---|
| [Queue catalog](queue-catalog.md) | The seven required queues, work-item states, operator actions, claimed assumptions and audit facts |
| [Evidence contract](evidence-contract.md) | Artifact schema, named checks, commands, the observed rehearsal result, the honesty boundary and the remaining gate |

Executable material lives in `infra/ops-queues`: the
[evaluator](../../../infra/ops-queues/ops_queues.py), the
[conforming](../../../infra/ops-queues/fixtures/synthetic_ops_queues_evidence.json)
and [violation](../../../infra/ops-queues/fixtures/violation_ops_queues_evidence.json)
fixtures, and the unit tests.

## Verification

| Check | Command | Proves |
|---|---|---|
| Unit and fixture gate | `bash infra/ops-queues/check.sh` | 103 synthetic unit tests pass; the conforming fixture passes and the violation fixture fails |
| Unit tests alone | `python3 -I -m unittest discover -s infra/ops-queues/tests -q` | The evaluator's enumerated branches behave as specified |
| Line coverage | `python3 -m trace --count --coverdir /tmp/opencode/ops-queues_cov infra/ops-queues/tests/run_coverage.py` | Every executable line of the evaluator runs; stdlib `trace` is used because `coverage.py` is not installed |
| Links and requirement IDs | `python3 -I scripts/check-docs.py` | Relative links resolve and every requirement ID is defined in the specification |
| Credential and PHI shapes | `python3 -I scripts/check-secrets.py` | No credential or patient-identifier pattern was introduced |

## Decisions

| # | Decision | Rationale |
|---|---|---|
| D1 | The queue vocabulary is exactly the seven ADM-02 queues | Omitting or adding a queue changes the declared operational contract; the checker fails closed on both |
| D2 | A non-open work item must be justified by a matching action | ADM-02 requires operators to assign, resolve, retry or escalate; a terminal state without its action could not show a reason or actor |
| D3 | Every action requires a non-empty reason and an active authorized actor; operator subjects and audit ids must be unique | "with reason" is explicit in ADM-02; IAM-01/02 require the permission to be re-derived, so a revoked operator cannot act on the next request, and a duplicate identity must not re-authorize them or forge an audit reference |
| D4 | `retry` is allowed only on `rejected_exchanges` and `document_failures` | A declared assumption, not a spec literal; retrying a clinical review or a coverage gap would destroy its audit trail. Site alignment is an open item |
| D5 | The operator-role allowlist is `tenant_admin`, `privacy_auditor`, `operator` | A declared assumption; site role names must be mapped to it before live enablement |
| D6 | Export TTL is checked as a positive window no longer than 86,400 seconds | ADM-02 requires an expiry; a missing, non-positive or unbounded window fails closed. The offline evaluator has no clock, so it cannot say whether a bounded window has since elapsed — that is an operational check |
| D7 | Exports require explicit tenant and queue scope plus an audit reference | ADM-02 requires explicit scope and audit; a broad export would leak operational history |
| D8 | A passing synthetic artifact is not final evidence | Rehearsal proves the check works, not that a site staffed its queues |
| D9 | Reserved migration version V53 is not used | No schema change is needed; version gaps are allowed by the repository's change-safety gate |
| D10 | An audit reference must corroborate the citing action's actor, verb, item and time (and an export's actor, verb and creation time) | An id alone can point at an unrelated event, so a snapshot could claim operator action without a matching audit fact (OPS-02) |
| D11 | An action cannot predate its work item's creation instant | An operator cannot act on an item before it exists; a backdated action would fake a timely response |

## Boundaries

- Synthetic data only. No fixture contains a real person, identifier, credential
  or clinical payload.
- No clinical approval is implied. Queue staffing, escalation ownership and
  site role mapping belong to the accountable owners in the specification's
  decision table.
- No application code, schema, API contract, CI or tracker file belongs in this
  story's scope. The two scope directories are the only files changed.

## Open items

| Item | Why it is open | Who can close it |
|---|---|---|
| Real queue snapshot and operator roster from each enabled site | No deployment or staffed queue exists in this repository | Operations and tenant administrators, in an authorized environment |
| Site role-name mapping to the declared operator allowlist | The allowlist is a declared assumption | Tenant administrators with the privacy owner |
| Confirmation that retry is permitted only on the two declared queues | Not a specification literal | Operations with the clinical safety lead |
| Human acceptance of the attested evidence | A script cannot attest that an operator really acted | G5 acceptance owners |

Evidence source for the delivered checks: executed tool commands in this
repository and bounded specialist design reviews (simulated). There is no
human, clinical or real-site operational evidence in this story.
