import type { ComponentType } from 'react';
import { describe, expect, it } from 'vitest';
import {
  collectFeatureRoutes,
  createRoutes,
  type AppRoute,
  type FeatureRouteModule,
} from './routes';

/** Inert page used only to exercise route bookkeeping; never rendered here. */
const Placeholder: ComponentType = () => null;

/** Build an app route with the inert placeholder component. */
function route(pattern: string): AppRoute {
  return { pattern, component: Placeholder };
}

describe('createRoutes', () => {
  it('resolves every existing path', () => {
    expect(createRoutes(false).map((candidate) => candidate.pattern)).toEqual([
      '/',
      '/schedule',
      '/schedule/book',
      '/patients/:id/chart',
      '/patients/:id/notes/:noteId',
      '/admin/facilities',
      '/admin/staff',
      '/admin/config',
      '/admin/audit',
      '*',
    ]);
  });

  it('keeps the catch-all last', () => {
    const patterns = createRoutes(false).map((candidate) => candidate.pattern);
    expect(patterns.at(-1)).toBe('*');
  });

  it('registers the development preview only when requested', () => {
    expect(createRoutes(false).map((candidate) => candidate.pattern)).not.toContain(
      '/design-preview',
    );
    expect(createRoutes(true).map((candidate) => candidate.pattern)).toContain(
      '/design-preview',
    );
  });

  it('keeps the development preview before the catch-all', () => {
    const patterns = createRoutes(true).map((candidate) => candidate.pattern);
    expect(patterns.indexOf('/design-preview')).toBeLessThan(patterns.indexOf('*'));
  });
});

describe('collectFeatureRoutes', () => {
  it('collects the routes declared by each feature module', () => {
    const modules: Record<string, FeatureRouteModule> = {
      '../features/admin/routes.ts': { routes: [route('/admin/facilities')] },
      '../features/chart/routes.ts': { routes: [route('/patients/:id/chart')] },
    };
    expect(collectFeatureRoutes(modules).map((candidate) => candidate.pattern)).toEqual([
      '/patients/:id/chart',
      '/admin/facilities',
    ]);
  });

  it('preserves the declared order of routes within one feature', () => {
    const modules: Record<string, FeatureRouteModule> = {
      '../features/scheduling/routes.ts': {
        routes: [route('/schedule'), route('/schedule/book')],
      },
    };
    expect(collectFeatureRoutes(modules).map((candidate) => candidate.pattern)).toEqual([
      '/schedule',
      '/schedule/book',
    ]);
  });

  it('returns no routes when no feature registers any', () => {
    expect(collectFeatureRoutes({})).toEqual([]);
  });

  it('produces a stable order independent of module enumeration order', () => {
    const firstRoute = route('/first');
    const secondRoute = route('/second');
    const forwards = collectFeatureRoutes({
      '../features/a/routes.ts': { routes: [firstRoute] },
      '../features/z/routes.ts': { routes: [secondRoute] },
    });
    const backwards = collectFeatureRoutes({
      '../features/z/routes.ts': { routes: [secondRoute] },
      '../features/a/routes.ts': { routes: [firstRoute] },
    });
    expect(backwards.map((candidate) => candidate.pattern)).toEqual(
      forwards.map((candidate) => candidate.pattern),
    );
  });

  it('throws a clear error when two features register the same pattern', () => {
    const modules: Record<string, FeatureRouteModule> = {
      '../features/admin/routes.ts': { routes: [route('/shared')] },
      '../features/chart/routes.ts': { routes: [route('/shared')] },
    };
    expect(() => collectFeatureRoutes(modules)).toThrow("Duplicate route pattern '/shared'");
  });

  it('throws when one feature declares the same pattern twice', () => {
    const modules: Record<string, FeatureRouteModule> = {
      '../features/admin/routes.ts': { routes: [route('/admin/twice'), route('/admin/twice')] },
    };
    expect(() => collectFeatureRoutes(modules)).toThrow("Duplicate route pattern '/admin/twice'");
  });

  it.each(['/', '/design-preview', '*'])('rejects the app-reserved pattern %s', (pattern) => {
    const modules: Record<string, FeatureRouteModule> = {
      '../features/admin/routes.ts': { routes: [route(pattern)] },
    };
    expect(() => collectFeatureRoutes(modules)).toThrow(
      `Feature route pattern '${pattern}' is reserved by the app`,
    );
  });
});
