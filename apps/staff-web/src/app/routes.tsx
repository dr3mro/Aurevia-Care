import { lazy } from 'react';
import type { ComponentType } from 'react';
// env.d.ts is owned by another module. Augment only the one build-time flag
// this module reads, so strict TypeScript accepts it without a global d.ts.
declare global {
  interface ImportMeta {
    readonly env: { readonly DEV: boolean };
  }
}

/** One registered route: an exact path and its lazily loaded page. */
export interface AppRoute {
  pattern: string;
  component: ComponentType;
}

const WelcomePage = lazy(() =>
  import('./WelcomePage').then(({ WelcomePage: Page }) => ({ default: Page })),
);
const DesignPreview = lazy(() =>
  import('./DesignPreview').then(({ DesignPreview: Page }) => ({ default: Page })),
);
const NotFoundPage = lazy(() =>
  import('./NotFoundPage').then(({ NotFoundPage: Page }) => ({ default: Page })),
);

/**
 * Build the route table. The development preview is registered only when
 * requested; a `'*'` sentinel is the catch-all because the pure matcher never
 * treats a wildcard as a pattern.
 */
export function createRoutes(includeDesignPreview: boolean): AppRoute[] {
  const candidates: AppRoute[] = [{ pattern: '/', component: WelcomePage }];
  if (includeDesignPreview) {
    candidates.push({ pattern: '/design-preview', component: DesignPreview });
  }
  candidates.push({ pattern: '*', component: NotFoundPage });
  return candidates;
}

/** Routes used by the running application; DEV adds the old preview page. */
export const routes: readonly AppRoute[] = createRoutes(import.meta.env.DEV);
