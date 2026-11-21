import { lazy } from 'react';
import type { AppRoute } from '../../app/routes';

const FacilitiesPage = lazy(() =>
  import('./FacilitiesPage').then(({ FacilitiesPage: Page }) => ({ default: Page })),
);
const StaffPage = lazy(() =>
  import('./StaffPage').then(({ StaffPage: Page }) => ({ default: Page })),
);

/**
 * Routes owned by the administration feature. Keeping them here lets a worker
 * add or change an admin screen without touching the shared route registry.
 */
export const routes: AppRoute[] = [
  { pattern: '/admin/facilities', component: FacilitiesPage },
  { pattern: '/admin/staff', component: StaffPage },
];
