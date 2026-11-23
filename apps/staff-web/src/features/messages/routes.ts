import { lazy } from 'react';
import type { AppRoute } from '../../app/routes';

const InboxPage = lazy(() =>
  import('./InboxPage').then(({ InboxPage: Page }) => ({ default: Page })),
);

/** Routes owned by the unit-inbox feature; registration is via the app glob. */
export const routes: AppRoute[] = [{ pattern: '/messages', component: InboxPage }];
