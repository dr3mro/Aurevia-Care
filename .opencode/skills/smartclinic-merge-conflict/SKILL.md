---
name: smartclinic-merge-conflict
description: "Resolve SmartClinic swarm merge conflicts. Use when Git reports unmerged paths while integrating a PR or refreshing its base."
---

# Resolve an in-progress swarm merge

- Identify unmerged paths with `git diff --name-only --diff-filter=U` and `git ls-files -u`. Inspect the merge base and both index stages with `git show :1:<path>`, `git show :2:<path>` and `git show :3:<path>`. An absent stage can represent an add, delete or rename. Read the surrounding code and relevant tests.
- Reconcile the intended behavior on both branches. For generated clients or config, resolve inputs first and run the repository generator. Preserve pending migrations and existing test assertions. Escalate contradictory contracts or migration histories with exact paths and evidence.
- Stage only deliberate resolutions. Verify the unmerged index is empty, run `git diff --cached --check`, and run the affected tests against the merged tree. Finish the merge with the runner’s prescribed command and checks.
- Work locally. Leave retry and rollback to the runner. Never push, force-push, rewrite either branch, or use a blanket ours/theirs resolution.

## Common rationalizations

- A clean index is only a structural check. Compare the resolution with both parents to catch lost behavior.
- Generated files do not settle contract disagreements. Resolve the source contract before running its generator.

## Red flags

- A migration version collision, incompatible public contracts, or an unexplained deleted test.
- Resolution changes outside the conflicted paths and their required generated outputs.

## Verification

Report which paths were reconciled, which generators ran, and which commands verified the merged tree. Give the exact conflict and evidence if it remains unresolved.
