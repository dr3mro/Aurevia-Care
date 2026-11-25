# WF-01..WF-35 final source dispositions

Read-only audit finalization, 2026-11-24. Evidence source: tool file reads. No live GitHub status can be upgraded in this session; `BLOCKED` rows name the missing evidence exactly. The earlier audit is in `tasks/01300000-ORCA-INFRASTRUCTURE-REFACTOR.md`.

| ID | Disposition | Evidence and missing live proof |
|---|---|---|
| WF-01 | CONFIRMED | `fix-pr-gate.yml:89-141`, `swarm.py:6477-6485`; missing live repair handoff run id |
| WF-02 | CONFIRMED | `swarm.py:6913-6948`, `994-1008`; missing empty-wave run id |
| WF-03 | CONFIRMED | `failure_classes.py:17-49`, `swarm.py:1010-1022`; missing misclassified run id |
| WF-04 | CONFIRMED | `swarm.py:419-439,2587,2635`, `gen-opencode.py:33-36,49-60`; missing auth-failure run and leak negative |
| WF-05 | PARTIAL | `agent-run.sh:297-339`; missing green CI tooling run id; local full suite flaky on macOS bash 3.2 |
| WF-06 | CONFIRMED | `swarm.py:3860-3894`, `check-verdicts.py:123`; missing live no-notes-PR run |
| WF-07 | CONFIRMED | `swarm.py:690,1009,1034-1035` plus quoting fix `424-439`; missing summary run id |
| WF-08 | CONFIRMED | `agent-run.sh:98-105,297-339`; missing live checkout-preservation trace |
| WF-09 | CONFIRMED | `agent-run.sh:34-50,78-96`; missing wrong-cwd destructive-op reproduction |
| WF-10 | BLOCKED | source denies force push at `gen-opencode.py:52-60`; missing ruleset state and exact-SHA direct-push rejection |
| WF-11 | CONFIRMED | `check-delivery-gate.py:25-33,41-76`, `delivery.yml:267-286`; missing live gate conclusion |
| WF-12 | CONFIRMED | `swarm-watchdog.yml:2-4,11-16,33-36`; missing live self-trigger negative |
| WF-13 | CONFIRMED | `swarm.py:2924-2969,3049-3060`; missing concurrent-claim race proof |
| WF-14 | PARTIAL | `swarm.py:2976-3013`; no lease TTL found; missing stale-worker fence race |
| WF-15 | CONFIRMED | `fix-pr-gate.yml:121-135`; missing live promotion run |
| WF-16 | BLOCKED | `swarm-integrator.yml:67-84`; missing live merge record, ruleset state and exact-SHA green |
| WF-17 | BLOCKED | `delivery.yml:14`, `swarm-gate.yml:10`; missing live `merge_group` event payload |
| WF-18 | CONFIRMED | `swarm.py:6690-6699,1536,5947`; missing live merge record |
| WF-19 | PARTIAL | `pr-rebase.yml:1-7,44-54`; missing live stale-branch outcome |
| WF-20 | CONFIRMED | `agent-run.sh:78-96,98-105`; strict worktree mandate N/A by owner ruling; missing live cleanup trace |
| WF-21 | PARTIAL | `agent-run.sh:360-366`, default model in `swarm-worker.yml:80`; missing live per-worker model id and eligibility validator |
| WF-22 | DISPROVEN | owner ruling `MEMORY.md:33,65`; no fallback model exists |
| WF-23 | DISPROVEN | same ruling; no `SWARM_FORCE_MODEL` exists |
| WF-24 | CONFIRMED | `swarm.py:32,936-955,1693,3320`; missing live repair-loop count |
| WF-25 | CONFIRMED | `agent-run.sh:34-50`; missing live inherited-`GIT_DIR` negative test |
| WF-26 | CONFIRMED | `delivery.yml:15-16,200-203`, `swarm-worker.yml:70,335,420`; missing live token audit |
| WF-27 | CONFIRMED | `delivery.yml:21-24`, `swarm.yml:48-50,60-63`; no `cancel-in-progress: true` in 21 workflows; missing live queue validation |
| WF-28 | CONFIRMED | `swarm.py:392,1172-1178`, `swarm-watchdog.yml:59-63`; missing live stalled-swarm issue |
| WF-29 | CONFIRMED | `swarm.py:442-444,493-502,5178`; missing live duplicate-issue reproduction |
| WF-30 | CONFIRMED | `delivery.yml:4-13` has no path filter; missing live duplicate-CI count |
| WF-31 | PARTIAL | `swarm.py:1433-1546`, `swarm_gate.py:255-302`; missing live multi-PR reconciliation |
| WF-32 | PARTIAL | `swarm-integrator.yml:113-117`, `fix-pr-gate.yml:63-67`; missing live cache ownership proof |
| WF-33 | CONFIRMED | `swarm.py:189-199,378,1017-1020`; missing live rate-limit run |
| WF-34 | CONFIRMED | `swarm.py:424-439,690,1009-1035`, `check-delivery-gate.py:41-76`; missing live diagnostic run |
| WF-35 | BLOCKED | only mocked evidence `test_swarm_gate.py:78-81,345-386`; missing full swarm-to-merge run, ruleset state, merge event and merge record |

## Source-level defect found

`scripts/swarm.py:3882` says Delivery skips Markdown-only pull requests, contradicted by the no-path-filter contract at `.github/workflows/delivery.yml:4-13`. This is a stale comment, not a gate behaviour; recorded in `BUGS.md` as SWARM-DELIVERY-COMMENT for a later truthful-comment fix. No path filter or gate was changed.

## Gate configuration unchanged

Delivery job ids and dependencies remain `policy` → `script-tests` → `frontend` → `java-verify` → `docs` → `verify` → `release` → `gate`, with `gate` needing all seven at `delivery.yml:267-286`; queueing gates still use `cancel-in-progress: false` and `queue: max`. Historical differences cannot be proven without Git.
