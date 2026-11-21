import { lazy } from 'react';
import type { AppRoute } from '../../app/routes';

const OrdersPage = lazy(() =>
  import('./OrdersPage').then(({ OrdersPage: Page }) => ({ default: Page })),
);
const OrderDetailPage = lazy(() =>
  import('./OrderDetailPage').then(({ OrderDetailPage: Page }) => ({ default: Page })),
);

/**
 * Routes owned by the orders feature. The app collects every feature's routes;
 * this module never edits the shared route registry or navigation model.
 */
export const routes: AppRoute[] = [
  { pattern: '/orders', component: OrdersPage },
  { pattern: '/orders/:id', component: OrderDetailPage },
];
