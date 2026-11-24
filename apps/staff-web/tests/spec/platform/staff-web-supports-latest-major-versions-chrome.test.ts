import { describe, expect, it } from 'vitest';
import {
  CHROME_BASELINE_MAJOR,
  EDGE_BASELINE_MAJOR,
  FIREFOX_BASELINE_MAJOR,
  MIN_SUPPORTED_WIDTH,
  SAFARI_BASELINE_MAJOR,
  SUPPORTED_VERSION_WINDOW,
  isSupportedBrowser,
} from '../../../src/features/system/browserSupport';
import { BREAKPOINT_WIDTHS, breakpointFor } from '../../../src/theme/breakpoints';

/**
 * Synthetic desktop Chrome user agent. It deliberately contains no Edge token,
 * so the generic Chrome rule resolves it.
 */
function chrome(major: number): string {
  return `Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${major}.0.0.0 Safari/537.36`;
}

/**
 * Synthetic desktop Chromium Edge user agent. Edge advertises `Chrome` before
 * its own `Edg` token, so this proves the more-specific rule wins.
 */
function edge(major: number): string {
  return `Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${major}.0.0.0 Safari/537.36 Edg/${major}.0.0.0`;
}

/**
 * Synthetic Edge user agent whose `Chrome` token deliberately disagrees with
 * its `Edg` token. Real Edge advertises both, so the more-specific rule must
 * decide support rather than the shared Chromium token.
 */
function edgeWithDivergentTokens(edgeMajor: number, chromeMajor: number): string {
  return `Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${chromeMajor}.0.0.0 Safari/537.36 Edg/${edgeMajor}.0.0.0`;
}

/** Synthetic desktop Firefox user agent for a given major version. */
function firefox(major: number): string {
  return `Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:${major}.0) Gecko/20100101 Firefox/${major}.0`;
}

/** Synthetic desktop Safari user agent for a given major version. */
function safari(major: number): string {
  return `Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/${major}.2 Safari/605.1.15`;
}

/** The four desktop browsers named by WEB-07 and their reference majors. */
const desktopBrowsers = [
  { name: 'Chrome', baseline: CHROME_BASELINE_MAJOR, userAgent: chrome },
  { name: 'Edge', baseline: EDGE_BASELINE_MAJOR, userAgent: edge },
  { name: 'Firefox', baseline: FIREFOX_BASELINE_MAJOR, userAgent: firefox },
  { name: 'Safari', baseline: SAFARI_BASELINE_MAJOR, userAgent: safari },
] as const;

describe('WEB-07 clause 2 — support latest major desktop browsers at 1280 px and above', () => {
  it('supports the current and two prior majors of each desktop browser at 1280 px', () => {
    // Pin the documented fallbacks so a silent constant change cannot make the
    // matrix below vacuously pass while the supported window has shifted.
    expect([CHROME_BASELINE_MAJOR, EDGE_BASELINE_MAJOR, FIREFOX_BASELINE_MAJOR]).toEqual([
      130, 130, 130,
    ]);
    expect(SAFARI_BASELINE_MAJOR).toBe(17);
    expect(SUPPORTED_VERSION_WINDOW).toBe(2);
    expect(MIN_SUPPORTED_WIDTH).toBe(320);

    for (const browser of desktopBrowsers) {
      for (let behind = 0; behind <= SUPPORTED_VERSION_WINDOW; behind += 1) {
        const major = browser.baseline - behind;
        expect(
          isSupportedBrowser(browser.userAgent(major), BREAKPOINT_WIDTHS.expanded),
          `${browser.name} ${major}`,
        ).toBe(true);
      }
    }
  });

  it('recognises Edge by its own token when the Chrome token disagrees', () => {
    const staleChrome = CHROME_BASELINE_MAJOR - SUPPORTED_VERSION_WINDOW - 1;
    expect(
      isSupportedBrowser(
        edgeWithDivergentTokens(EDGE_BASELINE_MAJOR, staleChrome),
        BREAKPOINT_WIDTHS.expanded,
      ),
    ).toBe(true);

    const staleEdge = EDGE_BASELINE_MAJOR - SUPPORTED_VERSION_WINDOW - 1;
    expect(
      isSupportedBrowser(
        edgeWithDivergentTokens(staleEdge, CHROME_BASELINE_MAJOR),
        BREAKPOINT_WIDTHS.expanded,
      ),
    ).toBe(false);
  });

  it('keeps the layout supported at 1280, 1024 and 768 px', () => {
    const layoutCases = [
      { width: BREAKPOINT_WIDTHS.expanded, breakpoint: 'expanded' },
      { width: 1024, breakpoint: 'medium' },
      { width: BREAKPOINT_WIDTHS.medium, breakpoint: 'medium' },
    ] as const;

    for (const layoutCase of layoutCases) {
      expect(
        isSupportedBrowser(chrome(CHROME_BASELINE_MAJOR), layoutCase.width),
        `${layoutCase.width}px`,
      ).toBe(true);
      expect(breakpointFor(layoutCase.width)).toBe(layoutCase.breakpoint);
    }
  });

  it('refuses a browser three majors behind', () => {
    for (const browser of desktopBrowsers) {
      const major = browser.baseline - SUPPORTED_VERSION_WINDOW - 1;
      expect(
        isSupportedBrowser(browser.userAgent(major), BREAKPOINT_WIDTHS.expanded),
        `${browser.name} ${major}`,
      ).toBe(false);
    }
  });

  it('refuses Internet Explorer and unrecognised user agents', () => {
    const internetExplorer =
      'Mozilla/5.0 (Windows NT 10.0; Trident/7.0; rv:11.0) like Gecko';
    expect(isSupportedBrowser(internetExplorer, BREAKPOINT_WIDTHS.expanded)).toBe(false);
    expect(isSupportedBrowser('curl/8.5.0', BREAKPOINT_WIDTHS.expanded)).toBe(false);
  });

  it('refuses a viewport below the 320 px reflow floor', () => {
    expect(isSupportedBrowser(chrome(CHROME_BASELINE_MAJOR), MIN_SUPPORTED_WIDTH - 1)).toBe(
      false,
    );
  });
});
