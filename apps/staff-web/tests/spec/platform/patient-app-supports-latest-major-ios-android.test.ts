/**
 * WEB-07 clause 3 — "The patient app supports the latest two major iOS and
 * Android versions."
 *
 * Staff-web evidence only. The native Expo app declares no iOS or Android
 * version floor in this checkout, so there is no production policy here that
 * could prove the clause. The two comparison cases below are labelled CONTRACT
 * SKETCHES over the real staff-web browser guard: they show how that web-engine
 * guard treats synthetic iOS and Android user agents across its version window.
 * They are an analogy, not evidence that the native app declares or supports
 * any OS version. The active gap tripwire pins the absence of a native version
 * floor and fails on the day one lands, when it must be replaced with a real
 * patient-app test on a device and an emulator.
 *
 * Synthetic user agents and local configuration introspection only. No network,
 * database, patient data, credential or cross-app module import is involved.
 */
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, expectTypeOf, it } from 'vitest';
import * as browserSupport from '../../../src/features/system/browserSupport';
import {
  CHROME_BASELINE_MAJOR,
  IOS_BASELINE_MAJOR,
  MIN_SUPPORTED_WIDTH,
  SUPPORTED_VERSION_WINDOW,
  isSupportedBrowser,
} from '../../../src/features/system/browserSupport';

/** Width chosen so the version decision is not entangled with the reflow floor. */
const DESKTOP_WIDTH = 1280;

/** Absolute path of the patient app's Expo config, read as a local evidence artifact. */
const PATIENT_APP_CONFIG = resolve(
  dirname(fileURLToPath(import.meta.url)),
  '../../../../patient-mobile/app.json',
);

/**
 * Local model of the Expo platform sections, used only to inspect the parsed
 * keys of the configuration file. The cast at the parse boundary is a named
 * interface so no widened value reaches the assertions.
 */
interface ExpoPlatformSections {
  readonly ios: Readonly<Record<string, unknown>>;
  readonly android: Readonly<Record<string, unknown>>;
}

/** Synthetic iPhone Safari agent; the guard reads the `CPU iPhone OS` major. */
function iphoneSafari(major: number): string {
  return `Mozilla/5.0 (iPhone; CPU iPhone OS ${major}_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/${major}.0 Mobile/15E148 Safari/604.1`;
}

/** Synthetic iPad Safari agent; the guard reads the `CPU OS` major. */
function ipadSafari(major: number): string {
  return `Mozilla/5.0 (iPad; CPU OS ${major}_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/${major}.0 Mobile/15E148 Safari/604.1`;
}

/**
 * Synthetic Chrome-on-iOS agent. Its stale `CriOS` token must be ignored in
 * favour of the `CPU iPhone OS` major, as iOS browsers all use WebKit.
 */
function chromeOnIos(iosMajor: number): string {
  return `Mozilla/5.0 (iPhone; CPU iPhone OS ${iosMajor}_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/118.0.0.0 Mobile/15E148 Safari/604.1`;
}

/** Synthetic Chrome-on-Android-phone agent; the guard reads the `Chrome` major. */
function chromeOnAndroidPhone(major: number): string {
  return `Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${major}.0.0.0 Mobile Safari/537.36`;
}

/** Synthetic Chrome-on-Android-tablet agent; it has no `Mobile` token. */
function chromeOnAndroidTablet(major: number): string {
  return `Mozilla/5.0 (Linux; Android 14; SM-T500) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${major}.0.0.0 Safari/537.36`;
}

/** One web-client family and the major its user agent carries for the guard. */
interface WebClientFamily {
  readonly name: string;
  readonly baseline: number;
  readonly agent: (major: number) => string;
}

/**
 * iOS rows carry an OS major; Android rows carry a Chrome engine major because
 * no Android OS baseline exists in the surveyed production policy. All five
 * rows are synthetic fixtures, not native-app configuration.
 */
const WEB_CLIENT_FAMILIES: readonly WebClientFamily[] = [
  { name: 'iPhone Safari', baseline: IOS_BASELINE_MAJOR, agent: iphoneSafari },
  { name: 'iPad Safari', baseline: IOS_BASELINE_MAJOR, agent: ipadSafari },
  { name: 'Chrome on iOS', baseline: IOS_BASELINE_MAJOR, agent: chromeOnIos },
  {
    name: 'Chrome on Android phone',
    baseline: CHROME_BASELINE_MAJOR,
    agent: chromeOnAndroidPhone,
  },
  {
    name: 'Chrome on Android tablet',
    baseline: CHROME_BASELINE_MAJOR,
    agent: chromeOnAndroidTablet,
  },
];

describe('WEB-07 clause 3 — patient app supports the latest two major iOS and Android versions', () => {
  /**
   * Contract sketch, not a native-app proof. The guard accepts baseline through
   * baseline minus SUPPORTED_VERSION_WINDOW, which is three majors rather than
   * the clause's literal two; the comparison is recorded, not claimed as proof.
   */
  it('sketchesTheSupportedWebClientWindowForIosAndAndroidThroughTheStaffWebGuard', () => {
    expect(IOS_BASELINE_MAJOR).toBe(17);
    expect(CHROME_BASELINE_MAJOR).toBe(130);
    expect(SUPPORTED_VERSION_WINDOW).toBe(2);

    for (const family of WEB_CLIENT_FAMILIES) {
      for (const offset of [0, 1, SUPPORTED_VERSION_WINDOW]) {
        const major = family.baseline - offset;
        expect(isSupportedBrowser(family.agent(major), DESKTOP_WIDTH)).toBe(true);
      }
    }

    // The version decision is independent of the 320 px reflow floor.
    expect(isSupportedBrowser(iphoneSafari(IOS_BASELINE_MAJOR), MIN_SUPPORTED_WIDTH)).toBe(true);
  });

  /**
   * Negative contract sketch. Baseline minus window minus one is the first
   * major the real guard refuses for every synthetic iOS and Android client;
   * an unrecognised user agent fails closed even at a wide viewport.
   */
  it('refusesAStaleNativeOsVersion', () => {
    const staleOffset = SUPPORTED_VERSION_WINDOW + 1;

    for (const family of WEB_CLIENT_FAMILIES) {
      const major = family.baseline - staleOffset;
      expect(isSupportedBrowser(family.agent(major), DESKTOP_WIDTH)).toBe(false);
    }
    expect(isSupportedBrowser('curl/8.5.0', DESKTOP_WIDTH)).toBe(false);
  });

  /**
   * GAP tripwire: no native iOS or Android version floor is declared by the
   * patient app. It is an active, green test because the repository forbids
   * skipped tests. The Expo config is read as data, so the absence check is a
   * runtime assertion: adding a floor to that file fails this test but does not
   * change any TypeScript type. The negative control proves the inspector flips
   * when a floor is present. The secondary export pin below does type-check a
   * real imported module. When a native floor lands, this case must be replaced
   * by a real patient-app proof rather than kept as a record of absence.
   */
  it('GAP: no native iOS/Android platform policy exists', () => {
    const expo = (JSON.parse(readFileSync(PATIENT_APP_CONFIG, 'utf8')) as {
      expo: ExpoPlatformSections;
    }).expo;

    for (const floorKey of ['deploymentTarget', 'minSdkVersion', 'sdkVersion'] as const) {
      expect(Object.keys(expo.ios)).not.toContain(floorKey);
      expect(Object.keys(expo.android)).not.toContain(floorKey);
    }

    const syntheticWithFloor = {
      supportsTablet: true,
      requireFullScreen: false,
      bundleIdentifier: 'com.synthetic.patientapp',
      deploymentTarget: '17.0',
    };
    expect(Object.keys(syntheticWithFloor)).toContain('deploymentTarget');

    // Secondary guard: a native policy must not be planted in the staff-web
    // web-engine guard, which has no authority over the Expo application.
    // Unlike the data-only config check, this pin types the imported module,
    // so adding an export here fails both the runtime assertion and typecheck.
    expect(Object.keys(browserSupport).sort()).toEqual([
      'CHROME_BASELINE_MAJOR',
      'EDGE_BASELINE_MAJOR',
      'FIREFOX_BASELINE_MAJOR',
      'IOS_BASELINE_MAJOR',
      'MIN_SUPPORTED_WIDTH',
      'SAFARI_BASELINE_MAJOR',
      'SUPPORTED_VERSION_WINDOW',
      'isSupportedBrowser',
    ]);
    expectTypeOf<keyof typeof browserSupport>().toEqualTypeOf<
      | 'CHROME_BASELINE_MAJOR'
      | 'EDGE_BASELINE_MAJOR'
      | 'FIREFOX_BASELINE_MAJOR'
      | 'IOS_BASELINE_MAJOR'
      | 'MIN_SUPPORTED_WIDTH'
      | 'SAFARI_BASELINE_MAJOR'
      | 'SUPPORTED_VERSION_WINDOW'
      | 'isSupportedBrowser'
    >();
  });
});
