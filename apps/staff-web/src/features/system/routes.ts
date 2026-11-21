import { lazy } from 'react';
import type { AppRoute } from '../../app/routes';

/** Fixed pages are code-split like the other features (WEB-08). */
const SignedOutPage = lazy(() =>
  import('./SignedOutPage').then(({ SignedOutPage: Page }) => ({ default: Page })),
);
const SessionExpiredPage = lazy(() =>
  import('./SessionExpiredPage').then(({ SessionExpiredPage: Page }) => ({ default: Page })),
);
const ForbiddenPage = lazy(() =>
  import('./ForbiddenPage').then(({ ForbiddenPage: Page }) => ({ default: Page })),
);
const NotFoundPage = lazy(() =>
  import('./NotFoundPage').then(({ NotFoundPage: Page }) => ({ default: Page })),
);

/**
 * Routes owned by the system feature. The unsupported page is not registered:
 * the entry point renders it before the application mounts, so the check is
 * never bypassed by navigating to a route.
 */
export const routes: AppRoute[] = [
  { pattern: '/signed-out', component: SignedOutPage },
  { pattern: '/session-expired', component: SessionExpiredPage },
  { pattern: '/forbidden', component: ForbiddenPage },
  { pattern: '/not-found', component: NotFoundPage },
];
