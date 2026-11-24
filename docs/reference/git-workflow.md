# Git workflow and conflict policy

How Orevia work moves from a branch to `main`. This is the short reference for
workers and integrators; the authoritative rules for gates, trackers and release
authority live in `AGENTS.md`, `docs/WORKFLOW.md` and `README-SETUP.md`.

## Branch and commit discipline

- Keep branches short-lived and scoped to one task. A branch that lives for days
  accumulates conflicts and hidden coupling; split the work into two tasks instead.
- Commit in thin, verifiable slices: implement one behavior, prove it with a test,
  run the relevant check, commit. A single large commit hides which slice regressed.
- Write messages that explain *why* the change exists, not just which files changed.
- Never force-push, hard-reset, bypass hooks or rewrite a published commit. The
  harness denies these; a person with authority handles exceptional history edits.
- Never commit credentials, real patient data or raw logs. Synthetic fixtures only.

## Before the final gate

A worker checks out a base that can be several commits behind `main`. Stale bases
are the most common cause of "local checks: failed" and of a draft pull request.
Refresh before the final check:

1. Fetch and merge or rebase onto the current `main` (the worker script does this
   automatically before its last gate).
2. Re-run the checks for every file that changed in the refresh, especially
   `gen:api:check` when `contracts/openapi.yaml` moved.
3. Resolve conflicts **before** publishing; never publish a conflicting branch and
   expect the integrator to guess.

## Resolving a conflict

Preserve both sides' intent, then prove the result:

- **Generated or refactor files**: keep the current `main` version (for example the
  regenerated `openapi.d.ts`, a renamed package, or a moved helper). Re-run the
  generator rather than hand-editing generated output.
- **Contract or feature files**: keep the pull request's new routes, schemas, tests
  and behavior, rebased onto `main`'s structure. A conflict here is a signal that
  the contract changed twice; check both sides against `contracts/openapi.yaml`.
- **Documentation-only conflicts**: prefer `main` when the wording is equivalent.
  Keep the PR's version only when it carries a correction or a fact `main` lacks.
- After resolving, run the checks for the touched areas and name the resolution and
  its evidence in the notes. A conflict resolved without a re-check is not resolved.

## Merging and bookkeeping

- The integrator merges with a passing gate; workers never merge their own pull
  request. Merged work must be recorded: mark the task `[x]` with evidence and the
  PR number, and close any stale handoff issue for the same task.
- A person may merge manually. When that happens, the workflow bookkeeping still
  has to be done by hand: reconcile `tasks/`, close the handoff issue and confirm
  the delivery run for the merged commit. An open handoff issue for a merged PR is
  stale noise, not a reminder to merge again.
- A draft pull request means the worker did not finish cleanly. Marking it ready
  without resolving the underlying check failure hides the problem; find the real
  failing tool first.

## Releases

Release tags are a separate authority. Tag only an exact green default-branch
commit, follow the version and tag policy in `README-SETUP.md`, and treat tags as
immutable. Production deployment is authorized separately from publication.
