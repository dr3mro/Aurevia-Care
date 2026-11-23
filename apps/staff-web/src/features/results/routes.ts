import { lazy } from 'react';

import type { AppRoute } from '../../app/routes';

/** The lazily loaded results page, shared by both registered results routes. */
const ResultsPage = lazy(() =>
  import('./ResultsPage').then(({ ResultsPage: Page }) => ({ default: Page })),
);

/**
 * Results routes.
 *
 * Both patterns already exist in `app/navigation.ts`; this feature owns them.
 * `/results/critical` opens the same screen with the critical queue at the top,
 * and its clinician-only role is enforced by the navigation access table.
 */
export const routes: AppRoute[] = [
  { pattern: '/results', component: ResultsPage },
  { pattern: '/results/critical', component: ResultsPage },
];
