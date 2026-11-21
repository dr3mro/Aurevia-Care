import { lazy } from 'react';

import type { AppRoute } from '../../app/routes';

/** The partner interface and outage board, reachable from the operations area. */
export const routes: AppRoute[] = [
  {
    pattern: '/operations/partner-board',
    component: lazy(() =>
      import('./PartnerBoardPage').then(({ PartnerBoardPage: Page }) => ({ default: Page })),
    ),
  },
];
