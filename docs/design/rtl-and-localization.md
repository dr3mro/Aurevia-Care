# RTL and localization

- Requirements: UX-01, PRD-05
- Applies to: `apps/staff-web` and `apps/patient-mobile`
- Index: [Design language home](README.md) · See also [Token contract](token-contract.md) and [Accessibility patterns](accessibility-patterns.md)

V1 supports English and Arabic, right-to-left layout, local name display and
mixed-script clinical text. This document states the rules that keep a
bidirectional clinical screen readable, and the parts a translator, a designer and
a reviewer each own.

The implemented subset today lives in `apps/staff-web`: `directionFor` maps a
locale to a direction, `displayLocalName` chooses a display form,
`formatInstant` and `formatCivilDate` format instants and civil dates, and
`MixedScriptText` isolates a bidirectional run. Those functions are the starting
point, not the whole contract.

## 1. Direction model

Direction is a property of a text run, decided by the locale, and applied by the
platform. It is never a per-component decision and never a styling trick.

| Rule | Detail |
|---|---|
| Document direction | `dir="rtl"` and `lang="ar"` on the document element for Arabic; `dir="ltr"` and `lang="en"` for English. The staff web already mirrors locale onto `lang` and `dir`. |
| Run direction | Every value whose script may differ from the interface language carries its own `lang` and `dir="auto"`: patient names, free text, addresses, result comments. |
| Isolation | Wrap a foreign run in `<bdi>` (or `unicode-bidi: isolate`) so it cannot reorder the punctuation around it. Neighbouring neutral characters (parentheses, slash, hyphen, colon) are the usual failure. |
| No nested direction overrides | A widget MUST NOT set `direction: rtl` on a subtree to "fix" a layout. Two direction scopes on one screen produce mirrored contrast, mis-ordered focus and unreadable numbers. |
| Focus order | Focus order follows the DOM, not the visual order. Under RTL the DOM order stays logical; the browser mirrors the layout. Reordering the DOM for RTL breaks screen readers and keyboard users. |
| Scroll origin | Horizontal scroll starts at the inline start; use `scroll-padding-inline-start`, not `scroll-padding-left`. |

### 1.1 Logical properties

Physical properties are a defect in shared components. Use the logical
equivalent for the platform:

| Physical (avoid) | Web (use) | Native (use) |
|---|---|---|
| `margin-left` / `margin-right` | `margin-inline-start` / `margin-inline-end` | `marginStart` / `marginEnd` |
| `padding-left` / `padding-right` | `padding-inline-start` / `padding-inline-end` | `paddingStart` / `paddingEnd` |
| `left` / `right` (positioning) | `inset-inline-start` / `inset-inline-end` | `start` / `end` on an absolutely positioned element |
| `border-left` | `border-inline-start` | `borderStartWidth` / `borderStartColor` |
| `text-align: left` / `right` | `text-align: start` / `end` | omit it, or use `textAlign: 'auto'` |
| `border-top-left-radius` | `border-start-start-radius` | `borderTopStartRadius` |
| `box-shadow: 0 1px` offsets that imply a side | Shadow with no horizontal offset, or a border | Shadow with `shadowOffset.width: 0`, or a border |
| `float: left` | `float: inline-start` | No equivalent: use a row with `justifyContent` |
| `transform: translateX(-100%)` for a drawer | `translate: -100% 0` with a direction-aware sign, or `inset-inline-start` animation | `transform: [{ translateX }]` with a direction-aware sign; transforms never mirror |

Flexbox and grid already mirror `row` and `start`. What does not mirror is
anything with a side baked in: absolute insets, one-sided borders, one-sided
shadows, transforms, `background-position`, and icon glyphs. Those are the review
targets.

On native, `flexDirection: 'row'` and `justifyContent: 'flex-start'` mirror only
under an RTL layout mode, which is a startup condition: `I18nManager.isRTL` does
not change until the app restarts, and Expo additionally needs RTL enabled in the
build configuration (Android `supportsRtl`, iOS localized bundle or device
language). A language switch inside the app therefore needs an explicit
"restart to apply direction" step. Physical `left`/`right` and
`marginLeft`/`marginRight` are worse than cosmetic there: iOS swaps them by
default under RTL while Android leaves them physical, so the same component
mirrors on one platform and not the other. Treat them as a defect, not as a
styling choice.

`writingDirection` exists but is iOS-only; `textAlign: 'start'` is not part of the
React Native `textAlign` set, which is `auto`, `left`, `right`, `center` and
`justify`.

## 2. Mirroring rules

| Element | Mirror under RTL? | Reason |
|---|---|---|
| Layout columns, gutters, insets, table column order | Yes | Reading direction |
| Back / forward chevrons, "next" and "previous" pagination arrows | Yes | They point along the reading direction |
| Indent and outdent, quote marks, blockquote edge | Yes | Direction of nesting |
| Progress bars, steppers, timelines, sliders | Yes | Time and progress advance in reading direction |
| Undo / redo arrows | Yes | They pair with back and forward |
| Checkbox and radio position, and toggle knob travel | Yes | The control sits at the start edge and the knob travels with the reading direction; the check or radio glyph itself never flips |
| Text alignment of prose and labels | Yes (implicitly, via `start`) | Reading direction |
| Digits, decimal separators, `+`/`-` signs, comparison operators | No | Numeric runs are left-to-right in both directions |
| Latin-script names, identifiers, MRN, accession, lot numbers | No | They are read as written; see section 4 |
| Units (`mg`, `mmol/L`, `%`, `°C`) | No | Unit symbols are invariant |
| Medical and status symbols: clock, calendar grid, checkmark, cross, warning triangle, caduceus, pill, syringe, ECG waveform | No | The glyph is not directional and mirroring makes it wrong or unreadable |
| Anatomical diagrams, body maps, imaging, ECG/EEG traces, waveforms | No | Laterality is clinical data; mirroring shows the wrong side |
| Logos, brand marks, signatures | No | Identity |
| Play / pause / record media controls | No | Media semantics are universal |
| Clocks and watch faces | No | A mirrored clock reads a different time |

Rules of thumb:

1. Mirror the **flow**, never the **content**.
2. A mirrorable icon is implemented once, as a directional variant, and flipped
   by a rule scoped to the direction (`[dir="rtl"] .icon--directional`). Do not
   ship two hand-drawn assets, which drift.
3. If mirroring an icon would change what a clinician believes about the patient,
   it is not a layout icon and MUST NOT mirror.
4. Arrow keys follow the reading direction for horizontal navigation inside a
   widget: in RTL, ArrowLeft moves to the next item.
5. A chronological axis (trend chart, result history) follows reading direction;
   the **data** does not change order, only the axis placement. Label the axis
   ends with dates, not with "earlier"/"later" arrows only.

## 3. Numbers, units and clinical values

Digits and units are the highest-risk text on the screen: a mirrored or
reordered value reads as a different result.

| Rule | Detail |
|---|---|
| Default numeral system | Latin digits `0-9` in both locales. Pass `numberingSystem: 'latn'` explicitly to **every** `Intl` constructor that renders a number — `Intl.NumberFormat` and `Intl.DateTimeFormat` alike — instead of relying on a platform default. Locale `ar` otherwise resolves to the `arab` numbering system, so a value renders Arabic-Indic while the identifier beside it stays Latin. |
| Site-approved alternative | A site may approve Arabic-Indic digits (٠١٢٣…) as a policy decision. When approved, apply it to every numeric field **on the same screen**; a mixed screen is a defect. The policy is configuration (ADM-01), not a component choice. |
| Never mixed | One screen, one numeral system for values. Identifiers stay Latin regardless, because they are quoted across systems. |
| Decimal and grouping separators | Come from `Intl`, never from string concatenation. A `,` decimal separator in one field and `.` in the next is a dosing hazard. |
| Value and unit | Same run, non-breaking space (`U+00A0`) between number and unit (`5 mg`, `37.5 °C`). Do not break a line between them; do not translate the unit symbol. A plain space lets the unit wrap onto the next line, away from its value. |
| Qualifiers | `<`, `>` and `≤`/`≥` stay immediately before the number and inside the same isolated run (`< 0.5 mg/L`). A qualifier separated from its value changes the meaning. |
| Ranges | Use an en dash without spaces in a value run: `3.1–3.4 mmol/L`. The direction of the dash is irrelevant; the run is isolated. |
| Negative and signed values | `-1.2` keeps the leading sign in the same run; never place the sign on the opposite side by letting bidi reorder it. |
| Percentages and ratios | `12 %` follows the locale's spacing convention but stays one run; blood pressure is written `120/80 mmHg` and never split across lines. |
| Dates used as data | ISO `YYYY-MM-DD` in an identifier or accession is a left-to-right run; see section 5 for display dates. |
| Unknown, none, not reported | Explicit words from the message bundle ("Not reported", "None known", "Unknown"), never an empty cell and never a bare dash. Colour or a dash alone is not a state. |

Do not perform unit conversion in the interface. Display the unit the source
returned, with its value; conversion is a clinical decision that belongs to a
documented workflow with provenance, not to a formatter.

The merged `apps/staff-web/src/i18n/format.ts` pins `NUMERAL_SYSTEM = 'latn'`
and passes `numberingSystem` to the date and number formatters, so Arabic
locales still render Latin digits. `formatMeasurement` joins value and unit with
a plain space, which remains a deviation from the non-breaking-space rule in
the [design language home](README.md#known-deviations-in-the-current-code).

## 4. Identifiers and codes

| Kind | Presentation |
|---|---|
| MRN, accession, requisition, specimen, study UID | `dir="ltr"` inside a `<bdi>`-equivalent isolation, `font.family.mono`, no wrapping, no hyphen reflow, no automatic translation |
| Order, invoice, receipt numbers | Same as above; keep the prefix that the server issued |
| Phone, email, URL | Isolated LTR run; `tel:` and `mailto:` links keep the raw value |
| Correlation ID, event ID, checksum | Mono, isolated, copy action with an accessible label that names what is copied |
| Diagnosis and procedure codes | Code and display text are separate fields, both shown: the code in mono and isolated, the text in the interface language. Mapping never replaces the original code or the original text. |

A copy control MUST copy the value with no direction marks and no formatting
characters, and MUST announce success in the interface language.

## 5. Dates, times and time zones

| Rule | Detail |
|---|---|
| Instants | Store and transmit UTC instants. Render with an explicit IANA zone: the facility zone for a visit, the ordering organization zone for a released result, both labelled when they differ. |
| Civil dates | A date of birth or a prescription date is a civil date, not an instant. Format it at UTC midnight so the calendar day never shifts; never convert a civil date through a local zone. |
| Format | `Intl.DateTimeFormat` with the interface locale and the zone; no hand-built `dd/mm` strings. Ambiguous numeric dates are prohibited: use a month name or an unambiguous format. |
| Clock style | Follow the locale's default 12/24-hour convention, and let a site policy override it. Never infer 12/24 from the zone. |
| Relative time | Use `Intl.RelativeTimeFormat` for "in 3 hours", and always pair a relative time with the absolute one for anything clinical ("in 3 hours, 14:05"). |
| Between zones | When the order was placed in one zone and the result released in another, show both labels; the review is about the instant, not the wall clock. |
| Daylight saving | Test two instants per zone: the spring-forward gap (a wall-clock time that does not exist) and the fall-back overlap (a wall-clock time that occurs twice). Assert on the rendered string, not on a computed offset. Use the tzdata present at run time as the source of truth rather than hard-coded transitions. |
| Ambiguous input | A time entered by staff during a fall-back overlap MUST be resolved with an explicit zone, and the resolution MUST be visible before the value is saved. |

## 6. Names and local-script display

Patient and staff names are clinical identifiers, not decoration.

| Rule | Detail |
|---|---|
| Local form first | In an Arabic interface, show the local-script name when it exists, and fall back to the Latin form otherwise. The staff web already implements this precedence; keep it in one function rather than in each screen. |
| Both forms when identity matters | Identity confirmation, duplicate review, registration and any high-risk transition show both the local and the Latin form when both exist, plus the second identifier. A single transliterated form is not enough to confirm identity. |
| Never transliterate in the UI | The interface does not invent a Latin spelling or an Arabic spelling. It displays what the record holds, with `lang` and `dir="auto"` on the run. |
| Preserve original spelling | Normalization for search happens on the server and preserves the original spelling. The UI MUST NOT silently re-spell, case-fold or strip diacritics before display. |
| Name order | Do not split a name into "first" and "last" for display or for sorting unless the record stores the parts. Display the recorded full name; sorting uses the recorded family name only when the record says which part that is. |
| Truncation | Do not truncate a name with an ellipsis in an identity confirmation, a banner, a signature block or a release header. Wrap or grow the row instead. |
| Shared or missing names | Show the explicit state ("Name not recorded", "Unknown — alternate identification used") instead of a blank. |
| Honorifics and titles | Take the title from the record or the message bundle. Do not concatenate an English honorific onto an Arabic name. |
| Names are not links | A matching name, phone or date of birth never links two records or two accounts. The interface must not offer a "merge" affordance on that basis; consolidation is a separate reviewed workflow. |
| Search results | Show the matched form, the original spelling and the distinguishing identifiers. Highlighting must not change the stored characters. |

## 7. Mixed-script clinical text

Free text may contain two scripts and two directions in one field: a name in
Arabic, a drug in Latin, a dosage with a unit, a comment mixing both.

| Rule | Detail |
|---|---|
| Isolate the foreign run | Wrap the Arabic run inside Latin prose, and the Latin run inside Arabic prose, in an isolation element with the right `lang`. |
| Keep values atomic | A dosage, a value with a unit, an identifier, or a code with its text is one isolated run. Splitting it lets the neutral characters reorder. |
| Parentheses and punctuation | Keep a parenthesised acronym inside the same isolated run as the acronym, or the closing parenthesis jumps to the other end. |
| Line breaking | Allow a break between runs, not inside a value. Set the isolation element to `white-space: nowrap` only for short values; long free text must still wrap. |
| Editing | A text area that may contain both scripts uses `dir="auto"` per paragraph where the platform supports it, and never forces the field direction from the interface locale. |
| Search | The query field follows the interface direction; the results are isolated per value. Bidirectional search input is not "corrected" by the UI. |
| Copy and paste | Copying a mixed run keeps the logical order; the clipboard text must equal the stored text. |
| Screen readers | On web, the `lang` attribute on the run switches the voice; missing `lang` on an Arabic run inside English prose produces an unreadable reading. On native, `accessibilityLanguage` is iOS-only and per element, and Android exposes no React Native API for a text locale, so the Android evidence requires the device in Arabic or a native span component outside this contract. |

## 8. Message bundles and translation

| Rule | Detail |
|---|---|
| One key per string | Both bundles carry the same keys; a missing key is a build failure, not a runtime fallback. |
| No concatenation | Never build a sentence from fragments ("Signed" + "by" + name). Word order differs, and so does the agreement. Use one message with placeholders, or ICU plural/select. |
| Placeholders | Explicit, named placeholders. Reordering a translation must not reorder the values. |
| Plurals | ICU plurals for counts ("1 result", "3 results", "0 results" — Arabic has its own plural categories); never `n + " results"`. |
| Gender and formality | Choose the register once per locale and record it; do not mix formal and informal address on one screen. |
| Length | Budget 30% expansion for Arabic. A button that only fits "Save" is a layout defect, not a translation defect. |
| No text in images | Icons, logos and illustrations carry no words; all labels are text. |
| Error messages | Machine-readable from the API, translated in the client by code, and always paired with the original correlation ID in a copyable field. |
| Clinical free text | Stored as written with a language tag. Never machine-translated for display, and never re-typed into a translated field. |
| Exports | Preserve the language and the units of the record in the export, and label the language of each generated document. |

## 9. Verification

A localization change is complete when all of the following are observed. All
data is synthetic.

1. **Direction**: the render test asserts `dir` and `lang` on the document for
   both locales, and asserts `dir="auto"` and the `lang` attribute on every
   isolated value.
2. **Mirroring**: a screenshot or style assertion covers each mirrorable icon and
   each budgeted logical property; a grep-style test fails the build on
   `margin-left`, `margin-right`, `padding-left`, `padding-right`,
   `text-align: left`, `text-align: right`, `inset-inline` misuse,
   `border-left`/`border-right` in shared web components, and the camel-case
   native equivalents `marginLeft`, `marginRight`, `paddingLeft`, `paddingRight`,
   `borderLeftWidth`, `borderRightWidth` and bare `left:`/`right:` in app styles.
3. **Logical properties survive pins**: the same component renders in both
   directions at 320 px and 1280 px without overlap or clipping.
4. **Numbers**: a table-driven test formats the same value in both locales and
   asserts the rendered digits (not just the separator), the separator, the unit
   spacing including the non-breaking space, and the qualifier position. Include
   a negative value, a qualified value, a range and a percentage, and assert the
   date formatter's digits as well as the number formatter's.
5. **Dates**: assertions for a UTC instant, a civil date, the spring-forward gap
   and the fall-back overlap, each rendered in a named IANA zone.
6. **Names**: precedence between local and Latin forms, the both-forms case for
   identity confirmation, and the missing-name state.
7. **Mixed script**: a stored value with both scripts round-trips through display
   and copy unchanged.
8. **Screen readers**: one iOS VoiceOver pass and one Android TalkBack pass on
   the bidirectional screens. Language switching is exercised on iOS via
   `accessibilityLanguage`; on Android the pass runs with the device language set
   to Arabic, because the platform exposes no per-run API. Automated assertions
   cannot replace this; record the pass as a named evidence item.
9. **Translation completeness**: the two bundles have identical key sets, and a
   test fails when a key is added to one bundle only.

Nothing in this document is clinical or human approval of a localization; a
site-level review with Arabic-speaking clinical staff remains a launch gate.
