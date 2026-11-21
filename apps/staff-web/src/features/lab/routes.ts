import { lazy } from 'react';
import type { AppRoute } from '../../app/routes';

const AccessionsPage = lazy(() =>
  import('./AccessionsPage').then(({ AccessionsPage: Page }) => ({ default: Page })),
);
const AccessionDetailPage = lazy(() =>
  import('./AccessionDetailPage').then(({ AccessionDetailPage: Page }) => ({ default: Page })),
);

/**
 * Routes owned by the lab feature. The app collects every feature's routes, so
 * adding a screen here never touches the shared route registry.
 */
export const routes: AppRoute[] = [
  { pattern: '/lab/accessions', component: AccessionsPage },
  { pattern: '/lab/accessions/:id', component: AccessionDetailPage },
];
