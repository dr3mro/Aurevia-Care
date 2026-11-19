# SmartClinic working rules

Read WORKSPACE.md and the current checkpoint in PROGRESS.md first. Read MEMORY.md, BUGS.md and SMARTCLINIC_V1_SPEC.md by relevant IDs. Current code and executed checks are implementation evidence. Preserve unrelated changes. Never include patient data or credentials in agent context, tests, logs or trackers.

Use the smartclinic-delivery skill. The primary agent owns edits and history; specialists return bounded findings and patches in their reports. Route each task to its domain specialist. Give specialists only story IDs, paths, acceptance criteria and relevant evidence. Prefer one bounded review per stage; combine independent reviews when useful. Rehydrate from tracking files after context resets. Report unavailable delegation accurately.

Run one story at a time. Save each stage to PROGRESS.md and update MEMORY.md with its current checkpoint pointer and any durable decision. Update WORKSPACE.md when branch, commands or environment changes. Record defects in BUGS.md. Keep unrelated notes out of the active checkpoint.

Start with failing acceptance/regression tests, implement the smallest change, then refactor and verify. Apply CODING_STYLE.md. No uncovered production code is permitted by default. Require all applicable gates and observed evidence before calling a commit releasable. Use synthetic fixtures and defensive tests. Follow real-patient approval gates in the specification.

When the active execution request authorizes GitHub delivery, commit scoped passing changes and push to the agreed delivery branch. Wait for the required checks on the exact SHA and repair failures through a new tested commit. Do not force-push or bypass protections. Tag/publish releases only under the release policy and with release authority. Keep production deployment separately authorized.

Read project.json for the current display name and suggested repository slug. Use stable technical identifiers for agent routing and the GitHub remote for publication.

Use docs/WORKFLOW.md to route routine, cross-boundary and safety-critical changes. Document changed public contracts, build scripts/docs.sh, and include a short entry-point-to-test trace in review evidence.
