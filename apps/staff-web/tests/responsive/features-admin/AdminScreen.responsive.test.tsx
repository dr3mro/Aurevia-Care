import { act, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Locale } from '../../../src/i18n/messages';
import { I18nProvider } from '../../../src/i18n/I18nProvider';
import { AdminScreen, type AdminScreenProps } from '../../../src/features/admin/AdminScreen';
import {
  adminAnchorCivilDate,
  adminConfigurationKey,
  alphaOrganizationId,
  syntheticAccessProbes,
  syntheticAuditEvents,
  syntheticConfigurations,
  syntheticStaffEvents,
} from '../../../src/features/admin/fixtures';
import { adminText } from '../../../src/features/admin/messages';
import type { Breakpoint } from '../../../src/theme/breakpoints';
import { BREAKPOINT_MEDIA_QUERIES } from '../../../src/theme/breakpoints';
import { ThemeProvider } from '../../../src/theme/ThemeProvider';
import { must } from '../../must';

type ChangeListener = (event: MediaQueryListEvent) => void;

interface QueryState {
  matches: boolean;
  listeners: Set<ChangeListener>;
}

const BREAKPOINTS: readonly Breakpoint[] = ['compact', 'medium', 'expanded'];

const LONG_ENGLISH =
  'A deliberately long synthetic configuration value that must wrap without clipping or overlap';
const LONG_ARABIC = 'قيمة تجريبية عربية طويلة جداً يجب أن تلتف دون قص أو تداخل';

/** Message keys for every section heading the composed screen must render. */
const SECTION_LABEL_KEYS: readonly Parameters<typeof adminText>[1][] = [
  'accessReview',
  'configurationEditor',
  'staffHistory',
  'auditEvents',
];

/** Message keys for the three labelled, keyboard-scrollable administration tables. */
const TABLE_LABEL_KEYS: readonly Parameters<typeof adminText>[1][] = [
  'configurationVersions',
  'staffHistory',
  'auditEvents',
];

/**
 * Deterministic media-query stand-in. The responsive hooks read the two
 * `min-width` queries and the coarse-pointer query; every other query reports
 * `false`. A single state object per query lets the hook's initializer and its
 * effect observe the same listeners, so a runtime width change can be fired.
 */
function installMatchMedia(breakpoint: Breakpoint, coarsePointer = false) {
  const initial: Record<string, boolean> = {
    [BREAKPOINT_MEDIA_QUERIES.medium]: breakpoint !== 'compact',
    [BREAKPOINT_MEDIA_QUERIES.expanded]: breakpoint === 'expanded',
    '(pointer: coarse)': coarsePointer,
    '(orientation: portrait)': true,
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
    vi.fn((query: string) => {
      const current = state(query);
      return {
        get matches() {
          return current.matches;
        },
        addEventListener: (_type: string, listener: ChangeListener) => {
          current.listeners.add(listener);
        },
        removeEventListener: (_type: string, listener: ChangeListener) => {
          current.listeners.delete(listener);
        },
      };
    }),
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

/** Screen props built only from the synthetic administration fixtures. */
function screenProps(overrides: Partial<AdminScreenProps> = {}): AdminScreenProps {
  return {
    organizationId: alphaOrganizationId,
    configurations: syntheticConfigurations,
    staffEvents: syntheticStaffEvents,
    auditEvents: syntheticAuditEvents,
    accessProbes: syntheticAccessProbes,
    configKey: adminConfigurationKey,
    subject: 'synthetic.reception',
    facilityCode: 'MAIN',
    anchorCivilDate: adminAnchorCivilDate,
    ...overrides,
  };
}

/** Render the screen inside the providers that supply locale and tokens. */
function wrap(props: AdminScreenProps, locale: Locale = 'en') {
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <AdminScreen {...props} />
      </I18nProvider>
    </ThemeProvider>,
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
  document.documentElement.removeAttribute('dir');
  document.documentElement.removeAttribute('lang');
});

describe('responsive AdminScreen (WEB-07, UX-01)', () => {
  it.each(BREAKPOINTS)(
    'uses the logical page wrapper and keeps every administration section at %s',
    (breakpoint) => {
      installMatchMedia(breakpoint);
      wrap(screenProps());

      const page = screen.getByRole('region', {
        name: adminText('en', 'admin'),
      });
      expect(page.classList.contains('page')).toBe(true);
      expect(page.getAttribute('data-breakpoint')).toBe(breakpoint);
      expect(page.getAttribute('style')).toBeNull();
      expect(page.getAttribute('aria-label')).toBe(adminText('en', 'admin'));

      // One DOM and one accessibility tree at every band: all headings, the
      // live status and the three labelled tables stay in the document.
      expect(
        screen.getByRole('heading', { level: 2, name: adminText('en', 'admin') }),
      ).toBeTruthy();
      expect(screen.getByText(adminText('en', 'syntheticNotice'))).toBeTruthy();
      expect(screen.getByTestId('admin-status').getAttribute('role')).toBe('status');
      for (const label of SECTION_LABEL_KEYS) {
        expect(
          screen.getByRole('heading', { level: 3, name: adminText('en', label) }),
        ).toBeTruthy();
      }

      const accessReview = screen.getByRole('region', {
        name: adminText('en', 'accessReview'),
      });
      expect(accessReview.classList.contains('stack')).toBe(true);
      expect(accessReview.getAttribute('style')).toBeNull();

      for (const key of TABLE_LABEL_KEYS) {
        const label = adminText('en', key);
        if (breakpoint === 'compact' && key === 'auditEvents') {
          expect(
            within(screen.getByRole('list', { name: label })).getAllByRole('listitem').length,
          ).toBeGreaterThan(0);
          continue;
        }
        const region = screen
          .getAllByRole('region', { name: label })
          .find((element) => element.getAttribute('tabindex') === '0');
        expect(region).toBeDefined();
        const tableName = breakpoint === 'compact' && key === 'staffHistory' ? /^Seq / : label;
        expect(
          within(must(region)).getAllByRole('table', { name: tableName }).length,
        ).toBeGreaterThan(0);
      }

      // Every responsive descendant agrees on the active layout band, so a
      // structural assertion in jsdom does not depend on real media queries.
      const observed = [...page.querySelectorAll('[data-breakpoint]')].map((element) =>
        element.getAttribute('data-breakpoint'),
      );
      expect(observed.length).toBeGreaterThan(1);
      expect(new Set(observed)).toEqual(new Set([breakpoint]));
    },
  );

  it.each(BREAKPOINTS)('keeps labels, roles and keyboard order at %s', (breakpoint) => {
    installMatchMedia(breakpoint);
    const { container } = wrap(screenProps());

    // Access states keep their distinct roles; the proposal live region has no
    // static accessible name that could mask its announcement.
    const denied = document.querySelector('[data-access-state="denied"]');
    expect(denied?.getAttribute('role')).toBe('alert');
    expect(within(denied as HTMLElement).getByText(adminText('en', 'accessDenied'))).toBeTruthy();
    const status = screen.getByTestId('admin-status');
    expect(status.getAttribute('role')).toBe('status');
    expect(status.hasAttribute('aria-label')).toBe(false);

    const value = screen.getByLabelText<HTMLInputElement>(adminText('en', 'proposedValue'));
    const effectiveFrom = screen.getByLabelText<HTMLInputElement>(adminText('en', 'effectiveFrom'));
    const propose = screen.getByRole('button', { name: adminText('en', 'propose') });
    expect([value.id, effectiveFrom.id]).toEqual(['admin-config-value', 'admin-config-effective']);
    expect(propose.textContent).toBe(adminText('en', 'propose'));

    // No positive tabindex may reorder the natural tab sequence; the labelled
    // table regions are the first stops, the form fields and action follow in
    // DOM order, and nothing clinical is removed from the tab order.
    const tabStops = [...container.querySelectorAll('[tabindex]')].map((element) =>
      Number(element.getAttribute('tabindex')),
    );
    expect(tabStops.every((index) => index >= 0)).toBe(true);
    expect([...container.querySelectorAll('[role="region"][tabindex="0"]')]).toHaveLength(
      breakpoint === 'compact' ? 2 : 3,
    );
    const focusable = [...container.querySelectorAll('button, input, [tabindex="0"]')];
    expect(focusable.indexOf(value)).toBeLessThan(focusable.indexOf(propose));
    expect(focusable.indexOf(effectiveFrom)).toBeLessThan(focusable.indexOf(propose));
    for (const stop of focusable) {
      expect((stop as HTMLElement).getAttribute('disabled')).toBeNull();
    }
  });

  it('keeps coarse-pointer targets, 16px inputs and every clinical column on a phone', () => {
    installMatchMedia('compact', true);
    wrap(screenProps());

    const value = screen.getByLabelText<HTMLInputElement>(adminText('en', 'proposedValue'));
    const effectiveFrom = screen.getByLabelText<HTMLInputElement>(adminText('en', 'effectiveFrom'));
    for (const input of [value, effectiveFrom]) {
      expect(input.classList.contains('field')).toBe(true);
      expect(input.classList.contains('touch')).toBe(true);
    }
    const propose = screen.getByRole('button', { name: adminText('en', 'propose') });
    expect(propose.classList.contains('touch')).toBe(true);
    expect(propose.closest('[data-part="actions"]')?.classList.contains('touch')).toBe(true);

    // No clinical column is hidden to make the narrow layout fit.
    for (const header of screen.getAllByRole('columnheader')) {
      expect(header.style.display).not.toBe('none');
    }
  });

  it('wraps long English and Arabic values with logical-only styles under right-to-left', () => {
    installMatchMedia('compact');
    document.documentElement.setAttribute('dir', 'rtl');
    document.documentElement.setAttribute('lang', 'ar');
    wrap(
      screenProps({
        configurations: [
          {
            organizationId: alphaOrganizationId,
            key: adminConfigurationKey,
            facilityCode: 'MAIN',
            version: 1,
            value: LONG_ENGLISH,
            effectiveFrom: '2026-01-01',
            approved: true,
            approver: 'Synthetic reviewer',
          },
        ],
        accessProbes: [
          {
            id: 'synthetic-long-label',
            label: LONG_ARABIC,
            resourceOrganizationId: alphaOrganizationId,
            viewer: {
              organizationId: alphaOrganizationId,
              roles: ['tenant_admin'],
              activeMembership: true,
            },
          },
        ],
      }),
      'ar',
    );

    expect(document.documentElement.dir).toBe('rtl');
    expect(document.documentElement.lang).toBe('ar');
    expect(screen.getByRole('heading', { level: 2, name: adminText('ar', 'admin') })).toBeTruthy();
    expect(screen.getByText(LONG_ENGLISH)).toBeTruthy();
    expect(screen.getByText(LONG_ARABIC)).toBeTruthy();
    expect(screen.getByRole('list', { name: adminText('ar', 'auditEvents') })).toBeTruthy();

    for (const run of document.querySelectorAll('bdi')) {
      const isolated = run;
      expect(isolated.getAttribute('dir')).toBe('auto');
      expect(isolated.hasAttribute('lang')).toBe(true);
      expect(isolated.style.overflowWrap).toBe('anywhere');
      expect(isolated.style.whiteSpace).toBe('normal');
      expect(isolated.style.minInlineSize).toBe('0');
    }

    // Direction is owned by the document; no subtree sets a physical side, a
    // physical alignment or a direction override.
    for (const element of [
      ...document.querySelectorAll('section[aria-label]'),
      ...document.querySelectorAll('bdi'),
    ] as HTMLElement[]) {
      const style = element.getAttribute('style') ?? '';
      expect(style).not.toMatch(/(?:margin|padding|border)-(?:left|right)\s*:/);
      expect(style).not.toMatch(/text-align\s*:\s*(?:left|right)/);
      expect(element.style.direction).toBe('');
    }
  });

  it('follows a width change without losing the page wrapper or the rendered data', () => {
    const media = installMatchMedia('compact');
    wrap(screenProps());
    let page = screen.getByRole('region', { name: adminText('en', 'admin') });
    expect(page.getAttribute('data-breakpoint')).toBe('compact');

    act(() => media.fire(BREAKPOINT_MEDIA_QUERIES.medium, true));
    page = screen.getByRole('region', { name: adminText('en', 'admin') });
    expect(page.classList.contains('page')).toBe(true);
    expect(page.getAttribute('data-breakpoint')).toBe('medium');
    expect(screen.getByText('Synthetic alpha revised booking rule')).toBeTruthy();
    expect(screen.getByLabelText(adminText('en', 'proposedValue'))).toBeTruthy();
  });
});
