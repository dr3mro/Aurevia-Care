# Operational exercise sheets (recovery and failure drills)

These sheets are **scripts for rehearsing failure with synthetic data**. They are
not evidence that a drill ran, they record no measurements, and they are not an
approval. Run a rehearsal only on synthetic fixtures. Run a conducted drill only
under an authorized incident or scheduled exercise with the named owners. Never
place patient data, credentials, a real domain or PHI in a drill log, an evidence
link, a URL or these sheets. Human acceptance of a conducted drill remains a G5
gate; a passing offline evaluator or a synthetic rehearsal never closes it.

Risk class (see [WORKFLOW.md](../../WORKFLOW.md)): **safety-critical
rehearsal**. These scripts exercise patient identity, permissions, critical
results and recovery. They were reviewed only as synthetic static documentation;
a clinical safety, security and independent QA review is required before any
sheet is used for live acceptance.

| Sheet | Goal | Primary requirements | Targets checked |
|---|---|---|---|
| [Database restore drill](./database-restore-drill.md) | Restore database, objects and IdP dependencies into a clean environment and reconcile before reopening writes | OPS-04, OPS-07 | RPO ≤ 15 min; RTO ≤ 4 h; four data classes; three restore classes; reconciliation gate; cadence; OPS-07 export/retention verification |
| [Partner outage drill](./partner-outage-drill.md) | Results stop arriving and orders pile up: detect, contain and drain without duplicates or a lost critical result | OPS-05, OPS-03, ARC-05, ADM-02 | 13 monitoring signals; named owners; human critical acknowledgment; p95 read ≤ 500 ms / command ≤ 900 ms |
| [Lost-credential drill](./lost-credential-drill.md) | A staff credential or secret is lost: revoke, rotate, prove nothing broke, keep secrets out of evidence | OPS-02, WEB-03, WEB-12, IAM-02 | Next-request revocation; append-only audit chain; no secret in repo or image; separation of duties |

## Shared rules for every exercise

1. **Synthetic only.** Use invented tenants, staff subjects and patients. Never
   copy a real identifier, phone number, e-mail address, credential or patient
   value into a sheet, a log, an artifact or a URL.
2. **No drill was run to produce these sheets.** An agent writing a sheet executes
   none of the steps below. Only an authorized operations team runs a drill.
3. **Capture observed values, never targets.** Copying `15`, `4` or a target boolean
   into a measured field fabricates evidence (see
   [recovery targets](../recovery/recovery-targets.md)).
4. **Gates are declarations, not telemetry.** The offline evaluators check that a
   declared envelope is complete and fail-closed; they cannot prove a restore, an
   alert or an operator action happened. See
   [recovery evidence](../recovery/recovery-evidence.md) and
   [operational runbooks](../recovery/operational-runbooks.md).
5. **Record approvals by role, not by a passing check.** One person must not both
   execute a restore and be its sole approver.
6. **Escalate out of scope.** If a step needs a tool, contact or authority that
   does not exist, write `NOT YET IN PLACE:` with the gap; do not invent it.

## Commands and checks the repository actually has

These are the real instruments a drill may use. An exercise sheet cites only
these, or marks a step `NOT YET IN PLACE:`.

| Command | What it proves | Source of truth |
|---|---|---|
| `bash db/check.sh` | Applies every Flyway migration to an ephemeral synthetic PostgreSQL database; asserts forced RLS, role separation, tenant isolation, append-only staff history, an intact audit hash chain and versioned config | [db/check.sh](../../../db/check.sh) |
| `bash infra/recovery/check.sh` | Recovery-evidence evaluator unit tests plus conforming and violating fixture runs | [recovery evidence](../recovery/recovery-evidence.md) |
| `python3 -I infra/recovery/recovery_evidence.py <artifact.json>` | Evaluates an OPS-04/OPS-05 evidence artifact; exit 0 pass, 1 failed checks, 2 unreadable input | [recovery targets](../recovery/recovery-targets.md) |
| `bash infra/tenant-lifecycle/check.sh` | OPS-07 tenant export/retention evaluator unit tests plus fixed positive and negative fixtures (a fixture gate, not an artifact evaluator) | [export manifest](../tenant-lifecycle/export-manifest.md) |
| `python3 -I infra/tenant-lifecycle/tenant_lifecycle_evidence.py <artifact.json>` | Evaluates a drill-supplied OPS-07 export/retention artifact; exit 0 pass, 1 failed checks, 2 unreadable input | [tenant_lifecycle_evidence.py](../../../infra/tenant-lifecycle/tenant_lifecycle_evidence.py) |
| `bash infra/ops-queues/check.sh` | ADM-02 operational-queues evaluator gate with positive and negative fixtures | [queue catalog](../ops-queues/queue-catalog.md) |
| `python3 -I infra/ops-queues/ops_queues.py <artifact.json>` | Evaluates a synthetic or operator-attested queue snapshot | [ops_queues.py](../../../infra/ops-queues/ops_queues.py) |
| `python3 -I scripts/check-secrets.py` | Fails on credential- or PHI-shaped strings in repository files | [check-secrets.py](../../../scripts/check-secrets.py) |
| `python3 -I scripts/check-pack.py`, `python3 -I scripts/check-docs.py` | Validates the agent pack, Markdown links and requirement IDs | [check.sh](../../../scripts/check.sh) |
| `curl -fsS http://127.0.0.1:8080/health.txt` | Bootstrap test-stack health response once the disposable stack is up | [README-SETUP.md](../../../.opencode/config/README-SETUP.md) |
| `GET /health/live`, `GET /health/ready` | Unauthenticated liveness and readiness of the real API (WEB-05); readiness checks the database and leaks no exception text | [HealthController.java](../../../apps/api/src/main/java/com/aurevia/web/HealthController.java) |

`docker compose up -d` / `docker compose down` start and stop the disposable
bootstrap stack described in [README-SETUP.md](../../../.opencode/config/README-SETUP.md). Docker is
not available in every worker environment; the drill owner confirms it before
using that step. `scripts/monitor.sh` is a **GitHub Actions swarm dashboard**, not
a clinical monitoring tool: it must not be cited as evidence for any OPS-05
signal.

## Reconciliation and evidence

- The empty envelope is [drill-evidence.md](../recovery/drill-evidence.md). It is
  a template, not a result.
- The procedure it records is the [recovery drill runbook](../recovery/runbook.md)
  and the [clean-environment restore procedure](../recovery/restore-procedure.md).
- The launch item that needs a conducted restore is the OPS-04 row of the
  [launch checklist](../production-readiness/launch-checklist.md).
- Deployment topology and operational expectations live in
  [DEPLOYMENT.md](../../../infra/DEPLOYMENT.md).
- The [production-readiness register](../production-readiness/README.md) tracks
  the CI-producible evidence beside these human gates.

## Known gaps across all drills

`NOT YET IN PLACE:` the following are required by the specification but have no
in-repository command or artifact in these sheets, so each sheet names the gap at
the step where it is needed: managed point-in-time restore, object-store restore,
identity-provider and secret-manager administration, a clinical monitoring
dashboard and alert routing, a partner-delivery inject simulator, a live
session-revocation endpoint, a named on-call rota and escalation contacts, and a
human G5 acceptance record.
