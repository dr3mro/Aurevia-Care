import { useEffect } from 'react';

/** Props for the invisible bridge between a loaded page and an accumulating list. */
export interface PageSinkProps<T> {
  /** The page object `ApiPage` just resolved. */
  page: T;
  /** Append callback; must be stable so one page is appended exactly once. */
  onPage: (page: T) => void;
}

/**
 * Append one resolved page to list state after commit. Rendering a list from
 * accumulated state keeps earlier rows on screen while the next cursor loads,
 * and a stable page identity prevents a re-render from duplicating rows.
 */
export function PageSink<T>({ page, onPage }: PageSinkProps<T>) {
  useEffect(() => {
    onPage(page);
  }, [page, onPage]);
  return null;
}
