import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import type { ReactElement } from 'react';
import { I18nProvider } from '../../../src/i18n/I18nProvider';
import type { Locale } from '../../../src/i18n/messages';
import { BREAKPOINT_MEDIA_QUERIES, BREAKPOINT_WIDTHS } from '../../../src/theme/breakpoints';
import { ThemeProvider } from '../../../src/theme/ThemeProvider';
import { ConfigEditor } from '../../../src/features/admin/ConfigEditor';
import type { ConfigurationVersion } from '../../../src/features/admin/domain';
import {
  adminAnchorCivilDate,
  adminConfigurationKey,
  alphaOrganizationId,
  syntheticConfigurations,
} from '../../../src/features/admin/fixtures';
import { adminText } from '../../../src/features/admin/messages';
import { must } from '../../must';

type ChangeListener = (event: MediaQueryListEvent) => void;
type Band = 'compact' | 'medium' | 'expanded';

interface WidthCase {
  width: number;
  band: Band;
  coarse: boolean;
}

/** The seven widths named in the story, mapped to their layout band and pointer. */
const WIDTH_CASES: readonly WidthCase[] = [
  { width: 320, band: 'compact', coarse: true },
  { width: 360, band: 'compact', coarse: true },
  { width: 390, band: 'compact', coarse: true },
  { width: 768, band: 'medium', coarse: true },
  { width: 820, band: 'medium', coarse: true },
  { width: 1024, band: 'medium', coarse: true },
  { width: 1280, band: 'expanded', coarse: false },
];

const longEnglish =
  'A deliberately long synthetic configuration value that must wrap without clipping or overlap on a narrow phone viewport';
const longArabic = 'قيمة configuring تجريبية طويلة جداً يجب أن تلتف دون قص أو تداخل على شاشة الهاتف';

/**
 * A deterministic matchMedia stand-in keyed by media query. One state object is
 * reused per query so the hook's initializer and effect agree. jsdom cannot lay
 * out pixels, so the assertions read classes, data attributes and DOM order.
 */
function installMatchMedia(width: number, coarse: boolean): void {
  const answers: Record<string, boolean> = {
    [BREAKPOINT_MEDIA_QUERIES.medium]: width >= BREAKPOINT_WIDTHS.medium,
    [BREAKPOINT_MEDIA_QUERIES.expanded]: width >= BREAKPOINT_WIDTHS.expanded,
    '(pointer: coarse)': coarse,
    '(orientation: portrait)': width < BREAKPOINT_WIDTHS.medium,
  };
  const states = new Map<string, { matches: boolean; listeners: Set<ChangeListener> }>();
  vi.stubGlobal(
    'matchMedia',
    vi.fn((query: string) => {
      let state = states.get(query);
      if (!state) {
        state = { matches: answers[query] ?? false, listeners: new Set<ChangeListener>() };
        states.set(query, state);
      }
      const current = state;
      return {
        get matches() {
          return current.matches;
        },
        media: query,
        onchange: null,
        addEventListener: (_type: string, listener: ChangeListener) => {
          current.listeners.add(listener);
        },
        removeEventListener: (_type: string, listener: ChangeListener) => {
          current.listeners.delete(listener);
        },
      } as unknown as MediaQueryList;
    }),
  );
}

function wrap(ui: ReactElement, locale: Locale = 'en') {
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>{ui}</I18nProvider>
    </ThemeProvider>,
  );
}

/** One tenant-scoped synthetic version; every clinical field is present. */
function syntheticEntry(overrides: Partial<ConfigurationVersion> = {}): ConfigurationVersion {
  return {
    organizationId: alphaOrganizationId,
    key: adminConfigurationKey,
    facilityCode: 'MAIN',
    version: 1,
    value: 'Synthetic baseline configuration value',
    effectiveFrom: '2026-01-01',
    approved: true,
    approver: 'Synthetic admin A',
    ...overrides,
  };
}

/** The editor's root section is the first element rendered by the component. */
function rootSection(container: HTMLElement): HTMLElement {
  const root = container.firstElementChild;
  if (!root) {
    throw new Error('ConfigEditor did not render a root section');
  }
  return root as HTMLElement;
}

/** Assert that no element uses a physical side, an alignment override or a local direction. */
function assertLogicalOnly(root: HTMLElement): void {
  const styled = Array.from(root.querySelectorAll<HTMLElement>('[style]')).concat(root);
  for (const node of styled) {
    expect(node.style.direction).toBe('');
    expect(node.style.getPropertyValue('margin-left')).toBe('');
    expect(node.style.getPropertyValue('margin-right')).toBe('');
    expect(node.style.getPropertyValue('padding-left')).toBe('');
    expect(node.style.getPropertyValue('padding-right')).toBe('');
    expect(node.style.getPropertyValue('left')).toBe('');
    expect(node.style.getPropertyValue('right')).toBe('');
    expect(node.style.textAlign).not.toBe('left');
    expect(node.style.textAlign).not.toBe('right');
  }
}

const baseProps = {
  entries: syntheticConfigurations,
  organizationId: alphaOrganizationId,
  configKey: adminConfigurationKey,
  anchorCivilDate: adminAnchorCivilDate,
};

const englishHeaders = [
  adminText('en', 'version'),
  adminText('en', 'configuredValue'),
  adminText('en', 'effectiveFrom'),
  adminText('en', 'approvalState'),
  adminText('en', 'approver'),
];

const arabicHeaders = [
  adminText('ar', 'version'),
  adminText('ar', 'configuredValue'),
  adminText('ar', 'effectiveFrom'),
  adminText('ar', 'approvalState'),
  adminText('ar', 'approver'),
];

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('responsive ConfigEditor (WEB-07, UX-01)', () => {
  it.each(WIDTH_CASES)(
    'exposes the layout seam and keeps every column logical at $width px ($band)',
    ({ width, band, coarse }) => {
      installMatchMedia(width, coarse);
      const { container } = wrap(<ConfigEditor {...baseProps} onPropose={vi.fn()} />);
      const root = rootSection(container);

      // The observation seams make the resolved layout band and pointer visible
      // in jsdom; they never change the data, the actions or their order.
      expect(root.tagName).toBe('SECTION');
      expect(root.classList.contains('stack')).toBe(true);
      expect(root.style.gap).not.toBe('');
      expect(root.getAttribute('data-breakpoint')).toBe(band);
      expect(root.getAttribute('data-coarse-pointer')).toBe(String(coarse));

      const table = within(root).getByRole('table', {
        name: adminText('en', 'configurationVersions'),
      });
      // The table fills the inline axis with a logical property, never a
      // physical width that would break the mirrored layout under RTL.
      expect(table.style.getPropertyValue('inline-size')).toBe('100%');
      expect(table.style.getPropertyValue('width')).toBe('');
      expect(
        within(table)
          .getAllByRole('columnheader')
          .map((node) => node.textContent),
      ).toEqual(englishHeaders);
      for (const header of within(table).getAllByRole('columnheader')) {
        expect(header.style.display).not.toBe('none');
      }
      expect(root.querySelector('.hide-compact')).toBeNull();
      expect(root.querySelector('.only-compact')).toBeNull();
      expect(root.querySelector('[style*="display: none"]')).toBeNull();
      assertLogicalOnly(root);
    },
  );

  it.each([320, 360, 390])('keeps the draft form single-column at %ipx', (width) => {
    installMatchMedia(width, true);
    const { container } = wrap(<ConfigEditor {...baseProps} onPropose={vi.fn()} />);
    const root = rootSection(container);
    const form = within(root).getByRole('form', { name: adminText('en', 'configurationEditor') });
    const fields = must(form.querySelector<HTMLElement>('[data-part="fields"]'));
    expect(fields).not.toBeNull();
    expect(fields.classList.contains('stack')).toBe(true);
    expect(fields.getAttribute('data-layout')).toBe('single-column');
  });

  it('wraps long English and Arabic values in isolated logical runs', () => {
    installMatchMedia(360, true);
    const { container } = wrap(
      <ConfigEditor
        {...baseProps}
        entries={[syntheticEntry({ value: longEnglish, approver: longArabic })]}
        onPropose={vi.fn()}
      />,
    );
    const root = rootSection(container);

    for (const text of [longEnglish, longArabic]) {
      const run = screen.getByText(text);
      expect(run.tagName).toBe('BDI');
      expect(run.getAttribute('dir')).toBe('auto');
      expect(run.style.minInlineSize).toBe('0');
      expect(run.style.overflowWrap).toBe('anywhere');
      expect(run.style.whiteSpace).toBe('normal');
    }
    assertLogicalOnly(root);
  });

  it('keeps the value, date and submit controls in keyboard order with the approve action', () => {
    installMatchMedia(320, true);
    const { container } = wrap(
      <ConfigEditor {...baseProps} onPropose={vi.fn()} onApprove={vi.fn()} />,
    );
    const root = rootSection(container);
    const valueInput = screen.getByLabelText(adminText('en', 'proposedValue'));
    const dateInput = screen.getByLabelText(adminText('en', 'effectiveFrom'));
    const submit = screen.getByRole('button', { name: adminText('en', 'propose') });

    expect(
      valueInput.compareDocumentPosition(dateInput) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    expect(
      dateInput.compareDocumentPosition(submit) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    // Both fields carry the 16px `.field` and 44px coarse-pointer `.touch`
    // classes; the submit and approve actions keep their `.touch` target too.
    for (const field of [valueInput, dateInput]) {
      expect(field.classList.contains('field')).toBe(true);
      expect(field.classList.contains('touch')).toBe(true);
    }
    expect(submit.classList.contains('touch')).toBe(true);

    const pendingRow = within(root)
      .getAllByRole('row')
      .find((row) => within(row).queryByText(adminText('en', 'pending')));
    expect(pendingRow).toBeDefined();
    const approve = within(must(pendingRow)).getByRole('button', {
      name: adminText('en', 'approve'),
    });
    expect(approve.classList.contains('touch')).toBe(true);
  });

  it('mirrors under right-to-left with no direction override or hidden column', () => {
    installMatchMedia(360, true);
    const { container } = wrap(
      <ConfigEditor
        {...baseProps}
        entries={[syntheticEntry({ value: longArabic, approver: 'مُعتمد تجريبي' })]}
        onPropose={vi.fn()}
      />,
      'ar',
    );

    expect(document.documentElement.dir).toBe('rtl');
    expect(document.documentElement.lang).toBe('ar');
    const root = rootSection(container);
    expect(root.getAttribute('data-breakpoint')).toBe('compact');
    expect(
      within(root).getAllByText(adminText('ar', 'configurationEditor')).length,
    ).toBeGreaterThan(0);

    const table = within(root).getByRole('table', {
      name: adminText('ar', 'configurationVersions'),
    });
    expect(
      within(table)
        .getAllByRole('columnheader')
        .map((node) => node.textContent),
    ).toEqual(arabicHeaders);
    for (const header of within(table).getAllByRole('columnheader')) {
      expect(header.style.display).not.toBe('none');
    }
    expect(screen.getByText(longArabic).style.overflowWrap).toBe('anywhere');
    expect(root.querySelector('[style*="display: none"]')).toBeNull();
    assertLogicalOnly(root);
  });
});
