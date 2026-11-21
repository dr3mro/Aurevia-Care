import { lazy } from 'react';
import type { AppRoute } from '../../app/routes';

const CollectionDeskPage = lazy(() =>
  import('./CollectionDeskPage').then(({ CollectionDeskPage: Page }) => ({ default: Page })),
);

/**
 * Routes owned by the lab collection feature. The feature-route glob discovers
 * this module, so the accession route needs no edit to the shared registry.
 */
export const routes: AppRoute[] = [{ pattern: '/lab/accessions', component: CollectionDeskPage }];
