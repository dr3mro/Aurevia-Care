/**
 * Client-support policy for the staff web (WEB-07). The decision is a pure
 * function so a matrix of synthetic user agents and viewport widths can prove
 * which clients see the application and which see the plain message.
 *
 * The production-readiness checklist names the four desktop browsers but no
 * numeric majors, so the baselines below are the documented fallbacks: two
 * below the release reference. A client is supported while it is at most
 * {@link SUPPORTED_VERSION_WINDOW} majors behind its baseline.
 *
 * Decision 0004 extends the supported matrix to phones and tablets: the
 * compact layout works from {@link MIN_SUPPORTED_WIDTH} upward, and mobile
 * engines (iOS Safari, the WebKit browsers on iOS, and Chrome, Firefox and Edge
 * on Android) are judged against their baselines. Internet Explorer and
 * unrecognised engines still fail closed (WEB-07).
 */

/** Reference major for current Chrome, including Chrome on Android. */
export const CHROME_BASELINE_MAJOR = 130;

/** Reference major for current Chromium Edge, including Edge on Android. */
export const EDGE_BASELINE_MAJOR = 130;

/** Reference major for current Firefox, including Firefox on Android. */
export const FIREFOX_BASELINE_MAJOR = 130;

/** Reference major for current desktop Safari. */
export const SAFARI_BASELINE_MAJOR = 17;

/**
 * Reference major for current iOS. Every iOS browser uses WebKit and reports
 * its own (often stale) engine token — `CriOS`, `FxiOS` or `EdgiOS` — so iOS
 * clients are judged by the OS version instead of that token.
 */
export const IOS_BASELINE_MAJOR = 17;

/** Reflow-layout floor; narrower viewports get no application data. */
export const MIN_SUPPORTED_WIDTH = 320;

/** Number of major versions behind the baseline that remain supported. */
export const SUPPORTED_VERSION_WINDOW = 2;

/** A detection rule: one capture group holds the browser's major version. */
interface BrowserRule {
  readonly pattern: RegExp;
  readonly baseline: number;
}

/**
 * Detection order is deliberate, most specific token first. The iOS rule leads
 * because every iOS browser advertises `Safari` and may also advertise `CriOS`,
 * `FxiOS` or `EdgiOS`; judging those stale tokens would reject a current iOS
 * device. Chromium Edge also advertises `Chrome`, and Chrome also advertises
 * `Safari`, so the generic engine rules follow. Android is identified by the
 * same engine rules: `Chrome`, `Firefox` and `EdgA` respectively.
 */
const BROWSER_RULES: readonly BrowserRule[] = [
  { pattern: /\bCPU (?:iPhone )?OS (\d+)_/, baseline: IOS_BASELINE_MAJOR },
  { pattern: /\bEdg(?:e|A|iOS)?\/(\d+)/, baseline: EDGE_BASELINE_MAJOR },
  { pattern: /\bFirefox\/(\d+)/, baseline: FIREFOX_BASELINE_MAJOR },
  { pattern: /\bChrome\/(\d+)/, baseline: CHROME_BASELINE_MAJOR },
  { pattern: /\bVersion\/(\d+)[^)]*\bSafari\//, baseline: SAFARI_BASELINE_MAJOR },
];

/** IE advertises `Trident` (11) or `MSIE` (older); no rule above matches it. */
const INTERNET_EXPLORER = /Trident\/|MSIE /;

/**
 * Return the captured major version, or null when the rule does not match.
 * The capture group is mandatory in every rule, so a match always yields a
 * numeric string at runtime.
 */
function matchedMajor(pattern: RegExp, userAgent: string): number | null {
  const match = pattern.exec(userAgent);
  if (match === null) {
    return null;
  }
  return Number(match[1]);
}

/**
 * Decide whether the staff web may render for this client.
 *
 * @param userAgent value of `navigator.userAgent`; never logged or stored
 * @param width viewport width in CSS pixels
 * @returns true only for a recognised, recent-enough desktop or mobile browser
 * at or above the 320 px reflow floor; unknown clients fail closed (WEB-07)
 */
export function isSupportedBrowser(userAgent: string, width: number): boolean {
  if (width < MIN_SUPPORTED_WIDTH) {
    return false;
  }
  if (INTERNET_EXPLORER.test(userAgent)) {
    return false;
  }
  for (const rule of BROWSER_RULES) {
    const major = matchedMajor(rule.pattern, userAgent);
    if (major !== null) {
      return major >= rule.baseline - SUPPORTED_VERSION_WINDOW;
    }
  }
  return false;
}
