import { lazy } from 'react';
import type { AppRoute } from '../../app/routes';

const SchedulingPage = lazy(() =>
  import('./SchedulingPage').then(({ SchedulingPage: Page }) => ({ default: Page })),
);

/**
 * Routes owned by the scheduling feature. Keeping them here lets a worker add
 * or change a scheduling screen without touching the shared route registry.
 */
export const routes: AppRoute[] = [
  { pattern: '/schedule', component: SchedulingPage },
  { pattern: '/schedule/book', component: SchedulingPage },
];
