# UX-01 clause 4 — preserve language and units in exports

## Clause

Preserve language and units in exports.

## Status

Partly proven

## Evidence

Test file: [`preserve-language-units-exports.test.ts`](../../../../apps/staff-web/tests/spec/localization/preserve-language-units-exports.test.ts).
It is a pure Vitest/jsdom test over the staff-web source with a local synthetic
export fixture; no database, network, patient record or credential is involved.
Evidence source for the executed commands below is the tool output; the code
survey statements come from reading the linked files. No human, clinical or
partner evidence is held here.

Test methods (one positive rule proof, one refusal, one active gap tripwire):

- `keeps the declared unit and Latin digits for Arabic and English` formats
  `12.5 cm` and `7.4 g/dL` in both `en` and `ar` and asserts the unit is present
  verbatim, the digits stay Latin and the mixed-script run keeps its `ar` tag.
  This proves the rendered-value half of the clause.
- `refuses export to a non-admin role` asserts that the export permission set is
  exactly `tenant_admin`, that `tenant_admin` is accepted and that `reception`
  and `privacy_auditor` are refused before an export can be started.
- `GAP: the tenant export manifest carries no language or unit metadata` calls
  the real `toExportListItem` on a synthetic export result and pins the exact
  keys of the resulting row, plus type-level assertions that neither the export
  result nor the start-export request declares a language or unit field.

Production symbols exercised by these tests:

- [`format.ts`](../../../../apps/staff-web/src/i18n/format.ts) — `formatMeasurement`
  (verbatim unit, Latin numeral system) and `mixedScriptRun` (language and
  direction tag).
- [`labResultMessages.ts`](../../../../apps/staff-web/src/features/labresults/labResultMessages.ts)
  — `formatValue` and `formatNumber` (invariant unit in one non-breaking-space
  run, Latin digits in both locales).
- [`domain.ts`](../../../../apps/staff-web/src/features/dataexchange/domain.ts) —
  `EXPORT_PERMITTED_ROLES`, `canStartExport`, `ExportResultResponse` and
  `toExportListItem`.
- [`api.ts`](../../../../apps/staff-web/src/features/dataexchange/api.ts) —
  `startExport`, used as a type-level witness that the export request has no
  locale parameter.

Pre-existing assertions that corroborate the rendered-value half:
[`format.test.ts`](../../../../apps/staff-web/tests/i18n/format.test.ts) asserts
the supplied unit is preserved for both locales, and
[`labResultMessages.test.ts`](../../../../apps/staff-web/src/features/labresults/labResultMessages.test.ts)
asserts the non-breaking-space unit run. The selected handout language is
carried into a download by `PrintPreviewDialog` and asserted in
[`PrintPreviewDialog.test.tsx`](../../../../apps/staff-web/src/features/print/components/PrintPreviewDialog.test.tsx).
None of these touches the tenant data-exchange export artifact.

## Gap

Display formatting and the handout download preserve language and units, but the
tenant data-exchange export does not. `ExportResultResponse` and its download
list row carry only `job_id`, `checksum`, `record_count`, `expires_at` and a
presentation state; `startExport` takes no locale and `downloadManifest`
returns an untyped value. Nothing writes a language tag or a unit declaration
into an export artifact, and the localization evaluator
[`ux01_evidence.py`](../../../../infra/accessibility/ux01_evidence.py) checks
only two boolean flags (`language_preserved` and `units_preserved`) rather than
inspecting an artifact. The clause is therefore partly proven, not proven end
to end.

Closing the gap would change, at minimum, the export result and client in
`apps/staff-web/src/features/dataexchange/api.ts` and `domain.ts`, the API-side
export response and manifest generation, the declared contract and regenerated
client, the evaluator in `infra/accessibility/ux01_evidence.py`, and the active
gap tripwire in the test file above. That is at least two separate engineering
tasks: one cross-boundary task to add language and unit metadata to the export
contract and artifact, and one staff-web task to replace the tripwire with a
positive proof. A live export with an authorized reviewer would be a further
human-gated evidence task. No such task is assumed here.

The story for this note asked for a deliberately skipped gap test. The
repository forbids added skipped tests in `AGENTS.md` and enforces that with
`scripts/check-constraints.py`; the gap is therefore recorded as an active,
green tripwire that fails typecheck when the metadata is added, and this is the
conflict being recorded rather than a suppression of it. The tripwire must be
replaced, not preserved, once the export artifact declares language and units.
