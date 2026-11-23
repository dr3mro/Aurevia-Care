import { render, screen } from '@testing-library/react';
import { renderToStaticMarkup } from 'react-dom/server';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useBreakpoint, usePointerCoarse } from '../../../src/theme/useBreakpoint';

/** Render the responsive layout class so media-query state is observable. */
function BreakpointProbe() {
  return <span data-testid="breakpoint">{useBreakpoint()}</span>;
}

/** Render the coarse-pointer flag so the server snapshot is observable. */
function PointerProbe() {
  return <span data-testid="pointer">{usePointerCoarse() ? 'coarse' : 'fine'}</span>;
}

/**
 * Install a deterministic matchMedia stand-in whose first answer is stale by
 * the time React subscribes. The snapshot sees a match; subscribe then sees a
 * different one, which must notify React so the reported breakpoint is refreshed.
 */
function installStaleFirstSnapshot(): void {
  const answers = [true, false, false, false, false, false];
  let read = 0;
  vi.stubGlobal('matchMedia', (query: string) => ({
    media: query,
    get matches() {
      const answer = answers[read] ?? false;
      read += 1;
      return answer;
    },
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
  }));
}

describe('useBreakpoint coverage paths', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('refreshes the snapshot when media state changed before subscription', () => {
    installStaleFirstSnapshot();
    render(<BreakpointProbe />);
    // The stale first match is discarded once subscribe finds a different value.
    expect(screen.getByTestId('breakpoint').textContent).toBe('compact');
  });

  it('reports the supplied fallback during server rendering', () => {
    // No matchMedia and no client subscription exist on the server.
    expect(renderToStaticMarkup(<PointerProbe />)).toBe('<span data-testid="pointer">fine</span>');
  });
});
