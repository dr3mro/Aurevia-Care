import { lazy } from 'react';
import type { AppRoute } from '../../app/routes';

const ResultsInboxPage = lazy(() =>
  import('./ResultsInboxPage').then(({ ResultsInboxPage: Page }) => ({ default: Page })),
);
const CriticalQueuePage = lazy(() =>
  import('./CriticalQueuePage').then(({ CriticalQueuePage: Page }) => ({ default: Page })),
);

/**
 * Routes owned by the results feature. Keeping them here lets a worker add or
 * change a results screen without touching the shared route registry.
 */
export const routes: AppRoute[] = [
  { pattern: '/results', component: ResultsInboxPage },
  { pattern: '/results/critical', component: CriticalQueuePage },
];
