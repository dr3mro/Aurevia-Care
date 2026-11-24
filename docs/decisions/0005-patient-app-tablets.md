# 0005. Patient app support for tablets and landscape

- Status: proposed
- Date: 2026-11-21
- Requirement IDs: PAT-01, PAT-02, UX-01
- Owner: Product

## Context

The patient app is the self-access surface for verified patients: it lists
appointments (PAT-01) and released reports (PAT-02) and, per [section 7, Web and
mobile row](../../.opencode/config/PROJECT_V1_SPEC.md), is built with React Native and Expo for
iOS and Android, with the server authorizing and validating every command.
[WEB-07](../../.opencode/config/PROJECT_V1_SPEC.md) commits it to the latest two major iOS and
Android versions, but the shipped [Expo configuration](../../apps/patient-mobile/app.json)
is phone- and portrait-only: `orientation` is `portrait` and
`ios.supportsTablet` is `false`. Patients therefore cannot use iPad or Android
tablets, and rotating a phone to landscape is not supported.

UX-01 requires native screen-reader flows on both mobile platforms, English and
Arabic with RTL, and text scaling to 200% without loss of content; the patient
app's [accessibility patterns](../design/accessibility-patterns.md) make font
scaling, 44 pt/48 dp targets and VoiceOver/TalkBack passes explicit. Messaging
(MSG-02) gives the patient an inbox with unread counts and a thread view, which is
the natural two-pane candidate on a wide window. A responsive contract is needed
so follow-on layout and configuration work is consistent and is driven by the
available window rather than by a device-type guess. This record is copied from
[template.md](template.md) and states the decision only; it adds no runtime code,
configuration change or dependency.

## Decision

- **Devices and orientation.** The patient app runs on iPhone, iPad, Android
  phones and Android tablets, in both portrait and landscape. Orientation is not
  locked to portrait; a rotation keeps the current screen and its state.
- **Layout classes come from the window, not the device.** Layout is chosen by
  current window width using the React Native window dimensions: `compact` below
  600 dp, `regular` from 600 to 839 dp, and `large` at 840 dp and above (the 600
  and 840 dp breakpoints). The same window in split-screen, Slide Over or landscape
  gets the same class regardless of whether the hardware is a phone or a tablet.
  Device type is never used to decide layout.
- **Two-pane on large widths.** On `large` windows the inbox and thread are shown
  side by side as two panes; on `compact` and `regular` windows the thread remains
  a separate pushed screen. The same list-and-detail treatment applies to other
  list-plus-detail screens (appointments and detail, reports and detail) where
  they exist. Selecting an item updates the detail pane without losing the list
  position.
- **Text scales to 200%.** System font size is honoured up to a 200% multiplier:
  font scaling stays enabled, clinical values are not capped below 2 with
  `maxFontSizeMultiplier`, and text is not shrunk with `adjustsFontSizeToFit` or
  truncated with a fixed `numberOfLines`. Layout reflows so no content or action
  is clipped at 200%.
- **iPad multitasking keeps working.** Split-screen and Slide Over on iPad remain
  functional: the app does not force full-screen mode, and resizing the window
  recomputes the layout class from the new width.
- **Screen-reader flows on phone and tablet (UX-01).** VoiceOver on iPhone and
  iPad and TalkBack on Android phone and tablet are exercised for the patient
  flows in both orientations and in English and Arabic RTL. Accessibility roles,
  labels, focus order and announcements are unchanged between compact and large
  layouts and between the one-pane and two-pane presentations.
- **Boundaries unchanged.** Responsive layout is presentation only: it adds no
  persistent clinical cache (PAT-02), changes no API call, and does not weaken
  server-side authorization. All data continues to be retrieved after
  authentication and authorized per linked patient and current release.

## Alternatives considered

- **Keep the current portrait-only, phone-only configuration** — rejected: it
  cannot run on iPad or Android tablets and contradicts the tablet and landscape
  requirement. It is the problem this decision resolves, not an option.
- **Choose layout by device type (phone versus tablet)** — rejected: split-screen,
  Slide Over and rotation change the available width without changing the device,
  so a device-based choice gives the wrong layout. Window width is the single
  source of truth.
- **Fixed font sizes or capping scaling below 200%** — rejected: UX-01 and the
  accessibility contract require text scaling to 200% with no loss of content or
  function; shrinking or truncating clinical values is a defect.
- **Forcing full-screen mode on iPad** — rejected: it would disable split-screen
  and Slide Over, which must keep working.
- **Two panes at every width** — rejected: on compact windows two panes clip
  content and shrink touch targets below the 44 pt/48 dp floor; two panes are
  reserved for `large` windows.

## Consequences and evidence

Positive: one window-width rule covers phones, tablets, rotation and iPad
multitasking, so follow-on stories share a single classification instead of
branching per device. Keeping layout purely presentational preserves PAT-02 (no
persistent clinical cache) and the server-authorization boundary in section 7.
Negative: list-and-detail screens need a two-pane and a one-pane rendering, the
hook must react to dimension changes, and VoiceOver/TalkBack passes must be run
on four device classes rather than two — accepted cost of the support commitment.

This is a documentation-only decision record: no source file, Expo configuration,
dependency, migration or test was added here. It is the prerequisite for the
follow-on configuration, window-size hook and screen-layout stories. Evidence
source: direct file reads of `docs/decisions/template.md`, the existing decision
records, `apps/patient-mobile/app.json`, the section 7 Web and mobile row and
UX-01 of `PROJECT_V1_SPEC.md`, and
`docs/design/accessibility-patterns.md` — tool evidence, with no human, clinical
or patient feedback. A simulated UX and senior-mobile review of this record found
no missing decision; real patient and clinical acceptance stay
a G5 gate. Document links and requirement IDs are verified by
`python3 -I scripts/check-docs.py` (result recorded in the worker notes file).
