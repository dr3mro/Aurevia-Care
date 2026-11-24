# Exercise sheet — database restore drill (OPS-04)

Back to the [exercise index](./index.md). This is a rehearsal script, **not** a
conducted restore, not evidence and not an approval. Run it on synthetic fixtures
only. Record observations in the [drill evidence template](../recovery/drill-evidence.md)
and follow the [recovery drill runbook](../recovery/runbook.md) and the
[clean-environment restore procedure](../recovery/restore-procedure.md). No drill
was run to write this sheet.

## Goal

Restore the authoritative database, private objects and identity-provider
dependencies into a clean, isolated environment, then prove through the
reconciliation gate that chart, order, document and association data survived
before writes reopen. Measure RPO and RTO from observed timestamps; never copy a
target into a measured field.

## Who takes part

| Role | Responsibility in this drill |
|---|---|
| Facilitator | Reads the injects and questions, keeps time, records deviations, runs nothing |
| Operational on-call owner | Executes the restore and the reconciliation checks; declares the RTO stop condition |
| Privacy / accountable operations owner | Co-signs reopening of writes after the gate is signed |
| Site clinical coverage owner | Confirms clinical integrity of chart, order and result data before clinical use resumes |
| Integration lead | Supports in-flight delivery reconciliation (step R3) |
| G5 acceptance owners | Accept a conducted-drill artifact; a separate gate from reopening writes |

The rota, escalation contacts and named approvals live in the approved policy
pack, not in this repository. `NOT YET IN PLACE:` an in-repository on-call rota or
named approval record; the facilitator writes names and timestamps into the
evidence template for the exercise.

## Recovery targets taken from the specification

From [OPS-04](../../../.opencode/config/PROJECT_V1_SPEC.md) and the enforced
[recovery targets](../recovery/recovery-targets.md):

| Objective | Target | Measured how |
|---|---|---|
| RPO | ≤ 15 minutes (`rpo_minutes` in `[0, 15]`) | Last recoverable committed point to incident declaration, from timestamped backup/PITR records |
| RTO | ≤ 4 hours (`rto_hours` in `(0, 4]`) | Incident declaration to the RTO stop condition (section 6 of the runbook), from observed drill timestamps |
| Data classes | All four of `core_chart`, `order_data`, `documents`, `document_associations` | Exact names, no near misses such as `documentation` |
| Restore classes | `database`, `objects`, `idp_dependencies`, `clean_environment = true` | Evidence envelope and drill log |
| Reconciliation | `hashes`, `source_versions`, `in_flight_deliveries`, `audit_coverage`, `completed_before_writes_reopened` — all `true` | Reconciliation gate completed while the environment is still read-only |
| Cadence | `before_pilot` and `quarterly_cadence` both `true` | Dated drill records; a single artifact cannot prove a recurring schedule |
| OPS-07 export and retention | Tenant export manifest with schema version, checksums, authorized documents, provenance and audit history; retention respects legal holds and tracks deletion of active data and approved backup expiration | [export manifest](../tenant-lifecycle/export-manifest.md); `bash infra/tenant-lifecycle/check.sh` fixture gate and `python3 -I infra/tenant-lifecycle/tenant_lifecycle_evidence.py <artifact.json>` for the drill's own envelope |

## Preconditions and synthetic setup

1. The facilitator declares a synthetic incident time in UTC and writes it in the
   drill log. That instant starts the RTO clock.
2. The only data in play is the synthetic fixture set. Never use a production
   credential, a real domain or a patient value in a step, log or evidence link.
3. The facilitator confirms which commands below exist in the checkout. Any step
   without a real command is marked `NOT YET IN PLACE:` in the evidence template
   rather than improvised.
4. `NOT YET IN PLACE:` provisioning an isolated clean environment, managed PITR,
   versioned private object-store restore and IdP configuration/secret restore —
   no in-repository command performs these. The exercise simulates them in the
   drill log; a conducted drill needs the managed platform and the named owners.

## Timed script with injects

`T+` is minutes from the synthetic incident declaration. Step IDs match the
[evidence template](../recovery/drill-evidence.md) rows.

| T+ | Step | Inject / trigger | Operator action (real command where one exists) | Expected observation |
|---:|---|---|---|---|
| 0 | — | Facilitator announces a synthetic loss of the live database and records the UTC declaration time | On-call owner starts the clock and opens the drill log | Declaration time present; kind = `synthetic_rehearsal` |
| 5 | D1 | Facilitator asks the owner to prove the target environment is isolated | Owner records the environment identifier and pinned schema version/digest in the log. `NOT YET IN PLACE:` no in-repo command provisions or proves an isolated clean environment | Isolation claim recorded by role, not by a script flag |
| 15 | D2 | "The authoritative database must be at the last recoverable committed point" | `NOT YET IN PLACE:` managed PITR is not in this repository. On synthetic infrastructure the owner runs `bash db/check.sh` to prove the migration set, forced RLS, role separation, tenant isolation and audit chain are intact on a freshly built synthetic database | `db: migrations, forced RLS, role separation, isolation, audit, append-only staff history and versioned config verified` |
| 30 | D3 | A synthetic object checksum in the drill log is deliberately altered | `NOT YET IN PLACE:` no in-repo object-store restore or checksum recomputation command. The owner describes the expected recompute-and-compare and records the mismatch and the `hold for review` decision | Mismatch held, not silently replaced (R1) |
| 40 | D4 | "Sign-in must work without copying any production credential" | `NOT YET IN PLACE:` no in-repo IdP or secret-manager admin command. In the disposable stack the owner may use `curl -fsS http://127.0.0.1:8080/health.txt` for bootstrap health and `GET /health/live`, `GET /health/ready` for the real API's liveness/readiness | Readiness returns no exception text; no credential appears in any log or link |
| 55 | R1 | Reconcile hashes before any write | Owner records the R1 result. The evaluator's `reconciliation.hashes` gate is exercised with `bash infra/recovery/check.sh` on synthetic fixtures | All reconciliation checks `true`; gate signed before writes |
| 60 | R1 (OPS-07) | "A restored tenant export must still be verifiable" | Owner checks that a synthetic export artifact has a manifest with schema version, record checksums, provenance, authorized documents and audit history, and that retention legal holds and approved backup expiration entries are tracked. Evaluate the drill's own envelope with `python3 -I infra/tenant-lifecycle/tenant_lifecycle_evidence.py <artifact.json>`; `bash infra/tenant-lifecycle/check.sh` is the fixture gate, not an artifact evaluator | Exit 0 on the drill artifact; no record checksum, authorization or audit-history breach |
| 65 | R2 | "A source system ID and its clinical effective time must survive unchanged" | Owner records that IDs were not renumbered and effective times not rewritten; check exercised by the synthetic fixture evaluator | `source_versions` true |
| 75 | R3 | Inject a duplicate and an out-of-order delivery event by immutable ID | Owner reconciles the synthetic outbox/dead-letter snapshot by immutable event ID in sender/type namespace and enforces per-aggregate version (ARC-05). Integration lead confirms order and patient identifiers before relinking | `in_flight_deliveries` true; one downstream effect and one clinical task per revision |
| 85 | R4 | Break a synthetic audit-chain segment in the drill log | Owner verifies the per-tenant append-only chain; the same property is proved on real synthetic PostgreSQL by `bash db/check.sh` | `audit_coverage` true; a broken segment would block the gate |
| 95 | R5 | Inject a synthetic membership revocation that predates the PITR point | Owner re-derives current membership/role grants (IAM-02), verified chart links (IAM-05) and withdrawn releases before any protected request. `db/check.sh` demonstrates that an appended revoke denies the next check | Revoked subject denied on next check; no stale grant |
| 105 | A1 | "May writes reopen?" | Operational on-call owner **and** privacy/operations owner sign in the evidence template; site clinical coverage owner confirms integrity. No script, boolean or passing check approves | Two distinct named approvers and timestamps; separation of duties |
| 110 | W1 | Reopen writes in the isolated environment | Owner watches error rate, queue age and partner delivery for the observation window. `NOT YET IN PLACE:` no in-repo clinical monitoring dashboard; `scripts/monitor.sh` is a swarm CI dashboard and is not used here | Any breach recorded as a failed step or corrective action |
| 120 | M1 | "Stop the RTO clock" | Owner records observed RPO and RTO from the logged timestamps, confirms all four data classes and three restore classes, and runs `python3 -I infra/recovery/recovery_evidence.py <artifact.json>` against the synthetic envelope | Exit 0 with `final_evidence: false` for a rehearsal; measured values within target |

## Questions the facilitator asks

1. Which exact data class does this row prove — `core_chart`, `order_data`,
   `documents` or `document_associations`? A near miss is a failure.
2. What is the observed timestamp behind this RPO/RTO value, and where is it
   recorded? If the answer is "the target", the measurement is fabricated.
3. Did the reconciliation gate complete and get signed **before** writes reopened?
   If writes reopened first, the gate fails even if every check later passed.
4. What happened to the altered checksum and the broken audit segment — were
   they held for review or silently replaced?
5. Which revocations and withdrawals could a point-in-time restore bring back,
   and who re-derived the current state?
6. Who approved reopening, and is that person different from the person who ran
   the restore?
7. Does the restored tenant export still carry its manifest, checksums,
   provenance, authorized documents and audit history, and are legal holds and
   approved backup expiration entries intact (OPS-07)?
8. Does any evidence link, URL or log line contain a patient value, a credential
   or a real domain?
9. Is this rehearsal labelled `synthetic_rehearsal`, and is it explicitly kept
   out of final G5 evidence?

## Evidence to capture

Fill the [drill evidence template](../recovery/drill-evidence.md): drill
identifier, kind, source/environment identifier, pinned schema version and digest,
incident declaration time, last recoverable committed point and RTO stop time;
the measured RPO/RTO with units from field names; every step row D1–D4, R1–R5,
A1, W1, M1 with planned time, observed UTC time, result (`pass`, `fail`,
`not observed`), an approved internal evidence link and approver; and the four
sign-off decisions. Keep findings and corrective actions with an owner.

Evidence links must reference approved internal artifacts, never a patient record
or a signed URL. Run `python3 -I scripts/check-secrets.py` over the captured
artifacts and this log before sharing them.

## Pass criteria

The drill passes only when all of the following are observed:

- RPO ≤ 15 minutes and RTO ≤ 4 hours, both measured from recorded timestamps;
- all four data classes (`core_chart`, `order_data`, `documents`,
  `document_associations`) present in the record, with no duplicate or near miss;
- `database`, `objects` and `idp_dependencies` restored and `clean_environment`
  true;
- every reconciliation gate true and completed and signed **before** writes
  reopened;
- current revocations and withdrawals re-derived before any protected request;
- authorized staff and test-patient sign-in succeeds in the isolated environment
  with no production credential copied;
- before-pilot and quarterly cadence declared, with the next scheduled drill
  dated;
- two distinct named owners for restore execution and reopening approval, plus
  clinical integrity confirmation;
- no patient data, credential, real domain or PHI in any field, link, URL or log;
- a synthetic OPS-07 export artifact still verifies after the simulated restore:
  manifest, checksums, provenance, authorized documents, audit history, legal
  holds and backup-expiration entries intact
  (`python3 -I infra/tenant-lifecycle/tenant_lifecycle_evidence.py <artifact.json>` exits 0);
- the synthetic envelope evaluates with exit 0 and is labelled as a rehearsal,
  never final evidence.

A `conducted_drill` whose source contains a synthetic marker fails as an
`evidence_conflict`; keep the label honest. Passing the evaluator never replaces
the human G5 acceptance gate.

## Real commands used

| Command | Exit / evidence |
|---|---|
| `bash db/check.sh` | 0 and the canonical success line; proves migrations, RLS, isolation, append-only history and audit chain on synthetic PostgreSQL |
| `bash infra/recovery/check.sh` | 0; evaluator unit tests plus conforming and violating fixtures |
| `python3 -I infra/tenant-lifecycle/tenant_lifecycle_evidence.py <artifact.json>` | 0 pass / 1 failed checks / 2 unreadable input; evaluates the drill's own OPS-07 export/retention artifact |
| `bash infra/tenant-lifecycle/check.sh` | 0; fixture gate — OPS-07 tenant export, retention, legal-hold, backup-expiration and disputed-access fixture checks |
| `python3 -I infra/recovery/recovery_evidence.py <artifact.json>` | 0 pass / 1 failed checks / 2 unreadable input |
| `curl -fsS http://127.0.0.1:8080/health.txt` | Healthy response on the disposable bootstrap stack |
| `python3 -I infra/recovery/tests/coverage_gate.py` | 0; evaluator is fully covered (tool result, not a drill result) |
| `python3 -I scripts/check-secrets.py` | 0; no credential- or PHI-shaped strings in captured files |

`NOT YET IN PLACE:` managed PITR, object-store and IdP restore, clean-environment
provisioning, an automated elapsed-time timer and a clinical monitoring
dashboard. These are gaps to own, not steps to invent.
