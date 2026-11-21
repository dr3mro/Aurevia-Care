import { lazy } from 'react';
import type { AppRoute } from '../../app/routes';

const OrdersPage = lazy(() =>
  import('./OrdersPage').then(({ OrdersPage: Page }) => ({ default: Page })),
);
const OrderDetailPage = lazy(() =>
  import('./OrderDetailPage').then(({ OrderDetailPage: Page }) => ({ default: Page })),
);

/** Routes owned by the radiography feature. */
export const routes: AppRoute[] = [
  { pattern: '/imaging/orders', component: OrdersPage },
  { pattern: '/imaging/orders/:id', component: OrderDetailPage },
];
