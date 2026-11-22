import { lazy } from 'react';
import type { AppRoute } from '../../app/routes';

/** Tenant export is code-split like the other operation screens (WEB-08). */
const ExportPage = lazy(() =>
  import('./ExportPage').then(({ ExportPage: Page }) => ({ default: Page })),
);

/** Onboarding import is code-split like the other operation screens (WEB-08). */
const ImportPage = lazy(() =>
  import('./ImportPage').then(({ ImportPage: Page }) => ({ default: Page })),
);

/**
 * Routes owned by the tenant data-exchange feature. Keeping them here lets a
 * worker change a screen without editing the shared route registry.
 */
export const routes: AppRoute[] = [
  { pattern: '/operations/exports', component: ExportPage },
  { pattern: '/operations/imports', component: ImportPage },
];
