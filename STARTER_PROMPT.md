# OpenCode starter prompt

Use this to start the SmartClinic V1 delivery loop in GitHub Actions. Setup steps are in [docs/TEAM-ACTIONS.md](docs/TEAM-ACTIONS.md).

## Start the first run

1. Create an issue titled `SmartClinic V1 delivery loop`.
2. Paste the prompt below as the issue body.
3. Add the `story` label. The orchestrator starts, works one story and opens a pull request. Three reviewers then comment on it.

```text
Resume SmartClinic V1 in this repository. Start with AGENTS.md, WORKSPACE.md and the latest PROGRESS.md checkpoint. Read MEMORY.md, BUGS.md and TODO.md, and read SMARTCLINIC_V1_SPEC.md only by the requirement IDs you need.

This is the first run if PROGRESS.md still says execution has not begun. In that case:
1. Inventory the repository and record the baseline in PROGRESS.md and WORKSPACE.md.
2. Take the smallest G0 story from TODO.md. Start with the first IAM-01, IAM-02, ARC-02 and audit slice that can be verified on its own. Write its acceptance scenarios and test plan in PROGRESS.md.
3. Write the failing tests first through a normal entry point, run them and record the red result. Implement the smallest change, refactor, and rerun until green. Follow CODING_STYLE.md.
4. Run scripts/check.sh and record the exact commands and results.

On later runs, pick the next eligible story from TODO.md and repeat steps 2 to 4.

Rules:
- One story only (WIP one). Use synthetic data only and never put patient data or credentials in code, tests, logs or trackers.
- Commit to the current branch with small scoped commits. Do not push, tag, force-push or call gh. The workflow publishes.
- Route each changed area to its specialist for a bounded review: backend, database, spring security, react, mobile, integrations, shell and so on.
- Update PROGRESS.md with one compact row per stage, MEMORY.md with the checkpoint pointer and any durable decision, and BUGS.md with observed defects.
- If a decision, evidence or access is missing, stop. Write the question in PROGRESS.md under "Blocked" and say what you need.
- End by writing "Next story:" with its ID and acceptance focus in PROGRESS.md.
```

## Continue the loop

After the pull request is reviewed and merged, comment this on the same issue:

```text
/oc next
```

The orchestrator reads "Next story:" in PROGRESS.md and runs the next story. You can also run the **OpenCode team** workflow manually with the story text `Continue the loop: pick the next eligible story from TODO.md`.

To change a pull request, comment on the pull request:

```text
/oc fix <what to change>
```

Pull requests reference the loop issue with `Refs #N` so merging one does not close it. Close the issue when the V1 gates pass.

## Stop conditions

The loop stops by itself when the model is rate limited, a run times out, TODO.md has no eligible story, or the agent writes a blocker in PROGRESS.md. Read the issue comment, resolve the blocker, then comment `/oc next`.
