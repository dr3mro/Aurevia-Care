import { lazy } from 'react';

import type { AppRoute } from '../../app/routes';

/**
 * Routes owned by the laboratory result-entry feature. The screen registers on
 * its own path so it stays reachable without editing the shared route registry.
 */
export const routes: AppRoute[] = [
  {
    pattern: '/lab/results/entry',
    component: lazy(() =>
      import('./ResultEntryPage').then(({ ResultEntryPage: Page }) => ({ default: Page })),
    ),
  },
];
