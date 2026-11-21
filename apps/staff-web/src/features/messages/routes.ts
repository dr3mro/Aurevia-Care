import { lazy } from 'react';
import type { AppRoute } from '../../app/routes';

const MessagesPage = lazy(() =>
  import('./MessagesPage').then(({ MessagesPage: Page }) => ({ default: Page })),
);
const ConversationPage = lazy(() =>
  import('./ConversationPage').then(({ ConversationPage: Page }) => ({ default: Page })),
);

/**
 * Routes owned by the messages feature. Keeping them here lets a worker add or
 * change a messages screen without touching the shared route registry.
 */
export const routes: AppRoute[] = [
  { pattern: '/messages', component: MessagesPage },
  { pattern: '/messages/:id', component: ConversationPage },
];
