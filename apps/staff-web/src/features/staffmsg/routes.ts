import { lazy } from 'react';
import type { AppRoute } from '../../app/routes';

/**
 * Page routes owned by the staff messaging feature (MSG-02, MSG-03, WEB-09).
 *
 * The application shell discovers this module through the feature route glob
 * declared in `src/app/routes.tsx`, so no edit to that file is needed.
 * Only the two slices whose pages need no injected command are routable here;
 * the messenger panel and the small parts are mounted by an owning screen.
 * Each page is loaded lazily to keep the initial bundle within the WEB-08 budget.
 * The patterns deliberately do not claim `/messages`, which belongs to the
 * unit-inbox feature.
 */
export const routes: AppRoute[] = [
  {
    pattern: '/messaging/mail',
    component: lazy(() =>
      import('./mail/MailPage').then(({ MailPage: Page }) => ({ default: Page })),
    ),
  },
  {
    pattern: '/messaging/directory',
    component: lazy(() =>
      import('./directory/DirectoryPage').then(({ DirectoryPage: Page }) => ({ default: Page })),
    ),
  },
];
