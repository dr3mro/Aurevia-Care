import { act, fireEvent, render, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Link, matchRoute, navigate, RouterProvider, useRoute } from '../../src/app/router';

let suppressAnchorNavigation: ((event: MouseEvent) => void) | undefined;

beforeEach(() => {
  // jsdom has no real navigation; cancel the native anchor default after the
  // React handler so modifier-click tests do not schedule a page load.
  suppressAnchorNavigation = (event: MouseEvent) => {
    if (event.target instanceof Element && event.target.closest('a') !== null) {
      event.preventDefault();
    }
  };
  document.addEventListener('click', suppressAnchorNavigation);
});

afterEach(() => {
  if (suppressAnchorNavigation !== undefined) {
    document.removeEventListener('click', suppressAnchorNavigation);
  }
  vi.restoreAllMocks();
  window.history.replaceState(null, '', '/');
});

function RouteProbe() {
  const route = useRoute();
  return <output data-testid="route">{`${route.path}|${JSON.stringify(route.params)}`}</output>;
}

function renderedPath(): string {
  return screen.getByTestId('route').textContent;
}

function renderAt(
  children: ReactNode,
  options?: { patterns?: readonly string[]; initialPath?: string },
) {
  return render(
    <RouterProvider patterns={options?.patterns} initialPath={options?.initialPath}>
      {children}
    </RouterProvider>,
  );
}

describe('matchRoute (pure matcher)', () => {
  it('matches an exact path with no parameters', () => {
    expect(matchRoute('/schedule', '/schedule')).toEqual({});
    expect(matchRoute('/', '/')).toEqual({});
  });

  it('extracts one and several :param segments', () => {
    expect(matchRoute('/patients/:id', '/patients/abc')).toEqual({ id: 'abc' });
    expect(matchRoute('/patients/:id/notes/:noteId', '/patients/abc/notes/n1')).toEqual({
      id: 'abc',
      noteId: 'n1',
    });
  });

  it('refuses a different segment count, a literal mismatch and an empty parameter', () => {
    expect(matchRoute('/patients/:id', '/patients/abc/chart')).toBeNull();
    expect(matchRoute('/patients/:id/chart', '/patients/abc')).toBeNull();
    expect(matchRoute('/patients/:id', '/orders/abc')).toBeNull();
    expect(matchRoute('/patients/:id', '/patients/')).toBeNull();
  });
});

describe('RouterProvider and useRoute', () => {
  it('uses the initial path and returns parameters of the first matching pattern', () => {
    renderAt(<RouteProbe />, {
      initialPath: '/patients/p1/notes/n2',
      patterns: ['/patients/:id', '/patients/:id/notes/:noteId'],
    });
    expect(renderedPath()).toBe('/patients/p1/notes/n2|{"id":"p1","noteId":"n2"}');
  });

  it('returns no parameters when no pattern matches', () => {
    renderAt(<RouteProbe />, { initialPath: '/unknown', patterns: ['/schedule'] });
    expect(renderedPath()).toBe('/unknown|{}');
  });

  it('reads window.location.pathname and tolerates an absent provider', () => {
    window.history.replaceState(null, '', '/from-location');
    render(<RouteProbe />);
    expect(renderedPath()).toBe('/from-location|{}');
    expect(window.location.pathname).toBe('/from-location');
  });
});

describe('Link and navigate', () => {
  it('navigates on a plain left click without a document load', () => {
    const pushState = vi.spyOn(window.history, 'pushState');
    renderAt(
      <>
        <Link to="/design-preview">Preview</Link>
        <RouteProbe />
      </>,
    );

    fireEvent.click(screen.getByRole('link', { name: 'Preview' }));

    expect(pushState).toHaveBeenCalledWith(null, '', '/design-preview');
    expect(window.location.pathname).toBe('/design-preview');
    expect(renderedPath()).toBe('/design-preview|{}');
  });

  it('renders an anchor with the destination in href', () => {
    renderAt(<Link to="/schedule">Schedule</Link>);
    expect(screen.getByRole('link', { name: 'Schedule' }).getAttribute('href')).toBe('/schedule');
  });

  it('follows a popstate event from the browser back button', () => {
    renderAt(<RouteProbe />, { initialPath: '/', patterns: [] });
    window.history.replaceState(null, '', '/back-target');
    fireEvent.popState(window);
    expect(renderedPath()).toBe('/back-target|{}');
  });

  it('lets the exported navigate update the provider through popstate', () => {
    renderAt(<RouteProbe />);
    act(() => navigate('/via-action'));
    expect(renderedPath()).toBe('/via-action|{}');
  });

  it.each([
    ['metaKey', { metaKey: true }],
    ['ctrlKey', { ctrlKey: true }],
    ['shiftKey', { shiftKey: true }],
    ['altKey', { altKey: true }],
  ])('does not navigate with %s held', (_name, init) => {
    renderAt(
      <>
        <Link to="/blocked">Blocked</Link>
        <RouteProbe />
      </>,
    );
    fireEvent.click(screen.getByRole('link', { name: 'Blocked' }), init);
    expect(window.location.pathname).toBe('/');
    expect(renderedPath()).toBe('/|{}');
  });

  it('does not navigate for a non-primary mouse button', () => {
    renderAt(
      <>
        <Link to="/blocked">Blocked</Link>
        <RouteProbe />
      </>,
    );
    fireEvent.click(screen.getByRole('link', { name: 'Blocked' }), { button: 1 });
    expect(window.location.pathname).toBe('/');
  });

  it('does not navigate when the click was already prevented', () => {
    renderAt(
      <div onClickCapture={(event) => event.preventDefault()}>
        <Link to="/blocked">Blocked</Link>
        <RouteProbe />
      </div>,
    );
    fireEvent.click(screen.getByRole('link', { name: 'Blocked' }));
    expect(window.location.pathname).toBe('/');
  });

  it('does not navigate for a new-tab target but does for an explicit self target', () => {
    renderAt(
      <>
        <Link to="/new-tab" target="_blank">
          New tab
        </Link>
        <Link to="/self" target="_self">
          Self
        </Link>
        <RouteProbe />
      </>,
    );
    fireEvent.click(screen.getByRole('link', { name: 'New tab' }));
    expect(window.location.pathname).toBe('/');
    fireEvent.click(screen.getByRole('link', { name: 'Self' }));
    expect(window.location.pathname).toBe('/self');
  });
});
