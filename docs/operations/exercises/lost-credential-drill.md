# Exercise sheet — lost-credential drill

Back to the [exercise index](./index.md). This is a rehearsal script, **not**
evidence, not a real compromise and not an approval. Use invented subjects and
synthetic credentials only. Never write a real key, token, password, domain or
patient value into a drill log, artifact, URL or these sheets. No drill was run to
write this sheet.

## Goal

A staff credential, signing secret or integration secret is lost or suspected
compromised. Revoke it so it fails on the next protected request, rotate it in the
approved store, terminate existing sessions, prove with the repository's real
checks that no secret leaked into the repository or image, and preserve an
append-only audit trail. Keep separation of duties and the remediation clock
visible; do not let a passing check become an approval.

## Who takes part

| Role | Responsibility in this drill |
|---|---|
| Facilitator | Announces the synthetic loss, injects follow-on findings, times the script and asks the questions |
| Operational on-call owner | Declares the incident, drives containment and watches error rate and audit status |
| Security owner | Owns revocation and rotation in the managed identity/secret store; confirms no secret in repo or image |
| Privacy / accountable operations owner | Approves any emergency clinical access and the close-out decision |
| Site clinical coverage owner | Confirms that emergency access stayed scoped inside its own organization and time-limited (IAM-06) |
| G5 acceptance owners | Accept conducted-drill evidence; a separate human gate |

`NOT YET IN PLACE:` an in-repository identity-provider or secret-manager console,
a named on-call rota and escalation contacts, and a session-revocation endpoint.
The facilitator records role names and timestamps in the drill log for each of
these steps rather than inventing a command.

## Targets taken from the specification

| Concern | Target | Source |
|---|---|---|
| Encrypt and review | Transport, data, queues and backups encrypted; least privilege and regular access review; audit append-only with restricted access and tamper detection | OPS-02 |
| Sessions | Staff MFA; server-side logout; list and revoke sessions; renewed authentication for sensitive actions; lockout and rate limiting; mobile authorization code with PKCE | WEB-03 |
| Secrets in code | Dependencies, images and secrets scanned on every change; critical findings block merges; critical remediated within 7 days, high within 30; base images pinned by digest and CI actions by commit SHA | WEB-12 |
| Permissions | Permission changes take effect on the next protected request; expired or removed membership fails closed | IAM-02 |
| Emergency access | Qualified clinicians, own organization only, time-limited, reasoned, alerted and reviewed; support access disabled by default | IAM-06 |
| Production access | Approved, attributable, audited, expiring, with separation of duties between requester and approver | OPS-06; [rollout and production access](../s_g5_07/rollout-and-production-access.md) |
| Rollback | Promote one signed artifact with an SBOM; progressive rollout with automatic rollback on error-rate or latency breach | WEB-11; [signed builds and migration checks](../s_g5_07/build-and-migration-gates.md) |
| Observability | Structured logs with correlation id, no tokens or patient fields; alert on availability and monitoring targets | WEB-06, OPS-03, OPS-05 |

## Preconditions and synthetic setup

1. The facilitator declares a synthetic incident time in UTC and states only that
   "a synthetic staff credential may be compromised". No real value is disclosed.
2. Every credential mentioned in this drill is a freshly generated *synthetic*
   test value or a placeholder description. The repository rule is that
   `NVD_API_KEY` is supplied only at runtime and never stored in the repository;
   no other credential is committed (see the
   [launch checklist](../production-readiness/launch-checklist.md)).
3. The facilitator confirms the real commands below exist in the checkout. A step
   without one is marked `NOT YET IN PLACE:` in the drill log.
4. `scripts/monitor.sh` is a GitHub Actions swarm dashboard, not a security
   monitor; it is not evidence for this drill.

## Timed script with injects

`T+` is minutes from the synthetic incident declaration.

| T+ | Inject / trigger | Operator action (real command where one exists) | Expected observation |
|---:|---|---|---|
| 0 | Facilitator: "A synthetic staff credential is reported lost." | On-call owner declares the incident, records UTC time and opens the drill log | Incident and named owner recorded; no secret value recorded |
| 5 | "Treat the subject as untrusted from now on." | Security owner disables the identity in the managed IdP. `NOT YET IN PLACE:` no in-repo IdP admin command | Disable time and actor recorded by role |
| 10 | "A protected request arrives with the lost credential." | Owner uses the append-only revocation path: an appended `revoked` membership event makes the next check deny. The same property is proved on synthetic PostgreSQL by `bash db/check.sh` (its check 9 adds a revoke and asserts the next `has_active_membership` is `false` and `active_roles` is `0`) | `db: migrations, forced RLS, role separation, isolation, audit, append-only staff history and versioned config verified`; no stale grant |
| 15 | "This person had an active web and mobile session." | Owner terminates sessions server-side and requires renewed authentication for sensitive actions (WEB-03). `NOT YET IN PLACE:` no in-repository session-list or session-revoke endpoint was found in the API main source | The gap is recorded with the spec clause; no claim of a completed revocation |
| 20 | "Rotate the affected integration secret." | Security owner rotates it in the managed secret manager and rebinds the runtime. `NVD_API_KEY` stays a runtime-only environment value. `NOT YET IN PLACE:` no in-repo secret-manager integration or rotation command | Rotation time, scope and verifier recorded; no secret in the log |
| 25 | "Did the old secret ever land in the repository?" | Run `python3 -I scripts/check-secrets.py` and `python3 -I scripts/check-pack.py` over the checkout | Both exit 0; no credential- or PHI-shaped string found |
| 30 | "Is production access still appropriate?" | Owner checks that the emergency production grant is approved, attributed, audited and expiring with requester distinct from approver; gate runs with `bash infra/s_g5_07/check.sh` | Evaluator passes; separation of duties and expiry declared, not asserted by a flag |
| 35 | "Did any emergency access breach tenant or time limits?" | Site clinical coverage owner confirms any break-glass use was qualified, reasoned, alerted, reviewed and confined to its own organization (IAM-06). Audit chain and append-only history are proved on synthetic PostgreSQL by `bash db/check.sh` | Audit chain intact; no cross-tenant access; scope and reason recorded |
| 40 | "What if the loss accompanied a bad release?" | If so, follow rollback under WEB-11 and the bad-deploy row of the [launch checklist](../production-readiness/launch-checklist.md): detect, roll back to the last signed digest, roll forward only with a verified artifact. `bash infra/s_g5_07/check.sh` checks the signed-build and migration declarations | Signed digest and promotion gate verified; no rebuild of the promoted artifact |
| 45 | "Re-issue access to the legitimate person." | Issue a fresh synthetic credential, require MFA and renewed authentication for sensitive actions, and re-check current membership before the first protected request (IAM-02) | First protected request succeeds with the new credential only; the old one still fails |
| 55 | Close-out | Owner records error rate and audit observations over the window, tracks the 7-day critical / 30-day high remediation clock (WEB-12) and writes corrective actions with owners | Every failed step has an owner and a next action |

## Questions the facilitator asks

1. At what UTC time did the lost credential stop being accepted, and what proves
   the next protected request denied it (IAM-02)?
2. Were all sessions ended server-side, or only the browser and app local state
   cleared? Where is the evidence, and what does WEB-03 require?
3. Which secret rotated, who rotated it, and how was the runtime rebound without
   writing the new value into the repository, an image, a log or a URL?
4. What did `scripts/check-secrets.py` and `scripts/check-pack.py` find, and what
   would a non-zero exit mean for close-out?
5. Is the audit trail append-only with an intact hash chain, and does it record
   actor, purpose, scope, timestamp, action, resource id, correlation id and
   outcome (OPS-02)?
6. Was any emergency access scoped to the correct organization and time window,
   and who reviewed it (IAM-06)?
7. Are requester and approver different people for any production access used,
   and is the grant expiring and audited (OPS-06)?
8. Does the evidence contain only synthetic values, and is the remediation clock
   tracked rather than assumed complete?

## Evidence to capture

- Declaration time, revocation time, rotation time, session termination time and
  re-issue time, each with the named role that acted.
- Exact commands and final lines for `python3 -I scripts/check-secrets.py`,
  `python3 -I scripts/check-pack.py`, `bash db/check.sh` and
  `bash infra/s_g5_07/check.sh`, plus the signed-build and rollout declarations
  in [build and migration gates](../s_g5_07/build-and-migration-gates.md) and
  [rollout and production access](../s_g5_07/rollout-and-production-access.md).
- The audit-event references for the disable, rotate, emergency access and
  close-out decisions, with correlation ids and same-tenant resource ids.
- The OPS-02 access review outcome and the WEB-12 remediation register with the
  7-day and 30-day clocks.
- A findings and corrective-actions list with an owner per item. No raw secret,
  token, password or patient value is ever part of the evidence.

Evidence links must reference approved internal artifacts, never a patient record
or a signed URL.

## Pass criteria

- The lost credential fails on the next protected request with no stale
  membership or role grant (IAM-02), demonstrated by the revocation path that
  `bash db/check.sh` exercises.
- Sessions are terminated server-side and sensitive actions require renewed
  authentication; if the in-repo endpoint is absent, this is recorded as an open
  gap, never a pass.
- The secret is rotated in the approved store, the runtime rebound, and no
  credential appears in the repository, an image, a log, a link or a URL;
  `scripts/check-secrets.py` and `scripts/check-pack.py` exit 0.
- Audit storage is append-only with an intact per-tenant hash chain and no
  cross-tenant emergency access; IAM-06 scope, reason and review are recorded.
- Production access is approved, attributable, audited, expiring and separated
  between requester and approver (OPS-06).
- If a bad release was involved, rollback used the previously signed digest and
  the promotion gate was verified before any roll-forward (WEB-11, WEB-12).
- The remediation clock is tracked with named owners; no critical finding is
  silently marked done.
- Evidence is synthetic only and contains no patient data, credential, real
  domain or PHI. Passing the offline evaluator never replaces the human G5
  acceptance gate.

## Real commands used

| Command | Exit / evidence |
|---|---|
| `bash db/check.sh` | 0; proves append-only staff history, next-request revocation, forced RLS and an intact audit chain on synthetic PostgreSQL |
| `python3 -I scripts/check-secrets.py` | 0; no credential- or PHI-shaped strings in tracked files |
| `python3 -I scripts/check-pack.py` | 0; agent pack and generated config valid |
| `bash infra/s_g5_07/check.sh` | 0; OPS-06 evaluator unit tests, signed-build and migration fixture checks, and `check-migrations --require-baseline` |
| `python3 -I infra/s_g5_06/recovery_evidence.py <artifact.json>` | 0 / 1 / 2, only when the drill also requires a data restore |

`NOT YET IN PLACE:` managed IdP and secret-manager administration, a live
session-revocation endpoint, an image and repository secret scanner wired to a
drill artifact, a named on-call rota and escalation contacts, and the human G5
acceptance record. These are gaps to own, not steps to invent.
