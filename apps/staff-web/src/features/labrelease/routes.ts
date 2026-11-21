import { lazy } from 'react';

import type { AppRoute } from '../../app/routes';

/**
 * Routes owned by the laboratory release feature. The screen registers on its
 * own path so it stays reachable without editing the shared route registry.
 */
export const routes: AppRoute[] = [
  {
    pattern: '/lab/release',
    component: lazy(() =>
      import('./ReleasePage').then(({ ReleasePage: Page }) => ({ default: Page })),
    ),
  },
];
