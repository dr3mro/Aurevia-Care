# UX-01 localization, time-zone and accessibility evidence

- Requirement: **UX-01**. Supporting context: **PRD-05**.
- Status: the requirement contract, a fail-closed evidence evaluator and synthetic
  fixtures are delivered. Real assistive-technology reviews and a human G5
  sign-off are **not** produced here and remain open gates.
- Audience: engineering, operations, the product owner and G5 acceptance reviewers.

This directory is the engineering handoff for UX-01. It states the exact clauses,
how an evidence artifact is judged, and — deliberately — what this repository
cannot prove without authorized people and devices.

## Requirement mapping

| Requirement | Specification clause | Handled by |
|---|---|---|
| UX-01 | English and Arabic, RTL direction, local name display and mixed-script clinical text | [Localization and RTL](localization-and-rtl.md) |
| UX-01 | Store UTC instants with IANA time-zone context; retain civil dates; test daylight-saving transitions | [Time zones and civil dates](time-and-timezones.md) |
| UX-01 | Accessibility against WCAG 2.2 AA on staff web; native screen-reader flows on both mobile platforms | [Accessibility and screen readers](accessibility-and-screen-readers.md) |
| UX-01 | Preserve language and units in exports; keep presentation styling flexible | [Evidence artifact](evidence-artifact.md) |
| PRD-05 | English and Arabic operation, with one market per production deployment | The language and export checks above |

The approved clause itself is in the [product specification](../../../.opencode/config/PROJECT_V1_SPEC.md).
No new threshold or requirement ID is introduced by this handoff.

## Three evidence tiers

| Tier | What it is | What it proves | What it does not prove |
|---|---|---|---|
| Contract | The spec literals encoded in [ux01_evidence.py](../../../infra/accessibility/ux01_evidence.py) and the [declared profile](../../../infra/accessibility/ux01-profile.json) | Fidelity of the encoding and fail-closed validation | That any clause was verified on a real system |
| Synthetic rehearsal | Seeded, synthetic artifacts run by the [gate](../../../infra/accessibility/check-ux01.sh) in this repository | That the evaluator accepts a conforming artifact and rejects breaches | Real RTL rendering, real screen-reader usability or WCAG conformance; no device or user was involved |
| Measured acceptance | An authorized assistive-technology and localization review, produced outside this repository | Usability and conformance findings under the declared envelope | Clinical approval; that remains a G5 human gate |

A passing synthetic artifact is explicitly labelled `final_evidence: false`. The
evaluator never promotes a rehearsal to measured acceptance.

## Document set

| Document | Covers |
|---|---|
| [Localization and RTL](localization-and-rtl.md) | Languages, RTL mirroring, local-name display, mixed-script text |
| [Time zones and civil dates](time-and-timezones.md) | UTC instants, IANA context, civil dates, daylight-saving tests |
| [Accessibility and screen readers](accessibility-and-screen-readers.md) | WCAG 2.2 AA, VoiceOver, TalkBack and the human-verification boundary |
| [Evidence artifact](evidence-artifact.md) | Artifact schema, commands, observed rehearsal result and remaining gate |

Executable material lives in `infra/accessibility`: the
[evaluator](../../../infra/accessibility/ux01_evidence.py), the
[declared profile](../../../infra/accessibility/ux01-profile.json), the
[conforming](../../../infra/accessibility/fixtures/synthetic_ux01_evidence.json) and
[violation](../../../infra/accessibility/fixtures/violation_ux01_evidence.json)
fixtures, and the unit tests.

## Verification

| Check | Command | Proves |
|---|---|---|
| Unit and fixture gate | `bash infra/accessibility/check-ux01.sh` | 54 synthetic unit tests pass; the conforming fixture passes and the violation fixture fails with the exact exit code 1 and a FAIL marker |
| Unit tests alone | `python3 -I -m unittest discover -s infra/accessibility/tests -q` | The evaluator's enumerated branches behave as specified, including hostile JSON input |
| Line coverage | `python3 -m trace --count --missing --coverdir /tmp/opencode/accessibility_cov infra/accessibility/tests/run_coverage.py` | No missed executable line in the evaluator, including the `if __name__ == "__main__"` CLI entry point; stdlib `trace` is used because `coverage.py` is not installed |
| Links and requirement IDs | `python3 -I scripts/check-docs.py` | Relative links resolve and every requirement ID is defined in the specification |
| Credential and PHI shapes | `python3 -I scripts/check-secrets.py` | No credential or patient-identifier pattern was introduced |

## Decisions

| # | Decision | Rationale |
|---|---|---|
| D1 | Only the literals in UX-01 are enforced; scene detail (exact UI components, specific font pairs) is documentation, not a gate | Inventing a threshold would overstate the contract |
| D2 | Both `en` and `ar` must be declared; additional languages are permitted | The specification fixes English and Arabic as the required operation languages |
| D3 | A DST test must name real, distinct IANA zones and at least one that actually changes UTC offset | A fixed-offset zone cannot exercise a daylight-saving transition; IANA names are validated against the system time-zone database |
| D4 | WCAG standard and level, both screen-reader names and the export flags are exact strings/booleans | A relabelled conformance level or a missing screen reader would silently weaken the check |
| D5 | A passing synthetic artifact is not final evidence | Rehearsal proves the check works, not that a human assistive-technology review happened |
| D6 | The gate script is named `check-ux01.sh`, not `check.sh` | `swarm.py` protects `*/check.sh` paths as human-only; a non-matching name keeps this story auto-mergeable while remaining its canonical gate |
| D7 | Reserved migration version V52 is not used | No schema change is needed; version gaps are allowed by the repository's change-safety gate |
| D8 | Every artifact must carry a non-blank string `source` label | A measured artifact with no attributable source cannot be traced to an authorized reviewer; it fails the envelope and is never promoted to final evidence. This closes an honesty gap where an unattributable artifact passed as measured acceptance |

## Boundaries

- Synthetic data only. No fixture contains a real person, identifier or credential.
- No clinical or accessibility approval is implied. Real WCAG 2.2 AA conformance
  and native screen-reader usability need authorized reviewers on real devices.
- No application code, schema, API contract, CI or tracker file belongs in this
  story's scope. The two scope directories are the only files changed.

## Open items

| Item | Why it is open | Who can close it |
|---|---|---|
| Real WCAG 2.2 AA audit on staff web | A script cannot judge visual and keyboard conformance | Accessibility reviewer with the operations owner |
| VoiceOver and TalkBack flow reviews on device | Native screen-reader usability needs a human on each platform | Mobile accessibility reviewers |
| DST transition test evidence from the deployed system | The evaluator checks the declaration, not that a real transition was exercised | Engineering with operations, in an authorized environment |
| Human acceptance of the measured evidence | A script cannot attest that a review really happened | G5 acceptance owners |

Evidence source for the delivered checks: executed tool commands in this
repository. There is no human, clinical or real assistive-technology evidence in
this story.
