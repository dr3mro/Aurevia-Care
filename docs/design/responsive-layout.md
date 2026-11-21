# Responsive layout patterns for the staff web

- Requirements: WEB-07, WEB-09, WEB-10, UX-01
- Status: contract extension 1.0.0, proposed. Documentation only — no runtime
  tokens, components or application code live in this directory.
- Applies to: `apps/staff-web` (React, TypeScript, DOM).
- Index: [Design language home](README.md) · [Decision 0004](../decisions/0004-responsive-staff-web.md) · [Token contract](token-contract.md) · [Accessibility patterns](accessibility-patterns.md) · [RTL and localization](rtl-and-localization.md) · [Screens and navigation](screens-and-navigation.md) · [Screen recipe](screen-recipe.md)

This document is the layout contract for the staff web at every supported width.
It answers one question for each pattern: what changes when the available inline
space changes, and how is that behaviour proven? It does not decide what the
server authorizes, whether a revision may be edited or whether a result is
critical; those are domain and policy decisions that the interface only
displays ([design home, decision D12](README.md)).

Everything below follows [decision 0004](../decisions/0004-responsive-staff-web.md):
the staff web is fully usable, with all clinical data and actions for the
signed-in role, from 320 px upward. Width is a layout input only. Below 768 px
there is no longer a "use a larger screen" notice and no data-free page — that
old rule in [screens and navigation, section 1](screens-and-navigation.md) and in
WEB-07 is superseded by the decision, but the specification text itself still
needs a person to amend it. This guide is documentation; it changes no code and
grants no approval.

## 1. Sources and conventions

The normative sources are the token contract for values, the accessibility
patterns for control behaviour, and RTL and localization for direction. Where
this guide names a component (`DataTable`, `Form`, `FormField`, `Dialog`,
`MixedScriptText`, `StateViews`) it means the component of that name in
`apps/staff-web/src/components`; a new screen is built exactly once per the
[screen recipe](screen-recipe.md).

Every CSS example uses **logical properties only**. Physical equivalents — the
left/right margin and padding, the one-sided borders and insets, the physical
text alignment and the horizontal transform listed in
[RTL and localization, section 1.1](rtl-and-localization.md#11-logical-properties) —
are defects in shared styles and are not used here. Media *features* such as
`min-width` are viewport queries, not CSS properties, and are the one place a
horizontal measurement is named. Spacing and sizing in examples use the
generated custom-property handles from the token contract; a raw pixel value
appears only in a breakpoint, a viewport definition or a named layout constant
declared once at the shell boundary, never in a component style.

`MUST`, `MUST NOT`, `SHOULD` and `MAY` have the RFC 2119 meaning used by the
token contract. All example data is synthetic (patient `SYN-0001`, facility
`SYN-FAC-01`).

## 2. Breakpoints and what changes at each

Three layout breakpoints are expressed as `min-width` media queries. They are
layout thresholds only; they do not change authorization, data or workflow.

| Breakpoint | Viewport inline size | Band | Navigation | Page grid | Table treatment |
|---|---|---|---|---|---|
| compact | 0 to 767 px | phone | off-canvas drawer | single column, full inline space | labelled scroll region |
| medium | 768 to 1279 px | tablet, portrait and landscape | icon rail | rail plus one content column | labelled region only when content overflows |
| expanded | 1280 px and above | desktop | full labelled rail | rail plus content, centred to a maximum | full table, sticky header |

```css
.page {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: var(--sc-space-4);
  padding-inline: var(--sc-space-4);
}

@media (min-width: 768px) {
  .page {
    grid-template-columns: auto minmax(0, 1fr);
    padding-inline: var(--sc-space-8);
  }
}

@media (min-width: 1280px) {
  .page {
    grid-template-columns: auto minmax(0, 1fr);
    padding-inline: var(--sc-space-8);
  }
}
```

What changes at each edge is presentation, never data: at 768 px the drawer is
replaced by an icon rail and the content column gains inline space; at 1280 px
the rail shows labels and the content reaches its maximum reading width. A
column of clinical data is never added or removed at a breakpoint, and no
clinical column is hidden with `display: none`
([decision 0004](../decisions/0004-responsive-staff-web.md)).

## 3. Page grid and maximum content width

The shell has one content column inside the rail. A page is a single grid whose
track sizes use `minmax(0, 1fr)` so a long clinical value can wrap instead of
forcing the grid wider than the viewport.

- **Forms and notes** are capped at 1200 px inline size and centred; this is the
  maximum reading width from [screens and navigation, section 1](screens-and-navigation.md#1-staff-web-application-shell)
  and is declared once as the layout constant `--layout-reading-max`.
- **Tables and worklists** use the full available inline size; they are the one
  place a labelled inline scroll region is allowed (WEB-07).
- **Banners** span the full content column at every breakpoint; they are never
  narrower than the data they summarize.
- Gutters come from the closed space scale (`space.4` on compact, `space.8` from
  medium upward). No page introduces a new gutter value.

```css
:root {
  --layout-reading-max: 1200px;
}

.content {
  max-inline-size: var(--layout-reading-max);
  margin-inline: auto;
}

.content--table {
  max-inline-size: none;
}
```

The grid must reflow at 320 CSS px and 400 percent zoom (1.4.10), and text must
scale to 200 percent without loss of content or function (1.4.4), with no
two-dimensional scrolling outside the labelled table region (UX-01). A value,
its unit, an identifier and a due time wrap; none is truncated to make it fit.
The 200 percent figure in decision 0004 is a text-scaling requirement; on its
own it does not satisfy 1.4.10, so both levels are tested (section 13).

## 4. Navigation per breakpoint

Navigation is the `nav` landmark holding the grouped items of the screen map
([screens and navigation, section 2](screens-and-navigation.md#2-staff-web-screen-map)).
Items the signed-in role cannot use are not rendered at any breakpoint.

| Breakpoint | Navigation form | Behaviour |
|---|---|---|
| compact | off-canvas drawer | Closed by default; a labelled button at the inline start opens it; Escape and a scrim click close it; focus enters on open and returns to the button on close |
| medium | icon rail | Icons only with accessible names and tooltips; active item marked by shape and word as well as colour |
| expanded | full labelled rail | Icons plus labels; active item marked the same way |

The drawer opens from the **inline start** edge in both directions. It is moved
with a logical inset, never a horizontal transform, so right-to-left needs no
second rule set.

```css
:root {
  --layout-drawer-inline-size: min(320px, 80vw);
}

.nav-drawer {
  position: fixed;
  inset-block: 0;
  inset-inline-start: 0;
  inline-size: var(--layout-drawer-inline-size);
  transition: inset-inline-start var(--sc-motion-duration-base)
    var(--sc-motion-easing-standard);
}

.nav-drawer[hidden] {
  inset-inline-start: -100%;
}
```

A sticky header or footer MUST NOT obscure the focused control: the scroll
container carries `scroll-padding-block-start` and `scroll-padding-block-end`
at least equal to the sticky region plus `space.2`
([accessibility patterns, section 2](accessibility-patterns.md#2-focus-and-keyboard)).
The skip link is the first focusable element and bypasses navigation at every
breakpoint.

## 5. Component pattern matrix

Every pattern keeps one DOM and one accessibility tree across breakpoints; a
breakpoint changes layout and density, not semantics or available actions.

| Pattern | Phone (320 to 767 px) | Tablet (768 to 1279 px) | Desktop (1280 px and above) |
|---|---|---|---|
| Data table (`DataTable`) | One labelled scroll region with `tabindex="0"` and `role="region"`; real `table`, `caption` and `th scope`; every API column reachable by inline scrolling; no column hidden; row action names include patient identity and time | Same semantics; region only when content overflows; sticky header; row actions in the inline-end column | Full table in view, sticky header, sort buttons in headers with `aria-sort`, full row action names |
| Form (`Form`, `FormField`) | One field per row, label above control, control fills the inline space; error summary at the top; primary action in a sticky footer reachable above the on-screen keyboard | Two columns for short fields, one for long text; label above; action row in the page footer | Two-column grid inside the 1200 px reading width; action in the page header or footer; one primary action |
| Dialog (`Dialog`) | Sheet nearly filling the viewport minus safe areas; content scrolls inside; close control at the inline end; action row stacks; initial focus on the safe choice; Escape closes unless a commit is in flight | Centred panel with a scrim; scroll inside; action row inline | Centred panel up to its maximum; scrim; action row at the inline end; focus returns to the opener |
| Tab strip | `tablist` on one non-wrapping line with inline scrolling; selected tab scrolled into view; arrow keys move between tabs; selection carried by word and shape | Tabs fit on one line; scroll only if they overflow | All tabs visible; arrow-key navigation; selected tab announced |
| Toolbar | Wraps or scrolls on the inline axis; primary action kept; icon-only controls keep accessible names; coarse-pointer targets at least 44 px | One row, wrapping to a second line only when needed | One row; fine-pointer targets may use the 24 px floor |
| Badge row / status chips | Wrap to several lines; each status carries a word and a shape-distinct icon; no value truncated; colour is reinforcement | Wrap to two lines | One line with wrapping before any truncation |
| Note editor | Single column; text area fills the inline space; formatting toolbar wraps; cursor stays visible above the keyboard; draft kept in memory and restored on rotation | Editor plus a compact metadata rail; full-width text area | Editor plus a provenance and revision-history panel inside the 1200 px reading width |
| Chart timeline | Vertical or labelled inline-scrolling track; chronological axis mirrors under RTL while data order does not change; values and units wrap; no value in caption size | Inline-scrolling horizontal track with pinned row labels | Full horizontal track across the table width; axis ends labelled with dates |
| Banner (critical-result region) | Full-width in-flow region at the top of content; patient identity block always visible; all required content visible without interaction; acknowledgment reachable; never a toast or dialog-only | Full-width card with a two-column internal grid | Full-width region in the chart header with the persistent chrome indicator |

Two rules apply to every row. First, a width change MUST NOT remove an action
or a clinical value, and a refresh MUST NOT rebind a focused row to a different
patient record ([accessibility patterns, section 4](accessibility-patterns.md#4-tables-and-worklists)).
Second, information is never available only on hover: the same name and meaning
is in the accessibility tree and reachable on focus
([accessibility patterns, section 8](accessibility-patterns.md#8-motion-timing-and-pointer-alternatives)).

```css
.table-region {
  overflow-inline: auto;
  border-block-end: var(--sc-border-width-thin) solid var(--sc-color-border-default);
  scroll-padding-inline-start: var(--sc-space-2);
}

.table-region:focus-visible {
  outline: var(--sc-border-width-focus) solid var(--sc-color-focus-ring);
  outline-offset: var(--sc-border-width-focus);
}
```

Where `overflow-inline` is not supported by a target browser, the region uses
the direction-neutral `overflow: auto`; no physical side is named. The region
has an accessible name from the table caption, is keyboard scrollable, and the
focus ring is an `outline` with the mandatory offset so forced-colours mode
keeps it ([token contract, focus](token-contract.md#34-focus)).

## 6. Touch targets and spacing

For coarse pointers, every interactive target MUST be at least
`size.target.comfortable` (44 × 44 CSS px) in both axes. The 24 × 24 px
`size.target.min` remains the general web floor for fine pointers and dense
rows; the coarse-pointer rule supersedes it. A 20 px icon is allowed inside a
44 px target.

```css
.action,
.tab,
.toolbar button {
  min-inline-size: var(--sc-size-target-comfortable);
  min-block-size: var(--sc-size-target-comfortable);
}

@media (pointer: fine) {
  .action,
  .tab,
  .toolbar button {
    min-inline-size: var(--sc-size-target-min);
    min-block-size: var(--sc-size-target-min);
  }
}
```

Spacing uses only the closed `space.<n>` scale from the token contract; a
component MUST NOT invent a 13 px gap. Vertical and inline rhythm use logical
padding and gap so the same stylesheet mirrors under right-to-left. Dense table
cells use `space.2` but keep the target floor; a dense control still carries a
full accessible name.

## 7. Safe-area insets

On a notched phone the shell respects the safe-area insets at the four viewport
edges. The platform environment variables are physical by definition, so they
are mapped once into layout variables at the shell boundary; components then use
only logical padding properties. Under right-to-left the inline pair is
swapped in that one mapping, so no component needs a direction branch.

```css
:root {
  --safe-block-start: env(safe-area-inset-top);
  --safe-block-end: env(safe-area-inset-bottom);
  --safe-inline-start: env(safe-area-inset-left);
  --safe-inline-end: env(safe-area-inset-right);
}

:root[dir='rtl'] {
  --safe-inline-start: env(safe-area-inset-right);
  --safe-inline-end: env(safe-area-inset-left);
}

.shell {
  padding-block-start: max(var(--sc-space-4), var(--safe-block-start));
  padding-block-end: max(var(--sc-space-4), var(--safe-block-end));
  padding-inline-start: max(var(--sc-space-4), var(--safe-inline-start));
  padding-inline-end: max(var(--sc-space-4), var(--safe-inline-end));
}
```

The `env()` names are platform-provided values, not CSS properties; every
property in the example is logical. These `--safe-*` names are layout
variables, not design tokens, and are defined in one place only. A modal dialog
uses the same treatment for its sheet so its close control and action row stay
inside the safe area.

## 8. On-screen keyboard behaviour

When the on-screen keyboard opens, the visible viewport shrinks. The focused
field MUST stay visible and the form's primary action MUST remain reachable
without dismissing the keyboard. The pattern is a sticky action footer plus
scroll margins on the fields, not a scroll-to-bottom hack.

```css
.form__scroll {
  scroll-padding-block-end: var(--sc-space-16);
}

.field:focus-visible {
  scroll-margin-block-end: var(--sc-space-8);
  outline: var(--sc-border-width-focus) solid var(--sc-color-focus-ring);
  outline-offset: var(--sc-border-width-focus);
}

.form__actions {
  position: sticky;
  inset-block-end: 0;
  padding-block: var(--sc-space-3);
  padding-inline: var(--sc-space-4);
  background: var(--sc-color-surface-default);
}
```

The action footer MUST NOT cover the focused control; the
`scroll-padding-block-end` on the scroll container reserves space for it. A
failed submit keeps what the user typed and moves focus to the error summary
([accessibility patterns, section 3](accessibility-patterns.md#3-forms)). A
critical-result banner never steals focus while the keyboard is open; its
polite announcement is suppressed when focus is in a text-entry control
([accessibility patterns, section 6.4](accessibility-patterns.md#64-announcement)).
jsdom can assert the attributes and the generated stylesheet but has no real
keyboard or modality; the visible-field and reachable-action claims are proven
in a real browser project with a virtual keyboard.

## 9. Orientation change and preserved work (WEB-09)

A phone rotated from portrait to landscape changes viewport width and often
crosses a breakpoint. The layout MAY reflow, but typed work MUST survive: the
component keeps draft state in React state, not in a remount keyed to viewport
size, and a resize does not clear a field, discard a selection or move focus to
another patient record.

- The page does not key a screen or a field on a breakpoint value; the same
  component instance renders in both orientations.
- A conflict or failed submit keeps the typed values and shows the compare,
  reload and copy choices ([screens and navigation, section 4](screens-and-navigation.md#4-required-states-for-every-screen)).
- Draft text stays in memory only and is never written to browser storage
  (WEB-09).
- Focus stays on the same control after the orientation change; no automatic
  focus movement is used to announce a resize.

Verification switches a Playwright project from 320 × 720 to 720 × 320 mid-test
and asserts the typed value, the selected record and focus are unchanged. A
jsdom test dispatches a resize after typing and asserts the same invariants on
component state.

## 10. Pointer versus touch

Hover is never the only way to reach information or an action. Every control
has a persistent accessible name; a tooltip, provenance popover or row hint is
an enhancement that also appears on keyboard focus and in the accessibility
tree.

```css
.control .hint {
  visibility: hidden;
}

@media (hover: hover) {
  .control:hover .hint,
  .control:focus-visible .hint {
    visibility: visible;
  }
}
```

Hover or focus content is dismissible with Escape, survives moving the pointer
onto it and persists until dismissed (1.4.13). Dragging has a button-based
alternative (move up, move down, reassign); a swipe-only action is a defect.
On a coarse pointer the touch target rules of section 6 apply, and a selected
row or tab is marked by a word and a shape, not by a tint alone.

## 11. Right-to-left behaviour

Direction comes from the document (`dir="rtl"`, `lang="ar"`), set by the
language toggle in `I18nProvider`; no component sets `direction: rtl` on a
subtree. Logical properties do the mirroring, so the patterns above need no
second stylesheet.

| Element | Behaviour under right-to-left |
|---|---|
| Drawer and rails | Open and sit at the inline-start edge; the drawer's logical inset mirrors automatically |
| Order of columns, gutters and table columns | Mirror by the grid and flex layout; focus order stays the DOM order |
| Back and forward, pagination and undo and redo arrows | Mirror as directional variants; a single asset is flipped by a direction-scoped rule |
| Timeline and progress axis | Mirror the axis placement; the chronological data order does not change; axis ends are labelled with dates |
| Digits, units and clinical identifiers | Do not mirror; stay in isolated left-to-right runs with the value and unit together |
| Anatomical diagrams, waveforms and imaging | Never mirror; laterality is clinical data |
| Status and warning icons | Never mirror; the glyph is not directional |

A foreign run is isolated with `<bdi dir="auto">` and its own `lang`; a patient
name, a value with its unit and an identifier are each atomic. Two direction
scopes on one screen are a defect
([RTL and localization, section 1](rtl-and-localization.md#1-direction-model)).
A language switch does not reload the page and keeps the reader's place
([screens and navigation, section 5](screens-and-navigation.md#5-layout-density-and-patterns)).
The physical inline inset table in section 7 is the one place a left/right
environment value is named, and it is confined to a single direction-aware
mapping.

## 12. Zoom and text spacing

Text is sized in `rem` so the browser preference scales it; a clinical value is
never in caption size and never truncated with an ellipsis
([token contract, typography](token-contract.md#4-typography)). The layout MUST
keep text scalable to 200 percent without loss of content or function (1.4.4),
reflow at 320 CSS px and 400 percent zoom (1.4.10), and survive the text-spacing
overrides of 1.4.12 (line height 1.5×, letter spacing 0.12 em, word spacing
0.16 em, paragraph spacing 2×) without clipping or overlap. Decision 0004 names
a 200 percent zoom figure; that alone is 1.4.4 text scaling, not 1.4.10 reflow,
so both levels are tested. Tracking stays 0 everywhere and 0 without exception
for Arabic, because letter spacing breaks the cursive joins.

```css
.clinical-value {
  min-inline-size: 0;
  overflow-wrap: anywhere;
  white-space: normal;
}
```

Rows and cards grow with their content; no fixed block size is placed on a
region holding a clinical value. The only permitted two-dimensional scroll is
the labelled table region of section 5, and even there no column is hidden.
A mode switch MUST NOT lose form state, scroll position or the focused element
([token contract, theming rule 7](token-contract.md#7-theming)).

## 13. Verification matrix — the test method for each rule

The existing end-to-end harness is the single `chromium` project in
`apps/staff-web/e2e/playwright.config.ts` with an axe scan of the development
preview in English and Arabic (WEB-10). The responsive project matrix below is
what this contract requires that harness to grow into; adding the projects is
implementation work in a separate story, not part of this documentation change.

| Playwright project | Viewport (width × height) | Direction and locale | Pointer |
|---|---|---|---|
| phone-portrait | 320 × 720 | `ltr`, `en` | coarse, touch |
| phone-ar | 360 × 780 | `rtl`, `ar` | coarse, touch |
| phone-landscape | 720 × 320 | `ltr`, `en` | coarse, touch |
| tablet-portrait | 768 × 1024 | `ltr`, `en` | coarse, touch |
| tablet-landscape | 1024 × 768 | `ltr`, `en` | coarse, touch |
| desktop-en | 1280 × 800 | `ltr`, `en` | fine, mouse |
| desktop-ar | 1280 × 800 | `rtl`, `ar` | fine, mouse |

jsdom (Vitest) can assert DOM attributes, component state and the text of the
generated stylesheet; it cannot paint a focus ring, evaluate a real media
query or know whether focus came from a keyboard. Axe
(`@axe-core/playwright`) reports serious and critical violations that fail the
build (WEB-10); it finds a minority of real barriers, so a named keyboard,
screen-reader and Arabic-clinician pass remains a launch gate.

| Rule | Behaviour under test | jsdom | Playwright project(s) | axe |
|---|---|---|---|---|
| R1 | Breakpoints switch at 320, 768 and 1280 px | Assert the three `min-width` queries are in the generated stylesheet | phone-portrait, tablet-portrait, desktop-en measure the grid | Reflow at each width |
| R2 | Page grid and 1200 px reading cap; tables full width | Assert `content` modifiers and logical properties in the stylesheet | All projects compare content and table inline sizes | No serious reflow violation |
| R3 | Navigation form per breakpoint and drawer focus return | Assert the rendered nav variant and drawer button | phone-portrait, tablet-portrait, desktop-en, phone-ar | Landmarks and names present |
| R4 | Component pattern matrix keeps semantics and actions | Per-pattern test asserts table semantics, labels and full column set | All projects assert no hidden clinical column | Names, roles, target size |
| R5 | Coarse-pointer targets at least 44 × 44 px | Assert the `min-inline-size` and `min-block-size` rules | All coarse projects measure bounding boxes | `target-size` rule |
| R6 | Logical properties only; spacing from the closed scale | Grep-style test fails on physical properties and raw gaps | desktop-ar and phone-ar assert no overlap | Not applicable |
| R7 | Safe-area insets mapped to logical padding | Assert the direction-aware custom-property mapping | phone-portrait and phone-ar with inset emulation | Region reachable |
| R8 | Focused field visible and primary action reachable with the keyboard open | Assert focus, error-summary and scroll-margin attributes | phone-portrait and phone-ar with a virtual keyboard | Focus not obscured |
| R9 | Rotation preserves typed work, selection and focus | Dispatch resize and assert state is unchanged | phone-portrait to phone-landscape mid-test | Not applicable |
| R10 | Hover is never the only way; drag has a button route | Assert every control has an accessible name | fine and coarse projects toggle the hint | Content on hover dismissible |
| R11 | Right-to-left mirrors flow but not clinical content | Assert `dir`, `lang`, isolated runs and no direction override | phone-ar and desktop-ar at both widths | Direction and name checks |
| R12 | Text scaling at 200 percent (1.4.4), reflow at 320 px and 400 percent zoom (1.4.10), text-spacing overrides | Assert values wrap and no fixed block size on clinical regions | phone-portrait at 200 and 400 percent zoom with the override stylesheet | Reflow and spacing rules |
| R13 | No clinical column hidden; row identity survives refresh | Assert full column set and stable row key | All projects scroll the table region | Names include patient identity |

Each rule's evidence is the named test result plus a screenshot reference from
the project that observes the painted layout. A jsdom assertion on a stylesheet
string is recorded as a stylesheet check, never as a claim about painted
output. Evidence is simulated and synthetic; it is not clinical or human
acceptance.

## 14. Boundaries and non-goals

- Synthetic data only. No example contains a real person, a real identifier or
  a credential.
- No offline clinical editing and no service worker cache of clinical data;
  authenticated responses keep the WEB-02 `no-store` rule
  ([decision 0004](../decisions/0004-responsive-staff-web.md)).
- This guide does not change the specification, the browser-support guard, any
  component, any token value or any end-to-end configuration. Those are
  separate implementation stories with their own tests and 100 percent
  coverage gates.
- Passing these layout checks is necessary, not sufficient. The per-site
  accessibility audit, the Arabic-speaking clinical review of the RTL flows and
  product-owner acceptance of WEB-10 evidence remain separate launch gates.
