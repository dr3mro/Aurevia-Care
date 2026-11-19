# S-G5-05 — Capacity targets, pilot load profile and measured performance evidence (OPS-03)

## Scope honoured

Only the declared scope was changed: `infra/s_g5_05/**` and
`docs/operations/s_g5_05/**`, plus this required `.agent/pr-notes.md`.
No shared tracker (`TODO.md`, `MEMORY.md`, `WORKSPACE.md`, `BUGS.md`, `PROJECT.md`,
`docs/swarm`) was edited. Reserved migration version **V47 was not used** — no
schema change is needed, and the repository's change-safety gate permits gaps.
Synthetic fixtures only. No push, tag or `gh` call was made. This is a review-and-harden
delivery on top of the prior attempt's in-scope implementation; the previous
code was verified, four real fail-open/robustness defects were found test-first
and fixed, and the documentation was reconciled with the code.

## Requirement IDs and acceptance mapping

Primary: **OPS-03**. Supporting honesty boundary: **G5** launch evidence gate.

| OPS-03 clause | Implementation | Evidence |
|---|---|---|
| 99.9% monthly core availability | `availability.percent >= 99.9` **and** `availability.window == "calendar_month"` | `AvailabilityTest` (6 cases); a 30-minute run cannot supply this — documented as a separate human gate |
| Server-side p95 read ≤ 500 ms, command ≤ 900 ms | `measurement.scope == "server_side"`; exact exclusion vocabulary; `route_class` allowlist; `results.p95_read_ms`/`p95_command_ms` | `MeasurementScopeTest`, `ThresholdTest`, `ReportAndCliTest`; fixture report |
| Exclude document transfer, rendering, external calls | `REQUIRED_EXCLUSIONS` checked for missing, extra and duplicate categories | `MeasurementScopeTest` (all breach shapes) |
| Profile: 20 orgs, 100,000 charts, 200 staff, 50 rps, 30 min | `TARGET_PROFILE` exact equality; pilot artifact and tests cross-checked | `ProfileTest`, `PilotLoadProfileTest`; `pilot-load-profile.json` |
| Mixed run at the declared rate | positive finite counts, `total == read + command`, 81,000 enforced floor (10% ramp allowance), each class ≥ 1% | `WorkloadTest` (incl. `Infinity`, class balance) |
| Run before pilot; revise assumptions after observation | synthetic rehearsal vs measured pilot classes; declared 70/30 mix marked an assumption; provenance required for a measured claim | `ProvenanceTest`, docs; real measurement remains open |

**Entry-point-to-test trace:** `bash infra/s_g5_05/check.sh` → 55 unit tests
(`tests/test_capacity_evidence.py`) → `capacity_evidence.evaluate` → named
checks on `profile`, `measurement`, `results`, `provenance` → `_verdict` →
`format_report` → exit 0/1/2. The same module is run directly on a conforming
and a deliberately violating fixture; the negative control requires exit 1 and
five named breaches.

## Red → green defects found and fixed (all synthetic, tool-generated)

1. **Unhashable `kind` crashed the evaluator.** `{"kind": []}` reached
   `{...}.get(kind)` in `_verdict` and raised `TypeError`, contradicting the
   module's fail-closed promise. RED: `test_unhashable_kind_fails_closed_without_raising`
   → 4 errors. GREEN: classify by equality instead of a dict lookup.
2. **`Infinity` request counts passed the mixed/sustained checks.**
   `json.loads` accepts the non-standard `Infinity` literal; with all three
   counts infinite, `inf == inf + inf` and `inf >= 81000` passed and the verdict
   was `pass`. RED: `test_infinite_request_counts_cannot_pass_the_mixed_check`;
   also added `inf` to latency/count/availability cases. GREEN: `_is_number`
   rejects non-finite floats (guarding huge ints against `OverflowError`).
3. **Invalid UTF-8 crashed `main()` instead of returning exit 2.**
   `UnicodeDecodeError` is a `ValueError`, not `OSError`/`JSONDecodeError`.
   RED: `test_main_rejects_invalid_utf8`. GREEN: catch `UnicodeError`.
4. **`final_evidence: true` was self-declared with no provenance.**
   A fabricated `kind: measured_pilot` artifact with a neutral source was
   promoted. RED: five `ProvenanceTest` cases. GREEN: require a named non-empty
   source and six non-empty provenance fields (`tool`, `tool_version`,
   `command`, `utc_timestamp`, `environment`, `source_sha`) for that kind.
5. **Negative fixture gate accepted exit 2.** `check.sh` treated any non-zero
   exit as a correct rejection, so deleting the fixture would silently disable
   the control. RED demo: missing fixture → exit 2, accepted by old logic.
   GREEN: require exactly exit 1 and five named `[FAIL]` checks; a temporary
   copy of `check.sh` with the violation fixture removed now exits 1.
6. **Declared workload families and evidence routes used different names.**
   The conforming fixture listed 8 differently-named routes while the profile
   declares 11 families; 7 command families were never measured. RED:
   `test_evidence_route_allowlist_matches_declared_families`. GREEN: both
   fixtures now use the canonical family names and classes; a unit test locks
   them together.
7. **A one-sample command class could make p95 meaningless.** RED:
   `test_each_class_needs_a_minimum_sample_share`. GREEN: `results.class_balance`
   requires each class ≥ 1% of the run.
8. **Docs contradicted the code.** `capacity-targets.md` called the 81,000 floor
   "not enforced" while `results.sustained_rate` enforced it, and the coverage
   claim said "155 executable lines, none uncovered" although the import-based
   runner never executes the `__main__` body. Both corrected.

Evidence source for items 1–4, 6–7: executed tool commands (red test output and
green reruns). Item 5: executed shell demonstration. Item 8: executed doc checks
plus a reviewer finding.

## Exact commands run and results

| Command | Result |
|---|---|
| `python3 -I -m unittest discover -s infra/s_g5_05/tests -q` (before fixes) | 55 tests, 5 failures / 2 errors — the intended red set |
| `bash infra/s_g5_05/check.sh` | **exit 0**; 55 tests OK; conforming fixture passes; violation fixture exits 1 with all five expected breaches |
| `python3 -m trace --count --coverdir /tmp/opencode/s_g5_05_cov5 infra/s_g5_05/tests/run_coverage.py` | 55 tests OK; `capacity_evidence.py` has **0** lines with the unexecuted `>>>>>>` marker |
| Fuzz: 473 malformed field/value combinations through `evaluate` | 0 unhandled exceptions; every result is `pass` or `fail` |
| `python3 -I scripts/check-docs.py` | exit 0 (relative links and requirement IDs valid) |
| `python3 -I scripts/check-secrets.py` | exit 0 (no credential/PHI-shaped strings) |
| `bash -n infra/s_g5_05/check.sh` | exit 0 |
| `python3 -I scripts/check-migrations.py --require-baseline` | exit 0 (V47 unused; gaps permitted) |
| `git status --short` before commit | only the nine scoped files plus `.agent/pr-notes.md` |

Database and integration gates are the integrator's responsibility and were not
run here. No real load generator, deployment or calendar-month monitoring is
available in this repository (Docker is unavailable locally), so no measured
performance result is fabricated.

## Decisions

- Only OPS-03 literals are enforced as targets; 70/30 and the 10% ramp allowance
  are declared, justified allowances, not new requirements.
- Provenance *presence and shape* is enforced; truth remains a human G5 gate.
- The negative control checks the exact exit code and named breaches rather than
  "any failure" so a broken harness cannot pass.
- Reviewer suggestions to derive a calendar-month span from timestamps and to
  enforce a declared workload mix weight against observed counts were not
  implemented: they are not specification literals and would overstate the
  contract; the canonical family vocabulary is cross-checked instead.

## Evidence provenance

- Tool evidence: Python unittest, stdlib `trace`, `check.sh`, fuzz harness,
  `check-docs.py`, `check-secrets.py`, `bash -n`, migration checker — all
  executed locally on synthetic fixtures.
- Reviewer evidence: two simulated specialists (`smartclinic-system-designer`,
  `smartclinic-senior-python`) reported findings; all with impact and file:line
  evidence were resolved above. No human participant or authorized clinical
  feedback was used.
- Open G5 gate: a real 30-minute load report, a calendar-month availability
  measurement, revision of assumptions after observation, and human acceptance.
  This story proves the check works; it does not claim OPS-03 is met.
