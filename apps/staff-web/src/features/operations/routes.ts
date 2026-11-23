import { lazy } from 'react';
import type { AppRoute } from '../../app/routes';

const QueuesPage = lazy(() =>
  import('./QueuesPage').then(({ QueuesPage: Page }) => ({ default: Page })),
);

/** Routes owned by the operations feature; registration is via the app glob. */
export const routes: AppRoute[] = [{ pattern: '/operations/queues', component: QueuesPage }];
