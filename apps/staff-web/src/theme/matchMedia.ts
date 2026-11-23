/** The browser `matchMedia` function. */
export type MatchMedia = (query: string) => MediaQueryList;

/**
 * Return `matchMedia` when the environment provides it. It is absent during
 * server render and in older test environments.
 */
export function getMatchMedia(): MatchMedia | undefined {
  return (globalThis as { matchMedia?: MatchMedia }).matchMedia;
}
