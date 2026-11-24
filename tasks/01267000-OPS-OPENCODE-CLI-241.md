- [x] OPS-OPENCODE-CLI-241 · Update the prepared agent image to OpenCode CLI 2.4.1 with its verified integrity
  - agent: orca
  - branch: main
  - scope: .github/agent-image/Dockerfile, .github/workflows/agent-image.yml, scripts/tests/test_agent_image.py
  - evidence: local tool commands below; CI run 45319400642 failed at a67ebd02, fix commit 78411794 has `Delivery tooling tests` success in run 45319793973
  - pr: none, direct commits on main (24e999ec, a67ebd02, 78411794); issue #1591 closed COMPLETED
  - details: The agent image pins `@opencode/cli` by version and sha512 npm integrity in the
    Dockerfile and repeats both values as the workflow dispatch default and fallback. Update both
    pins to 2.4.1 and the integrity of that exact tarball, keep the reproducibility test in step,
    and add assertions so version and integrity cannot drift apart. Verify the integrity against
    the real registry tarball before editing. Acceptance: Dockerfile and workflow agree on version
    and integrity, the pin test is green, and the integrity is the sha512 of the 2.4.1 tarball.

## Progress
- [x] Integrity obtained from npm registry and reproduced from the real tarball
- [x] Red test written
- [x] Pins updated to 2.4.1
- [x] Targeted and repository checks green
- [x] Specialist reviews recorded
- [x] Committed

## Test evidence (tool evidence)
- `npm view @opencode/cli@2.4.1 dist.integrity dist.shasum version --json` → `sha512-3TbGzJO3Y7NAR4oUvNkJtr1YREZIlc7iKKZflkHALjchMgEMGnekX26edv8icleNA60pq86bZcDN/aQ+I3pQPA==`, shasum `81d00a0ab0c8d976cdf515337975017de792f986`, version `2.4.1`.
- `npm pack '@opencode/cli@2.4.1'` plus the Dockerfile's Node sha512/base64 check → `COMPUTED=sha512-3TbGzJO3...` , `MATCH=true`.
- Red before the pin update: `python3 -I -m unittest discover -s scripts/tests -p 'test_agent_image.py' -q` → `Ran 7 tests ... FAILED (failures=6)`.
- Green: same command → `Ran 7 tests in 0.001s ... OK`.
- Full repository suite: `python3 -I -m unittest discover -s scripts/tests -q` → `Ran 1543 tests in 320.570s ... OK`, exit 0.
- `bash scripts/python-coverage.sh` → exit 1 today, but only for `scripts/check-verdicts.py:52` and `:76` branch not taken — a file changed concurrently by task `01270600-AUDIT-FIX-VERDICTS`, outside this task's scope. No finding in any file this task touches.
- `python3 -I scripts/check-docs.py` → exit 1 today, for broken links in docs and a missing `pr-notes/WFAUDIT-07-20261121-153622.md` introduced by concurrent docs work; it exited 0 when this task's own edits were checked. `python3 -I scripts/check-secrets.py` → exit 0.
- CI: run 45319400642 at `a67ebd02` `Delivery tooling tests` failure (issue #1591); run 45319793973 at `78411794` `Delivery tooling tests` success; that run was cancelled later as main advanced, so no whole-run green SHA is claimed.

## Review verdicts
- aurevia-github-workflow: GO (static review; no Critical or Required findings)
- aurevia-devsecops: GO (static review; integrity correctness confirmed by the orchestrator's npm pack check)
- aurevia-senior-python: GO (static review; no Critical or Required findings)

## Coverage
- No production application code changed; only workflow/Dockerfile pins and one test file. The three added tests are static pin assertions with no branches, so there is no new production line or branch to cover. No threshold, exclusion, skip or suppression added.
- The repository-wide coverage gate is currently red for two branches in `scripts/check-verdicts.py`, a file owned by the concurrent task `01270600-AUDIT-FIX-VERDICTS`; it is not a finding against this task's three files.

## Entry-point-to-test trace
`agent-image.yml` dispatch `inputs.opencode_version` / `inputs.opencode_integrity` and the `|| '2.4.1'` / `|| 'sha512-3TbGzJ…'` fallbacks → `env` build args → `agent-image/Dockerfile` `ARG OPENCODE_VERSION` / `ARG OPENCODE_INTEGRITY` → `npm pack` and the Node sha512 check before `npm install -g`. `scripts/tests/test_agent_image.py` asserts all four pin locations agree, including the fallback that caused issue #1591.

## Findings
- Root cause of issue #1591: commit `a67ebd02` updated the workflow dispatch default integrity but left the env fallback at a stale `sha256:83660...` value; the pin test failed and the Delivery gate went red. Commit `78411794` aligned the fallback; the issue is closed COMPLETED.
- Evidence source: executed local commands, GitHub Actions run/job reads and three read-only specialist reviewers. No human or clinical participant.
- `docs/spec/clauses/vulnerability-management/pin-base-images-digest-ci-actions-commit.md:31` still cites 2.3.2 / 4 tests as observed at its recorded historical HEAD `881c1667`; left unchanged as a historical evidence note.
