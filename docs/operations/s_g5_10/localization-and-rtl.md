# Localization and RTL (UX-01)

- Requirement: **UX-01**
- Machine source of truth: [ux01_evidence.py](../../../infra/s_g5_10/ux01_evidence.py).
  The constants there are the single enforced copy of these literals; this page
  explains them and must be kept in step with that module.
- Back to the [S-G5-10 handoff](README.md).

## Enforced declarations

| Clause | Artifact field | Rule |
|---|---|---|
| English and Arabic operation | `localization.languages` | A list containing both `en` and `ar`, each exactly once; extra languages are permitted but duplicates and non-strings fail closed |
| RTL direction | `localization.rtl.layout_mirrored`, `localization.rtl.navigation_mirrored` | Both must be boolean `true` |
| Local name display | `localization.local_names.display`, `localization.local_names.script_preserving` | Both must be boolean `true` |
| Mixed-script clinical text | `localization.mixed_script.clinical_text`, `localization.mixed_script.bidirectional_safe` | Both must be boolean `true` |

The check is exact: a truthy substitute such as `1` or `"yes"` is rejected
because `is True` is required. A missing sub-object is reported as a failed
named check rather than raising or defaulting to a pass.

## Why each clause is separate

RTL is not just text direction. Layout mirroring and navigation mirroring are
different surfaces; a portal can reverse a paragraph without reversing its
worklist or focus order. Local names must be displayed without being rewritten
into a single script, and mixed-script clinical text needs bidirectional-safe
handling so that a Latin laboratory code cannot be visually attached to the
wrong Arabic word. Each clause is therefore a distinct named check with a
distinct synthetic fixture breach in
[test_ux01_evidence.py](../../../infra/s_g5_10/tests/test_ux01_evidence.py).

## What is deliberately not enforced

- Font pairs, colour tokens and spacing scales are a design-system concern and
  are documented in the bilingual clinical design language, not re-declared here.
- The evaluator proves a declaration was supplied, not that an RTL screenshot or
  a native name-rendering review was performed. That evidence belongs to the
  measured-acceptance tier and to the human G5 gate.

## Synthetic breach coverage

The [violation fixture](../../../infra/s_g5_10/fixtures/violation_ux01_evidence.json)
omits `ar` and sets `navigation_mirrored` to `false`; the evaluator reports
`localization.languages` and `localization.rtl` as failures. Additional unit
cases cover duplicate and non-string languages, missing flags, truthy
substitutes and absent sub-objects, so every branch in the localization checks
has an executable synthetic case.
