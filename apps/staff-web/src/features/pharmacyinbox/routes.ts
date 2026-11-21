import { lazy } from 'react';
import type { AppRoute } from '../../app/routes';

const PharmacyInboxPage = lazy(() =>
  import('./PharmacyInboxPage').then(({ PharmacyInboxPage: Page }) => ({ default: Page })),
);
const PharmacyItemPage = lazy(() =>
  import('./PharmacyItemPage').then(({ PharmacyItemPage: Page }) => ({ default: Page })),
);

/**
 * Routes owned by the pharmacy feature. The application collects them through
 * its feature glob; shared registration is owned by a person or a later task.
 */
export const routes: AppRoute[] = [
  { pattern: '/pharmacy', component: PharmacyInboxPage },
  { pattern: '/pharmacy/:id', component: PharmacyItemPage },
];
