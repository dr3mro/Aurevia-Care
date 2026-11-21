import { lazy } from 'react';
import type { AppRoute } from '../../app/routes';

const InvoicesPage = lazy(() =>
  import('./InvoicesPage').then(({ InvoicesPage: Page }) => ({ default: Page })),
);
const InvoiceDetailPage = lazy(() =>
  import('./InvoiceDetailPage').then(({ InvoiceDetailPage: Page }) => ({ default: Page })),
);

/**
 * Routes owned by the finance feature. Keeping them here lets a worker add or
 * change a finance screen without touching the shared route registry.
 */
export const routes: AppRoute[] = [
  { pattern: '/finance', component: InvoicesPage },
  { pattern: '/finance/:id', component: InvoiceDetailPage },
];
