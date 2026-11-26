# Accessibility patterns

- Requirements: UX-01, PRD-05
- Applies to: `apps/staff-web` (WCAG 2.2 AA) and `apps/patient-mobile` (native platform accessibility APIs)
- Index: [Design language home](README.md) · See also [Token contract](token-contract.md) and [RTL and localization](rtl-and-localization.md)

This document is the pattern set a reviewer checks a screen against. It is not a
substitute for an audit with assistive technology, and it does not approve any
clinical workflow. Values referenced here come from the
[token contract](token-contract.md#5-contrast-contract); a pattern that needs a
value the contract does not have is a contract change, not a local exception.

## 1. Baseline

| Surface | Standard | Verified by |
|---|---|---|
| Staff web | WCAG 2.2 level AA on every screen and state | Automated checks plus a keyboard-only and screen-reader pass |
| Patient app | iOS VoiceOver and Android TalkBack on both platforms | A named pass per screen, per platform |
| Both | Text scaling to 200% without loss of content or function, and no information conveyed by colour alone | Rendered test plus a manual pass |

The criteria that drive most of the patterns below:

| Criterion | What it demands here |
|---|---|
| 1.4.1 Use of Colour | A state is never only a colour: every status has a word and an icon. |
| 1.4.3 Contrast (Minimum) | Body text 4.5:1; large text 3:1. Measured, see the contract. |
| 1.4.10 Reflow | Workable at 320 CSS px width and 400% zoom without two-dimensional scrolling, except for the data table itself, which becomes a labelled scroll region. |
| 1.4.11 Non-text Contrast | Control boundaries, state indicators and focus indicators 3:1 against adjacent colours. |
| 1.4.12 Text Spacing | Line height 1.5×, letter spacing 0.12 em, word spacing 0.16 em and paragraph spacing 2× must not clip or overlap content. |
| 1.4.13 Content on Hover or Focus | Any hover or focus content (tooltip, provenance popover) is dismissible, hoverable and persistent. |
| 2.4.3 / 2.4.7 Focus Order, Focus Visible | Logical order, always-visible indicator. |
| 2.4.11 Focus Not Obscured | A sticky header or footer never hides the focused control. |
| 2.5.7 Dragging Movements | Every drag has a single-pointer alternative. |
| 2.5.8 Target Size (Minimum) | Every pointer target is at least 24 × 24 CSS px, or has an equivalent target. |
| 3.2.6 Consistent Help | The help and support entry point sits in the same place on every screen. |
| 3.3.4 Error Prevention (Legal, Financial, Data) | Signing, releasing to a patient, voiding a revision and recording a payment are reversible, checked or explicitly confirmed, so a slip does not create a clinical or financial record. |
| 3.3.7 Redundant Entry | Information already entered in the same process is not asked for again. |
| 3.3.8 Accessible Authentication | Paste and password managers work; no cognitive puzzle without an alternative. |
| 4.1.3 Status Messages | Save, verify, acknowledge and refresh outcomes are announced without moving focus. |

## 2. Focus and keyboard

The focus indicator is the ring defined by the token contract: a
`border.width.focus` stroke in `color.focus.ring` with the same width as a
transparent offset. The offset is not cosmetic — the gap shows whatever surface
the control sits on — see the negative controls in
[the contrast tables](token-contract.md#5-contrast-contract).

| Rule | Detail |
|---|---|
| Never remove the indicator | `outline: none` and `outline: 0` are defects in every shared component. If the default ring is ugly, replace it with the token ring. |
| `:focus-visible` for the ring | Pointer clicks on buttons do not need a ring; keyboard focus always does. Do not use `:focus` alone to draw the ring, because it makes mouse use look pressed. |
| Ring on every focusable element | Buttons, links, inputs, selects, text areas, checkboxes, radios, table row actions, tabs, dialog controls, scrollable regions with `tabindex="0"`, and custom controls. |
| Not obscured | Give a sticky header a `scroll-padding-block-start` on the scroll container, or `scroll-margin-block-start` on focusables, equal to the header height plus `space.2`. |
| Skip link | The first focusable element bypasses navigation to the main region; it is visible when focused. |
| One tab stop per composite | A table or a list has one tab stop; arrow keys move inside only when the composite is fully implemented as a grid with its own announcement model. Otherwise the native table semantics and normal Tab behaviour are used, which is the default choice here. |
| No keyboard trap | Escape exits any overlay; Tab always leaves a widget eventually. |
| Modal focus containment | A modal dialog contains Tab, with the exception below. |
| Programmatic focus only on purpose | Move focus for an error summary, a newly opened dialog, or an inline validation result that replaced the control. Never to announce an update. |
| Access keys | None. Browsers and assistive technology reserve far more of them than a clinical app can safely claim. |

## 3. Forms

Forms carry patient identity and clinical orders, so the pattern is stricter
than plain AA.

| Rule | Detail |
|---|---|
| Label | Every field has a visible `<label for>`. Placeholder text is a hint, never a label. |
| Hint | Persistent hint text is bound with `aria-describedby`, and it survives the value being typed. |
| Required | Required fields are marked with the word "required" in the label or hint, not only with an asterisk and a colour. Optional fields are marked "optional" when most fields are required. |
| Errors | On submit failure, render an error summary at the top of the form and move focus to it. The summary is the one live region for the outcome. Each field also gets `aria-invalid="true"`, the field boundary changes and an inline message is bound with `aria-describedby` — with no live role, because the summary already announced it. |
| Error text | Says what is wrong **and** what to do. It never blames the user, and it never repeats the field label only. |
| No colour-only errors | The field boundary, the icon and the text message all change; the message carries the meaning. |
| Preserve input | A failed submit never clears the form's values, except a password field. |
| Autofill | Use `autocomplete` for name, date of birth, phone, email and address so browser and OS assistance works. Identifier fields set `spellcheck="false"`, `autocapitalize="none"` and `inputmode` as appropriate. |
| Accessible authentication | Paste and password-manager entry work in every authentication field. A second factor is a normal field with a normal label; no character puzzles, no memory tests. |
| Redundant entry | Do not re-ask for the patient identifier, encounter or reason already established in the same flow. Prefill it and show it as read-only with the source. |
| Target size | Controls are at least `size.target.min` (24 px) and default to `size.control.height.md` (40 px). Dense toolbar controls keep a 24 px target. |
| Consistent help | A help or support link is in the same position on every screen and opens the appropriate contact channel for the active organization. |
| Destructive actions | A destructive command states the object and the consequence, requires an explicit confirm, and records the reason when policy requires it. The confirm's initial focus is the safe choice. |
| Save and verify outcomes | Announced through a status message (section 7), not through a colour change on the button. |
| Submit in progress | The control shows progress, stays operable by assistive technology and is announced once. It is not removed from the accessibility tree. |
| Clinical values | Units are part of the field label or the bound hint, and they persist after the value is entered. A value with an unknown unit is refused rather than guessed. |

Example field contract used by the staff-web field component: a `<label>`, an
`<input aria-describedby="hint error">`, `aria-invalid` only when invalid, and an
inline error paragraph bound by that id and rendered after the field. The inline
paragraph has no `role="alert"`; the single live announcement comes from the
summary in the Errors row above, so the same failure is not read twice. Keep that
order; do not put the error before the label, where it is announced out of
context.

## 4. Tables and worklists

Worklists are the densest clinical surface: a wrong row read is a wrong-patient
risk.

| Rule | Detail |
|---|---|
| Semantics | A real `<table>` with `<caption>` (or a labelled region), `<th scope="col">`, and `<th scope="row">` where the row has a key (the patient identifier or the appointment). A layout grid built from `div`s is not an option for tabular clinical data. |
| Sorting | The sort control is a button inside the header cell, and the header carries `aria-sort`. The current sort and direction are also stated in text near the caption, because `aria-sort` is not announced by every screen reader in every mode. |
| Numeric columns | Right-aligned in both directions, with tabular figures, so magnitudes line up. Right alignment is a deliberate exception to the "align to `start`" rule; digits are read left to right and column alignment is the scan affordance. |
| Row identity | Each row is keyed by the stable domain key (the appointment or order id), never by array index or position, and its accessible name includes the patient identifier and time, so a row action button announces "Open appointment, patient SYN-0001, 09:20" and not "Open". A refresh that leaves the focused or selected entity bound to a different record is a wrong-patient defect. |
| Row actions | A real button with an accessible name. Icon-only actions carry a label and a hint; a hover tooltip alone is not a name. |
| Selection | A checkbox per row with a label that includes the row identity, plus a select-all with an announced count. A selection is bound to the record, not to the row number: it survives a refresh on the same records, and a record that disappeared is released with an announced count. |
| Empty state | A defined empty state in the table body that explains the filter and how to clear it. Not an empty white area, and not a spinner that never resolves. |
| Loading | A `role="status"` message names what is loading and announces the result count when it completes. |
| Refresh | A polled or pushed refresh never moves focus, never reorders under an open row menu, never resets scroll, and never rebinds the focused row to another entity. New rows are announced as a count, not by reading the table. A worklist that shows a critical or actionable row may not ship with an index-keyed row until that is fixed. |
| Mid-reorder | No clinical row action is accepted while the body is reordering; the action targets the record captured when the control was activated, not the row at that screen position. |
| Horizontal overflow | Below the reflow breakpoint the table is a labelled scroll region with `tabindex="0"` and `role="region"`, so a keyboard user can scroll it. No clinical column is hidden with `display: none`. |
| Sticky header | Header row uses `color.surface.raised` with a `color.border.strong` bottom edge, and the contrast of header text stays at the body level. |
| Dense mode | A compact row still keeps a 24 px target for every control and never reduces the text below `font.size.label`. |
| Critical rows | A critical or overdue row is marked with an icon and a word inside the row, not only with a row tint. |

## 5. Dialogs

| Rule | Detail |
|---|---|
| Mechanism | Prefer the native `<dialog>` with `showModal()`, which gives the focus trap, the top-layer rendering and the inert background. If a custom dialog is used, it must reproduce all of them. |
| Labelling | `aria-labelledby` points at the dialog's heading; `aria-describedby` points at the consequence text when the dialog changes data. |
| Initial focus | The first meaningful control. For a destructive confirmation the initial focus is the safe choice (Cancel), and the dialog states the object and the consequence. |
| Focus containment | Tab cycles inside the dialog. Escape closes it, unless a commit is in flight; then the dialog stays open, shows a status message and explains that the action is completing. |
| Restore | On close, focus returns to the control that opened the dialog. If that control no longer exists, focus goes to the closest stable landmark. |
| Background | Inert: no pointer interaction, no focus, hidden from assistive technology. A visual scrim alone is not enough. |
| Nesting | No dialog opens another dialog. Replace the content, or return to the invoking screen. |
| No critical-result-only content | A critical result is never shown only inside a dialog. A dialog may offer the acknowledgment action; the finding itself lives in a persistent region of the chart or worklist. |
| Size | Grows with content up to a maximum, scrolls inside, stays usable at 320 px width and 400% zoom. |
| RTL | Insets, close-button position and scroll origin follow the direction; the heading and the action row mirror with the layout. |

## 6. Critical-result banners

This is the highest-risk pattern in the product. A final or corrected critical
finding must reach a named responsible clinician with a due time, and the
interface must make the finding impossible to miss without ever pretending that
display equals acknowledgment.

### 6.1 What the banner is

A critical-result banner presents one verified critical finding while it is in an
open clinical-review state. It is a **persistent in-flow region**, rendered in the
patient's chart header and in the owning clinician's worklist at page load.

The region is derived from the clinical-review state, not from the
acknowledgment: it is present for `unreviewed`, `acknowledged` and
`action pending`, and only an authorized `resolved` transition removes it. A
clinician who acknowledged twelve results and acted on nine must still see the
other three; anchoring the region to acknowledgment alone would hide them.

It is not:

- a toast, snackbar or transient notification;
- a modal dialog that blocks the whole application;
- a badge whose content is hidden behind a tooltip;
- a row tint;
- a substitute for the acknowledgment record.

The distinction between an event happening, being delivered, being seen, being
acknowledged and being acted on is a clinical contract owned by the exchange and
result workflows. The banner displays those facts; it never collapses them into
"done" because the banner was rendered or scrolled past.

### 6.2 Required content

All of the following are visible without interaction. Patient identity, linkage
and report status are mandatory, not optional, because the banner appears in a
worklist where the patient is not implied by the page:

| Element | Detail |
|---|---|
| Patient identity | Patient display name plus one verified identifier (issuer-scoped MRN or date of birth), and the encounter or facility. The banner MUST NOT render in a surface that cannot show this block. |
| Label | The word "Critical result" as text, plus a shape-distinct icon. |
| Report status | Preliminary, Final, Corrected or Entered in error as a word, with the revision number. A corrected revision says "Corrected" and states what changed. |
| Linkage | Order id, accession, specimen type and collection site where the finding is specimen-derived, source organization and destination. These are how the reader checks that the result belongs to this patient. |
| The finding | Test name, value with unit and reference range, qualifier (`<`, `>`), and the collection time with its zone. |
| Provenance | Verifying person or service and verification time. |
| Responsibility | The named owner **and** the coverage queue currently responsible, plus the due time with its zone. When no named owner is staffed, the banner says so in words ("No named owner staffed") instead of naming an absent clinician. |
| Escalation state | The escalation policy version in force, how many attempts, through which channel, the outcome of each attempt including failures, and whether the escalation deadline has passed. Overdue is a word, not only a colour. |
| Action | The acknowledgment control, labelled with what it does, and a read-back control when the site policy requires read-back. They are separate commands (section 6.6). |
| Recovery | A link to the result detail and to the notification history, because the banner summarizes rather than replaces the record. |

No clinical payload is ever placed in a notification body, a URL, an analytics
event or a page title; the banner reads from the authenticated response.

### 6.3 Persistence and dismissal

1. The banner remains while the finding is `unreviewed`, `acknowledged` or
   `action pending`. Acknowledgment changes the banner's state label and its
   outstanding action; it does not remove the region.
2. Only an authorized `resolved` transition removes it. That transition requires
   a reason, an audit record and a recorded follow-up owner and deadline, and the
   authorized paths are enumerated: the responsible clinician resolves the
   finding, or an authorized supervisor resolves it with a reason. There is no
   close button, no `Escape`-to-dismiss, no "clear", and no bulk close.
3. It MUST NOT auto-dismiss, collapse on a timer, or be hidden by a "don't show
   again" preference.
4. It MUST survive navigation within the chart. It re-renders on each page load
   while the clinical-review state is open.
5. A correction after resolution reopens the review and re-renders the region,
   even after acknowledgment.
6. Acknowledgment is never triggered by scrolling, by opening the detail, by
   hovering, or by a focus event. It requires an explicit activation and a
   confirmation that names the patient, the finding and the revision.
7. Acknowledgment and read-back carry an `If-Match` revision precondition. A
   correction that commits between render and submit is rejected (412), the
   region re-renders the corrected revision with an explicit "this changed since
   you opened it" state, and no acknowledgment is recorded against the superseded
   revision.

### 6.4 Announcement

| Situation | Mechanism |
|---|---|
| Page load with an open critical finding | Static region with a heading, one **polite** (`role="status"`) announcement of the same text that is visible, and no focus movement. The announcement is suppressed when focus is already inside a text-entry control at that moment, so typing is not interrupted. |
| Any authenticated screen while a finding is open | A persistent indicator in the application chrome — the count and the words "Critical results", keyboard reachable, linking to the owning queue. It is not a toast and it never auto-dismisses. This is what makes the finding reachable to a clinician working somewhere else. |
| A new critical result arrives during the session | One `role="alert"` announcement with the label, the patient, the owner and the due time, plus a persistent region update. The announcement is not repeated on re-render. |
| A correction arrives for an already acknowledged result | One announcement naming the correction and reopening the review, again with a persistent region update. |
| The due time passes | A polite `role="status"` message that the finding is overdue. |
| A countdown or clock update | No live region. A live clock is a continuous announcement and makes the screen unusable. |

Live region content MUST be injected after the region exists in the DOM, and the
injected text MUST be the same text that is visible, so a screen-reader user and
a sighted user receive the same information. Web and mobile use the same
treatment: the web is not allowed to be weaker than the app for the same finding.
Whether the patient's name is spoken aloud in a shared clinical space is a site
privacy decision, and it changes the announced text, not the visible text.

### 6.5 Presentation

| Property | Requirement |
|---|---|
| Distinguishability | The word, the icon and the boundary all carry the meaning. Colour is reinforcement only (1.4.1). |
| Contrast | Text at least 4.5:1 on the critical surface (6.36:1 in light, 9.38:1 in dark with the contract palette); boundary at least 3:1 against both its own surface and the page. |
| Focus | The acknowledgment control takes the contract focus ring, which measures at least 5.8:1 against the critical surface in light mode and at least 9.7:1 in dark mode. |
| Type | The headline is `font.weight.bold` at `font.size.heading.sm` or larger; the value uses `font.size.numeric.lg` where a single value is the finding. Nothing is in `font.size.caption`. |
| Motion | No animation, no pulsing, no flashing; motion is never the signal. |
| Truncation | Never truncate the value, the unit, the due time, the owner or the patient identity. Wrap instead. |
| Forced colours | Works with system colours; the boundary and the icon survive; the focus ring stays visible. |
| Print | The banner region is printable, and a printed chart states the outstanding acknowledgment. |
| Mobile | A persistent summary card at the top of the patient screen plus the full detail screen; the same single announcement as web (section 9); haptics are never the only signal; text scaling to 200% does not hide the action. |

### 6.6 Focus policy and the acknowledgment gate

The banner MUST NOT steal focus on load. Focus theft during entry causes
mis-keyed clinical data, and a screen-reader user loses their place. Instead:

1. the region is announced once, as specified above;
2. it is early in the heading and landmark order;
3. the persistent chrome indicator (section 6.4) keeps it reachable from every
   screen, including when the finding arrives while the clinician is elsewhere;
4. the worklist shows the outstanding item with its owner and due time as data,
   not only as a banner.

Acknowledgment gates the dependent clinical actions, and the gate is enforced by
the API, not by a disabled button. While an unacknowledged critical finding
exists for the patient, these commands are denied server-side with a
machine-readable `409`/`422` and a reason:

| Gated command | Why |
|---|---|
| Completing the review of the result | The finding is the review |
| Signing the encounter | A signature asserts the author saw the results (CLN-05) |
| Releasing the related report to the patient | Release implies a clinician has reviewed it (XCH-06) |
| Discharge or episode closure | Closure ends the window in which the finding can be acted on (PT-01) |
| The next order step that depends on the result | Acting on an unread result repeats the omission |

Disabling the control in the UI is additional and is never the control. Where a
finding has no dependent action, acknowledgment creates a follow-up task with an
owner and a deadline, so the gate always has a subject.

**Read-back is a separate command and a separate record.** It carries the
recipient identity, the channel (telephone, in person, callback), the timestamp
and the attestation of the person who performed it. A read-back whose recipient
resolves to the acknowledger's own identity is rejected. Where policy requires
read-back, the queue state is "escalated, awaiting read-back" until it is
recorded; it is not "acknowledged", so the distinction survives into the worklist
and the audit. The acknowledgment record names the patient, the order, the
accession and the revision.

## 7. Status messages, live regions and toasts

| Outcome | Pattern |
|---|---|
| Saved, verified, acknowledged, assigned, resolved | Short text in a `role="status"` region near the action, plus the persistent state change in the row or header. Never only a colour change on the button. |
| Error that blocks progress | One `role="alert"` region: the error summary, with focus moved to it. The inline field messages are `aria-describedby` text and carry no live role. |
| Background refresh completed | `role="status"`, counts only, no table re-read. |
| Transient confirmation | Allowed only for non-clinical affordances (copied to clipboard, theme changed). Never for a clinical state change. |
| Long-running operation | Announced once at start and once at completion, with a stable identifier for the operation. |

A toast is transient by design, so it is never the only record of a clinical
outcome. The durable record lives in the page.

## 8. Motion, timing and pointer alternatives

1. `prefers-reduced-motion` removes non-essential motion; the app remains fully
   operable.
2. No content updates automatically in a way that loses information the user is
   reading (2.2.2). Polling is announced as a count and never reorders under the
   pointer.
3. Dragging (reordering a queue, moving an appointment) has button-based
   alternatives: move up, move down, reassign. Dragging is never the only way.
4. Hover and focus content is dismissible with `Escape`, survives moving the
   pointer onto it, and stays until dismissed (1.4.13).
5. Timeline and progress animations stop at the final state and never loop.
6. No time limit is imposed on documentation reading or acknowledgment. A session
   timeout is a policy decision and must warn and allow extension.

## 9. Native mobile patterns

| Pattern | Requirement |
|---|---|
| Role and label | Every interactive element has `accessibilityRole` and a label that states the action and the object. Icon-only controls always have a label. `accessibilityHint` is iOS-only in React Native, so Android parity needs the hint inside the label; pick one convention (`accessibilityRole`/`accessibilityLabel`, or the newer `role`/`aria-*`) and use it consistently, because the newer props take precedence. |
| Grouped rows | Group only a row with no interactive children. `accessible` collapses the subtree, so an action inside a grouped row becomes unreachable; for a row with actions use `accessibilityActions` and `onAccessibilityAction` with a hint that states what is available. Compose the combined label from tokens in reading order, not from child-label concatenation, whose order differs between platforms. |
| Live regions | `accessibilityLiveRegion` is **Android-only** and fires only when the text of an already-mounted node changes. iOS has no live region: announce with `AccessibilityInfo.announceForAccessibility`. Use one mechanism per event to avoid a double announcement, fire once per revision id rather than per render, and keep the durable state on screen because the announcer is a no-op when the screen reader is off. |
| Text language | `accessibilityLanguage` is iOS-only and per element; Android exposes no React Native API for a text locale. Put `accessibilityLanguage="ar"` on every Arabic-script run on iOS, and accept that the Android language-switching pass requires the device in Arabic or a native span component outside this contract. |
| Target size | 44 pt on iOS and 48 dp on Android, enforced with `minWidth`/`minHeight`. `hitSlop` enlarges the touch area but not the accessibility frame the screen reader draws, so it is an addition, never the only size. The 24 px web floor does not apply here. |
| Text scaling | `allowFontScaling` stays on and screens are verified at 200%; `maxFontSizeMultiplier` below 2 is not allowed on clinical values. `adjustsFontSizeToFit` and `numberOfLines` on a clinical value both defeat the requirement by shrinking instead of reflowing. Line height stays a ratio, never a baked number. |
| Reduce motion | Read `useReducedMotion()` (React Native 0.71+) or `AccessibilityInfo.isReduceMotionEnabled()` (`Promise<boolean>`) plus a `reduceMotionChanged` listener, resolve it once in a provider and inject it into the animation policy. `Animated` and `LayoutAnimation` do not opt out by themselves. |
| Gestures | Every gesture has a tappable alternative: swipe actions have a visible button, and no action needs two fingers. A swipe-only action is a defect, not a shortcut. |
| Announcements | Use the platform announcer for a non-visual confirmation, and only for the event; the durable state is always on screen. Haptics are never the only signal. |
| RTL | Use `marginStart`/`marginEnd`, `paddingStart`/`paddingEnd`, `borderStart*`/`borderEnd*`, `start`/`end` offsets and `borderTopStartRadius` and friends; CSS logical property names do not exist here. `flexDirection: 'row'` and `justifyContent: 'flex-start'` mirror under an RTL layout mode, but physical `left`/`right` do not mirror consistently (iOS swaps them, Android does not), so they are a defect. Direction is fixed at app start: `I18nManager.forceRTL` needs a restart, and RTL must be enabled in the build configuration before any RTL evidence counts. |
| Styles per mode | Build style sheets against the resolved mode; a module-scope `StyleSheet.create` freezes the first mode it saw. Font size is a number in dp/pt, and `fontFamily` takes a single name, not a web stack. |
| Biometrics and PIN | OS-provided; the app does not implement its own gesture lock, and enrollment has a fallback path. |
| Secure screens | A released document opened in the app is protected against background snapshots with `expo-screen-capture`; on Android that sets `FLAG_SECURE`, on iOS it is best-effort, so the app-switcher snapshot is also blanked on `AppState` change. This blocks user screenshots too, which is consistent with the record-copy requirement (PAT-02): the copy is the in-app download, never a screenshot. |

## 10. Verification and evidence

A screen is accessibility-reviewed when each applicable row is observed, by tool
or by a named reviewer:

| Check | How | Evidence to record |
|---|---|---|
| Semantics and names | Component test with the rendered accessibility tree, plus the automated axe scan already recorded in the staff-web Playwright e2e suite. A jsdom test can assert the attribute and the generated stylesheet, not the painted focus ring. | Test name and result |
| Keyboard only | Tab, Shift+Tab, arrow, Enter, Space and Escape through the whole flow, including the scroll region and the dialog | Named pass with the screens covered |
| Focus visible and not obscured | A real-browser pass at 320 px and 1280 px in both directions, with the sticky header scrolled over the focused control; the ring's painted colour is not observable in jsdom | Browser run and screenshot references |
| Critical-result banner | A screen-reader pass on a page loaded with an outstanding critical result: one polite announcement, no focus movement, no dismiss control, and the patient identity present | Named pass per platform |
| No live region duplicates | One submission failure produces exactly one announcement; a correction after acknowledgment produces exactly one | Test or recorded pass |
| Acknowledgment gate | Calling each gated command directly while an unacknowledged finding exists is denied by the API, and succeeds after acknowledgment | API test names and results |
| Contrast | The measurement recipe in the token contract, applied to every text/boundary pair actually used | Computed ratios |
| Zoom and reflow | 400% zoom and 320 px width; no horizontal scrolling outside a labelled region | Named pass |
| Text spacing | Override stylesheet applied; no clipping or overlap | Named pass |
| Screen readers | VoiceOver (iOS and macOS) and TalkBack, plus NVDA or JAWS on the staff web at least once, with Arabic content present | Named pass per screen |
| Reduced motion and forced colours | OS setting toggled; content and focus remain usable | Named pass |
| Localization | The checks in [RTL and localization](rtl-and-localization.md#9-verification) | Test references |

Two honest limits: automated rules find a minority of real barriers, and a
simulated or synthetic review is not a clinical or human acceptance of the
interface. The per-site launch gate still requires approval by qualified staff,
including at least one Arabic-speaking clinician for the RTL flows.
