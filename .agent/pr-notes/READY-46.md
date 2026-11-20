# READY-46 — Launch checklist document with the owner of every item that needs a person

## Story and scope

- Task: `READY-46` (already marked `[-]` by the orchestrator, `agent: swarm-3`; I did not edit TODO.md).
- Scope used: `docs/operations/production-readiness` (new file `launch-checklist.md`) plus my own note file `.agent/pr-notes/READY-46.md`, which the task explicitly assigns to me. No other file was changed. No migration was added or edited. No tracker file was edited.
- Branch: `swarm/READY-46-251` at base `8771c36`. No push, tag or `gh` call was made.

## Requirement IDs and acceptance mapping

Requirements from the task: **WEB-01, WEB-11, WEB-12** (plus WEB-03, WEB-04, WEB-05-adjacent OPS-05, WEB-10, WEB-13, OPS-04 and G5 introduced by the required row list).

| Story step | Acceptance | Where satisfied |
|---|---|---|
| 1 | Create `docs/operations/production-readiness/launch-checklist.md`; one row per item; columns item, spec ID, owner (Infrastructure, Security, Product Owner, Clinical lead, Engineering), evidence required, status (open); role words only, no invented names | New file: six-column table (the sixth column is `Link`, added to satisfy step 3). All 15 rows have status `open`. Owners use only the allowed role words. |
| 2 | Include at least the 15 named rows | All 15 rows are present: TLS/HSTS (WEB-01), WAF+DDoS (WEB-04), OIDC/MFA/PKCE (WEB-03), backups/restore drill/RTO (OPS-04), secrets in manager (WEB-12), CI actions by SHA + images by digest (WEB-12), blocking scans (WEB-12), SBOM+signed artifact (WEB-11), 5/25/100 rollout with rollback (WEB-11), E2E CI job for the READY-45 suite (WEB-10), penetration test, status/support pages (WEB-13), on-call rota + incident runbook, privacy notice + accessibility statement, per-site PO and clinical sign-off (G5). |
| 3 | Link each row to an existing runbook/evidence file under `docs/operations/` or `infra/`, else `none yet` | `ls` survey below. Seven rows link to existing files: restore procedure, recovery evidence, CI evidence contract, signed builds, rollout, operational runbooks. Eight rows honestly say `none yet` because no such file exists. |
| 4 | Run `python3 -I scripts/check-docs.py` (links and requirement IDs resolve) | Green — exact command and output below. |
| Do NOT claim any row is done | Every row status is `open`; the document states it asserts nothing as done and that a synthetic rehearsal is not evidence. | New file, intro and status column. |

## `ls` survey of candidate link targets

`find docs/operations -type f` and `find infra -type f` were run. Existing files that genuinely cover a row:

- `docs/operations/s_g5_06/restore-procedure.md`, `docs/operations/s_g5_06/recovery-evidence.md` — restore drill / RPO-RTO row.
- `docs/operations/s_g5_06/operational-runbooks.md` — on-call / incident runbook row.
- `docs/operations/s_g5_07/evidence-contract.md` — dependency/image/secret scanning row (`ci_checks.dependency_scan`, `ci_checks.secret_scan`).
- `docs/operations/s_g5_07/build-and-migration-gates.md` — SBOM / signed artifact row (`signed_builds`, digest, promotion gate).
- `docs/operations/s_g5_07/rollout-and-production-access.md` — progressive rollout row.
- `infra/DEPLOYMENT.md` — cited as general deployment design handoff only, not as row evidence.

No runbook/evidence file exists for edge TLS/WAF, OIDC registration, secret-manager storage, action SHA/image digest pinning, the E2E CI job (the READY-45 suite is a separate, not-yet-merged story), penetration test, separately hosted status/support pages, the published privacy/accessibility statements, or per-site G5 sign-off. Those rows say `none yet` in the committed file.

## Exact commands run and results

Baseline and final, executed from the repository root:

```
$ python3 -I scripts/check-docs.py            # baseline, before adding the file
exit=0                                        # no output = no broken links, no unknown IDs

$ python3 -I scripts/check-docs.py            # after adding launch-checklist.md
exit=0                                        # no output = all 15 rows' links and IDs resolve

$ python3 -I scripts/check-names.py
exit=0
```

This is the check named in step 6 of "Instructions for every agent" for a
Markdown-only change (`python3 -I scripts/check-docs.py`). Result pasted above:
exit 0, no errors.

I also surveyed with `find docs/operations -type f` and `find infra -type f`
(results summarised above), and read the adjacent
`docs/operations/production-readiness/README.md` plus the s_g5_06/s_g5_07
READMEs and link-target files to choose accurate links.

### Fast unit tests — pre-existing baseline failures, not introduced here

```
$ python3 -I -m unittest discover -s scripts/tests -q
Ran 268 tests ... FAILED (failures=8, errors=10)
```

These 18 failures were present before I made any change (same result on the
clean tree). They are in `test_check_pack.py`, `test_agent_run.py`, `test_swarm.py`
and two loader errors (`test_fast_tier`, `test_workflow_concurrency`), and report
`opencode.json is stale: run scripts/gen-opencode.py` and CI/workflow-profile
staleness — the known worker CI-profile artifact described in MEMORY.md
(OPS-CW-003-01), not a consequence of this docs-only change. They are outside my
scope (`docs/operations/production-readiness`), so I did not modify them. The
integrator runs the authoritative gates. This is recorded as an observed baseline
defect, not a claim that the full suite is green.

```
$ bash scripts/quick-check.sh
exit=1
check-pack: opencode.json is stale: run scripts/gen-opencode.py
```

Verified as the same pre-existing artifact: `git ls-files -v opencode.json`
returns `S` (skip-worktree), so this worker's checked-out `opencode.json` is the
CI profile copied by `agent-run.sh`, not the committed local profile. The
committed HEAD profile is the correct generated file (MEMORY.md,
OPS-CW-003-01). `git status --short` shows only my two new files changed; the
fast gate's failure is not caused by and cannot be fixed within this docs-only
scope.

## Defects found

- None in the new document. `check-docs.py` and `check-names.py` are green.
- Observed (pre-existing, out of scope): the 18 failing Python tooling tests noted above, all failing on the clean tree before any edit. No patient data or credential was involved.

## Decisions

1. Added a sixth `Link` column while keeping the five required columns, because step 3 requires a per-row link/evidence target and the adjacent README uses the same `Link` pattern.
2. Used only the five allowed role words for owners; where two roles share a row ("Product Owner / Clinical lead") both are allowed role words and no name is invented.
3. Wrote `none yet` where no runbook/evidence file actually exists, rather than pointing at loosely related files, so the checklist does not overstate available evidence.
4. Assigned OPS-05 to the on-call/incident row (the spec clause that names on-call coverage owners and runbooks) and WEB-12 to the penetration-test row (the spec clause that requires it).
5. Kept every status `open` and added an explicit honesty note that synthetic rehearsals do not close a row.

## Evidence provenance

- Tool evidence: `python3 -I scripts/check-docs.py` (exit 0), `python3 -I scripts/check-names.py` (exit 0), `find`/`ls` surveys, and file reads listed above — all executed by me.
- Reviewer evidence: none requested; this is a contained documentation change (Workflow "routine" class). Skills loaded: `smartclinic-delivery`, `smartclinic-devsecops`, `smartclinic-product-owner`, `smartclinic-github-workflow`.
- Simulated-user evidence: none.
- Human / clinical / patient evidence: none. All rows are open and require the named human owners to supply real evidence before G5.

## Missing decision or evidence

None blocking. No owner names were invented (role words used as instructed), and the task's required check passes.
