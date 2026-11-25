import { act, fireEvent, render, screen } from '@testing-library/react';
import { useState } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { defined } from '../../../components/invariant';
import { I18nProvider } from '../../../i18n/I18nProvider';
import { ThemeProvider } from '../../../theme/ThemeProvider';
import { layoutText } from './labels';
import { MessagePaneLayout } from './MessagePaneLayout';
import type { MessagePaneLayoutProps } from './MessagePaneLayout';

/** Synthetic pane content; no real message, identity or file name is used. */
const auxiliary = <p data-testid="synthetic-auxiliary">Synthetic folder navigation</p>;
const list = <p data-testid="synthetic-list">Synthetic conversation row</p>;
const detail = <p data-testid="synthetic-detail">Synthetic thread body</p>;
const composer = <p data-testid="synthetic-composer">Synthetic composer</p>;

/** Render the wrapper under the shared providers at a chosen locale. */
function wrap(overrides: Partial<MessagePaneLayoutProps> = {}, locale: 'en' | 'ar' = 'en') {
  const props: MessagePaneLayoutProps = {
    width: 320,
    hasDetail: false,
    onBack: vi.fn(),
    auxiliary,
    list,
    detail,
    composer,
    ...overrides,
  };
  return {
    ...render(
      <ThemeProvider>
        <I18nProvider initialLocale={locale}>
          <MessagePaneLayout {...props} />
        </I18nProvider>
      </ThemeProvider>,
    ),
    props,
  };
}

/** The whole tree for one width, so a rerender reuses the same component instance. */
function composedView(width: number, content = composer) {
  return (
    <ThemeProvider>
      <I18nProvider initialLocale="en">
        <MessagePaneLayout
          width={width}
          hasDetail={false}
          onBack={vi.fn()}
          auxiliary={auxiliary}
          list={list}
          detail={detail}
          composer={content}
        />
      </I18nProvider>
    </ThemeProvider>
  );
}

/** Stateful owner that models a list selection, a Back action and a live draft. */
function BackHarness() {
  const [hasDetail, setHasDetail] = useState(false);
  const [selected, setSelected] = useState('');
  const [text, setText] = useState('Synthetic draft');
  return (
    <ThemeProvider>
      <I18nProvider initialLocale="en">
        <MessagePaneLayout
          width={320}
          hasDetail={hasDetail}
          onBack={() => setHasDetail(false)}
          auxiliary={auxiliary}
          list={
            <button
              type="button"
              aria-current={selected === '' ? undefined : 'true'}
              onClick={() => {
                setSelected('synthetic-selection');
                setHasDetail(true);
              }}
            >
              Synthetic conversation row
            </button>
          }
          detail={detail}
          composer={<textarea aria-label="Synthetic composer" value={text} onChange={(event) => setText(event.target.value)} />}
        />
      </I18nProvider>
    </ThemeProvider>
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('MessagePaneLayout pane counts', () => {
  it('renders one pane and a drawer toggle below 768 px', () => {
    wrap({ width: 767 });
    expect(screen.getByRole('region', { name: layoutText('en', 'listPane') })).toBeTruthy();
    expect(screen.queryByRole('region', { name: layoutText('en', 'detailPane') })).toBeNull();
    expect(screen.getByText('Synthetic folder navigation')).toBeTruthy();
    expect(screen.getByRole('button', { name: layoutText('en', 'drawerToggle') })).toBeTruthy();
    expect(screen.queryByRole('button', { name: layoutText('en', 'back') })).toBeNull();
    expect(document.querySelector('[data-breakpoint]')?.getAttribute('data-breakpoint')).toBe('compact');
  });

  it('renders two panes at 768 px and keeps the auxiliary pane reachable in the drawer', () => {
    wrap({ width: 768 });
    expect(screen.getByRole('region', { name: layoutText('en', 'listPane') })).toBeTruthy();
    expect(screen.getByRole('region', { name: layoutText('en', 'detailPane') })).toBeTruthy();
    const drawer = defined(document.querySelector('[data-message-drawer]'), 'synthetic drawer');
    const root = defined(document.querySelector('[data-breakpoint]'), 'synthetic root');
    expect(drawer.hasAttribute('hidden')).toBe(true);
    expect(root.getAttribute('data-drawer-open')).toBe('false');
    const toggle = screen.getByRole('button', { name: layoutText('en', 'drawerToggle') });
    fireEvent.click(toggle);
    expect(drawer.hasAttribute('hidden')).toBe(false);
    expect(root.getAttribute('data-drawer-open')).toBe('true');
    expect(screen.getByText('Synthetic folder navigation')).toBeTruthy();
    expect(document.activeElement).toBe(screen.getByRole('button', { name: layoutText('en', 'drawerClose') }));
    fireEvent.click(screen.getByRole('button', { name: layoutText('en', 'drawerClose') }));
    expect(drawer.hasAttribute('hidden')).toBe(true);
    expect(document.activeElement).toBe(toggle);
  });

  it('renders all three panes and no drawer toggle at 1280 px', () => {
    wrap({ width: 1280 });
    expect(screen.getByRole('region', { name: layoutText('en', 'auxiliaryPane') })).toBeTruthy();
    expect(screen.getByRole('region', { name: layoutText('en', 'listPane') })).toBeTruthy();
    expect(screen.getByRole('region', { name: layoutText('en', 'detailPane') })).toBeTruthy();
    expect(screen.queryByRole('button', { name: layoutText('en', 'drawerToggle') })).toBeNull();
    expect(document.querySelector('[data-message-drawer]')).toBeNull();
    expect(document.querySelector('[data-message-scrim]')).toBeNull();
    expect(document.querySelector('[data-breakpoint]')?.getAttribute('data-breakpoint')).toBe('expanded');
  });

  it('opens the drawer to reach the list while a compact detail is active', () => {
    wrap({ width: 320, hasDetail: true });
    expect(screen.queryByRole('region', { name: layoutText('en', 'listPane') })).toBeNull();
    expect(screen.getByRole('button', { name: layoutText('en', 'back') })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: layoutText('en', 'drawerToggle') }));
    expect(screen.getByRole('region', { name: layoutText('en', 'listPane') })).toBeTruthy();
  });

  it('closes the drawer when a breakpoint change removes it', () => {
    const { rerender } = render(composedView(320));
    fireEvent.click(screen.getByRole('button', { name: layoutText('en', 'drawerToggle') }));
    const root = defined(document.querySelector('[data-breakpoint]'), 'synthetic root');
    expect(root.getAttribute('data-drawer-open')).toBe('true');
    rerender(composedView(1280));
    expect(document.querySelector('[data-message-drawer]')).toBeNull();
    expect(root.getAttribute('data-drawer-open')).toBe('false');
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(root.getAttribute('data-drawer-open')).toBe('false');
  });

  it('does not reopen the drawer or move focus when a width returns', () => {
    const { rerender } = render(composedView(320));
    fireEvent.click(screen.getByRole('button', { name: layoutText('en', 'drawerToggle') }));
    const closeButton = defined(document.querySelector('[data-message-drawer] button'), 'close control');
    expect(document.activeElement).toBe(closeButton);
    rerender(composedView(1280));
    rerender(composedView(767));
    expect(document.querySelector('[data-breakpoint]')?.getAttribute('data-drawer-open')).toBe('false');
    expect(defined(document.querySelector('[data-message-drawer]'), 'drawer').hasAttribute('hidden')).toBe(true);
    expect(document.activeElement).not.toBe(closeButton);
  });

  it('keeps the drawer closed if the hidden toggle is activated at expanded width', () => {
    wrap({ width: 1280 });
    const hiddenToggle = defined(
      Array.from(document.querySelectorAll('button')).find(
        (button) => button.textContent === layoutText('en', 'drawerToggle'),
      ),
      'hidden drawer toggle',
    );
    fireEvent.click(hiddenToggle);
    expect(document.querySelector('[data-breakpoint]')?.getAttribute('data-drawer-open')).toBe('false');
    expect(document.querySelector('[data-message-drawer]')).toBeNull();
  });

  it('ignores Escape while the drawer is closed', () => {
    wrap({ width: 768 });
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(defined(document.querySelector('[data-message-drawer]'), 'drawer').hasAttribute('hidden')).toBe(true);
    expect(document.querySelector('[data-breakpoint]')?.getAttribute('data-drawer-open')).toBe('false');
  });

  it('falls back to the viewport hook when no width is supplied', () => {
    const props: MessagePaneLayoutProps = {
      hasDetail: false,
      onBack: vi.fn(),
      auxiliary,
      list,
      detail,
      composer,
    };
    render(
      <ThemeProvider>
        <I18nProvider initialLocale="en">
          <MessagePaneLayout {...props} />
        </I18nProvider>
      </ThemeProvider>,
    );
    expect(document.querySelector('[data-breakpoint]')?.getAttribute('data-breakpoint')).toBe('expanded');
  });
});

describe('MessagePaneLayout compact navigation', () => {
  it('calls Back and restores the list, its selection, draft and focus', () => {
    const { props } = wrap({ width: 320, hasDetail: true });
    fireEvent.click(screen.getByRole('button', { name: layoutText('en', 'back') }));
    expect(props.onBack).toHaveBeenCalledTimes(1);
  });

  it('returns to the list without clearing selected or typed work', () => {
    render(<BackHarness />);
    fireEvent.click(screen.getByRole('button', { name: 'Synthetic conversation row' }));
    expect(screen.queryByRole('region', { name: layoutText('en', 'listPane') })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: layoutText('en', 'back') }));
    const restored = screen.getByRole('region', { name: layoutText('en', 'listPane') });
    expect(restored).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Synthetic conversation row' }).getAttribute('aria-current')).toBe('true');
    expect(screen.getByLabelText('Synthetic composer')).toHaveProperty('value', 'Synthetic draft');
    expect(document.activeElement).toBe(restored);
  });

  it('closes the drawer when Back clears the detail', () => {
    render(<BackHarness />);
    fireEvent.click(screen.getByRole('button', { name: 'Synthetic conversation row' }));
    fireEvent.click(screen.getByRole('button', { name: layoutText('en', 'drawerToggle') }));
    expect(document.querySelector('[data-breakpoint]')?.getAttribute('data-drawer-open')).toBe('true');
    fireEvent.click(screen.getByRole('button', { name: layoutText('en', 'back') }));
    expect(document.querySelector('[data-breakpoint]')?.getAttribute('data-drawer-open')).toBe('false');
  });

  it('closes the drawer from the scrim and returns focus to the toggle', () => {
    wrap({ width: 768 });
    const toggle = screen.getByRole('button', { name: layoutText('en', 'drawerToggle') });
    fireEvent.click(toggle);
    fireEvent.click(screen.getByRole('button', { name: layoutText('en', 'scrimClose') }));
    expect(defined(document.querySelector('[data-message-drawer]'), 'drawer').hasAttribute('hidden')).toBe(true);
    expect(document.activeElement).toBe(toggle);
  });

  it('closes the drawer with Escape and returns focus to the toggle in Arabic', () => {
    wrap({ width: 320, hasDetail: true }, 'ar');
    expect(screen.getByRole('button', { name: layoutText('ar', 'back') })).toBeTruthy();
    const toggle = screen.getByRole('button', { name: layoutText('ar', 'drawerToggle') });
    fireEvent.click(toggle);
    const drawer = defined(document.querySelector('[data-message-drawer]'), 'Arabic drawer');
    expect(document.activeElement).toBe(screen.getByRole('button', { name: layoutText('ar', 'drawerClose') }));
    fireEvent.keyDown(drawer, { key: 'Tab' });
    expect(drawer.hasAttribute('hidden')).toBe(false);
    fireEvent.keyDown(drawer, { key: 'Escape' });
    expect(drawer.hasAttribute('hidden')).toBe(true);
    expect(document.activeElement).toBe(toggle);
    expect(document.documentElement.dir).toBe('rtl');
  });
});

describe('MessagePaneLayout keyboard and reflow attributes', () => {
  it('reserves keyboard space and keeps the composer sticky at 768 px', () => {
    wrap({ width: 768 });
    const composerRegion = screen.getByTestId('message-layout-composer');
    expect(composerRegion.style.position).toBe('sticky');
    expect(composerRegion.style.insetBlockEnd).toBe('0px');
    expect(composerRegion.style.scrollMarginBlockEnd).toBe('16px');
    const panes = defined(document.querySelector('[data-message-panes]'), 'synthetic panes');
    expect(panes.getAttribute('style')).toContain('grid-template-columns: minmax(0, 1fr) minmax(0, 1fr)');
    const root = defined(document.querySelector('[data-breakpoint]'), 'synthetic root');
    expect(root.getAttribute('style')).toContain('max-block-size: 100dvh');
    expect(root.getAttribute('style')).toContain('overflow-block: auto');
    expect(root.getAttribute('style')).toContain('overflow-x: clip');
    expect(root.getAttribute('style')).toContain('scroll-padding-block-end: 16px');
    expect(root.getAttribute('style')).toContain('inline-size: 100%');
    for (const pane of document.querySelectorAll('[data-pane]')) {
      expect(pane.getAttribute('style')).toContain('min-inline-size: 0');
    }
  });

  it('keeps typed work, selection position and focus across a resize', () => {
    const textarea = <textarea aria-label="Synthetic composer" />;
    const { rerender } = render(composedView(767, textarea));
    const field = screen.getByLabelText<HTMLTextAreaElement>('Synthetic composer');
    fireEvent.change(field, { target: { value: 'Synthetic draft' } });
    field.focus();
    field.setSelectionRange(3, 7);
    rerender(composedView(1280, textarea));
    act(() => {
      window.dispatchEvent(new Event('resize'));
    });
    expect(screen.getByLabelText('Synthetic composer')).toBe(field);
    expect(field).toHaveProperty('value', 'Synthetic draft');
    expect(field.selectionStart).toBe(3);
    expect(document.activeElement).toBe(field);
  });
});
