import { describe, expect, it } from 'vitest';
import {
  CHROME_BASELINE_MAJOR,
  EDGE_BASELINE_MAJOR,
  FIREFOX_BASELINE_MAJOR,
  IOS_BASELINE_MAJOR,
  MIN_SUPPORTED_WIDTH,
  SAFARI_BASELINE_MAJOR,
  SUPPORTED_VERSION_WINDOW,
  isSupportedBrowser,
} from '../../../src/features/system/browserSupport';

/** Desktop width used by the user-agent cases, from WEB-07. */
const DESKTOP_WIDTH = 1280;

/** Every supported viewport width in the responsive staff web (decision 0004). */
const SUPPORTED_WIDTHS = [320, 360, 390, 768, 820, 1024] as const;

/** Synthetic Chromium user agent for a given Chrome major version. */
function chrome(major: number): string {
  return `Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${major}.0.0.0 Safari/537.36`;
}

/** Synthetic Chromium Edge user agent for a given Edge major version. */
function edge(major: number): string {
  return `Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${major}.0.0.0 Safari/537.36 Edg/${major}.0.0.0`;
}

/** Synthetic Firefox user agent for a given major version. */
function firefox(major: number): string {
  return `Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:${major}.0) Gecko/20100101 Firefox/${major}.0`;
}

/** Synthetic Safari user agent for a given major version. */
function safari(major: number): string {
  return `Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/${major}.2 Safari/605.1.15`;
}

/** Synthetic iPhone Safari user agent; iOS Safari is judged by the iOS version. */
function iphoneSafari(iosMajor: number): string {
  return `Mozilla/5.0 (iPhone; CPU iPhone OS ${iosMajor}_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/${iosMajor}.0 Mobile/15E148 Safari/604.1`;
}

/** Synthetic iPad Safari user agent; iPad Safari is judged by the iOS version. */
function ipadSafari(iosMajor: number): string {
  return `Mozilla/5.0 (iPad; CPU OS ${iosMajor}_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/${iosMajor}.0 Mobile/15E148 Safari/604.1`;
}

/**
 * Synthetic Chrome-on-iOS user agent. It reports a stale `CriOS` version on
 * WebKit, so the guard must judge it by the `CPU iPhone OS` version instead.
 */
function chromeIos(iosMajor: number, chromeMajor = 118): string {
  return `Mozilla/5.0 (iPhone; CPU iPhone OS ${iosMajor}_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/${chromeMajor}.0.0.0 Mobile/15E148 Safari/604.1`;
}

/**
 * Synthetic Firefox-on-iOS user agent. It reports a stale `FxiOS` version on
 * WebKit, so the guard must judge it by the `CPU iPhone OS` version instead.
 */
function firefoxIos(iosMajor: number, firefoxMajor = 118): string {
  return `Mozilla/5.0 (iPhone; CPU iPhone OS ${iosMajor}_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) FxiOS/${firefoxMajor}.0 Mobile/15E148 Safari/604.1`;
}

/**
 * Synthetic Edge-on-iOS user agent. Edge reports a stale `EdgiOS` token on
 * WebKit, so the guard must judge it by the `CPU iPhone OS` version instead.
 */
function edgeIos(iosMajor: number, edgeMajor = 118): string {
  return `Mozilla/5.0 (iPhone; CPU iPhone OS ${iosMajor}_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) EdgiOS/${edgeMajor}.0.0.0 Mobile/15E148 Safari/604.1`;
}

/** Synthetic Chrome-on-Android-phone user agent. */
function chromeAndroidPhone(major: number): string {
  return `Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${major}.0.0.0 Mobile Safari/537.36`;
}

/** Synthetic Chrome-on-Android-tablet user agent (no `Mobile` token). */
function chromeAndroidTablet(major: number): string {
  return `Mozilla/5.0 (Linux; Android 14; SM-T500) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${major}.0.0.0 Safari/537.36`;
}

/** Synthetic Firefox-on-Android user agent. */
function firefoxAndroid(major: number): string {
  return `Mozilla/5.0 (Android 14; Mobile; rv:${major}.0) Gecko/${major}.0 Firefox/${major}.0`;
}

/** Synthetic Edge-on-Android user agent; it advertises `EdgA`, not `Edg`. */
function edgeAndroid(major: number): string {
  return `Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${major}.0.0.0 Mobile Safari/537.36 EdgA/${major}.0.0.0`;
}

interface SupportCase {
  name: string;
  userAgent: string;
  width: number;
  supported: boolean;
}

/** Current mobile clients, one per platform in the supported browser matrix. */
const mobileClients: readonly { name: string; userAgent: string }[] = [
  { name: 'iPhone Safari', userAgent: iphoneSafari(17) },
  { name: 'iPad Safari', userAgent: ipadSafari(17) },
  { name: 'Chrome on iOS', userAgent: chromeIos(17) },
  { name: 'Firefox on iOS', userAgent: firefoxIos(17) },
  { name: 'Chrome on Android phone', userAgent: chromeAndroidPhone(130) },
  { name: 'Chrome on Android tablet', userAgent: chromeAndroidTablet(130) },
  { name: 'Firefox on Android', userAgent: firefoxAndroid(130) },
  { name: 'Edge on Android', userAgent: edgeAndroid(130) },
];

/**
 * Each supported mobile client must render at every responsive width, from the
 * 320 px reflow floor through phone, tablet and desktop widths (decision 0004).
 */
const mobileWidthCases: readonly SupportCase[] = mobileClients.flatMap((client) =>
  SUPPORTED_WIDTHS.map((width) => ({
    name: `${client.name} at ${width}px`,
    userAgent: client.userAgent,
    width,
    supported: true,
  })),
);

/**
 * Matrix of synthetic clients. Each desktop browser covers its baseline, the
 * oldest still-supported version (baseline minus two) and a version older than
 * that; each mobile platform covers the same boundaries.
 */
const supportCases: readonly SupportCase[] = [
  { name: 'current Chrome', userAgent: chrome(130), width: DESKTOP_WIDTH, supported: true },
  {
    name: 'Chrome two majors behind',
    userAgent: chrome(128),
    width: DESKTOP_WIDTH,
    supported: true,
  },
  {
    name: 'Chrome three majors behind',
    userAgent: chrome(127),
    width: DESKTOP_WIDTH,
    supported: false,
  },
  { name: 'current Edge', userAgent: edge(130), width: DESKTOP_WIDTH, supported: true },
  { name: 'Edge two majors behind', userAgent: edge(128), width: DESKTOP_WIDTH, supported: true },
  { name: 'old Edge', userAgent: edge(120), width: DESKTOP_WIDTH, supported: false },
  { name: 'current Firefox', userAgent: firefox(130), width: DESKTOP_WIDTH, supported: true },
  {
    name: 'Firefox two majors behind',
    userAgent: firefox(128),
    width: DESKTOP_WIDTH,
    supported: true,
  },
  { name: 'old Firefox', userAgent: firefox(120), width: DESKTOP_WIDTH, supported: false },
  { name: 'current Safari', userAgent: safari(17), width: DESKTOP_WIDTH, supported: true },
  {
    name: 'Safari two majors behind',
    userAgent: safari(15),
    width: DESKTOP_WIDTH,
    supported: true,
  },
  { name: 'old Safari', userAgent: safari(14), width: DESKTOP_WIDTH, supported: false },
  {
    name: 'Internet Explorer 11',
    userAgent: 'Mozilla/5.0 (Windows NT 10.0; Trident/7.0; rv:11.0) like Gecko',
    width: DESKTOP_WIDTH,
    supported: false,
  },
  {
    name: 'Internet Explorer 10',
    userAgent: 'Mozilla/5.0 (compatible; MSIE 10.0; Windows NT 6.1; Trident/6.0)',
    width: DESKTOP_WIDTH,
    supported: false,
  },
  { name: 'compact width', userAgent: chrome(130), width: 768, supported: true },
  { name: 'phone reflow floor', userAgent: chrome(130), width: 320, supported: true },
  { name: 'narrower than reflow floor', userAgent: iphoneSafari(17), width: 319, supported: false },
  {
    name: 'unknown client fails closed',
    userAgent: 'curl/8.5.0',
    width: DESKTOP_WIDTH,
    supported: false,
  },
  {
    name: 'old iPhone Safari',
    userAgent: iphoneSafari(14),
    width: DESKTOP_WIDTH,
    supported: false,
  },
  { name: 'old iPad Safari', userAgent: ipadSafari(14), width: DESKTOP_WIDTH, supported: false },
  { name: 'old Chrome on iOS', userAgent: chromeIos(14), width: DESKTOP_WIDTH, supported: false },
  { name: 'old Firefox on iOS', userAgent: firefoxIos(14), width: DESKTOP_WIDTH, supported: false },
  {
    name: 'iPhone Safari two iOS versions behind',
    userAgent: iphoneSafari(15),
    width: DESKTOP_WIDTH,
    supported: true,
  },
  {
    name: 'iPad Safari two iOS versions behind',
    userAgent: ipadSafari(15),
    width: DESKTOP_WIDTH,
    supported: true,
  },
  {
    name: 'Chrome on iOS two iOS versions behind',
    userAgent: chromeIos(15),
    width: DESKTOP_WIDTH,
    supported: true,
  },
  {
    name: 'Edge on iOS judged by iOS version',
    userAgent: edgeIos(17),
    width: DESKTOP_WIDTH,
    supported: true,
  },
  {
    name: 'Chrome on Android two majors behind',
    userAgent: chromeAndroidPhone(128),
    width: DESKTOP_WIDTH,
    supported: true,
  },
  {
    name: 'Firefox on Android two majors behind',
    userAgent: firefoxAndroid(128),
    width: DESKTOP_WIDTH,
    supported: true,
  },
  {
    name: 'Edge on Android two majors behind',
    userAgent: edgeAndroid(128),
    width: DESKTOP_WIDTH,
    supported: true,
  },
  {
    name: 'old Chrome on Android phone',
    userAgent: chromeAndroidPhone(127),
    width: DESKTOP_WIDTH,
    supported: false,
  },
  {
    name: 'old Chrome on Android tablet',
    userAgent: chromeAndroidTablet(127),
    width: DESKTOP_WIDTH,
    supported: false,
  },
  {
    name: 'old Firefox on Android',
    userAgent: firefoxAndroid(120),
    width: DESKTOP_WIDTH,
    supported: false,
  },
  {
    name: 'old Edge on Android',
    userAgent: edgeAndroid(120),
    width: DESKTOP_WIDTH,
    supported: false,
  },
  ...mobileWidthCases,
];

describe('browser support policy constants', () => {
  it('matches the documented baseline versions and reflow floor', () => {
    expect([CHROME_BASELINE_MAJOR, EDGE_BASELINE_MAJOR, FIREFOX_BASELINE_MAJOR]).toEqual([
      130, 130, 130,
    ]);
    expect(SAFARI_BASELINE_MAJOR).toBe(17);
    expect(IOS_BASELINE_MAJOR).toBe(17);
    expect(MIN_SUPPORTED_WIDTH).toBe(320);
    expect(SUPPORTED_VERSION_WINDOW).toBe(2);
  });
});

describe('isSupportedBrowser', () => {
  it.each(supportCases)('$name', ({ userAgent, width, supported }) => {
    expect(isSupportedBrowser(userAgent, width)).toBe(supported);
  });
});
