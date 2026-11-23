import { createContext, useContext, useEffect, useMemo, useState } from 'react';
import type { MouseEvent, ReactNode } from 'react';
import { defined } from '../components/invariant';

/**
 * Named segments extracted from a matched route pattern. Values are opaque
 * local identifiers; they are never patient names or free text (WEB-07).
 */
export type RouteParams = Record<string, string>;

/** The current location and the parameters bound to it. */
export interface Route {
  path: string;
  params: RouteParams;
}

interface RouterContextValue extends Route {
  patterns: readonly string[];
}

const RouterContext = createContext<RouterContextValue | null>(null);

/**
 * Match a path against a pattern of exact and `:name` segments, with no
 * wildcards and no partial matches. Pure so it can be tested independently of
 * the browser and of React.
 *
 * @param pattern route pattern, for example `/patients/:id/notes/:noteId`
 * @param path current pathname, for example `/patients/p1/notes/n2`
 * @returns the named parameters when every segment matches, otherwise `null`
 */
export function matchRoute(pattern: string, path: string): RouteParams | null {
  const expected = pattern.split('/');
  const actual = path.split('/');
  if (expected.length !== actual.length) {
    return null;
  }

  const params: RouteParams = {};
  for (let index = 0; index < expected.length; index += 1) {
    const segment = defined(expected[index], 'pattern segment');
    const value = defined(actual[index], 'path segment');
    if (segment.startsWith(':')) {
      if (value.length === 0) {
        return null;
      }
      params[segment.slice(1)] = value;
    } else if (segment !== value) {
      return null;
    }
  }
  return params;
}

/**
 * Change the visible path without a document load and notify the provider.
 * A popstate event is the same signal the browser emits for Back/Forward, so
 * one listener covers programmatic and history navigation.
 */
export function navigate(path: string): void {
  window.history.pushState(null, '', path);
  window.dispatchEvent(new PopStateEvent('popstate'));
}

/** Props for the router provider mounted once at the application root. */
export interface RouterProviderProps {
  children: ReactNode;
  /** Registered patterns, used to bind `useRoute().params`. */
  patterns?: readonly string[] | undefined;
  /** Deterministic starting path; production callers omit it. */
  initialPath?: string | undefined;
}

/**
 * Own the current pathname from `window.location.pathname` and follow browser
 * history changes. No router dependency and no state in the URL beyond the path.
 */
export function RouterProvider({ children, patterns = [], initialPath }: RouterProviderProps) {
  const [path, setPath] = useState(() => initialPath ?? window.location.pathname);

  useEffect(() => {
    const onPopState = () => setPath(window.location.pathname);
    window.addEventListener('popstate', onPopState);
    return () => window.removeEventListener('popstate', onPopState);
  }, []);

  const value = useMemo<RouterContextValue>(() => {
    let params: RouteParams = {};
    for (const pattern of patterns) {
      const matched = matchRoute(pattern, path);
      if (matched !== null) {
        params = matched;
        break;
      }
    }
    return { path, params, patterns };
  }, [path, patterns]);

  return <RouterContext.Provider value={value}>{children}</RouterContext.Provider>;
}

/**
 * Read the current route. Outside a provider it falls back to the live
 * pathname with no parameters, so a shell rendered in isolation still works.
 */
export function useRoute(): Route {
  const context = useContext(RouterContext);
  if (context === null) {
    return { path: window.location.pathname, params: {} };
  }
  return { path: context.path, params: context.params };
}

/** Props for an in-application link that never reloads the document. */
export interface LinkProps {
  to: string;
  children: ReactNode;
  target?: string | undefined;
  ariaCurrent?: 'page' | undefined;
}

/**
 * Render a real anchor for keyboard, copy-link and middle-click behavior, and
 * intercept only plain left clicks that stay in the current tab.
 */
export function Link({ to, children, target, ariaCurrent }: LinkProps) {
  const onClick = (event: MouseEvent<HTMLAnchorElement>): void => {
    if (event.defaultPrevented) {
      return;
    }
    if (event.button !== 0) {
      return;
    }
    if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) {
      return;
    }
    if (target !== undefined && target !== '_self') {
      return;
    }
    event.preventDefault();
    navigate(to);
  };

  return (
    <a href={to} target={target} aria-current={ariaCurrent} onClick={onClick}>
      {children}
    </a>
  );
}
