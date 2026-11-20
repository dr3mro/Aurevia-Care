# Accessibility and screen readers (UX-01)

- Requirement: **UX-01**
- Machine source of truth: [ux01_evidence.py](../../../infra/accessibility/ux01_evidence.py).
- Back to the [S-G5-10 handoff](README.md).

## Enforced declarations

| Surface | Artifact field | Rule |
|---|---|---|
| Staff web conformance | `accessibility.staff_web.standard`, `accessibility.staff_web.level` | Must equal `WCAG 2.2` and `AA` respectively |
| Staff web evidence | `accessibility.staff_web.tested`, `accessibility.staff_web.report` | `tested` must be boolean `true`; `report` must be a non-blank string |
| iOS | `accessibility.mobile.ios_screen_reader` | Must equal `VoiceOver` |
| Android | `accessibility.mobile.android_screen_reader` | Must equal `TalkBack` |
| Both platforms | `accessibility.mobile.flows_tested` | Must be boolean `true` |

The exact standard and level matter: accepting `WCAG 2.1` or level `A` would
silently downgrade the stated conformance target. Naming both screen readers
prevents a single-platform review from being presented as two-platform
coverage.

## What the evaluator cannot prove

A JSON artifact can only declare that a conformance level was tested and that a
report exists. It cannot judge focus order, colour contrast, target size,
reading order, announcements or label quality. Native screen-reader usability
requires a human on a real device for each platform; WCAG 2.2 AA conformance
requires an accessibility reviewer on the staff web build. This is the honesty
boundary encoded in `final_evidence`: a synthetic rehearsal is never final, and
even a measured artifact is not a substitute for the G5 sign-off.

## Synthetic breach coverage

The [violation fixture](../../../infra/accessibility/fixtures/violation_ux01_evidence.json)
declares `WCAG 2.1` at level `A`, sets `tested` to `false` with an empty report,
names an unknown Android reader and sets `flows_tested` to `false`; the
evaluator reports both accessibility checks as failures. Unit cases cover each
field independently, absent sub-objects, blank reports and truthy substitutes,
so every branch of the accessibility checks has an executable synthetic case.
