# Token contract

- Contract version: 1.0.0 (proposed)
- Requirements: UX-01, PRD-05
- Applies to: `apps/staff-web` (React, DOM) and `apps/patient-mobile` (React Native, Expo)
- Index: [Design language home](README.md) · See also [RTL and localization](rtl-and-localization.md) and [Accessibility patterns](accessibility-patterns.md)

This document is the normative contract for design tokens. A token is a named,
measured presentation value. Components MUST read tokens and MUST NOT contain
raw colours, pixel values or font stacks. `MUST`, `MUST NOT`, `SHOULD` and `MAY`
are used in the RFC 2119 sense.

Nothing here is runtime code. The contract exists so that the staff web and the
patient app present the same clinical information with the same readable,
translatable and themeable values, and so a later change is a token change
rather than a redesign.

## 1. Naming grammar

```text
<family>.<segment>[.<segment>…]
```

| Part | Rule |
|---|---|
| `family` | One of `color`, `space`, `size`, `radius`, `border`, `font`, `elevation`, `motion`, `layer`. Lowercase. |
| meaning segment | A lowercase kebab noun naming the **meaning**, never the appearance: `text`, `primary`, `surface`, `status`, `critical`, `action`, `control`, `width`, `duration`, `target`. |
| facet segment | The part of the role a value applies to, from a closed vocabulary: `surface`, `background`, `text`, `border`, `icon`, `ring`, `shadow`, `default`, `raised`, `sunken`, `scrim`, `subtle`, `strong`, `inverse`, `link`, `destructive`, `sm`, `md`, `lg`, `xl`, `caption`, `label`, `body`, `heading`, `numeric`, `regular`, `medium`, `semibold`, `bold`, `arabic`, `min`, `comfortable`, `height`, `hairline`, `thin`, `thick`, `none`, `pill`, `fast`, `base`, `slow`, `standard`, `sticky`, `menu`, `dialog`, `toast`. |
| state segment | Last, and only when the value varies by interaction or lifecycle: `hover`, `active`, `focus`, `disabled`, `selected`, `checked`, `visited`, `open`, `overdue`. |

Appearance words (`gray`, `blue`, `big`, `left`, `right`) are forbidden in every
position, including inside a kebab segment.

Additional rules:

1. Segments are lowercase ASCII, hyphen-separated inside a segment, dot-separated
   between segments. No spaces, no leading or trailing dots.
2. Numeric scale names encode the base unit: `space.<n>` is `n × 4 px`, so
   `space.4` is `16px`. Numeric names are used only for `space`, `size` and
   `layer`; everything else is named by role and facet.
3. A token name never contains a mode (`light`, `dark`), a platform
   (`web`, `ios`) or a unit. The same name resolves to a different value per
   mode and per platform.
4. A colour token holds a **colour only**. Anything that combines a colour with
   a geometry — a `box-shadow` string, an `outline`, a gradient — is a
   composite and MUST be described by the geometry tokens plus the colour token.
5. Two tokens MUST NOT be given identical roles. If two names always hold the
   same value, one of them is redundant and must be removed or given a distinct
   meaning.
6. Names describe meaning, so a component MUST NOT add a component-specific
   token (`color.button.save.background`). If a role is genuinely new, it is
   added to this document first.
7. The normative name set is the tables in sections 3 to 6, not the vocabulary
   above. A validator MUST be generated from those tables, MUST reject a name
   that is not in them, and MUST reject a segment outside the vocabulary. Adding
   a token means adding it to a table here, and that is what makes "asserted by a
   test rather than reviewed by eye" true.

## 2. Platform derivation

One source of truth, three projections. The source is a JSON map keyed by the
dotted token name, per mode. A token whose value is the same on both platforms is
a scalar; a token whose projection differs says so explicitly:

```json
{
  "contractVersion": "1.0.0",
  "light": {
    "color.surface.default": "#ffffff",
    "space.4": "16px",
    "font.size.body": { "web": "1rem", "native": 16 },
    "font.family.mono": { "web": "ui-monospace, SFMono-Regular, Menlo, monospace",
                          "native": { "ios": "Menlo", "android": "monospace" } },
    "size.target.comfortable": { "web": "44px", "native": { "ios": 44, "android": 48 } }
  },
  "dark": { "color.surface.default": "#111827" }
}
```

| Target | Transformation | Example |
|---|---|---|
| CSS custom property | prefix `--sc-`, then every dot to a hyphen; a generated typed handle, not a hand-written string | `--sc-color-surface-default` |
| React Native object key | drop nothing, split on dots **and hyphens**, camel-case every following segment | `colorSurfaceDefault`, `colorStateEnteredInError` |
| TypeScript type | union of the dotted names, plus a `cssVar` map from each name to its `--sc-` string | `type TokenName = 'color.surface.default' \| ...` |

Derivation is mechanical, so it is the same on every platform and can be asserted
by a test rather than reviewed by eye. Requirements:

1. Consumers MUST generate their token objects from the source rather than
   re-typing values, and MUST fail their build on an unknown token name.
2. The hyphen rule is part of the derivation: a kebab segment camel-cases into one
   segment per word (`entered-in-error` becomes `EnteredInError`), otherwise the
   projected key is not a valid identifier.
3. The derivation test MUST assert that projected keys are unique for every token
   in the source, not just check two examples.
4. On web, a generated `cssVar` handle of type
   `Record<TokenName, \`--sc-${string}\`>` is how a component names a custom
   property. Hand-writing `var(--sc-...)` in CSS is outside the compile-time
   check, so the generated stylesheet is asserted separately.
5. Strict TypeScript rejects an index signature on `CSSProperties`; a generated
   `TokenStyle = CSSProperties & Record<\`--sc-${string}\`, string>` is the
   supported way to set a custom property in a style object.
6. jsdom does not substitute `var()` and does not compute cascaded custom
   properties, and it has no keyboard-versus-pointer modality, so the focus-ring
   assertions in a unit test cover the generated stylesheet and the attribute,
   not the painted result. The painted ring is a browser check.
7. Custom properties do not need `@property` registration in v1: no token is
   animated or type-checked by the browser. Adding registration later is not a
   contract change.

Spacing is a number on native and a length on web. The source keeps the platform
pair for those tokens and the native projection derives the number; a consumer
MUST NOT re-declare the scale.

## 3. Colour tokens

Values below are contract v1. The `Use` column is normative: a value MUST NOT be
reused for a role that is not listed.

### 3.1 Surfaces, text and borders

| Token | Light | Dark | Use |
|---|---|---|---|
| `color.surface.default` | `#ffffff` | `#111827` | Page and dialog background |
| `color.surface.raised` | `#f7f9fc` | `#1a2434` | Cards, table header, zebra stripe |
| `color.surface.sunken` | `#eef1f6` | `#0b1220` | Filter bars, inset panels |
| `color.surface.scrim` | `rgba(11, 20, 32, 0.45)` | `rgba(0, 0, 0, 0.60)` | Modal scrim; content behind it is unsupported for reading |
| `color.text.primary` | `#152033` | `#f3f6fb` | Body text, clinical values, labels |
| `color.text.secondary` | `#4a5768` | `#c2ccdb` | Timestamps, provenance, hints, metadata |
| `color.text.inverse` | `#ffffff` | `#0b1420` | Text on an inverted fill; equals `color.action.primary.text` in v1 but is a distinct role |
| `color.text.link` | `#0a4d8f` | `#a5c9ff` | Inline link, skip link and the "open the full record" affordance; always underlined, so colour is reinforcement |
| `color.text.disabled` | `#6b7280` | `#7c8ca3` | Disabled label; contrast is exempt, meaning MUST also change |
| `color.border.default` | `#6b7280` | `#7c8ca3` | Input and control boundary, focusable region edge |
| `color.border.subtle` | `#c3cbd8` | `#3a4759` | Decorative separator only; MUST NOT be the sole boundary of an interactive control |
| `color.border.strong` | `#3c4a5c` | `#aab6c6` | Selected row edge, table outline, emphasised boundary |

### 3.2 Actions

| Token | Light | Dark | Use |
|---|---|---|---|
| `color.action.primary.background` | `#0b5cab` | `#7db3ff` | Primary command fill |
| `color.action.primary.text` | `#ffffff` | `#0b1420` | Label on the primary fill |
| `color.action.primary.hover` | `#08498a` | `#a5c9ff` | Primary fill, hover |
| `color.action.primary.active` | `#06386a` | `#8fc0ff` | Primary fill, pressed |
| `color.action.primary.disabled` | `#c3cbd8` | `#3a4759` | Disabled primary fill |
| `color.action.primary.text.disabled` | `#4a5768` | `#c2ccdb` | Label on the disabled primary fill; 4.50:1 and 5.82:1, so a gated control still explains itself |
| `color.action.secondary.background` | `transparent` | `transparent` | Secondary command fill; boundary from `color.border.default` |
| `color.action.destructive.background` | `#a4271f` | `#ffb4ab` | Irreversible command fill; label `color.text.inverse` |

A destructive command MUST also carry a word ("Void", "Deactivate"), not only a
colour.

A link is not an action fill. `color.text.link` is for navigation and must be
underlined at rest, so the role survives a monochrome or forced-colours render;
`color.action.primary.background` remains a fill and MUST NOT be used for text.

Red is reserved. `color.status.critical` means "requires immediate clinical
action" and MUST NOT be reused for a record fault, a form error or a destructive
command. Those three meanings look alike and are acted on differently, so they
use different token groups: `color.state.fault.*` for a record revision fault,
`color.status.critical` for a finding that needs action now, and
`color.action.destructive` for an irreversible command that has not happened yet.

### 3.3 Status

Status colour is reinforcement. Every status surface MUST also carry a text
label and an icon whose shape differs from every other status.

| Token group | Light | Dark |
|---|---|---|
| `color.status.critical.surface` / `.border` / `.text` | `#fdecea` / `#b3261e` / `#a4271f` | `#3b1714` / `#ff897d` / `#ffb4ab` |
| `color.status.warning.surface` / `.border` / `.text` | `#fff4d6` / `#8a6100` / `#6b4a00` | `#332506` / `#e0a800` / `#ffd88a` |
| `color.status.success.surface` / `.border` / `.text` | `#e8f7ee` / `#1f7a44` / `#14532d` | `#0e2a19` / `#52c07c` / `#9fe3b4` |
| `color.status.info.surface` / `.border` / `.text` | `#e8f1fb` / `#1b5fa8` / `#103f77` | `#0f2137` / `#6ba6ea` / `#a9cdf7` |

### 3.4 Focus

| Token | Light | Dark | Use |
|---|---|---|---|
| `color.focus.ring` | `#0b5cab` | `#ffbf47` | The focus indicator stroke |

The ring is drawn as an `outline` of `border.width.focus` with a
`border.width.focus` offset:

```css
:focus-visible {
  outline: var(--sc-border-width-focus) solid var(--sc-color-focus-ring);
  outline-offset: var(--sc-border-width-focus);
}
```

The offset is mandatory and the gap is **transparent**: it shows the surface the
control already sits on. There is therefore no second colour token for the gap,
and the ring must be an outline rather than a `box-shadow`, because forced-colours
mode discards shadows and force-recolours outlines, which is exactly the
behaviour a focus indicator needs.

A ring drawn flush against the primary fill measures 1.00:1 in light mode and
1.31:1 in dark mode (see [section 5](#5-contrast-contract)) and is invisible. With
the offset, the ring's adjacent colours are surfaces, and every surface it can sit
on clears 3:1.

Free-standing decorative rings (for example a hover outline) do not reuse
`color.focus.ring`; focus is a state, not decoration.

### 3.5 Clinical record states

Signing, amendment and error states are meanings, not colours. Each state maps
onto a status group **and** MUST render the state word:

| State token | Maps to | Light | Dark | Rendered word |
|---|---|---|---|---|
| `color.state.draft` | `color.border.default` + `color.surface.raised` | — | — | Draft |
| `color.state.signed` | `color.status.success` | — | — | Signed |
| `color.state.released` | `color.status.info` | — | — | Released to patient |
| `color.state.amended` | `color.status.info` | — | — | Amended |
| `color.state.unverified` | `color.status.warning` | — | — | Unverified |
| `color.state.fault.surface` / `.border` / `.text` | Its own group | `#f9eefa` / `#8b2f8b` / `#6d1b6d` | `#35203a` / `#d98ad9` / `#f0b3f0` | Entered in error |
| `color.state.unknown` | `color.border.default` + `color.text.secondary`, dashed outline | — | — | Unknown |

`color.state.fault` is a separate group on purpose. A record fault (an entered-in-error
revision), an unacknowledged critical finding and a destructive command are three
different obligations, and reusing one red value for all three erodes the one that
means "act now". Their borders are close in luminance, so the difference must
never rest on hue: the word and a shape-distinct icon carry it, per the
use-of-colour rule.

Two record states are deliberately separate words:

- **Signed** means the author attested to the revision. It does not mean released
  to the patient and it does not mean a PDF was rendered; a signed but unreleased
  record shows "Signed" alone and MUST NOT imply the patient has it.
- **Released to patient** is a later, separately authorized event. When both are
  true, both words are shown, adjacent.

State words are the API's own state names. Approbatory synonyms ("Complete",
"Done", "Approved", "Success") MUST NOT replace them, because staff read them as a
statement about the patient record rather than about a rendering step.

`color.state.unknown` deliberately does **not** use `color.border.subtle`: that
value is a decorative separator that measures 1.63:1 in light mode, and "unknown"
is a state a clinician must be able to see. It uses the control boundary, the
secondary text colour, the word and a dashed outline, so the state is carried by
shape and text first.

These mirror the record rules in the specification (draft to signed, separate
amendment revisions, entered-in-error that preserves the prior revision). The
design layer never decides whether a revision may be edited; it only shows the
state the API returned. An "unknown" value is never rendered as empty or as zero.

## 4. Typography

| Token | Web value | Native projection | Use |
|---|---|---|---|
| `font.family.body` | `system-ui, -apple-system, "Segoe UI", Roboto, "Noto Sans", "Noto Naskh Arabic", "Segoe UI Arabic", Arial, sans-serif` | Omit `fontFamily`: the platform system face already carries Arabic (iOS falls back to SF Arabic / Geeza Pro, Android to Noto Naskh Arabic) | All clinical text |
| `font.family.heading` | Same stack as body | Same as body | Headings differ by size and weight, not family |
| `font.family.mono` | `ui-monospace, SFMono-Regular, Menlo, Consolas, "Noto Sans Mono", monospace` | One family name only: `Platform.select({ ios: 'Menlo', android: 'monospace' })` | Identifiers, accessions, correlation IDs, checksums |
| `font.family.numeric` | Body stack with tabular figures | Not a family: a style, `fontVariant: ['tabular-nums']` | Vitals, results, money |

A font stack is a web concept. React Native takes a single family name, and
`system-ui`, `-apple-system` and `ui-monospace` are not valid native families, so
the native projection above is the contract, not an implementation detail. If a
specific Arabic face is added later it is loaded with `expo-font` and selected per
locale, because native cannot choose a face per script; a mixed-script run then
needs one `<Text>` per script with the matching family, which is another reason
to keep values in short isolated runs.

The web Arabic faces are named in the fallback chain because a desktop system may
not have a face with the needed presentation forms. A webfont MAY be added later
only if it is self-hosted, subsetted, has a metric-compatible fallback and is not
required to read a clinical value.

| Token | Web size | Native size (dp / pt) | Line height | Weight | Use |
|---|---|---|---|---|---|
| `font.size.caption` | `0.75rem` (12 px) | 12 | 1.5 | regular | Timestamps, legal footnotes; never a clinical value |
| `font.size.label` | `0.8125rem` (13 px) | 13 | 1.5 | medium | Field labels, table headers |
| `font.size.body` | `1rem` (16 px) | 16 | 1.5 | regular | Default clinical text |
| `font.size.body.lg` | `1.125rem` (18 px) | 18 | 1.5 | regular | Reading-heavy prose, patient instructions |
| `font.size.heading.sm` | `1.25rem` (20 px) | 20 | 1.3 | semibold | Card and dialog titles |
| `font.size.heading.md` | `1.5rem` (24 px) | 24 | 1.3 | semibold | Screen titles |
| `font.size.heading.lg` | `2rem` (32 px) | 32 | 1.25 | semibold | Page title, at most one per screen |
| `font.size.numeric.lg` | `1.5rem` (24 px) | 24 | 1.25 | medium | A single emphasised result or vital sign |

Native sizes are density-independent (dp on Android, pt on iOS), equal to the
documented pixel value at the default scale, and are scaled by the OS text
setting. They are not physical pixels: converting with `PixelRatio.get()` renders
a quarter of the intended size on a 3× screen and will fail review.

| Token | Value | Use |
|---|---|---|
| `font.weight.regular` / `.medium` / `.semibold` / `.bold` | 400 / 500 / 600 / 700 | `bold` is reserved for the critical-result headline |
| `font.tracking.default` | `0` | Everything, including Latin |
| `font.tracking.arabic` | `0` | Arabic text: letter spacing breaks the cursive joins and MUST stay at 0 |

Rules:

1. Text sizing is in `rem` on web so the browser font-size preference scales it;
   `px` is used only for hairlines, radii and spacing, where a fractional value
   is meaningless.
2. `font.size.caption` MUST NOT carry a clinical value, a due time or an
   acknowledgement action.
3. Numbers in tables and vitals columns MUST set tabular figures
   (`font-variant-numeric: tabular-nums` on web, `fontVariant: ['tabular-nums']`
   on native) so digits line up across rows.
4. Layout MUST survive the WCAG text-spacing overrides (line height 1.5×, letter
   spacing 0.12 em, word spacing 0.16 em, paragraph spacing 2×) without clipping
   or overlap. This rule is web-only: a native app has no user stylesheet. The
   tracking override applies to Latin only, per rule 5.
5. Tracking is 0 everywhere, and on native it is 0 without exception: there is no
   text-spacing preference to honour, and iOS applies a non-zero `letterSpacing`
   as kerning, which breaks Arabic shaping.
6. Native text scaling MUST remain enabled and remain usable at 200%.
   `maxFontSizeMultiplier` below 2 is not allowed on a clinical value, and
   `adjustsFontSizeToFit` or `numberOfLines` on a clinical value defeats the
   requirement, because both shrink text to fit instead of reflowing it.
7. Native line height is a **ratio** resolved against the current scaled size, or
   it is omitted. Android scales `fontSize` with the OS text setting but does not
   scale a fixed `lineHeight`, so a baked `lineHeight: 24` clips clinical values
   at 130–200%.
8. Letter case MUST NOT be forced with `text-transform` on translated strings;
   several scripts have no case and the transform silently does nothing.

## 5. Contrast contract

Ratios are WCAG 2.2 relative-luminance contrast. Thresholds: body and clinical
text 4.5:1; large text, UI component boundaries, state indicators and focus
indicators 3:1. Numbers below were produced by the recipe in
[section 5.4](#54-reproducing-the-numbers) against the values in section 3; a
`FAIL` row is either an intentional negative control or an explicitly exempt
decorative value, and both are called out. Every pair the tables in section 3
declare as a use is measured, including the destructive, pressed and disabled
labels, so "not measured" and "measured and exempt" cannot be confused. The
`color.shadow.*` tokens are the one exception: a shadow is not text or a control
boundary, and its separation is carried by the border rule in section 6.3.

### 5.1 Light mode

| Pair | Values | Ratio | Required | Result | Use |
|---|---|---|---|---|---|
| `color.text.primary` on `color.surface.default` | #152033 on #ffffff | 16.33:1 | 4.5:1 | pass | body, clinical text |
| `color.text.primary` on `color.surface.raised` | #152033 on #f7f9fc | 15.48:1 | 4.5:1 | pass | raised card, table stripe |
| `color.text.primary` on `color.surface.sunken` | #152033 on #eef1f6 | 14.42:1 | 4.5:1 | pass | sunken panel |
| `color.text.primary` on `color.status.critical.surface` | #152033 on #fdecea | 14.28:1 | 4.5:1 | pass | copy inside a critical banner |
| `color.text.secondary` on `color.surface.default` | #4a5768 on #ffffff | 7.36:1 | 4.5:1 | pass | metadata, timestamps, hints |
| `color.text.secondary` on `color.surface.raised` | #4a5768 on #f7f9fc | 6.98:1 | 4.5:1 | pass | raised card metadata |
| `color.action.primary.text` on `color.action.primary.background` | #ffffff on #0b5cab | 6.70:1 | 4.5:1 | pass | primary action label |
| `color.action.primary.text` on `color.action.primary.hover` | #ffffff on #08498a | 9.01:1 | 4.5:1 | pass | primary action label on hover |
| `color.border.default` on `color.surface.default` | #6b7280 on #ffffff | 4.83:1 | 3.0:1 | pass | input and control boundary |
| `color.border.strong` on `color.surface.default` | #3c4a5c on #ffffff | 9.03:1 | 3.0:1 | pass | selected row edge, emphasised boundary |
| `color.border.subtle` on `color.surface.default` | #c3cbd8 on #ffffff | 1.63:1 | 3.0:1 | FAIL | decorative separator only (exempt; never a control boundary) |
| `color.focus.ring` on `color.surface.default` | #0b5cab on #ffffff | 6.70:1 | 3.0:1 | pass | focus indicator on the page |
| `color.focus.ring` on `color.surface.raised` | #0b5cab on #f7f9fc | 6.35:1 | 3.0:1 | pass | focus indicator on a raised surface |
| `color.focus.ring` on `color.surface.sunken` | #0b5cab on #eef1f6 | 5.92:1 | 3.0:1 | pass | focus indicator on a sunken surface |
| `color.focus.ring` on `color.status.critical.surface` | #0b5cab on #fdecea | 5.86:1 | 3.0:1 | pass | focus indicator inside a critical banner |
| `color.focus.ring` on `color.status.warning.surface` | #0b5cab on #fff4d6 | 6.12:1 | 3.0:1 | pass | focus indicator inside a warning banner |
| `color.focus.ring` on `color.status.success.surface` | #0b5cab on #e8f7ee | 6.05:1 | 3.0:1 | pass | focus indicator inside a success panel |
| `color.focus.ring` on `color.status.info.surface` | #0b5cab on #e8f1fb | 5.87:1 | 3.0:1 | pass | focus indicator inside an info panel |
| `color.status.critical.text` on `color.status.critical.surface` | #a4271f on #fdecea | 6.36:1 | 4.5:1 | pass | critical banner text |
| `color.status.critical.border` on `color.status.critical.surface` | #b3261e on #fdecea | 5.72:1 | 3.0:1 | pass | critical banner boundary |
| `color.status.critical.border` on `color.surface.default` | #b3261e on #ffffff | 6.54:1 | 3.0:1 | pass | critical banner boundary on the page |
| `color.status.warning.text` on `color.status.warning.surface` | #6b4a00 on #fff4d6 | 7.36:1 | 4.5:1 | pass | warning banner text |
| `color.status.warning.border` on `color.status.warning.surface` | #8a6100 on #fff4d6 | 5.06:1 | 3.0:1 | pass | warning banner boundary |
| `color.status.success.text` on `color.status.success.surface` | #14532d on #e8f7ee | 8.23:1 | 4.5:1 | pass | success panel text |
| `color.status.success.border` on `color.status.success.surface` | #1f7a44 on #e8f7ee | 4.83:1 | 3.0:1 | pass | success panel boundary |
| `color.status.info.text` on `color.status.info.surface` | #103f77 on #e8f1fb | 9.21:1 | 4.5:1 | pass | informational panel text |
| `color.status.info.border` on `color.status.info.surface` | #1b5fa8 on #e8f1fb | 5.67:1 | 3.0:1 | pass | informational panel boundary |
| `color.state.fault.text` on `color.state.fault.surface` | #6d1b6d on #f9eefa | 9.19:1 | 4.5:1 | pass | entered-in-error text |
| `color.state.fault.border` on `color.state.fault.surface` | #8b2f8b on #f9eefa | 6.48:1 | 3.0:1 | pass | entered-in-error boundary |
| `color.state.fault.border` on `color.surface.default` | #8b2f8b on #ffffff | 7.30:1 | 3.0:1 | pass | entered-in-error boundary on the page |
| `color.focus.ring` on `color.state.fault.surface` | #0b5cab on #f9eefa | 5.95:1 | 3.0:1 | pass | focus indicator inside an entered-in-error block |
| `color.text.link` on `color.surface.default` | #0a4d8f on #ffffff | 8.50:1 | 4.5:1 | pass | inline and skip link |
| `color.text.link` on `color.surface.raised` | #0a4d8f on #f7f9fc | 8.06:1 | 4.5:1 | pass | link inside a card |
| `color.text.link` on `color.surface.sunken` | #0a4d8f on #eef1f6 | 7.51:1 | 4.5:1 | pass | link inside a filter bar |
| `color.action.primary.text.disabled` on `color.action.primary.disabled` | #4a5768 on #c3cbd8 | 4.50:1 | 4.5:1 | pass | label of a gated primary command |
| `color.action.primary.text` on `color.action.primary.active` | #ffffff on #06386a | 11.79:1 | 4.5:1 | pass | primary label while pressed |
| `color.text.inverse` on `color.action.destructive.background` | #ffffff on #a4271f | 7.27:1 | 4.5:1 | pass | Void / Deactivate label |

### 5.2 Dark mode

| Pair | Values | Ratio | Required | Result | Use |
|---|---|---|---|---|---|
| `color.text.primary` on `color.surface.default` | #f3f6fb on #111827 | 16.38:1 | 4.5:1 | pass | body, clinical text |
| `color.text.primary` on `color.surface.raised` | #f3f6fb on #1a2434 | 14.40:1 | 4.5:1 | pass | raised card, table stripe |
| `color.text.primary` on `color.surface.sunken` | #f3f6fb on #0b1220 | 17.28:1 | 4.5:1 | pass | sunken panel |
| `color.text.primary` on `color.status.critical.surface` | #f3f6fb on #3b1714 | 14.70:1 | 4.5:1 | pass | copy inside a critical banner |
| `color.text.secondary` on `color.surface.default` | #c2ccdb on #111827 | 10.94:1 | 4.5:1 | pass | metadata, timestamps, hints |
| `color.text.secondary` on `color.surface.raised` | #c2ccdb on #1a2434 | 9.63:1 | 4.5:1 | pass | raised card metadata |
| `color.action.primary.text` on `color.action.primary.background` | #0b1420 on #7db3ff | 8.60:1 | 4.5:1 | pass | primary action label |
| `color.action.primary.text` on `color.action.primary.hover` | #0b1420 on #a5c9ff | 10.92:1 | 4.5:1 | pass | primary action label on hover |
| `color.border.default` on `color.surface.default` | #7c8ca3 on #111827 | 5.18:1 | 3.0:1 | pass | input and control boundary |
| `color.border.strong` on `color.surface.default` | #aab6c6 on #111827 | 8.63:1 | 3.0:1 | pass | selected row edge, emphasised boundary |
| `color.border.subtle` on `color.surface.default` | #3a4759 on #111827 | 1.88:1 | 3.0:1 | FAIL | decorative separator only (exempt; never a control boundary) |
| `color.focus.ring` on `color.surface.default` | #ffbf47 on #111827 | 10.81:1 | 3.0:1 | pass | focus indicator on the page |
| `color.focus.ring` on `color.surface.raised` | #ffbf47 on #1a2434 | 9.51:1 | 3.0:1 | pass | focus indicator on a raised surface |
| `color.focus.ring` on `color.surface.sunken` | #ffbf47 on #0b1220 | 11.41:1 | 3.0:1 | pass | focus indicator on a sunken surface |
| `color.focus.ring` on `color.status.critical.surface` | #ffbf47 on #3b1714 | 9.70:1 | 3.0:1 | pass | focus indicator inside a critical banner |
| `color.focus.ring` on `color.status.warning.surface` | #ffbf47 on #332506 | 9.09:1 | 3.0:1 | pass | focus indicator inside a warning banner |
| `color.focus.ring` on `color.status.success.surface` | #ffbf47 on #0e2a19 | 9.38:1 | 3.0:1 | pass | focus indicator inside a success panel |
| `color.focus.ring` on `color.status.info.surface` | #ffbf47 on #0f2137 | 9.90:1 | 3.0:1 | pass | focus indicator inside an info panel |
| `color.status.critical.text` on `color.status.critical.surface` | #ffb4ab on #3b1714 | 9.38:1 | 4.5:1 | pass | critical banner text |
| `color.status.critical.border` on `color.status.critical.surface` | #ff897d on #3b1714 | 6.92:1 | 3.0:1 | pass | critical banner boundary |
| `color.status.critical.border` on `color.surface.default` | #ff897d on #111827 | 7.71:1 | 3.0:1 | pass | critical banner boundary on the page |
| `color.status.warning.text` on `color.status.warning.surface` | #ffd88a on #332506 | 10.97:1 | 4.5:1 | pass | warning banner text |
| `color.status.warning.border` on `color.status.warning.surface` | #e0a800 on #332506 | 6.94:1 | 3.0:1 | pass | warning banner boundary |
| `color.status.success.text` on `color.status.success.surface` | #9fe3b4 on #0e2a19 | 10.35:1 | 4.5:1 | pass | success panel text |
| `color.status.success.border` on `color.status.success.surface` | #52c07c on #0e2a19 | 6.74:1 | 3.0:1 | pass | success panel boundary |
| `color.status.info.text` on `color.status.info.surface` | #a9cdf7 on #0f2137 | 9.87:1 | 4.5:1 | pass | informational panel text |
| `color.status.info.border` on `color.status.info.surface` | #6ba6ea on #0f2137 | 6.39:1 | 3.0:1 | pass | informational panel boundary |
| `color.state.fault.text` on `color.state.fault.surface` | #f0b3f0 on #35203a | 8.75:1 | 4.5:1 | pass | entered-in-error text |
| `color.state.fault.border` on `color.state.fault.surface` | #d98ad9 on #35203a | 6.05:1 | 3.0:1 | pass | entered-in-error boundary |
| `color.state.fault.border` on `color.surface.default` | #d98ad9 on #111827 | 7.25:1 | 3.0:1 | pass | entered-in-error boundary on the page |
| `color.focus.ring` on `color.state.fault.surface` | #ffbf47 on #35203a | 9.02:1 | 3.0:1 | pass | focus indicator inside an entered-in-error block |
| `color.text.link` on `color.surface.default` | #a5c9ff on #111827 | 10.47:1 | 4.5:1 | pass | inline and skip link |
| `color.text.link` on `color.surface.raised` | #a5c9ff on #1a2434 | 9.21:1 | 4.5:1 | pass | link inside a card |
| `color.text.link` on `color.surface.sunken` | #a5c9ff on #0b1220 | 11.05:1 | 4.5:1 | pass | link inside a filter bar |
| `color.action.primary.text.disabled` on `color.action.primary.disabled` | #c2ccdb on #3a4759 | 5.82:1 | 4.5:1 | pass | label of a gated primary command |
| `color.action.primary.text` on `color.action.primary.active` | #0b1420 on #8fc0ff | 9.82:1 | 4.5:1 | pass | primary label while pressed |
| `color.text.inverse` on `color.action.destructive.background` | #0b1420 on #ffb4ab | 10.90:1 | 4.5:1 | pass | Void / Deactivate label |

### 5.3 Negative controls

These rows are expected to fail and are kept as regression evidence for the
rules they justify.

| Pair | Values | Ratio | Required | Result | Use |
|---|---|---|---|---|---|
| `color.focus.ring` as shipped in staff-web v0 | #ffbf47 on #ffffff | 1.64:1 | 3.0:1 | FAIL | why the single amber ring is replaced |
| `color.focus.ring` flush on the fill (light) | #0b5cab on #0b5cab | 1.00:1 | 3.0:1 | FAIL | why a 2 px offset is mandatory |
| `color.focus.ring` flush on the fill (dark) | #ffbf47 on #7db3ff | 1.31:1 | 3.0:1 | FAIL | why a 2 px offset is mandatory |

### 5.4 Reproducing the numbers

The ratios use the WCAG 2.2 definition: linearise each sRGB channel, weight by
0.2126 / 0.7152 / 0.0722, then `(L1 + 0.05) / (L2 + 0.05)`. Run the following
with `python3 -I` from the repository root and compare against the tables; a
value that drifts is a token change and needs a new row, not an edited number.

```python
def channel(value):
    srgb = value / 255
    return srgb / 12.92 if srgb <= 0.04045 else ((srgb + 0.055) / 1.055) ** 2.4


def luminance(color):
    digits = color.lstrip("#")
    red, green, blue = (int(digits[i:i + 2], 16) for i in (0, 2, 4))
    return 0.2126 * channel(red) + 0.7152 * channel(green) + 0.0722 * channel(blue)


def contrast(foreground, background):
    high, low = sorted((luminance(foreground), luminance(background)), reverse=True)
    return (high + 0.05) / (low + 0.05)


print(f"{contrast('#0b5cab', '#ffffff'):.2f}")  # 6.70, focus ring on the light page
print(f"{contrast('#ffbf47', '#ffffff'):.2f}")  # 1.64, the v0 ring, rejected
```

None of these values are user or clinical acceptance. They are tool-generated
measurements of a proposed palette; a real accessibility audit with assistive
technology remains part of the launch gates.

## 6. Space, size, radius, border, elevation, motion, layer

### 6.1 Space

`space.<n>` is `n × 4 px`, and `n` is one of the steps listed below. The list is
closed: a component never invents `13px`, it picks the nearest step and the
design review argument is about which step, not about a new number.

| Token | Value | Use |
|---|---|---|
| `space.0` | 0 | Reset |
| `space.1` | 4px | Icon to label gap, dense row inset |
| `space.2` | 8px | Control padding, dense table cell |
| `space.3` | 12px | Field vertical rhythm |
| `space.4` | 16px | Default gap, card padding |
| `space.5` | 20px | Between grouped fields |
| `space.6` | 24px | Section spacing |
| `space.8` | 32px | Page gutter, block separation |
| `space.10` | 40px | Wide separation |
| `space.12` | 48px | Panel separation |
| `space.16` | 64px | Page section break |

Spacing is applied with logical properties so it mirrors under RTL; see
[RTL and localization](rtl-and-localization.md).

### 6.2 Size and targets

| Token | Value | Use |
|---|---|---|
| `size.target.min` | 24 × 24 px | WCAG 2.2 AA floor for a pointer target on **web only**; it is not the native floor |
| `size.target.comfortable` | 44 × 44 px on web; 44 pt on iOS; 48 dp on Android | Default target; the platform pair is in the token source, not re-declared by a component |
| `size.control.height.sm` | 32px | Dense toolbar control |
| `size.control.height.md` | 40px | Default field and button height |
| `size.control.height.lg` | 48px | Touch-first screens and the patient app |
| `size.icon.sm` / `.md` / `.lg` | 16px / 20px / 24px | Icon box; the symbol may be smaller |
| `size.hairline` | 1px on web; `StyleSheet.hairlineWidth` on native | Dividers, table lines |

A target MUST be at least `size.target.min` in both axes on web, including in
dense tables: a 20 px icon is allowed inside a 24 px target. On native the floor
is the platform minimum (`size.target.comfortable` at 44 pt / 48 dp) and it is
enforced with `minWidth`/`minHeight`, because `hitSlop` enlarges the touch area
but not the accessibility frame the screen reader draws.

### 6.3 Radius, border, elevation

| Token | Value | Use |
|---|---|---|
| `radius.none` | 0 | Tables, inline alerts |
| `radius.sm` | 4px | Inputs, badges |
| `radius.md` | 8px | Cards, dialogs, banners |
| `radius.pill` | 9999px | Status chips, filter pills |
| `border.width.thin` | 1px | Default boundary |
| `border.width.thick` | 2px | Emphasis, selected row edge, banner edge |
| `border.width.focus` | 2px | Focus ring stroke |
| `elevation.none` | none | In-flow content |
| `elevation.raised` | `0 1px 2px` | Geometry only: cards, sticky header |
| `elevation.overlay` | `0 12px 32px` | Geometry only: dialog, menu |

A shadow is geometry plus a colour, and rule 4 of section 1 forbids a composite
token, so the two halves are separate:

| Token | Light | Dark | Use |
|---|---|---|---|
| `color.shadow.raised` | `rgba(15, 23, 42, 0.12)` | `rgba(0, 0, 0, 0.48)` | Colour half of `elevation.raised` |
| `color.shadow.overlay` | `rgba(15, 23, 42, 0.24)` | `rgba(0, 0, 0, 0.64)` | Colour half of `elevation.overlay` |

Elevation is decoration and MUST NOT be the only signal of a layer; a dialog
also has a scrim, a sticky header also has a bottom border. A dark-mode shadow on
a dark surface is close to invisible, which is why the border carries the
separation there. React Native needs its own projection for these two tokens
(`shadowColor`/`shadowOffset`/`shadowOpacity`/`shadowRadius` plus `elevation` on
Android, or `boxShadow` on the New Architecture with its API-level floor), and
`elevation` on Android needs an opaque background to draw at all.

### 6.4 Motion and layer

| Token | Value | Use |
|---|---|---|
| `motion.duration.fast` | 120ms | Hover, focus tint |
| `motion.duration.base` | 180ms | Expand, dialog enter |
| `motion.duration.slow` | 240ms | Larger transitions; the maximum |
| `motion.easing.standard` | `cubic-bezier(0.2, 0, 0.2, 1)` | Default |
| `layer.base` / `.sticky` / `.menu` / `.dialog` / `.toast` | 0 / 10 / 20 / 30 / 40 | Stacking order |

Motion rules: honour `prefers-reduced-motion` by removing non-essential motion,
never animate a critical-result banner, never animate a value change that a
clinician must read, and never use motion as the only indication that something
changed. Layers are a closed list; a component does not invent `9999`.

## 7. Theming

The same token names resolve to the values in section 3 per mode. A component
never branches on the mode to choose a value. The one permitted read of the mode
is the toggle's own accessible state (`aria-pressed`), which is a semantic, not a
paint, branch.

| Concern | Web (`apps/staff-web`) | Native (`apps/patient-mobile`) |
|---|---|---|
| Mode source | `<html data-theme="light\|dark">` | `useColorScheme()` (`'light' \| 'dark' \| null`) plus a stored explicit choice |
| Resolution | One generated stylesheet with a `[data-theme]` block per mode; one provider resolves the same token object for JavaScript reads | One provider resolves the same token object |
| Initial value | Pre-paint: follow `prefers-color-scheme`, unless the stored explicit choice exists | Same, with `null` treated as light |
| Native widgets | `color-scheme` set to the **active** mode, not `light dark` | Native surfaces follow the OS; an explicit in-app choice needs `Appearance.setColorScheme` to reach alerts, keyboards and pickers |
| Persistence | Per-user display preference, stored client-side | Same |

Rules:

1. A media query may choose the initial mode; it MUST NOT style a component.
   Otherwise the two mechanisms disagree and a screenshot review passes on a
   configuration a user does not have.
2. The paint sequence on web is fixed, because a late `data-theme` write paints
   the wrong mode first:
   1. the token stylesheet is emitted at build time with both modes;
   2. a small inline script in `<head>`, before the module script, reads the
      stored preference with a `prefers-color-scheme` fallback and sets
      `data-theme` and the matching `color-scheme` on `<html>`;
   3. the provider initialises its state from the attribute already present,
      rather than defaulting to light;
   4. the toggle writes the attribute, the `color-scheme` and the stored value.
3. `color-scheme` names the active mode. `color-scheme: light dark` makes the
   browser follow the **operating system**, so an explicit in-app light choice
   on a dark-preferring OS would render dark scrollbars and form controls on a
   light page.
4. Theme is not branding. V1 has no per-tenant palette: colour is a clinical
   readability contract, and per-site configuration (ADM-01) governs workflows,
   dictionaries and policies, not token values.
5. Forced colours (Windows high contrast) and `prefers-contrast` are supported by
   keeping borders and text labels meaningful without the palette. The focus ring
   MUST be an `outline`, because forced-colours mode discards `box-shadow` and
   force-recolours outlines; a `forced-colors: active` block MUST NOT override
   the system colours it was given.
6. Print output for clinical documents is a server-side rendering concern
   (localized PDFs are a separate requirement); the web app does not own a print
   token set in v1.
7. A mode switch MUST NOT lose form state, scroll position or the focused
   element.
8. Native styles are built per resolved mode. A module-scope
   `StyleSheet.create` freezes the first mode it saw; build inside the provider
   or key the sheet on the mode.
9. The stored preference is a display setting, not a secret. If it is kept in
   `expo-secure-store` because that is the only store the module has, say so in
   the module README and record the size and backup-restore behaviour; otherwise
   add a key-value store and use it.

## 8. Adoption

### 8.1 Mapping from the staff-web foundation

`apps/staff-web/src/theme/tokens.ts` currently exposes a six-field
`ThemeTokens`. The contract is a superset, so the migration is mostly a rename —
but it is not only a rename, and the differences below are the ones that will
change what a user sees:

| Current field | Contract token | Note |
|---|---|---|
| `background` | `color.surface.default` | Rename only |
| `foreground` | `color.text.primary` | Rename only |
| `border` | `color.border.default` | Rename only; keep `color.border.subtle` for decorative lines |
| `accent` | `color.action.primary.background` | Add `color.action.primary.text`; the label colour is not currently a token |
| `focusRing` | `color.focus.ring` + `border.width.focus` + a 2 px offset | The current value is a whole `box-shadow` string, measures 1.64:1 on the light page, and is not the ring that actually paints; it must be replaced, not renamed |
| `spacing.sm` / `.md` / `.lg` | `space.1` / `space.2` / `space.4` | Values 4 / 8 / 16 px are unchanged |
| `spacing.sm` used as a corner radius | `radius.sm` | `Button.tsx` sets `borderRadius: tokens.spacing.sm`; the value is the same but the role is not, and section 1 forbids a spacing token in a radius slot |
| Control target floors from `--sc-size-target-min` / `--sc-size-target-comfortable` via `.touch` in `responsive.css` | `size.control.height.sm` | The contract's default is `.md` (40 px), so adoption **changes every control height**; choose per surface and record it. Leftover inline `minHeight: '32px'` values live in feature screens, not in `Button.tsx` or `FormField.tsx` |
| Secondary button (`transparent` fill, `foreground` text) | `color.action.secondary.background` + `color.text.primary` + `color.border.default` | No rename exists; the variant must be mapped explicitly |
| Dialog `minWidth`, scrim, control heights inside `Dialog.tsx` | `size.*`, `color.surface.scrim` | Literal values, not fields of `ThemeTokens`; they are part of the same migration |

Three things a real migration will trip on:

1. **Tests that encode the defect.** `tests/theme/tokens.test.ts` asserts the
   ring starts with `0 0 0` and that the dark ring equals the light ring. Both
   assertions must be replaced by the new derivation and contrast assertions, not
   adjusted to pass.
2. **Rendered ring versus token.** The ring that actually paints today is a raw
   literal in `apps/staff-web/index.html`, not the `focusRing` field. Replacing
   the field alone leaves the 1.64:1 ring live. The migration must remove the
   literal and adopt the token there too, which also changes the ring from 3 px
   to 2 px and must be reviewed as a visible change.
3. **Generated source and the coverage gate.** The module enforces 100 % per-file
   coverage with generated declarations excluded. A generated `tokens.ts` must be
   imported and exercised by the derivation test, or the gate fails on it.

### 8.2 Adoption steps

For a consuming app:

1. Generate the token source per contract version and commit the generator
   output; assert derivation rules (dotted to custom property, dotted to
   camel-case, keys unique) with a unit test.
2. Replace every raw colour, radius and spacing literal in components with a
   token reference, and add a test that fails when a component style contains a
   hex value.
3. Replace the old focus treatment with the ring plus offset — in the component
   **and** in the document-level stylesheet — and add a keyboard test that
   asserts the ring is present on `:focus-visible` in a real browser. A jsdom
   test cannot see the painted ring; keep it to the stylesheet and the attribute.
4. Keep both modes in a visual smoke test at 320 px, 768 px and 1280 px widths,
   in `en` and `ar`, because RTL changes which side the ring and the controls sit
   on.
5. Record the adopted contract version in the module README, so a later
   contract change is traceable to the module that needs the upgrade.

### 8.3 Change process and versioning

| Change | Version effect | Extra requirement |
|---|---|---|
| Add a token with a new role | Minor | Contrast row for both modes, and a use note |
| Change a value | Minor if every contrast row still passes and the role is unchanged; otherwise major | Re-measured contrast table and a screenshot diff in `en` and `ar` |
| Rename or remove a token | Major | Keep the old name as a deprecated alias for one **minor contract version**, with a deprecation note, a codemod and the alias behaviour defined for both projections |
| Add a mode | Major | Every token resolves in the new mode; no component branch |
| Add a platform projection | Minor | Derivation test for the new target |

The contract version is a string in the token source and in this document, and
the two MUST match. Alias lifetime is measured in contract versions, not in
application releases, because the two apps are not released together and "one
release" for them has no single owner or clock. A consumer on version `n` MUST
fail its own build on an unknown token rather than silently rendering nothing,
and the generated output is checked for drift the same way the generated API
client is (`gen:api:check`), so a stale copy cannot be committed. A repository
check SHOULD assert that every consuming app declares the contract version it was
generated from, since nothing else detects two apps drifting apart.

## 9. Non-goals

- No per-tenant palette, brand colours or logo-driven theming in V1.
- No clinical decision logic: tokens describe presentation. Whether a revision is
  editable, who may release a result and whether a value is critical are server
  and policy decisions.
- No replacement for the WCAG 2.2 AA audit, the native screen-reader passes or
  the per-site clinical approvals; measuring contrast is necessary, not
  sufficient.
