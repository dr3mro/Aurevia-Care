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
 * Both render the same screen, whose critical panel is the first region after
 * the heading. The clinician-only access to `/results/critical` is enforced by
 * the navigation access table.
 */
export const routes: AppRoute[] = [
  { pattern: '/results', component: ResultsPage },
  { pattern: '/results/critical', component: ResultsPage },
];
