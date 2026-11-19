# S-G5-10 — Localization, RTL, time zones, WCAG 2.2 AA and screen readers (UX-01)

## Requirements and acceptance mapping

| Requirement ID | Specification clause | Where enforced / evidenced |
|---|---|---|
| UX-01 | English and Arabic, RTL direction, local name display, mixed-script clinical text | `infra/s_g5_10/ux01_evidence.py` checks `localization.languages`, `localization.rtl`, `localization.local_names`, `localization.mixed_script` |
| UX-01 | UTC instants with IANA context; retained civil dates; DST transitions tested | checks `time.instant_storage`, `time.iana_timezone_context`, `time.civil_dates_retained`, `time.dst_transitions_tested` (real `zoneinfo` validation, both hemispheres) |
| UX-01 | WCAG 2.2 AA on staff web; native screen-reader flows on both mobile platforms | checks `accessibility.staff_web` (exact `WCAG 2.2` / `AA`, tested, report) and `accessibility.mobile` (`VoiceOver`, `TalkBack`, flows) |
| UX-01 | Preserve language and units in exports; flexible presentation styling | checks `exports.language_preserved`, `exports.units_preserved`, `presentation.styling_flexible` |
| PRD-05 | English/Arabic operation, one market per deployment | covered by the language/export checks; market policy pack remains a separate G5 story |

The base implementation (present at the branch base commit) already delivered the
full evaluator, profile, synthetic/violation fixtures, 51 unit tests, gate script
and the five-document operations handoff. This worker **verified** that baseline
and closed one genuine honesty defect found during verification.

## Defect found and fixed (test first)

**Defect S-G5-10-01 (provenance / honesty gap).** A `measured_acceptance`
evidence artifact with a missing, blank, empty or non-string `source` was
accepted with verdict `PASS` and promoted to `final_evidence: true`. Such an
artifact cannot be traced to an authorized reviewer, which contradicts the
evaluator's own honesty rule and the specification's requirement that real
assistive-technology / WCAG reviews are produced by authorized people and
accepted at the human G5 gate.

- Reproducer before fix (tool, synthetic artifact): `missing -> verdict pass final_evidence True failed []`; same for blank, empty and non-string sources.
- Red: added `EnvelopeSourceTest` (3 methods / 11 subtest assertions) to `infra/s_g5_10/tests/test_ux01_evidence.py`; `python3 -I -m unittest discover -s infra/s_g5_10/tests -q` → `Ran 54 tests ... FAILED (failures=11)`.
- Fix: added `_check_source()` in `ux01_evidence.py`, recording a named `source` check that requires a non-blank string for every artifact, and wired it into `evaluate()` before the `evidence_conflict` check. `final_evidence` therefore requires an attributable source as well as a measured kind.
- Green: same unit command → `Ran 54 tests ... OK`; reproducer now yields `verdict fail final_evidence False failed ['source']` for missing/blank/non-string sources, while a valid source still yields `PASS` / `final_evidence True`.
- Documentation updated in scope: `evidence-artifact.md` (schema + attribution rules) and `README.md` (decision D8); test counts corrected to 54.

## Exact commands and results (all executed in this worker)

| Command | Result |
|---|---|
| `python3 -I -m unittest discover -s infra/s_g5_10/tests -q` | `Ran 54 tests ... OK` (51 baseline + 3 new) |
| `bash infra/s_g5_10/check-ux01.sh` | exit 0; conforming fixture passes; violation fixture fails with exact exit 1 and a `[FAIL]` marker |
| `python3 -m trace --count --missing --coverdir /tmp/ux01-final-cov infra/s_g5_10/tests/run_coverage.py` | 0 uncovered executable lines in `ux01_evidence.py` (stdlib `trace`; `coverage.py` unavailable), including the `if __name__ == "__main__"` CLI entry point |
| `python3 -I scripts/check-docs.py` | exit 0 — new/edited Markdown links resolve, requirement IDs defined |
| `python3 -I scripts/check-secrets.py` | exit 0 — no credential or PHI-shaped strings in fixtures or docs |
| `python3 -I infra/s_g5_10/ux01_evidence.py infra/s_g5_10/fixtures/synthetic_ux01_evidence.json` | `PASS`, class `rehearsal`, `final_evidence False` |

Fast gate scope: this worker ran the story's unit gate and repository policy
checks. The integrator runs the database and integration tests.

### Environmental / pre-existing gate results (not caused by this change)

- `bash scripts/quick-check.sh` stops at `check-pack: opencode.json is stale`. This is the documented cloud-worker CI-profile artifact: `git ls-files -v opencode.json` shows flag `S` (skip-worktree) and the worktree copy differs from `HEAD`. Against the committed profile (`git show HEAD:opencode.json`), `python3 -I scripts/check-pack.py` returns `Delivery tooling validated: 33 agents, 33 skills`, exit 0. The worktree file was restored byte-for-byte; `opencode.json` is not in this change.
- `python3 -I -m unittest discover -s scripts/tests -q` reports 6 failures / 4 errors at base, all in modules unrelated to this scope (`test_check_pack`, `test_agent_run`, `test_swarm`, and import-error loaders for `test_fast_tier` / `test_workflow_concurrency`). None reference `s_g5_10` or `ux01`; my diff touches only four files under `infra/s_g5_10` and `docs/operations/s_g5_10`.

## Decisions

- D1–D7 from the base handoff are retained (only UX-01 literals enforced; both `en` and `ar` required; real distinct DST-zone validation; exact standard/level/screen-reader strings; synthetic rehearsal is never final; canonical gate named `check-ux01.sh` to avoid the swarm's `*/check.sh` human-only protection; no V52 schema change).
- D8 (new): every evidence artifact must carry a non-blank string `source`. An unattributable "measured" artifact fails the envelope and is never final evidence.
- No new requirement ID, threshold, schema version or migration was introduced. Reserved migration V52 remains unused (no schema change needed).

## Entry-point-to-test trace

`ux01_evidence.py:evaluate(artifact)` → `_check_source(source, checks)` → named `source` check → `_verdict()` sets `final_evidence` only when the kind is measured, no check failed (including attribution) and the source is not synthetic → CLI `main()` → exit 1 on failure. The trace is exercised by `test_ux01_evidence.EnvelopeSourceTest` via `python3 -I -m unittest discover -s infra/s_g5_10/tests -q`, and by `check-ux01.sh` against both fixtures.

## Evidence classification

- Gate results, reproducer results, coverage and policy checks: **executed tool commands** in this repository (synthetic data only).
- No simulated-user, clinician, human or real assistive-technology evidence is claimed. Real WCAG 2.2 AA audit, VoiceOver/TalkBack on-device reviews, DST exercise in a deployed environment and the G5 human sign-off remain open human gates, as documented in `docs/operations/s_g5_10/README.md`.

## Scope

Changed files are limited to `infra/s_g5_10/ux01_evidence.py`,
`infra/s_g5_10/tests/test_ux01_evidence.py`,
`docs/operations/s_g5_10/evidence-artifact.md` and
`docs/operations/s_g5_10/README.md`. No tracker, script, workflow, API,
database, contract or unrelated file was edited. No push, tag or `gh` call was made.
