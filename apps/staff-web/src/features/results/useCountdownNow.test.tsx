import { act, cleanup, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { COUNTDOWN_INTERVAL_MS } from './domain';
import { startTicker, useCountdownNow } from './useCountdownNow';

/** Minimal consumer so the hook's tick and cleanup run in a real React tree. */
function ClockProbe() {
  const now = useCountdownNow();
  return <output data-testid="clock">{new Date(now).toISOString()}</output>;
}

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(new Date('2026-03-08T12:00:00Z'));
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

describe('startTicker', () => {
  it('calls the tick handler on each interval and stops on cleanup', () => {
    const onTick = vi.fn();
    const stop = startTicker(onTick, COUNTDOWN_INTERVAL_MS);
    act(() => {
      vi.advanceTimersByTime(COUNTDOWN_INTERVAL_MS);
    });
    expect(onTick).toHaveBeenCalledTimes(1);
    stop();
    act(() => {
      vi.advanceTimersByTime(COUNTDOWN_INTERVAL_MS * 2);
    });
    expect(onTick).toHaveBeenCalledTimes(1);
  });
});

describe('useCountdownNow', () => {
  it('refreshes the displayed instant every thirty seconds', () => {
    render(<ClockProbe />);
    expect(screen.getByTestId('clock').textContent).toBe('2026-03-08T12:00:00.000Z');
    act(() => {
      vi.advanceTimersByTime(COUNTDOWN_INTERVAL_MS);
    });
    expect(screen.getByTestId('clock').textContent).toBe('2026-03-08T12:00:30.000Z');
  });
});
