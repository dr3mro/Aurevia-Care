import { lazy } from 'react';

import type { AppRoute } from '../../app/routes';

/** The laboratory turnaround board, reachable from the laboratory area. */
export const routes: AppRoute[] = [
  {
    pattern: '/lab/turnaround-board',
    component: lazy(() =>
      import('./LabBoardPage').then(({ LabBoardPage: Page }) => ({ default: Page })),
    ),
  },
];
