import { lazy } from 'react';
import type { ComponentType } from 'react';
import { defined } from '../components/invariant';
// env.d.ts is owned by another module. Augment only the build-time surface this
// module reads, so strict TypeScript accepts glob without a global d.ts edit.
declare global {
  interface ImportMeta {
    readonly env: { readonly DEV: boolean };
    /** Vite's eager glob: every matching module, keyed by its path. */
    readonly glob: <Module>(pattern: string, options: { eager: true }) => Record<string, Module>;
  }
}

/** One registered route: an exact path and its lazily loaded page. */
export interface AppRoute {
  pattern: string;
  component: ComponentType;
}

/** The contract every feature route module exports. */
export interface FeatureRouteModule {
  routes: AppRoute[];
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

const featureRouteModules = import.meta.glob<FeatureRouteModule>('../features/*/routes.ts', {
  eager: true,
});

/** Patterns the app owns; a feature must never claim one of them. */
const RESERVED_PATTERNS = new Set(['/', '/design-preview', '*']);

/**
 * Merge the routes declared by every feature module into one list.
 *
 * Module keys are sorted (descending, so the established scheduling → chart →
 * admin navigation order is preserved) to make registration independent of the
 * order in which the bundler enumerates the glob. Each module keeps the order
 * in which it declared its own routes. A pattern claimed more than once, or one
 * the app reserves (welcome, preview, catch-all), is a programming error: fail
 * loudly instead of letting one screen shadow another.
 */
export function collectFeatureRoutes(modules: Record<string, FeatureRouteModule>): AppRoute[] {
  const collected: AppRoute[] = [];
  const claimed = new Set<string>();
  const paths = Object.keys(modules).sort().reverse();
  for (const path of paths) {
    for (const route of defined(modules[path], 'feature route module').routes) {
      if (RESERVED_PATTERNS.has(route.pattern)) {
        throw new Error(`Feature route pattern '${route.pattern}' is reserved by the app`);
      }
      if (claimed.has(route.pattern)) {
        throw new Error(`Duplicate route pattern '${route.pattern}'`);
      }
      claimed.add(route.pattern);
      collected.push(route);
    }
  }
  return collected;
}

/**
 * Build the route table. Feature routes self-register; the app contributes the
 * welcome page, the development preview (only when requested) and the `'*'`
 * catch-all, which stays last because the pure matcher never matches a wildcard.
 */
export function createRoutes(includeDesignPreview: boolean): AppRoute[] {
  const candidates: AppRoute[] = [
    { pattern: '/', component: WelcomePage },
    ...collectFeatureRoutes(featureRouteModules),
  ];
  if (includeDesignPreview) {
    candidates.push({ pattern: '/design-preview', component: DesignPreview });
  }
  candidates.push({ pattern: '*', component: NotFoundPage });
  return candidates;
}

/** Routes used by the running application; DEV adds the old preview page. */
export const routes: readonly AppRoute[] = createRoutes(import.meta.env.DEV);
