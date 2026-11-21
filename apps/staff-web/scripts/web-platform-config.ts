/**
 * Pure WEB-08 build-platform helpers shared by `vite.config.ts` and its tests.
 *
 * Keeping these values outside the config file lets the build checks assert the
 * exact chunking, caching and font-loading policy without importing Vite's
 * config (whose `import.meta.url` is not a file URL under Vitest).
 */

/** Packages that must stay in one small shared chunk rather than per-route. */
const REACT_VENDOR_PACKAGES = ['react', 'react-dom', 'react-error-boundary', 'scheduler'] as const;

/**
 * Decide which named chunk a module belongs to.
 *
 * Shared React packages go to `react-vendor`, other dependencies to `vendor`.
 * Application modules return `undefined` so Vite keeps the existing
 * dynamic-import split: every feature route is its own lazy chunk.
 *
 * @param id absolute module id supplied by Rollup
 * @returns a chunk name, or `undefined` to let the dynamic import decide
 */
export function assignChunk(id: string): string | undefined {
  if (!id.includes('/node_modules/')) {
    return undefined;
  }
  const isReactVendor = REACT_VENDOR_PACKAGES.some((name) => id.includes(`/node_modules/${name}/`));
  return isReactVendor ? 'react-vendor' : 'vendor';
}

/** Cache header applied to the hashed asset directory (WEB-08). */
export const IMMUTABLE_ASSET_HEADERS = {
  '/assets/': { 'Cache-Control': 'public, max-age=31536000, immutable' },
} as const;

/**
 * Return the immutable cache-control value for a request to a hashed asset.
 *
 * Only the content-hashed `/assets/` directory is safe to cache forever; the
 * document and other URLs must still revalidate.
 *
 * @param url request URL, or `undefined` when the request has none
 * @returns the header value for hashed assets, otherwise `undefined`
 */
export function immutableCacheHeader(url: string | undefined): string | undefined {
  return url?.startsWith('/assets/')
    ? IMMUTABLE_ASSET_HEADERS['/assets/']['Cache-Control']
    : undefined;
}

/** Same-origin stylesheet that declares the self-hosted, Arabic-coverage faces. */
export const FONT_STYLESHEET_HREF = '/fonts/fonts.css';

/**
 * Inject the self-hosted font stylesheet into the built HTML exactly once.
 *
 * @param html the HTML Vite is about to emit
 * @returns HTML containing the local font `<link>` in its head
 */
export function injectFontStylesheet(html: string): string {
  if (html.includes(FONT_STYLESHEET_HREF)) {
    return html;
  }
  const link = `<link rel="stylesheet" href="${FONT_STYLESHEET_HREF}">`;
  return html.replace('</head>', `  ${link}\n  </head>`);
}
