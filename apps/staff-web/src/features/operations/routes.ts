import { lazy } from 'react';
import type { AppRoute } from '../../app/routes';

const QueuesPage = lazy(() =>
  import('./QueuesPage').then(({ QueuesPage: Page }) => ({ default: Page })),
);

/**
 * Routes owned by the operations feature. The app collects every feature's
 * routes; only the operational-queues screen is registered here.
 */
export const routes: AppRoute[] = [
  { pattern: '/operations/queues', component: QueuesPage },
];
