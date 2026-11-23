import { lazy } from 'react';
import type { AppRoute } from '../../app/routes';

const ChartPage = lazy(() =>
  import('./ChartPage').then(({ ChartPage: Page }) => ({ default: Page })),
);
const NotePage = lazy(() => import('./NotePage').then(({ NotePage: Page }) => ({ default: Page })));

/**
 * Routes owned by the chart feature. Keeping them here lets a worker add or
 * change a chart screen without touching the shared route registry.
 */
export const routes: AppRoute[] = [
  { pattern: '/patients/:id/chart', component: ChartPage },
  { pattern: '/patients/:id/notes/:noteId', component: NotePage },
];
