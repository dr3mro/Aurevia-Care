import { renderToString } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { useBreakpoint, useOrientation, usePointerCoarse } from './useBreakpoint';

describe('server snapshots', () => {
  it('uses the documented fallbacks while rendering on the server', () => {
    function Probe() {
      const breakpoint = useBreakpoint();
      const coarse = usePointerCoarse();
      const orientation = useOrientation();
      return <span>{`${breakpoint}|${coarse}|${orientation}`}</span>;
    }

    // On the server there is no matchMedia, so useSyncExternalStore must take
    // its getServerSnapshot path and report each hook's fallback.
    expect(renderToString(<Probe />)).toContain('expanded|false|portrait');
  });
});
