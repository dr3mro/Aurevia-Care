# Bilingual clinical design language

- Requirements: UX-01, PRD-05
- Status: contract version 1.0.0, proposed. Documentation only — no runtime
  tokens, components or application code live in this directory.
- Audience: maintainers of `apps/staff-web` and `apps/patient-mobile`, design and
  accessibility reviewers, and the reviewers of any change that adds a screen.

This directory holds the design-system specification for the staff web and the
patient app: the token names and values, the light and dark modes, the
right-to-left and mixed-script rules, and the accessibility patterns a screen is
reviewed against. It is deliberately smaller than a component library. It states
the contract; each app implements it in its own module.

## Requirement mapping

| Requirement | What it demands | Where it is specified |
|---|---|---|
| UX-01 | English and Arabic, RTL direction, local name display, mixed-script clinical text, UTC instants with IANA time zones and civil dates, WCAG 2.2 AA on staff web, native screen-reader flows on both mobile platforms, flexible presentation styling | [Token contract](token-contract.md), [RTL and localization](rtl-and-localization.md), [Accessibility patterns](accessibility-patterns.md) |
| PRD-05 | English and Arabic operation for a single market per production deployment, under a market-specific policy pack | [Token contract](token-contract.md#4-typography) typography and font coverage; [RTL and localization](rtl-and-localization.md) scripts, numerals and dates; the per-site policy hook is recorded in [open items](#open-items) |

The other requirements these documents touch are stated where they are used:
clinical record states follow the draft, signed, amendment and entered-in-error
rules; release and acknowledgment of critical findings follow the exchange and
result rules; catalog and policy values are site configuration.

## Document set

| Document | Covers |
|---|---|
| [Token contract](token-contract.md) | Naming grammar, platform derivation, colour, typography, spacing, radius, border, elevation, motion, layer, theming, contrast measurements, adoption and versioning |
| [RTL and localization](rtl-and-localization.md) | Direction model, logical properties, mirroring rules, numerals and units, identifiers, local-name display, mixed-script text, translation rules, verification |
| [Accessibility patterns](accessibility-patterns.md) | Focus and keyboard, forms, tables and worklists, dialogs, critical-result banners, status messages, motion, native patterns, verification |
| [Approved screen designs](approved/README.md) | Pictures and page layout of the reception worklist, clinician chart, results and critical queue, operations and unit inbox, and the patient app |

Related material: the [maintainer guide](../reference/maintainer.md) for the
per-module trace, the [delivery workflow](../WORKFLOW.md) for review routing, the
[decision-record template](../decisions/template.md) for a consequential design
decision, and the approved
[product specification](../../.opencode/config/PROJECT_V1_SPEC.md) for the requirement text
itself. The implemented subset today is in
`apps/staff-web/src/theme/tokens.ts` and `apps/staff-web/src/i18n/format.ts`.

## Reading order for a change

1. Find the requirement IDs the screen serves.
2. Pick the tokens from the [token contract](token-contract.md); do not add a
   raw value.
3. Apply the direction and text rules from
   [RTL and localization](rtl-and-localization.md) while building, not after.
4. Walk the applicable patterns in
   [accessibility patterns](accessibility-patterns.md) before asking for review.
5. Measure contrast, run the module checks and record the evidence with the
   commit.

## How this is verified

These documents contain no executable code, so the gates are policy checks and
reproducible measurements:

| Check | Command | What it proves |
|---|---|---|
| Links and requirement IDs | `python3 -I scripts/check-docs.py` | Every relative Markdown link's target file exists, and every requirement ID mentioned outside the specification is defined in it. The checker does not validate `#fragment` anchors, so those are checked by hand at review |
| Credential and PHI shapes | `python3 -I scripts/check-secrets.py` | No credential or patient-identifier shape was introduced |
| Contrast | The recipe in [token contract section 5.4](token-contract.md#54-reproducing-the-numbers) | Every documented ratio in the contrast tables can be recomputed |
| Style rules | `python3 -I scripts/check.sh` runs the pack, docs, secrets and migration checks plus the tooling unit tests | The documentation set is consistent with the rest of the repository |

Evidence for a change is the recorded command output, not a summary. Runtime
accessibility behaviour (focus, announcements, screen readers) is verified in the
owning application module, not here.

## Decisions

These are the decisions this specification makes, with the reason. A change to
one of them is a contract version change, not an edit in place.

| # | Decision | Rationale |
|---|---|---|
| D1 | Tokens are named by meaning with the grammar `<family>.<segment>[.<segment>…]`, validated against the tables in the contract; numeric names are allowed only for `space`, `size` and `layer` | A meaning-based name survives a palette change; a numeric name encodes a base unit that can be validated mechanically |
| D2 | Colour tokens hold a colour only; geometry such as ring width and offset lives in `border.width.*` | The current staff-web token stores a whole `box-shadow` string, which hides the width and blocks a theme-aware ring |
| D3 | Exactly one focus ring per mode, drawn with a mandatory 2 px offset | The shipped single amber ring measures 1.64:1 on the light page; a ring flush on the primary fill measures 1.00:1 and 1.31:1, so an offset is the only treatment that works on both fill and page |
| D4 | Latin digits are the default in both locales; an approved site policy may switch to Arabic-Indic digits for every value on a screen | Mixed numeral systems on one screen are a dosing hazard; a site choice is configuration, not a component decision |
| D5 | V1 has no per-tenant palette or branding | Colour is a clinical readability contract; site configuration governs workflows, dictionaries and policies, not token values |
| D6 | A critical finding is a persistent in-flow region, never a toast, badge, tooltip or modal-only dialog | Persistence is the safety control; a transient surface cannot carry an acknowledgment obligation |
| D7 | Letter spacing stays at 0 for Arabic | Any tracking breaks the cursive joins and makes the text unreadable |
| D8 | A critical-result banner never steals focus on load; a page with an open finding announces it once, politely, and an arrival during the session announces it once assertively; the acknowledgment gates the dependent actions server-side | Focus theft during data entry causes mis-keyed clinical data; gating is the safety mechanism, not interruption |
| D9 | One generated token source per contract version; dotted names project to CSS custom properties and camel-case native keys, and an unknown token fails the consumer build | Mechanical derivation is testable; hand-copied values drift silently |
| D10 | The mode is a per-user display preference, resolved in one provider; a media query may choose the initial value but never styles a component | Two styling mechanisms disagree, and a screenshot review then passes on a configuration no user has |
| D11 | Right alignment of numeric columns is an explicit exception to mirroring | Digits are read left to right; column alignment is the scan affordance in both directions |
| D12 | Consent and clinical meaning are never derived from presentation; the interface shows the state the API returned | Whether a revision is editable, whether a result is critical and who may release it are server and policy decisions |

## Known deviations in the current code

Observed while writing this contract against the merged staff-web foundation. All
of them are outside this documentation story's scope; none of them is fixed here.
Each is a follow-up for the owning module.

| # | Observation | Evidence | Required by |
|---|---|---|---|
| 1 | The single amber focus ring fails non-text contrast on light surfaces, and it is a raw literal in the document, not a token | 1.64:1 measured on `#ffffff` (see [negative controls](token-contract.md#53-negative-controls)); the rule is `:focus-visible { outline: 3px solid #ffbf47 }` in `apps/staff-web/index.html` | D3, 1.4.11 |
| 2 | The `focusRing` token is a complete `box-shadow` string, so its width cannot be themed — and it is not the ring that paints | `apps/staff-web/src/theme/tokens.ts` is referenced by the token test only; the rendered ring is the `index.html` literal in row 1 | D2 |
| 3 | The label colour of the primary button is taken from the page background token, which passes by coincidence rather than by role | 6.70:1 light and 8.25:1 dark today (`tokens.background` on the accent); 8.60:1 is the proposed `color.action.primary.text`, not the shipped value | `color.action.primary.text` |
| 4 | One border token serves both control boundaries and decorative lines, so a decorative line and an input edge cannot diverge | `apps/staff-web/src/theme/tokens.ts` | `color.border.subtle` |
| 5 | The spacing token exposes three steps; the contract defines a closed scale | `apps/staff-web/src/theme/tokens.ts` | `space.<n>` |
| 6 | Component and document styles contain literal values outside the token set (control heights, dialog width, scrim colour, skip-link colours, index keys, cell borders) | `Dialog.tsx`, `Button.tsx`, `FormField.tsx`, `DataTable.tsx`, `index.html` | Sections 6.2, 3.1 and 6.3 of the contract |
| 7 | Table rows are keyed by array index, so a refresh can reuse a row key for a different patient | `apps/staff-web/src/components/DataTable.tsx` | Row identity, accessibility patterns section 4 |
| 8 | There is no automated accessibility rule set in the module test tooling yet | `apps/staff-web/package.json` | Verification table, accessibility patterns section 10 |
| 9 | The patient app has configuration but no source yet, so the contract is unadopted there | `apps/patient-mobile` | Section 8.2 adoption |
| 10 | The mode provider hard-codes light, writes `data-theme` after first paint, has no stored preference and never reads `prefers-color-scheme`, so there is no flash-free first load and no explicit choice to persist | `apps/staff-web/src/theme/ThemeProvider.tsx`; the ring and `color-scheme` are also set in `index.html` | Theming rules 1–2 in the contract |
| 11 | Neither the number nor the date formatter pins a numbering system, so locale `ar` renders Arabic-Indic digits beside Latin identifiers and dates; measurement also joins value and unit with a plain space | `apps/staff-web/src/i18n/format.ts`; the tests assert neither the digits nor the separator | RTL section 3, D4 |
| 12 | A disabled primary button differs from the enabled state only by the cursor, with no disabled fill or label treatment | `apps/staff-web/src/components/Button.tsx` | `color.action.primary.disabled` |

## Adoption

The [adoption section of the token contract](token-contract.md#8-adoption) maps
the current staff-web fields onto contract tokens and lists the steps for a
consuming app. Adoption is incremental, and most values do not change, but it is
not a pure rename: the focus ring is replaced in two places (the token and the
document stylesheet), the button label colour becomes its own token, the spacing
token that is used as a corner radius becomes a radius token, and adopting
`size.control.height.md` visibly changes every control height. The adoption
section lists those, plus the tests that encode the current defects and must be
replaced rather than adjusted.

Contract version 1.0.0 has never been adopted, so it is still the initial
proposal and these revisions are part of it rather than a version bump. A module
records the version it implements in its own README when it adopts one.

## Open items

| Item | Why it is open | Who can close it |
|---|---|---|
| Arabic-Indic digit policy per site | A clinical and market decision, not a design one | Site clinical lead with the market policy pack |
| Whether the acknowledgment alone may clear the critical-result region, and which commands it gates | A coverage and escalation policy decision; the document states the safest default, not the approved one | Site clinical safety lead and medical director |
| Banner content sufficiency at the moment of decision | What a clinician needs to act on a critical value is a clinical judgement | Site clinical safety lead |
| Read-back requirements and who may be a recipient | Policy decides when read-back is required | Site clinical safety lead and privacy lead |
| Whether the patient's name is spoken aloud in a shared clinical space | A privacy decision that changes the announced text | Privacy lead with the clinical lead |
| Whether the amber accent survives as a secondary highlight once the ring is replaced | Cosmetic, but it touches the audit highlight and the selected-row treatment | Design review with the module owner |
| Contrast measurement in continuous integration | The recipe is reproducible by hand; a CI job needs a token source file to consume | Module owner with the build tooling owner |
| Automated accessibility rule set (axe-core or equivalent) in the staff-web test run | Needs a dependency decision and a baseline of existing failures | Staff web module owner |
| Index-keyed table rows in any worklist that shows an actionable clinical row | The contract requires a stable domain key; the current table uses the array index | Staff web module owner, before that worklist ships |
| Patient app token adoption and native screen-reader pass | Module is pending | Patient app module owner |
| Clinical review of the critical-result banner content and the acknowledgment gate | Requires qualified clinical reviewers; nothing in this repository substitutes for it | Site clinical safety lead |

## Boundaries

- Synthetic data only. No example in this directory contains a real person, a real
  identifier or a credential.
- No clinical approval is implied. These are engineering and design rules that
  make clinical work readable, reversible and reviewable; the per-site clinical,
  privacy and operational approvals remain separate gates.
- No application code, no schema, no API contract and no delivery change belongs
  in this directory. A token that needs a new value is added here first, then
  adopted by a module in its own change.

## Screens and navigation

The screen map, application shell, required screen states and layout rules are in [screens and navigation](screens-and-navigation.md).
