import type { ReactNode } from 'react';

/** Props for the labelled scroll wrapper around a wide administration table. */
export interface AdminTableRegionProps {
  /** Accessible name of the scroll region, matching the table's own caption. */
  label: string;
  children: ReactNode;
}

/**
 * Keep a wide table workable at 320 CSS px and 400% zoom: the table itself may
 * scroll in two dimensions, but only inside one labelled, keyboard-focusable
 * region, so a keyboard user can reach every clinical column (UX-01, WCAG
 * 1.4.10). No column is hidden.
 */
export function AdminTableRegion({ label, children }: AdminTableRegionProps) {
  return (
    <div role="region" aria-label={label} tabIndex={0} style={{ overflowX: 'auto' }}>
      {children}
    </div>
  );
}
