# WEB-07 clause 3 — patient app supports the latest two major iOS and Android versions

## Clause

The patient app supports the latest two major iOS and Android versions.

## Status

Not built

## Evidence

Test file: [`patient-app-supports-latest-major-ios-android.test.ts`](../../../../apps/staff-web/tests/spec/platform/patient-app-supports-latest-major-ios-android.test.ts).

This is a pure Vitest `.ts` test in the staff web. It renders no React, opens no
database and contacts no network. It uses synthetic user agents for the version
comparisons and reads the patient app's Expo configuration as a local artifact
by path, without importing a patient-mobile module. Evidence for the executed
commands comes from tool output; the survey statements below come from reading
the linked files. There is no human, clinical, partner or simulated-user
acceptance here, and no real device or emulator run.

Test methods:

- `sketchesTheSupportedWebClientWindowForIosAndAndroidThroughTheStaffWebGuard`
  is a labelled CONTRACT SKETCH over the real staff-web guard. It pins the
  surveyed constants, then asserts that a synthetic iPhone Safari, iPad Safari,
  Chrome-on-iOS, Chrome-on-Android-phone and Chrome-on-Android-tablet client is
  accepted at its baseline, at baseline minus one and at baseline minus two, and
  that the version decision holds at the 320 px reflow floor. This compares web
  clients only. It also records that the guard's window of two majors behind
  baseline accepts three majors, which is wider than the clause's literal two.
- `refusesAStaleNativeOsVersion` is the negative sketch. It asserts that the
  same five synthetic clients are refused at baseline minus three, and that an
  unrecognised user agent fails closed at a wide viewport.
- `GAP: no native iOS/Android platform policy exists` is an active green
  tripwire. It reads [`app.json`](../../../../apps/patient-mobile/app.json) and
  asserts that neither the `ios` nor the `android` section declares a
  `deploymentTarget`, `minSdkVersion` or `sdkVersion` key; a synthetic negative
  control proves the inspector flips when such a key is present. The config is
  read as data, so this is a runtime assertion: adding a floor fails the test but
  changes no TypeScript type. A secondary pin checks the exact runtime and
  type-level export names of the imported staff-web guard so a native policy
  cannot be planted in the wrong app.

Production units surveyed by these tests:

- [`browserSupport.ts`](../../../../apps/staff-web/src/features/system/browserSupport.ts)
  — `isSupportedBrowser`, `IOS_BASELINE_MAJOR` (17), `CHROME_BASELINE_MAJOR`
  (130), `SUPPORTED_VERSION_WINDOW` (2) and `MIN_SUPPORTED_WIDTH` (320). It
  judges iOS web clients by the OS token and Android clients by the Chrome
  engine major. It is the staff web's unsupported-browser guard, not a native
  application policy.
- [`app.json`](../../../../apps/patient-mobile/app.json) — the Expo
  configuration carries orientation, tablet, full-screen and bundle settings
  but no iOS deployment target and no Android minimum SDK level.
- [`browserSupport.test.ts`](../../../../apps/staff-web/tests/features/system/browserSupport.test.ts)
  — the pre-existing matrix that proves the staff-web browser decision, and the
  source of the baseline-minus-two accepted and baseline-minus-three refused
  boundaries used in the sketches.
- [`evidence.md`](../../../operations/production-readiness/evidence.md) line 27
  labels WEB-07 "proven by test" while citing only staff-web browser and
  responsive-layout tests. That row does not prove this native-app clause.

The name of the positive case was adjusted from the brief's proposed
`supportsTheLatestTwoMajorIosAndAndroidVersionsWhenTheNativePolicyIsDeclared`
because it described a behaviour that does not exist in this checkout and would
invite a false "Proven by tests" reading. The chosen name describes the
comparison the test actually performs.

## Gap

No native iOS or Android version floor exists in this checkout. The Expo
configuration declares no minimum OS version, there is no native policy module
or build setting that encodes the "latest two major versions" commitment, and
the staff-web browser guard has no authority over the installed application.
[`background and decision 0005`](../../../decisions/0005-patient-app-tablets.md)
is `proposed` and covers tablets, landscape and window width, not an OS
baseline; its context is also stale where it describes the current Expo
orientation and tablet settings. The clause is therefore not built, and the
comparison cases above are not proof of it.

Closing the gap would change at least these files: the patient app's Expo
configuration in `apps/patient-mobile/app.json` to declare the iOS deployment
target and Android minimum SDK level; a patient-app configuration test that
asserts those floors and rejects a stale one; the browser matrix in the
end-to-end suite; and this test file, replacing the tripwire with a real
patient-app proof. That is at least two separate engineering tasks — one
cross-boundary task to declare and verify the native floors, and one
patient-app task to exercise the clause on an iOS device or simulator and an
Android emulator and replace the tripwire. Real-device acceptance is a further
human-gated evidence task. No such task is assumed here.

The task asked for a deliberately skipped gap test. `AGENTS.md` Coverage rule 2
forbids an added skipped test and `scripts/check-constraints.py` rejects it. The
gap is therefore recorded as the active green tripwire described above, which
fails at runtime when a native version floor is added to the Expo config; the
secondary staff-web export pin also fails typecheck if a policy is planted in
the wrong app. This is the conflict being recorded, not a suppression of it: the
tripwire must be replaced, not preserved, once the native policy exists.
