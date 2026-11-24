# Launch checklist — items that need a person

This is the G5 memory aid for launch work an agent cannot complete: it needs cloud
access, a human decision or workflow permissions. It is derived from
[section 8.1 of the Orevia Cloud V1 specification](../../../.opencode/config/PROJECT_V1_SPEC.md)
and the adjacent [production readiness evidence register](README.md). The
register tracks the CI-producible evidence for WEB-01 to WEB-13; this checklist
tracks only the items that must be carried by a named role. "Owner" is a role,
not a person's name — the accountable individual is recorded on the G5
acceptance record, not here.

Every row is `open`. This document asserts nothing as done: a status may change
only when the owner attaches the evidence described in that row and the G5
acceptance owner records the sign-off. A passing synthetic rehearsal in this
repository is not evidence for any row below.

| Item | Spec ID | Owner | Evidence required | Status | Link |
|---|---|---|---|---|---|
| Managed TLS 1.2 or newer certificates and HSTS at the edge, with HTTP-to-HTTPS redirect, automatic renewal and a 21-day expiry alert | WEB-01 | Infrastructure | External TLS scan recorded before pilot showing TLS 1.2+, HSTS max-age at least one year with includeSubDomains and the redirect; provider configuration for automatic renewal and the 21-day alert | open | none yet |
| Managed WAF with the OWASP core rule set and provider DDoS protection in front of the API and staff web | WEB-04 | Security | WAF rule test result and the provider DDoS configuration recorded before pilot | open | none yet |
| Managed OIDC provider with staff MFA and a registered mobile authorization-code-with-PKCE client | WEB-03 | Security | Provider configuration showing enforced staff MFA and the registered PKCE client, plus the automated expiry, revocation, CSRF and lockout test results | open | none yet |
| Database backups with a conducted restore drill into a clean environment and a recorded recovery time | OPS-04 | Engineering | Authorized drill artifact with measured RPO (15 minutes or less) and RTO (4 hours or less), plus reconciliation of hashes, source versions, in-flight deliveries and audit coverage before reopening writes | open | [Restore procedure](../recovery/restore-procedure.md), [Recovery evidence](../recovery/recovery-evidence.md) |
| Secrets held in a secret manager, with none stored in images or the repository | WEB-12 | Security | Secret-manager inventory, an image and repository scan showing no credentials, and the rotation record; `NVD_API_KEY` supplied only at runtime | open | none yet |
| CI actions pinned by commit SHA and base images pinned by digest | WEB-12 | Engineering | Workflow and Dockerfile review showing action SHAs and image digests, with the resolved digest recorded at publication | open | none yet |
| Dependency, image and secret scanning that blocks merges on critical findings | WEB-12 | Security | Pipeline evidence that OWASP dependency, image and secret scans run on every change, critical findings block merges and remediation meets the 7-day and 30-day targets | open | [CI evidence contract](../delivery-safety/evidence-contract.md) |
| SBOM and one signed artifact promoted unchanged through environments | WEB-11 | Engineering | SBOM, verified signature and matching digest for the artifact promoted from staging to production without rebuild | open | [Signed builds and migration checks](../delivery-safety/build-and-migration-gates.md) |
| Progressive rollout at 5%, 25% and 100% with automatic rollback on error-rate or latency breach | WEB-11 | Infrastructure | Monitored rollout record showing all three stages, health gates, a rehearsed rollback and the release checklist with named approvers | open | [Rollout and production access](../delivery-safety/rollout-and-production-access.md) |
| E2E CI job that runs the automated accessibility and end-to-end suite (READY-45) on every release candidate | WEB-10 | Engineering | A protected CI job running the browser journeys in English and Arabic RTL with a zero serious-or-critical accessibility scan and the retained report; a person must add the job because workflow files are outside worker scope | open | none yet |
| Independent penetration test with every finding tracked to closure | WEB-12 | Security | Penetration-test report commissioned before pilot and after major changes, with a finding register showing disposition and closure | open | none yet |
| Status/maintenance page and support page hosted separately from the authenticated apps | WEB-13 | Infrastructure | Deployed pages with no patient data carrying the privacy notice, accessibility statement, terms and support contact, plus noindex and robots.txt checks on the authenticated apps | open | none yet |
| Named on-call rota and incident runbook with a recorded owner and escalation trail | OPS-05 | Infrastructure | Published rota with named operational and clinical coverage owners, the four required runbook topics and an escalation record for critical alerts | open | [Operational runbooks](../recovery/operational-runbooks.md) |
| Privacy notice and accessibility statement published on the support page | WEB-13 | Product Owner | Published privacy notice and accessibility statement reviewed by the accountable privacy and accessibility owners | open | none yet |
| Per-site Product Owner and clinical sign-off covering workflow, release policy, market constraints, integrations, identity procedures, staffing and downtime drills | G5 | Product Owner / Clinical lead | Signed site acceptance matrix and the final G5 acceptance record with no unresolved patient-safety, tenant-isolation, record-integrity or critical-escalation defect | open | none yet |

## How this checklist is used

The owner of each row attaches the named evidence and records it on the G5
acceptance record; the integrator does not close a row from a repository check.
Rows with `none yet` have no runbook or evidence file under `docs/operations/`
or `infra/` at the time of writing — the owner must create the artifact as part
of closing the row.

The general deployment design handoff is [infra/DEPLOYMENT.md](../../../infra/DEPLOYMENT.md);
it states topology and operational expectations but does not replace the
evidence required by any row above.
