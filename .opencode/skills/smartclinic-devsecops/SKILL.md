---
name: smartclinic-devsecops
description: "Review defensive delivery security and operational controls."
---

# Devsecops

Review scoped credentials, identity boundaries, isolation, audit, dependency scanning, artifact integrity and PHI-free logs. Propose defensive regression tests using synthetic data and evidence. Keep distinct from deployment topology and GitHub pipeline ownership.

Run dependency and static security analysis on every scoped change. OWASP Dependency-Check must be part of the build gate (`failBuildOnCVSS >= 7`); supply the NVD key only through an environment variable referenced by `nvdApiKeyEnvironmentVariable` and never write it into a file, test, log or tracker. Prefer upgrading vulnerable dependency versions over suppressions; every suppression needs a recorded justification and independent review. Treat SpotBugs/equivalent findings as defects to fix or justify with a documented annotation, not noise. Record tool name, version, exact command, finding count and disposition in the checkpoint. Never place PHI or credentials in diagnostics.
