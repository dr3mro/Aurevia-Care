import { lazy } from 'react';
import type { AppRoute } from '../../app/routes';

const PatientsPage = lazy(() =>
  import('./PatientsPage').then(({ PatientsPage: Page }) => ({ default: Page })),
);
const PatientOverviewPage = lazy(() =>
  import('./PatientOverviewPage').then(({ PatientOverviewPage: Page }) => ({ default: Page })),
);

/**
 * Routes owned by the patients feature. Keeping them here lets a worker change
 * a patient screen without touching the shared route registry.
 */
export const routes: AppRoute[] = [
  { pattern: '/patients', component: PatientsPage },
  { pattern: '/patients/:id', component: PatientOverviewPage },
];
