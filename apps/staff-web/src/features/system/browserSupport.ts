/**
 * Client-support policy for the staff web (WEB-07). The decision is a pure
 * function so a matrix of synthetic user agents and viewport widths can prove
 * which clients see the application and which see the plain message.
 *
 * The production-readiness checklist names the four browsers but no numeric
 * majors, so the baselines below are the documented fallbacks: two below the
 * release reference. A client is supported while it is at most
 * {@link SUPPORTED_VERSION_WINDOW} majors behind its baseline.
 */

/** Reference major for current Chrome. */
export const CHROME_BASELINE_MAJOR = 130;

/** Reference major for current Chromium Edge. */
export const EDGE_BASELINE_MAJOR = 130;

/** Reference major for current Firefox. */
export const FIREFOX_BASELINE_MAJOR = 130;

/** Reference major for current Safari. */
export const SAFARI_BASELINE_MAJOR = 17;

/** Compact-layout floor; narrower viewports get no application data. */
export const MIN_SUPPORTED_WIDTH = 768;

/** Number of major versions behind the baseline that remain supported. */
export const SUPPORTED_VERSION_WINDOW = 2;

/** A detection rule: one capture group holds the browser's major version. */
interface BrowserRule {
  readonly pattern: RegExp;
  readonly baseline: number;
}

/**
 * Detection order is deliberate. Chromium Edge also advertises `Chrome`, and
 * Chrome also advertises `Safari`, so the most specific token is tested first.
 */
const BROWSER_RULES: readonly BrowserRule[] = [
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
 * @returns true only for a recognised, recent-enough desktop browser at or
 * above the compact-layout floor; unknown clients fail closed (WEB-07)
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
