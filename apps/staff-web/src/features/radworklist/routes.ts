import { lazy } from 'react';
import type { AppRoute } from '../../app/routes';

const RadWorklistPage = lazy(() =>
  import('./RadWorklistPage').then(({ RadWorklistPage: Page }) => ({ default: Page })),
);

/**
 * Routes owned by the radiology worklist feature. Keeping them here lets this
 * screen register on the existing `/radiology` rail item without editing the
 * shared route registry.
 */
export const routes: AppRoute[] = [{ pattern: '/radiology', component: RadWorklistPage }];
