import { useState } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { defined } from '../../../components/invariant';
import type { Locale } from '../../../i18n/messages';
import { I18nProvider } from '../../../i18n/I18nProvider';
import { BREAKPOINT_MEDIA_QUERIES, BREAKPOINT_WIDTHS } from '../../../theme/breakpoints';
import type { Breakpoint } from '../../../theme/breakpoints';
import { layoutText } from './layoutMessages';
import { MessageLayout } from './MessageLayout';
import type { MessageLayoutProps } from './MessageLayout';

/** Drive the real breakpoint hook through a controlled media-query stub. */
function forceBreakpoint(breakpoint: Breakpoint): void {
  vi.stubGlobal('matchMedia', (query: string) => {
    const matches =
      breakpoint === 'compact'
        ? false
        : breakpoint === 'medium'
          ? query === BREAKPOINT_MEDIA_QUERIES.medium
          : true;
    return {
      matches,
      media: query,
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
    };
  });
}

/** Drive the same hook from a viewport width, including both edges. */
function forceWidth(width: number): void {
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches:
      query === BREAKPOINT_MEDIA_QUERIES.medium ? width >= BREAKPOINT_WIDTHS.medium : width >= BREAKPOINT_WIDTHS.expanded,
    media: query,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
  }));
}

/** Render the wrapper with synthetic, non-clinical content. */
function wrap(overrides: Partial<MessageLayoutProps> = {}, locale: Locale = 'en') {
  const props: MessageLayoutProps = {
    list: <button type="button">Open synthetic thread</button>,
    detail: <p>Synthetic thread body</p>,
    composer: <p>Synthetic composer</p>,
    detailOpen: false,
    onBack: vi.fn(),
    ...overrides,
  };
  const view = render(
    <I18nProvider initialLocale={locale}>
      <MessageLayout {...props} />
    </I18nProvider>,
  );
  return { ...view, props };
}

/** A realistic owner that keeps draft state while panes come and go. */
function LayoutHarness() {
  const [open, setOpen] = useState(false);
  const [draft, setDraft] = useState('');
  return (
    <MessageLayout
      list={
        <button type="button" onClick={() => setOpen(true)}>
          Open synthetic thread
        </button>
      }
      detail={<p>Synthetic thread body</p>}
      composer={
        <label>
          Reply
          <textarea value={draft} onChange={(event) => setDraft(event.target.value)} />
        </label>
      }
      detailOpen={open}
      onBack={() => setOpen(false)}
    />
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
  document.documentElement.removeAttribute('dir');
  document.documentElement.removeAttribute('lang');
});

describe('MessageLayout pane counts', () => {
  it('renders three labelled panes on desktop', () => {
    forceBreakpoint('expanded');
    const { container } = wrap();
    const root = defined(container.querySelector<HTMLElement>('section[data-breakpoint]'), 'layout root');
    expect(root.getAttribute('data-panes')).toBe('triple');
    expect(root.getAttribute('data-breakpoint')).toBe('expanded');
    expect(screen.getByRole('region', { name: layoutText('en', 'listPane') })).toBeTruthy();
    expect(screen.getByRole('region', { name: layoutText('en', 'detailPane') })).toBeTruthy();
    expect(screen.getByRole('region', { name: layoutText('en', 'composerPane') })).toBeTruthy();
    expect(screen.queryByRole('button', { name: layoutText('en', 'backToList') })).toBeNull();
  });

  it('renders two panes at the 768 px breakpoint with no horizontal scroll', () => {
    forceBreakpoint('medium');
    const { container } = wrap({ detailOpen: true });
    const root = defined(container.querySelector<HTMLElement>('section[data-breakpoint]'), 'layout root');
    expect(root.getAttribute('data-panes')).toBe('paired');
    expect(root.getAttribute('style')).toContain('max-inline-size: 100%');
    expect(root.getAttribute('style')).not.toContain('overflow-x');
    expect(container.querySelectorAll('[data-pane]')).toHaveLength(3);
    const detail = defined(container.querySelector<HTMLElement>('[data-pane="detail"]'), 'detail pane');
    const composer = defined(container.querySelector<HTMLElement>('[data-pane="composer"]'), 'composer');
    expect(detail.style.gridColumn).toBe('2');
    expect(composer.style.gridColumn).toBe('2');
    expect(composer.style.position).toBe('sticky');
    expect(screen.getByRole('region', { name: layoutText('en', 'listPane') })).toBeTruthy();
    expect(screen.getByRole('region', { name: layoutText('en', 'detailPane') })).toBeTruthy();
  });

  it('keeps the single-pane band below 768 px and the two-pane band at 768 px', () => {
    const expected: [number, Breakpoint, string][] = [
      [767, 'compact', 'single'],
      [768, 'medium', 'paired'],
      [1279, 'medium', 'paired'],
      [1280, 'expanded', 'triple'],
    ];
    for (const [width, breakpoint, shape] of expected) {
      forceWidth(width);
      const { container, unmount } = wrap({ detailOpen: true });
      const root = defined(container.querySelector<HTMLElement>('section[data-breakpoint]'), 'layout root');
      expect(root.getAttribute('data-breakpoint')).toBe(breakpoint);
      expect(root.getAttribute('data-panes')).toBe(shape);
      unmount();
    }
  });

  it('renders only the list pane before a selection on a narrow screen', () => {
    forceBreakpoint('compact');
    const { container } = wrap();
    const root = defined(container.querySelector<HTMLElement>('section[data-breakpoint]'), 'layout root');
    expect(root.getAttribute('data-panes')).toBe('single');
    expect(screen.getByRole('region', { name: layoutText('en', 'listPane') })).toBeTruthy();
    expect(screen.queryByRole('region', { name: layoutText('en', 'detailPane') })).toBeNull();
    expect(screen.queryByRole('region', { name: layoutText('en', 'composerPane') })).toBeNull();
    expect(screen.queryByRole('button', { name: layoutText('en', 'backToList') })).toBeNull();
  });

  it('hides the list and offers back once a narrow-screen detail is open', () => {
    forceBreakpoint('compact');
    const { props } = wrap({ detailOpen: true });
    expect(screen.queryByRole('region', { name: layoutText('en', 'listPane') })).toBeNull();
    expect(screen.getByRole('region', { name: layoutText('en', 'detailPane') })).toBeTruthy();
    const back = screen.getByRole('button', { name: layoutText('en', 'backToList') });
    expect(back.closest('[data-pane="detail"]')).not.toBeNull();
    expect(back.className).toContain('touch');
    fireEvent.click(back);
    expect(props.onBack).toHaveBeenCalledTimes(1);
  });
});

describe('MessageLayout behaviour', () => {
  it('restores the list and keeps typed work after the back action', () => {
    forceBreakpoint('compact');
    render(
      <I18nProvider initialLocale="en">
        <LayoutHarness />
      </I18nProvider>,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Open synthetic thread' }));
    fireEvent.change(screen.getByRole('textbox', { name: 'Reply' }), {
      target: { value: 'Synthetic draft retained' },
    });
    fireEvent.click(screen.getByRole('button', { name: layoutText('en', 'backToList') }));
    expect(screen.getByRole('region', { name: layoutText('en', 'listPane') })).toBeTruthy();
    expect(screen.queryByRole('region', { name: layoutText('en', 'detailPane') })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Open synthetic thread' }));
    expect(screen.getByRole('textbox', { name: 'Reply' })).toHaveProperty(
      'value',
      'Synthetic draft retained',
    );
  });

  it('docks the composer against the keyboard inset', () => {
    forceBreakpoint('compact');
    const { container } = wrap({ detailOpen: true });
    const composer = defined(container.querySelector<HTMLElement>('[data-pane="composer"]'), 'composer pane');
    const style = defined(composer.getAttribute('style'), 'composer style');
    const root = defined(container.querySelector<HTMLElement>('section[data-breakpoint]'), 'layout root');
    expect(style).toContain('position: sticky');
    expect(style).toContain('inset-block-end');
    expect(style).toContain('safe-block-end');
    expect(root.getAttribute('style')).toContain('scroll-padding-block-end');
    expect(composer.parentElement).toBe(root);
  });

  it('labels the back action and panes in Arabic', () => {
    forceBreakpoint('compact');
    wrap({ detailOpen: true }, 'ar');
    expect(screen.getByRole('button', { name: layoutText('ar', 'backToList') })).toBeTruthy();
    expect(screen.getByRole('region', { name: layoutText('ar', 'detailPane') })).toBeTruthy();
    expect(screen.getByRole('region', { name: layoutText('ar', 'composerPane') })).toBeTruthy();
    expect(document.documentElement.dir).toBe('rtl');
  });
});
