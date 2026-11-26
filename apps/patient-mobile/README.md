# patient-mobile

Run `bash apps/patient-mobile/check.sh` from the repository root: pinned install, typecheck, lint and Jest with 100% global coverage.

## Tablets and landscape

The patient app runs on iPhone, iPad, Android phones and Android tablets in both
portrait and landscape (PAT-01, PAT-02, UX-01). Layout follows the current window,
not the device type, so rotation, iPad split-screen and Slide Over all use the
same rule. This is the user-facing contract of
[decision 0005](../../docs/decisions/0005-patient-app-tablets.md); the app
configuration already declares `orientation: default`, `ios.supportsTablet: true`
and `ios.requireFullScreen: false` in `app.json`.

### Window classes and the hook

`src/app/layout/useWindowClass.ts` classifies the **current window width** (in
density-independent pixels) into three window classes. A boundary width belongs
to the larger class:

| Class | Width (dp) | Typical presentation |
|---|---|---|
| `compact` | below 600 | Phone portrait; one pane, detail is a pushed screen |
| `regular` | 600 to 839 | Large phone landscape / small tablet; one pane |
| `large` | 840 and above | iPad and Android tablet landscape; two panes |

`windowClassFor(width)` is the pure classifier; `useWindowClass()` reads
React Native's `useWindowDimensions()` plus the existing `useSafeAreaInsets()`
and returns `{ windowClass, width, height, isLandscape, fontScale, insets }`.
Because the window hook re-renders on a dimension change, the class, orientation
(`width > height`) and font scale are recomputed after a rotation or a resize.
On `large` windows the appointments list/detail, report summary/result and
Account/Messages menu/screen render side by side. On `compact` and `regular`
the appointment detail is stacked below the list and the Account/Messages screens
use the in-app Back control. The conversation thread component is not yet
reachable from the Messages tab (the row press is a placeholder), so it is not
part of the installed two-pane flow. Device type is never used to choose a
layout.

### Running on an iPad (iOS Simulator)

Requires Xcode with an iOS simulator runtime. From `apps/patient-mobile`:

1. Install dependencies once with `npm install`.
2. List iPad simulators with `xcrun simctl list devices available` and boot the
   one to test, for example `xcrun simctl boot "iPad Pro (11-inch)"`.
3. Start the app on the simulator with `npm run ios` (equivalent to
   `expo start --ios`); press `i` in the Expo CLI to open iOS if you used
   `npm start`. Use an installed iPad in the simulator's device list rather than
   a phone.
4. Toggle **Device ▸ Rotate Left/Right** (or `⌘`+arrow keys) to exercise
   landscape, and exercise iPad **Split View / Slide Over** by opening another
   app; the layout class must follow the new window width.

### Running on an Android tablet (emulator)

Requires Android Studio with an Android SDK and emulator.

1. In **Device Manager** create an Android Virtual Device with a tablet device
   definition (for example Pixel Tablet) and a supported system image, then
   start it (or run `emulator -avd <avd-name>`). Confirm it appears in
   `adb devices`.
2. From `apps/patient-mobile` run `npm run android` (equivalent to
   `expo start --android`); press `a` in the Expo CLI if you used `npm start`.
   If more than one device is running, pass `--device <serial>` with the value
   from `adb devices`.
3. Rotate with the emulator's rotate buttons (or `Ctrl`+`F11`/`Ctrl`+`F12`) to
   exercise landscape, and use the emulator's multi-window controls to test a
   resized window.

### Screen-reader checks on tablet

These are manual passes recorded as simulated review here; final G5 sign-off
still requires an authorized human on a real device. Follow the
[accessibility patterns](../../docs/design/accessibility-patterns.md) and the
[screen-reader evidence contract](../../docs/operations/accessibility/accessibility-and-screen-readers.md),
and keep accessibility roles, labels, focus order and announcements identical
between the compact and large (one-pane and two-pane) layouts:

- **iPad / VoiceOver:** enable **Settings ▸ Accessibility ▸ VoiceOver**; swipe
  right/left to move focus and double-tap to activate. Walk the appointments
  (PAT-01) and released reports (PAT-02) flows in portrait and landscape, and
  confirm the inbox and thread are both reachable in the two-pane layout with a
  logical focus order and no orphaned controls.
- **Android tablet / TalkBack:** enable **Settings ▸ Accessibility ▸ TalkBack**
  and explore by touch (swipe to move, double-tap to activate). Repeat the same
  flows in portrait and landscape and check the announcements and labels match
  the VoiceOver pass.
- **Both platforms:** repeat each flow in English and Arabic RTL, at 200% text
  size (iOS **Larger Text** / Android **Font size**), confirming no content or
  action is clipped, that touch targets keep their 44 pt / 48 dp minimum, and
  that rotation or a split-screen resize preserves the current screen and state.

## Mutation testing

Run `npm run mutation` (opt-in; not part of check.sh or CI by default). Configure scope in `stryker.config.json`.
