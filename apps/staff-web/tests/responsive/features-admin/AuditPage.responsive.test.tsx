import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { act, render, screen, within } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ActiveOrganizationProvider } from '../../../src/api/activeOrganization';
import { send } from '../../../src/api/transport';
import type * as TransportModule from '../../../src/api/transport';
import { AuditPage } from '../../../src/features/admin/AuditPage';
import { alphaOrganizationId } from '../../../src/features/admin/fixtures';
import { adminMessage, adminText } from '../../../src/features/admin/messages';
import { I18nProvider } from '../../../src/i18n/I18nProvider';
import type { Locale } from '../../../src/i18n/messages';
import { BREAKPOINT_MEDIA_QUERIES } from '../../../src/theme/breakpoints';
import type { Breakpoint } from '../../../src/theme/breakpoints';
import { ThemeProvider } from '../../../src/theme/ThemeProvider';

vi.mock('../../../src/api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);
const BREAKPOINTS: readonly Breakpoint[] = ['compact', 'medium', 'expanded'];

/**
 * The shared stylesheet is read as text: jsdom cannot evaluate media queries or
 * paint pixels, so these assertions prove the rules behind the layout hooks,
 * never painted output. Painted size, reflow and zoom stay Playwright evidence.
 */
const responsiveCss = readFileSync(
  join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..', 'src', 'theme', 'responsive.css'),
  'utf8',
);

/** One synthetic audit row with deliberately long English and Arabic values. */
const LONG_ENGLISH =
  'A deliberately long synthetic audit action that must wrap without clipping or overlap';
const LONG_ARABIC = 'إجراء تدقيق تجريبي طويل جداً يجب أن يلتف دون قص أو تداخل';

interface QueryState {
  matches: boolean;
  listeners: Set<(event: MediaQueryListEvent) => void>;
}

/**
 * Install a deterministic `matchMedia` stand-in keyed by query string. One
 * state object is reused per query so the hook's initializer and effect see the
 * same listeners; `fire` crosses a breakpoint in a test. Only synthetic data is
 * used and no real endpoint is contacted.
 */
function installMatchMedia(initial: Record<string, boolean>) {
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
        addEventListener: (_type: string, listener: (event: MediaQueryListEvent) => void) => {
          current.listeners.add(listener);
        },
        removeEventListener: (_type: string, listener: (event: MediaQueryListEvent) => void) => {
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

/** Select the two min-width queries that define the layout band. */
function breakpointQueries(breakpoint: Breakpoint): Record<string, boolean> {
  return {
    [BREAKPOINT_MEDIA_QUERIES.medium]: breakpoint !== 'compact',
    [BREAKPOINT_MEDIA_QUERIES.expanded]: breakpoint === 'expanded',
  };
}

/** Resolve the page under all providers the real route supplies. */
function wrap(locale: Locale = 'en'): void {
  const view: ReactElement = (
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <ActiveOrganizationProvider value={{ organizationId: alphaOrganizationId }}>
          <AuditPage />
        </ActiveOrganizationProvider>
      </I18nProvider>
    </ThemeProvider>
  );
  render(view);
}

/** Resolve one page with a single synthetic event and no next cursor. */
function resolvePage(): void {
  sendMock.mockResolvedValue({
    events: [
      {
        id: 'audit-synthetic',
        occurredAt: '2026-03-08T06:30:00Z',
        actorId: 'synthetic.actor',
        action: LONG_ENGLISH,
        resourceType: 'configuration',
        resourceId: 'cfg-synthetic',
        purpose: LONG_ARABIC,
        scope: 'facility:MAIN',
        correlationId: 'synthetic-corr',
        outcome: 'SUCCESS',
        seq: 3,
      },
    ],
    nextCursor: null,
  });
}

/** Return the root element that owns the responsive page grid. */
function auditRoot(): HTMLElement {
  const root = document.querySelector<HTMLElement>('[data-audit-page]');
  if (root === null) {
    throw new Error('responsive audit page root was not rendered');
  }
  return root;
}

beforeEach(() => {
  sendMock.mockReset();
  resolvePage();
});

afterEach(() => {
  vi.unstubAllGlobals();
  document.documentElement.removeAttribute('dir');
  document.documentElement.removeAttribute('lang');
});

// These tests cover AuditPage together with the shared children it composes.
// Assertions on Form/Button/I18nProvider hooks are integration checks, not
// claims that AuditPage owns those rules.
describe('responsive AuditPage and its shared children (WEB-07, UX-01)', () => {
  it.each(BREAKPOINTS)(
    'keeps one labelled page grid with %s filter fields',
    async (breakpoint) => {
      installMatchMedia(breakpointQueries(breakpoint));
      wrap();
      await screen.findByText(LONG_ARABIC);

      const root = auditRoot();
      expect(root.classList.contains('page')).toBe(true);
      expect(root.getAttribute('data-breakpoint')).toBe(breakpoint);
      expect(root.getAttribute('style')).toBeNull();

      const filters = screen.getByRole('region', { name: adminText('en', 'auditFilters') });
      expect(filters.classList.contains('stack')).toBe(true);
      expect(filters.getAttribute('data-audit-part')).toBe('filters');
      expect(root.contains(filters)).toBe(true);

      const fields = screen.getByRole('form', { name: adminText('en', 'auditFilters') });
      expect(fields.classList.contains('page')).toBe(true);
      expect(fields.getAttribute('data-breakpoint')).toBe(breakpoint);

      const fieldGrid = fields.querySelector<HTMLElement>('[data-audit-part="filter-fields"]');
      expect(fieldGrid).not.toBeNull();
      expect(fieldGrid?.classList.contains('grid-2')).toBe(true);
      expect(fieldGrid?.getAttribute('data-audit-fields')).toBe(
        breakpoint === 'compact' ? 'stacked' : 'two-column',
      );
    },
  );

  it.each(BREAKPOINTS)('keeps roles, labels and keyboard order at %s', async (breakpoint) => {
    installMatchMedia(breakpointQueries(breakpoint));
    wrap();
    await screen.findByText(LONG_ARABIC);

    const root = auditRoot();
    expect(
      screen.getByRole('heading', { name: adminText('en', 'auditPageTitle') }),
    ).toBeTruthy();
    expect(
      screen.getByRole('button', { name: adminText('en', 'auditApplyFilters') }),
    ).toBeTruthy();
    expect(screen.getByRole('button', { name: adminText('en', 'auditClearFilters') })).toBeTruthy();
    expect(
      screen.getAllByRole('region', { name: adminText('en', 'auditEvents') }).length,
    ).toBeGreaterThan(0);

    const labels = [
      'auditActionFilter',
      'auditOutcomeFilter',
      'auditResourceFilter',
      'auditFrom',
      'auditTo',
    ] as const;
    const expectedIds = ['audit-action', 'audit-outcome', 'audit-resource', 'audit-from', 'audit-to'];
    expect(labels.map((key) => screen.getByLabelText(adminText('en', key)).id)).toEqual(expectedIds);

    // Focus follows DOM order: no positive tabindex reorders the five fields
    // or the two actions, and the event time keeps its native focus position.
    const focusable = Array.from(
      root.querySelectorAll<HTMLElement>('input, button, a[href], [tabindex="0"]'),
    );
    expect(focusable.map((element) => element.tagName.toLowerCase()).slice(0, 7)).toEqual([
      'input',
      'input',
      'input',
      'input',
      'input',
      'button',
      'button',
    ]);
    expect(focusable.slice(0, 5).map((element) => (element as HTMLInputElement).id)).toEqual(
      expectedIds,
    );
    expect(focusable.slice(5, 7).map((element) => element.textContent)).toEqual([
      adminText('en', 'auditApplyFilters'),
      adminText('en', 'auditClearFilters'),
    ]);
    for (const element of focusable) {
      expect(element.tabIndex).toBeLessThanOrEqual(0);
    }
  });

  it.each(BREAKPOINTS)('keeps the full audit content in the tree at %s (layout proxy)', async (breakpoint) => {
    installMatchMedia(breakpointQueries(breakpoint));
    wrap();
    await screen.findByText(LONG_ARABIC);

    expect(screen.getAllByText(LONG_ENGLISH).length).toBeGreaterThan(0);
    expect(screen.getAllByText(LONG_ARABIC).length).toBeGreaterThan(0);
    expect(screen.getAllByText('cfg-synthetic').length).toBeGreaterThan(0);
    const zone = screen.getByText(
      adminMessage('en', 'auditFacilityZone', { zone: 'America/New_York' }),
    );
    // .stack gives its children min-inline-size: 0 and overflow-wrap: anywhere,
    // so a long zone message or audit value wraps instead of scrolling the page.
    expect(zone.parentElement?.classList.contains('stack')).toBe(true);
    expect(zone.style.fontSize).toBe('');

    // No audit value is hidden: the existing table and every field stay in the
    // accessibility tree at all three bands, including the narrowest phone.
    expect(document.body.querySelectorAll('[style*="display: none"]')).toHaveLength(0);
    expect(document.body.querySelectorAll('[style*="visibility: hidden"]')).toHaveLength(0);

    // The wrapping and gutter rules behind that structure are in the shared
    // stylesheet; assert the text so a rule deletion fails this suite. Painted
    // no-scroll/zoom behaviour is still Playwright evidence only.
    expect(responsiveCss).toMatch(
      /\.stack\s*>\s*\*\s*,[\s\S]*?\{[^}]*min-inline-size:\s*0[^}]*overflow-wrap:\s*anywhere/,
    );
    expect(responsiveCss).toMatch(
      /\.page\s*\{[\s\S]*?padding-inline-start:\s*max\([^;]*var\(--safe-inline-start/,
    );
  });

  it('reflows the fields when the viewport crosses a breakpoint', async () => {
    const media = installMatchMedia(breakpointQueries('compact'));
    wrap();
    await screen.findByText(LONG_ARABIC);

    expect(auditRoot().getAttribute('data-breakpoint')).toBe('compact');
    expect(document.querySelector('[data-audit-part="filter-fields"]')?.getAttribute('data-audit-fields')).toBe('stacked');

    act(() => media.fire(BREAKPOINT_MEDIA_QUERIES.medium, true));

    expect(auditRoot().getAttribute('data-breakpoint')).toBe('medium');
    expect(document.querySelector('[data-audit-part="filter-fields"]')?.getAttribute('data-audit-fields')).toBe('two-column');
  });

  it('asserts the touch-target and 16px-text layout hooks on a phone', async () => {
    installMatchMedia({ ...breakpointQueries('compact'), '(pointer: coarse)': true });
    wrap();
    await screen.findByText(LONG_ARABIC);

    const fields = [
      ['audit-action', 'auditActionFilter'],
      ['audit-outcome', 'auditOutcomeFilter'],
      ['audit-resource', 'auditResourceFilter'],
      ['audit-from', 'auditFrom'],
      ['audit-to', 'auditTo'],
    ] as const;
    for (const [id, labelKey] of fields) {
      const input = screen.getByLabelText(adminText('en', labelKey)) as HTMLInputElement;
      expect(input.id).toBe(id);
      expect(input.classList.contains('field')).toBe(true);
      expect(input.classList.contains('touch')).toBe(true);
      expect(input.style.minHeight).toBe('');
    }
    for (const button of within(auditRoot()).getAllByRole('button')) {
      expect(button.classList.contains('touch')).toBe(true);
    }

    // The classes above are proxies. These stylesheet assertions are the local
    // regression signal for the 44px coarse floor and the 16px iOS zoom guard;
    // actual bounding boxes belong to the Playwright coarse-pointer projects.
    expect(responsiveCss).toMatch(/\.field\s*\{[^}]*font-size:\s*16px/s);
    expect(responsiveCss).toMatch(
      /@media\s*\(pointer:\s*coarse\)[\s\S]*?\.touch\s*\{[^}]*min-block-size:\s*var\(--sc-size-target-comfortable,\s*44px\)[^}]*min-inline-size:\s*var\(--sc-size-target-comfortable,\s*44px\)/,
    );
  });

  it('asserts logical-only styling and isolated Arabic runs under right-to-left', async () => {
    installMatchMedia(breakpointQueries('compact'));
    wrap('ar');
    await screen.findByText(LONG_ARABIC);

    expect(document.documentElement.dir).toBe('rtl');
    expect(document.documentElement.lang).toBe('ar');
    expect(screen.getByRole('button', { name: adminText('ar', 'auditApplyFilters') })).toBeTruthy();
    expect(auditRoot().getAttribute('data-breakpoint')).toBe('compact');
    expect(document.querySelector('[data-audit-part="filter-fields"]')?.getAttribute('data-audit-fields')).toBe('stacked');

    const root = auditRoot();
    const zone = screen.getByText(adminMessage('ar', 'auditFacilityZone', { zone: 'America/New_York' }));
    expect(root.contains(zone)).toBe(true);
    expect(zone.parentElement?.classList.contains('stack')).toBe(true);

    // No component sets a physical side or overrides direction; the document
    // direction mirrors the logical CSS on its own. No fixed inline font size
    // is a proxy for text scaling, not proof of 200% zoom reflow.
    for (const element of [root, ...Array.from(root.querySelectorAll('*'))] as HTMLElement[]) {
      const style = element.getAttribute('style') ?? '';
      expect(style).not.toMatch(/(?:margin|padding|border)-(?:left|right)\s*:/);
      expect(style).not.toMatch(/text-align\s*:\s*(?:left|right)/);
      expect(element.style.direction).toBe('');
      expect(element.style.fontSize).toBe('');
    }
    expect(responsiveCss).toMatch(
      /:root\[dir='rtl'\]\s*\{[\s\S]*?--safe-inline-start:\s*env\(safe-area-inset-right\)[\s\S]*?--safe-inline-end:\s*env\(safe-area-inset-left\)/,
    );
    expect(responsiveCss).not.toMatch(/(?:margin|padding|border)-(?:left|right)\s*:/);
    expect(responsiveCss).not.toMatch(/text-align\s*:\s*(?:left|right)/);
  });
});
