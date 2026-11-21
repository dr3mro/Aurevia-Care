import { useEffect, useState } from 'react';
import { COUNTDOWN_INTERVAL_MS } from './domain';

/**
 * Start a repeating clock and return the exact function that stops it.
 * Keeping the cleanup as a value makes interval disposal directly testable
 * with fake timers instead of relying on an unmount side effect.
 */
export function startTicker(onTick: () => void, intervalMs: number): () => void {
  const timer = setInterval(onTick, intervalMs);
  return () => clearInterval(timer);
}

/**
 * Current instant, refreshed every thirty seconds for critical-result countdowns.
 *
 * The tick is never announced: a continuously updating live region would make
 * the queue unusable with a screen reader (accessibility patterns 6.4).
 */
export function useCountdownNow(): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => startTicker(() => setNow(Date.now()), COUNTDOWN_INTERVAL_MS), []);
  return now;
}
