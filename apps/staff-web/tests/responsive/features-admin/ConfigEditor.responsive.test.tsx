import type { ReactElement } from 'react';
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ConfigEditor } from '../../../src/features/admin/ConfigEditor';
import type { ConfigurationVersion } from '../../../src/features/admin/domain';
import {
  adminAnchorCivilDate,
  adminConfigurationKey,
  alphaOrganizationId,
  syntheticConfigurations,
} from '../../../src/features/admin/fixtures';
import { adminText } from '../../../src/features/admin/messages';
import { I18nProvider } from '../../../src/i18n/I18nProvider';
import type { Locale } from '../../../src/i18n/messages';
import { BREAKPOINT_MEDIA_QUERIES } from '../../../src/theme/breakpoints';
import { ThemeProvider } from '../../../src/theme/ThemeProvider';

type Band = 'compact' | 'medium' | 'expanded';
type ChangeListener = (event: MediaQueryListEvent) => void;

interface QueryState {
  matches: boolean;
  listeners: Set<ChangeListener>;
}

const bandQueries: Record<Band, { medium: boolean; expanded: boolean }> = {
  compact: { medium: false, expanded: false },
  medium: { medium: true, expanded: false },
  expanded: { medium: true, expanded: true },
};

/**
 * Deterministic `matchMedia` stand-in keyed by query string. One state object
 * per query is shared by the hook's initializer and effect so a fired change
 * follows the real MediaQueryList lifecycle. All values are synthetic.
 */
function installMatchMedia(band: Band, coarse = false) {
  const initial: Record<string, boolean> = {
    [BREAKPOINT_MEDIA_QUERIES.medium]: bandQueries[band].medium,
    [BREAKPOINT_MEDIA_QUERIES.expanded]: bandQueries[band].expanded,
    '(pointer: coarse)': coarse,
  };
  const states = new Map<string, QueryState>();
  const state = (query: string): QueryState => {
    let current = states.get(query);
    if (!current) {
      current = { matches: initial[query] ?? false, listeners: new Set() };
      states.set(query, current);
    }
    return current;
  };
  vi.stubGlobal(
    'matchMedia',
    vi.fn((query: string) => ({
      get matches() {
        return state(query).matches;
      },
      media: query,
      onchange: null,
      addEventListener: (_type: string, listener: ChangeListener) => {
        state(query).listeners.add(listener);
      },
      removeEventListener: (_type: string, listener: ChangeListener) => {
        state(query).listeners.delete(listener);
      },
      addListener: () => undefined,
      removeListener: () => undefined,
      dispatchEvent: () => false,
    })),
  );
  return {
    fire(query: string, matches: boolean) {
      const current = state(query);
      current.matches = matches;
      for (const listener of current.listeners) {
        listener({ matches } as MediaQueryListEvent);
      }
    },
  };
}

/** Render the editor inside the shell's providers; synthetic fixtures only. */
function wrap(ui: ReactElement, locale: Locale = 'en') {
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>{ui}</I18nProvider>
    </ThemeProvider>,
  );
}

const baseProps = {
  entries: syntheticConfigurations,
  organizationId: alphaOrganizationId,
  configKey: adminConfigurationKey,
  anchorCivilDate: adminAnchorCivilDate,
};

function styleOf(element: Element | null): string {
  return element?.getAttribute('style') ?? '';
}

/** Assert an element uses logical (never physical side) inline styling. */
function expectLogicalOnly(element: Element | null): void {
  const style = styleOf(element);
  expect(style).not.toMatch(/\b(?:width|height|left|right|top|bottom)\s*:/);
  expect(style).not.toMatch(/\b(?:margin|padding|border|inset)-(?:left|right|top|bottom)\s*:/);
  expect(style).not.toMatch(/text-align\s*:\s*(?:left|right)/);
  expect(style).not.toContain('direction');
  expect(style).not.toContain('display: none');
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('responsive ConfigEditor (WEB-07, UX-01)', () => {
  it('keeps one labelled, single-column, keyboard-scrollable structure at every band', () => {
    for (const band of ['compact', 'medium', 'expanded'] as const) {
      installMatchMedia(band);
      const view = wrap(<ConfigEditor {...baseProps} onPropose={vi.fn()} />);

      const editor = screen.getByRole('region', {
        name: adminText('en', 'configurationEditor'),
      });
      expect(editor.getAttribute('data-part')).toBe('config-editor');
      expect(editor.getAttribute('data-breakpoint')).toBe(band);
      expect(editor.classList.contains('stack')).toBe(true);

      const table = screen.getByRole('table', {
        name: adminText('en', 'configurationVersions'),
      });
      expect(table.getAttribute('data-part')).toBe('versions-table');
      expect(table.getAttribute('data-breakpoint')).toBe(band);
      // Only a labelled, keyboard-focusable region may scroll inline; the page
      // itself never gains a horizontal scrollbar and no column is hidden.
      const scrollRegion = table.closest('[role="region"]');
      expect(scrollRegion?.getAttribute('tabindex')).toBe('0');
      expect(scrollRegion?.getAttribute('aria-label')).toBe(
        adminText('en', 'configurationVersions'),
      );
      // The labelled region owns the inline overflow, so the page never scrolls
      // horizontally and every clinical column stays reachable by keyboard.
      expect(styleOf(scrollRegion)).toContain('overflow-x');
      expect(within(table).getAllByRole('columnheader')).toHaveLength(5);
      expect(within(table).getAllByRole('rowheader')).toHaveLength(4);
      expect(styleOf(table)).not.toContain('display: none');

      const form = screen.getByRole('form', { name: adminText('en', 'configurationEditor') });
      expect(form.classList.contains('page')).toBe(true);
      expect(form.getAttribute('data-breakpoint')).toBe(band);
      expect(form.querySelector('[data-part="fields"]')?.getAttribute('data-layout')).toBe(
        'single-column',
      );
      view.unmount();
    }
  });

  it('stacks both draft fields with 16px text and coarse-pointer targets', () => {
    installMatchMedia('compact', true);
    wrap(<ConfigEditor {...baseProps} onPropose={vi.fn()} />);

    const form = screen.getByRole('form', { name: adminText('en', 'configurationEditor') });
    // Form owns the sticky footer and logical scroll reservation on a phone.
    expect(styleOf(form)).toContain('scroll-padding-block-end');
    expect(styleOf(form.querySelector('[data-part="actions"]'))).toContain('position: sticky');

    for (const label of [adminText('en', 'proposedValue'), adminText('en', 'effectiveFrom')]) {
      const input = screen.getByLabelText(label) as HTMLInputElement;
      expect(input.classList.contains('field')).toBe(true);
      expect(input.classList.contains('touch')).toBe(true);
      const field = input.closest('.stack');
      expect(field).not.toBeNull();
      expect(field?.getAttribute('data-breakpoint')).toBe('compact');
      expect(field?.getAttribute('data-coarse-pointer')).toBe('true');
      expectLogicalOnly(input);
    }
  });

  it('keeps approve actions reachable in a stable keyboard order', () => {
    installMatchMedia('compact');
    const onApprove = vi.fn();
    wrap(<ConfigEditor {...baseProps} onPropose={vi.fn()} onApprove={onApprove} />);

    const approveButtons = screen.getAllByRole('button', { name: adminText('en', 'approve') });
    expect(approveButtons).toHaveLength(1);
    for (const button of approveButtons) {
      expect(button.getAttribute('data-coarse-pointer')).not.toBe('true');
      expect(button.classList.contains('touch')).toBe(true);
    }

    const scrollRegion = screen.getByRole('region', {
      name: adminText('en', 'configurationVersions'),
    });
    const focusables = [
      scrollRegion,
      ...scrollRegion.querySelectorAll<HTMLElement>('button'),
      ...screen
        .getByRole('form', { name: adminText('en', 'configurationEditor') })
        .querySelectorAll<HTMLElement>('input, button'),
    ];
    expect(focusables.map((element) => element.tagName)).toEqual([
      'DIV',
      'BUTTON',
      'INPUT',
      'INPUT',
      'BUTTON',
    ]);
    expect(focusables.at(-1)?.textContent).toBe(adminText('en', 'propose'));
  });

  it('renders Arabic labels, RTL direction and isolated wrapping runs without physical styles', () => {
    installMatchMedia('compact', true);
    wrap(<ConfigEditor {...baseProps} onPropose={vi.fn()} />, 'ar');

    expect(document.documentElement.dir).toBe('rtl');
    expect(document.documentElement.lang).toBe('ar');
    expect(
      screen.getByRole('region', { name: adminText('ar', 'configurationEditor') }),
    ).toBeTruthy();
    const table = screen.getByRole('table', { name: adminText('ar', 'configurationVersions') });
    expect(within(table).getAllByRole('columnheader')).toHaveLength(5);
    expect(
      screen.getByRole('button', { name: adminText('ar', 'propose') }).getAttribute('dir'),
    ).toBeNull();

    const value = screen.getByText('Synthetic alpha revised booking rule');
    expect(value.tagName).toBe('BDI');
    expect(value.getAttribute('dir')).toBe('auto');
    expect((value as HTMLElement).style.overflowWrap).toBe('anywhere');
    expect((value as HTMLElement).style.whiteSpace).toBe('normal');

    for (const element of [
      screen.getByRole('region', { name: adminText('ar', 'configurationEditor') }),
      table,
      screen.getByRole('form', { name: adminText('ar', 'configurationEditor') }),
      value,
    ]) {
      expectLogicalOnly(element);
    }
  });

  it('wraps long English and Arabic configuration values without clipping', () => {
    installMatchMedia('compact');
    const longEnglish =
      'A deliberately long synthetic configuration value that must wrap on a narrow phone without clipping or a page-level horizontal scroll';
    const longArabic =
      'قيمة_config تجريبية طويلة جداً يجب أن تلتف على شاشة الهاتف الضيقة دون قص أو تداخل';
    const entries: ConfigurationVersion[] = [
      {
        organizationId: alphaOrganizationId,
        key: adminConfigurationKey,
        facilityCode: 'MAIN',
        version: 1,
        value: longEnglish,
        effectiveFrom: '',
        approved: false,
      },
      {
        organizationId: alphaOrganizationId,
        key: adminConfigurationKey,
        facilityCode: 'MAIN',
        version: 2,
        value: longArabic,
        effectiveFrom: '2026-04-01',
        approved: true,
        approver: 'Synthetic alpha admin',
      },
    ];
    wrap(<ConfigEditor {...baseProps} entries={entries} onPropose={vi.fn()} />);

    for (const text of [longEnglish, longArabic]) {
      const run = screen.getByText(text) as HTMLElement;
      expect(run.tagName).toBe('BDI');
      expect(run.getAttribute('dir')).toBe('auto');
      expect(run.style.overflowWrap).toBe('anywhere');
      expect(run.style.whiteSpace).toBe('normal');
      expect(run.getAttribute('style')).not.toMatch(/height:\s*\d+px/);
      expect(run.getAttribute('style')).not.toContain('display: none');
    }
  });

  it('follows a viewport change without remounting or losing the typed draft', () => {
    const media = installMatchMedia('compact');
    wrap(<ConfigEditor {...baseProps} onPropose={vi.fn()} />);

    const value = screen.getByLabelText(adminText('en', 'proposedValue')) as HTMLInputElement;
    fireEvent.change(value, { target: { value: 'Synthetic retained draft' } });
    const editor = screen.getByRole('region', { name: adminText('en', 'configurationEditor') });
    expect(editor.getAttribute('data-breakpoint')).toBe('compact');

    act(() => media.fire(BREAKPOINT_MEDIA_QUERIES.medium, true));
    expect(editor.getAttribute('data-breakpoint')).toBe('medium');
    act(() => media.fire(BREAKPOINT_MEDIA_QUERIES.expanded, true));
    expect(editor.getAttribute('data-breakpoint')).toBe('expanded');
    expect((screen.getByLabelText(adminText('en', 'proposedValue')) as HTMLInputElement).value).toBe(
      'Synthetic retained draft',
    );
  });
});
