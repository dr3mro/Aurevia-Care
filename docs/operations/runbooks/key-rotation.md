# Secret and key rotation

This runbook rotates a credential or key and checks that nothing broke. It
deliberately names a secret by its **configuration key**, never by its value.
 No credential, token, password, private key or customer identifier may be
 written into a runbook, a tracker, a log, a URL or a commit. See the
 [runbook index](index.md) for the common rules.

## When to use it

- A scheduled rotation interval in the approved policy pack (ADM-01) is reached,
  or a person/role with access changes.
- A credential may be exposed, or a secret-scanning / dependency-scanning check
  reports a finding that requires replacing a secret.
- A TLS certificate approaches its renewal or 21-day expiry-alert threshold
  (WEB-01).

`NOT YET IN PLACE:` there is no rotation schedule or tooling in this repository.
The approved interval, the responsible roles and the notification rules must
come from the policy pack; the launch checklist's secret-management row is open
and asks for a secret-manager inventory and a rotation record.

## Who may act

| Role | Responsibility |
|---|---|
| Security owner | Decides rotation is required, approves the new value and the retirement of the old one |
| Infrastructure owner | Applies the value to the runtime environment and confirms the deployment |
| Operational on-call owner | Runs the health and log checks and records the outcome |
| Privacy / accountable operations owner | Approves any rotation that affects protected data access |

Two roles are required: the person applying a value must not be the sole
approver. `NOT YET IN PLACE:` a named approver list and a secret-manager access
grant; no person or credential is stored in this repository.

## What actually holds secrets today

All values are supplied at runtime through environment variables or CI secrets.
The repository contains configuration keys and tests only.

| Configuration key | Where it is read | Purpose |
|---|---|---|
| `AUREVIA_DB_URL`, `AUREVIA_DB_USER`, `AUREVIA_DB_PASSWORD` | [application.yml](../../../apps/api/src/main/resources/application.yml) | Database connection; password defaults to empty |
| `AUREVIA_OIDC_ISSUER_URI`, `AUREVIA_OIDC_AUDIENCE` | [application.yml](../../../apps/api/src/main/resources/application.yml) | Managed OIDC validation; blank issuer fails startup outside the synthetic profile |
| `NVD_API_KEY` | OWASP plugin via `nvdApiKeyEnvironmentVariable` in [pom.xml](../../../apps/api/pom.xml) | Fresh NVD data for dependency analysis; absent from the tree, cached-data fallback |
| `OPENCODE_API_KEY_<slot>`, fallback `OPENCODE_API_KEY`, `SWARM_TOKEN` | Workflows and [docs/SWARM.md](../../../docs/SWARM.md) | Swarm worker authentication and workflow-file pushes |

`scripts/check-secrets.py` (wired into `scripts/check.sh`, `scripts/quick-check.sh`
and the Delivery policy job) scans tracked text for credential shapes, including
a hard-coded NVD key. A passing scan is evidence that no literal is in the tree,
not that a value was rotated.

A historical exposure of `NVD_API_KEY` is recorded in [BUGS.md](../../../.opencode/config/BUGS.md)
(`DS-G0-01-01`) and the durable decision in [MEMORY.md](../../../MEMORY.md) was
**not** to rotate it and not to rewrite published history. That decision is an
owner decision about that incident, not a general rule that secrets need not be
rotated. Any change to it requires a new owner decision; history rewrite and
force-push are out of scope for a worker.

## Steps

1. **Name the key and its scope.** Record the configuration key, the environment
   (development, test, staging or production), and the role requesting the
   rotation. Never write the value.
2. **Confirm the authority.** Check the policy pack's approved interval and the
   approval for this rotation. `NOT YET IN PLACE:` the policy pack and the
   rotation-approval record; if neither exists, stop and escalate rather than
   improvise a schedule.
3. **Obtain the new value through the approved channel.**
   `NOT YET IN PLACE:` a secret manager or rotation tool. Values are currently
   supplied as environment variables or repository/CI secrets, so the only
   in-repository step is to update the owning secret store outside this repo and
   make it available under the same configuration key.
4. **Apply to one scoped environment first.** Update the runtime source for the
   target environment and restart or redeploy the affected service so the new
   value is read. Do not change the schema or the application version as part
   of a rotation.
5. **Check the service came back.** Call `GET /health/live` and
   `GET /health/ready`. A blank OIDC issuer fails startup outside the synthetic
   profile; a wrong database password shows up as readiness HTTP 503
   ([HealthController](../../../apps/api/src/main/java/com/aurevia/web/HealthController.java),
   [SecurityConfiguration](../../../apps/api/src/main/java/com/aurevia/web/SecurityConfiguration.java)).
6. **Check nothing broke in behavior.** Read structured request logs for the
   affected routes: no new 401/403 wave for OIDC changes, no new 503 for database
   changes, and status and p95 within OPS-03. Use correlation IDs only.
7. **Run the security checks.** Run `scripts/check.sh` and, for an API change,
   the module gate. OWASP Dependency-Check 12.1.0 (`failBuildOnCVSS 7`) and
   SpotBugs 4.10.5.0 run in `mvn verify`; if `NVD_API_KEY` was rotated, confirm
   the dependency scan still completes with the new runtime value.
8. **Retire the old value.** Remove it from the secret store and from any
   environment that still references it. `NOT YET IN PLACE:` an automated
   "old key is unused" probe or a key-use audit; confirm manually with the
   Infrastructure owner and record the evidence.
9. **Repeat for each scoped environment**, with the same checks, before the
   production environment.
10. **Write the rotation record.** Attach it to the launch checklist's
    secret-management row and to the incident record if the rotation was
    reactive.

`NOT YET IN PLACE:` certificate renewal automation and the 21-day expiry alert
(WEB-01), a secret-manager inventory, key-use restriction and access review,
and an image vulnerability scanner. Each is an open row in the
[production readiness checklist](../production-readiness/launch-checklist.md).

## How to check it worked

- `GET /health/live` and `GET /health/ready` both return HTTP 200 `UP` in the
  rotated environment.
- A representative protected request succeeds with the new value; an
  unauthenticated request is still rejected, and no new authorization failures
  appear in the structured logs.
- `python3 -I scripts/check-secrets.py` and `scripts/check.sh` exit 0; the OWASP
  scan completes without a credential-related failure.
- The old value no longer authenticates anywhere, and no value appears in a
  commit, log, link or tracker entry.

## What to record

Configuration key (not the value), environment, UTC start and end instants,
requesting and approving roles, the old value's retirement time, each check and
its final line, failures and corrective actions, and the link to the rotation
record. Mark a rehearsal with synthetic values as a rehearsal.

## Related material

[Runbook index](index.md) · [Incident response](incident-response.md) ·
[Production readiness checklist](../production-readiness/launch-checklist.md) ·
[Vulnerability management and dependency checks](../../../apps/api/pom.xml)
