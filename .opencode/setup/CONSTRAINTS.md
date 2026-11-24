# Project constraints (written quality bar)

This is the standing quality bar for every change in this repository. It is the
written companion to `AGENTS.md` and the machine checks in `scripts/check-constraints.py`.
Agents must not lower a number here without a person's explicit decision; the constraint
check fails any added line that weakens one.

| # | Dimension | Bar | Enforced by |
|---|---|---|---|
| 1 | Java line/branch/instruction/method/class coverage | 100% | JaCoCo in `apps/api/check.sh` |
| 2 | Staff web line/branch/function/statement coverage | 100% | Vitest thresholds in `vitest.config.ts` |
| 3 | Patient app coverage | 100% of touched source | Jest `coverageThreshold` in `apps/patient-mobile/jest.config.ts` |
| 4 | Lint warnings | 0 | `eslint --max-warnings=0` in both apps |
| 5 | TypeScript strictness | `strict: true`, `noUncheckedIndexedAccess: true` | app `tsconfig*.json` + `scripts/frontend-check.sh` |
| 6 | Suppressions / skipped tests / exclusions | none added by a change | `scripts/check-constraints.py` |
| 7 | Review verdicts | GO from plan, code, coverage and security reviewers | `scripts/check-verdicts.py` |
| 8 | New production Java/migrations | wiring + entry point + HTTP integration test | `scripts/check-wiring.py` + `docs/WORKFLOW.md` |
| 9 | Credentials / patient data in context, logs or trackers | never | `scripts/check-secrets.py` + AGENTS.md |

The numbers above are the contract. `scripts/check-constraints.py` reads the key tokens
from this file and fails when a change replaces a stricter value with a looser one.
Do not edit a threshold to make a gate green; fix the code, add the test, or ask a person.
