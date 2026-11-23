import { afterEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { ThemeProvider } from '../../src/theme/ThemeProvider';
import { BREAKPOINT_MEDIA_QUERIES } from '../../src/theme/breakpoints';
import { Dialog } from '../../src/components/Dialog';
import { must } from '../must';

type ChangeListener = (event: MediaQueryListEvent) => void;

/**
 * Install a deterministic `matchMedia` stand-in for the two breakpoint queries
 * that the responsive hook reads. jsdom cannot evaluate media queries, so the
 * mocked answers drive the layout class the component reports.
 */
function installMatchMedia(medium: boolean, expanded: boolean): void {
  const answers: Record<string, boolean> = {
    [BREAKPOINT_MEDIA_QUERIES.medium]: medium,
    [BREAKPOINT_MEDIA_QUERIES.expanded]: expanded,
  };
  vi.stubGlobal(
    'matchMedia',
    vi.fn((query: string) => {
      const current = {
        matches: answers[query] ?? false,
        listeners: new Set<ChangeListener>(),
      };
      return {
        matches: current.matches,
        addEventListener: (_type: string, listener: ChangeListener) => {
          current.listeners.add(listener);
        },
        removeEventListener: (_type: string, listener: ChangeListener) => {
          current.listeners.delete(listener);
        },
      };
    }),
  );
}

const physicalStyleKeys = [
  'marginLeft',
  'marginRight',
  'marginTop',
  'marginBottom',
  'paddingLeft',
  'paddingRight',
  'paddingTop',
  'paddingBottom',
  'borderLeft',
  'borderRight',
  'borderTop',
  'borderBottom',
  'left',
  'right',
  'top',
  'bottom',
];

function renderDialog(children: React.ReactNode = <span>synthetic detail</span>) {
  return render(
    <ThemeProvider>
      <Dialog open title="Details" closeLabel="Close dialog" onClose={() => undefined}>
        {children}
      </Dialog>
    </ThemeProvider>,
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
  document.documentElement.removeAttribute('dir');
  document.documentElement.removeAttribute('lang');
});

describe('responsive Dialog across breakpoints', () => {
  it('renders a full-screen sheet with sticky header and actions on compact', () => {
    installMatchMedia(false, false);
    renderDialog();

    const dialog = screen.getByRole('dialog', { name: 'Details' });
    expect(dialog.className).toContain('sheet');
    expect(dialog.getAttribute('data-breakpoint')).toBe('compact');
    expect(dialog.getAttribute('data-dialog-view')).toBe('sheet');

    const header = must(dialog.querySelector<HTMLElement>('[data-dialog-region="header"]'));
    const actions = must(dialog.querySelector<HTMLElement>('[data-dialog-region="actions"]'));
    expect(header).toBeTruthy();
    expect(actions).toBeTruthy();
    expect(header.style.position).toBe('sticky');
    expect(header.style.insetBlockStart).toBe('0px');
    expect(actions.style.position).toBe('sticky');
    expect(actions.style.insetBlockEnd).toBe('0px');
    expect(dialog.style.scrollPaddingBlockStart).not.toBe('');
    expect(dialog.style.scrollPaddingBlockEnd).not.toBe('');
  });

  it('renders a centred dialog without sticky chrome on medium', () => {
    installMatchMedia(true, false);
    renderDialog();

    const dialog = screen.getByRole('dialog', { name: 'Details' });
    expect(dialog.className).toContain('sheet');
    expect(dialog.getAttribute('data-breakpoint')).toBe('medium');
    expect(dialog.getAttribute('data-dialog-view')).toBe('centered');

    const header = must(dialog.querySelector<HTMLElement>('[data-dialog-region="header"]'));
    const actions = must(dialog.querySelector<HTMLElement>('[data-dialog-region="actions"]'));
    expect(header.style.position).toBe('');
    expect(actions.style.position).toBe('');
    expect(dialog.style.scrollPaddingBlockStart).toBe('');
    expect(dialog.style.scrollPaddingBlockEnd).toBe('');
  });

  it('renders a centred dialog on expanded', () => {
    installMatchMedia(true, true);
    renderDialog();

    const dialog = screen.getByRole('dialog', { name: 'Details' });
    expect(dialog.getAttribute('data-breakpoint')).toBe('expanded');
    expect(dialog.getAttribute('data-dialog-view')).toBe('centered');
    expect(screen.getByText('synthetic detail')).toBeTruthy();
  });

  it('keeps labels, roles and a visible touch-sized close control', () => {
    installMatchMedia(false, false);
    renderDialog();

    const dialog = screen.getByRole('dialog', { name: 'Details' });
    expect(dialog.getAttribute('aria-modal')).toBe('true');
    expect(dialog.getAttribute('aria-labelledby')).toBe('staff-dialog-title');
    expect(screen.getByRole('heading', { name: 'Details' })).toBeTruthy();

    const closeButton = screen.getByRole('button', { name: 'Close dialog' });
    expect(closeButton.className).toContain('touch');
    expect(closeButton.getAttribute('type')).toBe('button');
    expect(closeButton.hidden).toBe(false);
  });

  it('uses logical properties and never hides content with display:none', () => {
    installMatchMedia(false, false);
    renderDialog();

    const dialog = screen.getByRole('dialog', { name: 'Details' });
    const styled = Array.from(dialog.querySelectorAll<HTMLElement>('[style]')).concat(dialog);
    for (const element of styled) {
      for (const key of physicalStyleKeys) {
        expect(element.style.getPropertyValue(key)).toBe('');
      }
      expect(element.style.display).not.toBe('none');
    }
    expect(dialog.style.overflowWrap).toBe('anywhere');
    const content = must(dialog.querySelector<HTMLElement>('.stack'));
    expect(content).toBeTruthy();
    expect(content.textContent).toContain('synthetic detail');
  });

  it('locks page scroll while open and restores it on unmount', () => {
    installMatchMedia(false, false);
    document.body.style.overflow = 'scroll';
    const view = renderDialog();
    expect(document.body.style.overflow).toBe('hidden');
    view.unmount();
    expect(document.body.style.overflow).toBe('scroll');
    document.body.style.overflow = '';
  });

  it('does not touch scroll state when closed', () => {
    installMatchMedia(false, false);
    document.body.style.overflow = 'scroll';
    render(
      <ThemeProvider>
        <Dialog open={false} title="Details" closeLabel="Close dialog" onClose={() => undefined}>
          <span>synthetic detail</span>
        </Dialog>
      </ThemeProvider>,
    );
    expect(document.body.style.overflow).toBe('scroll');
    expect(screen.queryByRole('dialog')).toBeNull();
    document.body.style.overflow = '';
  });
});

describe('responsive Dialog keyboard order and right-to-left', () => {
  it('traps Tab inside the dialog and cycles through focusable children', () => {
    installMatchMedia(false, false);
    renderDialog(
      <>
        <input aria-label="First field" />
        <button type="button">Middle action</button>
      </>,
    );

    const first = screen.getByLabelText('First field');
    const middle = screen.getByRole('button', { name: 'Middle action' });
    const close = screen.getByRole('button', { name: 'Close dialog' });

    expect(document.activeElement).toBe(close);

    // Tab on the last control wraps to the first focusable child.
    expect(fireEvent.keyDown(document, { key: 'Tab' })).toBe(false);
    expect(document.activeElement).toBe(first);

    // Shift+Tab on the first control wraps back to the last.
    expect(fireEvent.keyDown(document, { key: 'Tab', shiftKey: true })).toBe(false);
    expect(document.activeElement).toBe(close);

    // A control in the middle leaves Tab to the browser (no wrap).
    middle.focus();
    expect(fireEvent.keyDown(document, { key: 'Tab' })).toBe(true);
    expect(fireEvent.keyDown(document, { key: 'Tab', shiftKey: true })).toBe(true);
  });

  it('closes on Escape and restores focus to the opener', () => {
    installMatchMedia(false, false);
    const opener = document.createElement('button');
    document.body.appendChild(opener);
    opener.focus();
    const onClose = vi.fn();
    const view = render(
      <ThemeProvider>
        <Dialog open title="Details" closeLabel="Close dialog" onClose={onClose}>
          <span>synthetic detail</span>
        </Dialog>
      </ThemeProvider>,
    );
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Close dialog' }));
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(onClose).toHaveBeenCalledTimes(1);
    view.unmount();
    expect(document.activeElement).toBe(opener);
    opener.remove();
  });

  it('mirrors under right-to-left without a local direction override', () => {
    installMatchMedia(false, false);
    document.documentElement.dir = 'rtl';
    document.documentElement.lang = 'ar';
    renderDialog(<span>تفاصيل تجريبية طويلة للاختبار</span>);

    const dialog = screen.getByRole('dialog', { name: 'Details' });
    expect(dialog.getAttribute('data-dialog-view')).toBe('sheet');
    const header = must(dialog.querySelector<HTMLElement>('[data-dialog-region="header"]'));
    const actions = must(dialog.querySelector<HTMLElement>('[data-dialog-region="actions"]'));
    expect(header.style.paddingInline).not.toBe('');
    expect(header.style.borderBlockEnd).not.toBe('');
    expect(actions.style.paddingInline).not.toBe('');
    expect(actions.style.borderBlockStart).not.toBe('');

    const styled = Array.from(dialog.querySelectorAll<HTMLElement>('[style]')).concat(dialog);
    for (const element of styled) {
      expect(element.style.direction).toBe('');
      for (const key of physicalStyleKeys) {
        expect(element.style.getPropertyValue(key)).toBe('');
      }
    }
  });
});
