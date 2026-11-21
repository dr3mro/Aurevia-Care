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

/** Desktop width used by the user-agent cases, from WEB-07. */
const DESKTOP_WIDTH = 1280;

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

interface SupportCase {
  name: string;
  userAgent: string;
  width: number;
  supported: boolean;
}

/**
 * Matrix of synthetic clients. Each browser covers its baseline, the oldest
 * still-supported version (baseline minus two) and a version older than that.
 */
const supportCases: readonly SupportCase[] = [
  { name: 'current Chrome', userAgent: chrome(130), width: DESKTOP_WIDTH, supported: true },
  { name: 'Chrome two majors behind', userAgent: chrome(128), width: DESKTOP_WIDTH, supported: true },
  { name: 'Chrome three majors behind', userAgent: chrome(127), width: DESKTOP_WIDTH, supported: false },
  { name: 'current Edge', userAgent: edge(130), width: DESKTOP_WIDTH, supported: true },
  { name: 'Edge two majors behind', userAgent: edge(128), width: DESKTOP_WIDTH, supported: true },
  { name: 'old Edge', userAgent: edge(120), width: DESKTOP_WIDTH, supported: false },
  { name: 'current Firefox', userAgent: firefox(130), width: DESKTOP_WIDTH, supported: true },
  { name: 'Firefox two majors behind', userAgent: firefox(128), width: DESKTOP_WIDTH, supported: true },
  { name: 'old Firefox', userAgent: firefox(120), width: DESKTOP_WIDTH, supported: false },
  { name: 'current Safari', userAgent: safari(17), width: DESKTOP_WIDTH, supported: true },
  { name: 'Safari two majors behind', userAgent: safari(15), width: DESKTOP_WIDTH, supported: true },
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
  { name: 'narrower than compact', userAgent: chrome(130), width: 767, supported: false },
  { name: 'unknown client fails closed', userAgent: 'curl/8.5.0', width: DESKTOP_WIDTH, supported: false },
];

describe('browser support policy constants', () => {
  it('matches the documented baseline versions and compact floor', () => {
    expect([CHROME_BASELINE_MAJOR, EDGE_BASELINE_MAJOR, FIREFOX_BASELINE_MAJOR]).toEqual([
      130, 130, 130,
    ]);
    expect(SAFARI_BASELINE_MAJOR).toBe(17);
    expect(MIN_SUPPORTED_WIDTH).toBe(768);
    expect(SUPPORTED_VERSION_WINDOW).toBe(2);
  });
});

describe('isSupportedBrowser', () => {
  it.each(supportCases)('$name', ({ userAgent, width, supported }) => {
    expect(isSupportedBrowser(userAgent, width)).toBe(supported);
  });
});
