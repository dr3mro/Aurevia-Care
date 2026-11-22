import type { ReactNode } from 'react';
import { useBreakpoint } from '../../theme/useBreakpoint';

/** Props for the labelled scroll wrapper around a wide administration table. */
export interface AdminTableRegionProps {
  /** Accessible name of the scroll region, matching the table's own caption. */
  label: string;
  children: ReactNode;
}

/**
 * Keep a wide table workable at 320 CSS px and 200% text scaling: the table
 * itself may scroll in two dimensions, but only inside one labelled,
 * keyboard-focusable region, so a keyboard user can reach every clinical
 * column (UX-01, WCAG 1.4.10). No column is hidden.
 *
 * The `table-region` class supplies the shared, direction-neutral scroll and
 * focus-ring treatment from `responsive.css` at every band, so no new CSS is
 * needed. The inline `overflowX` is kept unchanged from the original wrapper
 * for the existing admin-screen assertion. `data-breakpoint` is an observation
 * seam that exposes the resolved band for jsdom structure checks; it does not
 * change which data or actions are available. Direction stays with the
 * document: the wrapper sets no physical side or direction override, so the
 * same markup mirrors under right-to-left (WEB-07).
 */
export function AdminTableRegion({ label, children }: AdminTableRegionProps) {
  const breakpoint = useBreakpoint();
  return (
    <div
      className="table-region"
      role="region"
      aria-label={label}
      tabIndex={0}
      data-breakpoint={breakpoint}
      style={{ overflowX: 'auto' }}
    >
      {children}
    </div>
  );
}
