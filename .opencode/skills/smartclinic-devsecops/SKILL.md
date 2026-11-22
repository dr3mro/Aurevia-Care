---
name: smartclinic-devsecops
description: "Reviews defensive security and operational controls of a change. Use when a change touches credentials, identity, tenant isolation, audit, dependencies, artifacts, logging or the supply chain."
---

# Devsecops

Review scoped credentials, identity boundaries, isolation, audit, dependency scanning, artifact integrity and PHI-free logs. Propose defensive regression tests using synthetic data and evidence. Keep distinct from deployment topology and GitHub pipeline ownership.

Run dependency and static security analysis on every scoped change. OWASP Dependency-Check must be part of the build gate (`failBuildOnCVSS >= 7`); supply the NVD key only through an environment variable referenced by `nvdApiKeyEnvironmentVariable` and never write it into a file, test, log or tracker. Prefer upgrading vulnerable dependency versions over suppressions; every suppression needs a recorded justification and independent review. Treat SpotBugs/equivalent findings as defects to fix or justify with a documented annotation, not noise. Record tool name, version, exact command, finding count and disposition in the checkpoint. Never place PHI or credentials in diagnostics.

**Review verdict.** When the orchestrator asks for the security review of a finished change, return one finding per line as `Critical: <file>:<line> ...`, `Required: ...` or `Optional: ...` (a risk to PHI, tenant isolation, credentials or the supply chain is Critical or Required) and end with exactly one last line, `VERDICT: GO` or `VERDICT: NO-GO`. The swarm's review gate (`scripts/check-verdicts.py`) reads that line.

## Common rationalizations

| Rationalization | Reality |
|---|---|
| The suppression is a false positive. | Prefer upgrading the dependency. Every suppression needs a recorded justification and independent review. |
| A debug log with the payload helps me now. | PHI and credentials never go into logs, diagnostics, tests or trackers. |
| The scan is slow, run it at the end. | Scans run on every scoped change; findings are fixed or justified, not noise. |

## Red flags

- A secret in a file, test, log or tracker.
- A suppression with no justification.
- A token reaching a step that runs untrusted code.

## Verification

- [ ] Tool, version, exact command, finding count and disposition are recorded.
- [ ] Dependency analysis fails the build at CVSS 7 or higher.
- [ ] Logs and diagnostics were checked for PHI.
