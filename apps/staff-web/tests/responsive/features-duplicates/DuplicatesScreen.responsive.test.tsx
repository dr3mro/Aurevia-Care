import type { ReactElement } from 'react';
import { act, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ActiveOrganizationProvider } from '../../../src/api/activeOrganization';
import type { ApprovalPanelProps } from '../../../src/features/duplicates/DuplicatesScreen';
import {
  ApprovalPanel,
  ReviewScreen,
  WorklistScreen,
} from '../../../src/features/duplicates/DuplicatesScreen';
import {
  alphaOrganizationId,
  reviewOpen,
  syntheticDetail,
  syntheticPair,
} from '../../../src/features/duplicates/fixtures';
import { I18nProvider } from '../../../src/i18n/I18nProvider';
import type { Locale } from '../../../src/i18n/messages';
import { BREAKPOINT_MEDIA_QUERIES } from '../../../src/theme/breakpoints';
import { ThemeProvider } from '../../../src/theme/ThemeProvider';
import { must } from '../../must';

type ChangeListener = (event: MediaQueryListEvent) => void;
type Band = 'compact' | 'medium' | 'expanded';

interface QueryState {
  matches: boolean;
  listeners: Set<ChangeListener>;
}

/**
 * Deterministic matchMedia stand-in keyed by media query. One state object is
 * reused per query so the hook's initializer, effect and change listener all
 * observe the same value. All data below is synthetic.
 */
function installMatchMedia(band: Band, coarse: boolean) {
  const answers: Record<string, boolean> = {
    [BREAKPOINT_MEDIA_QUERIES.medium]: band !== 'compact',
    [BREAKPOINT_MEDIA_QUERIES.expanded]: band === 'expanded',
    '(pointer: coarse)': coarse,
  };
  const states = new Map<string, QueryState>();
  const state = (query: string): QueryState => {
    let current = states.get(query);
    if (!current) {
      current = { matches: answers[query] ?? false, listeners: new Set() };
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
        media: query,
        onchange: null,
        addEventListener: (_type: string, listener: ChangeListener) => {
          current.listeners.add(listener);
        },
        removeEventListener: (_type: string, listener: ChangeListener) => {
          current.listeners.delete(listener);
        },
        addListener: () => undefined,
        removeListener: () => undefined,
        dispatchEvent: () => false,
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

function wrap(ui: ReactElement, locale: Locale = 'en') {
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <ActiveOrganizationProvider value={{ organizationId: alphaOrganizationId }}>
          {ui}
        </ActiveOrganizationProvider>
      </I18nProvider>
    </ThemeProvider>,
  );
}

const LONG_ENGLISH =
  'A deliberately long synthetic surviving identity name that must wrap without clipping or overlap on a phone';
const LONG_ARABIC = 'اسم مركب تجريبي طويل جداً يجب أن يلتف دون قص أو تداخل في شاشة الهاتف';

const longPair = {
  survivorId: 'SYN-SURVIVOR-LONG',
  survivorName: LONG_ENGLISH,
  duplicateId: 'SYN-DUPLICATE-LONG',
  duplicateName: LONG_ARABIC,
};

const longDetail = {
  ...syntheticDetail,
  conflicts: [{ field: 'notes', survivorValue: LONG_ENGLISH, duplicateValue: LONG_ARABIC }],
};

function panelProps(overrides: Partial<ApprovalPanelProps> = {}): ApprovalPanelProps {
  return {
    locale: 'en',
    detail: syntheticDetail,
    review: reviewOpen,
    reason: '',
    confirmed: [],
    alternateProcess: '',
    errors: {},
    notice: undefined,
    openError: undefined,
    approvalError: undefined,
    approved: undefined,
    onOpenReview: () => undefined,
    onReasonChange: () => undefined,
    onToggleReference: () => undefined,
    onAlternateChange: () => undefined,
    onApprove: () => undefined,
    ...overrides,
  };
}

/** Assert that a subtree introduces no physical side or direction override. */
function assertLogicalOnly(root: HTMLElement): void {
  for (const element of [root, ...Array.from(root.querySelectorAll('*'))] as HTMLElement[]) {
    const style = element.getAttribute('style') ?? '';
    expect(style).not.toMatch(/(?:margin|padding|border|inset)-(?:left|right)\s*:/);
    expect(style).not.toMatch(/text-align\s*:\s*(?:left|right)/);
    expect(element.style.direction).toBe('');
  }
}

function focusableTags(root: HTMLElement): string[] {
  return [...root.querySelectorAll<HTMLElement>('[tabindex="0"], button, input, a[href]')].map(
    (element) => element.tagName,
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('responsive DuplicatesScreen (WEB-07, UX-01)', () => {
  it('puts the compact worklist in a labelled keyboard-scrollable region with every column', () => {
    installMatchMedia('compact', true);
    wrap(<WorklistScreen pairs={[longPair]} locale="en" onSelect={() => undefined} />);

    const region = screen.getByRole('region', { name: 'Duplicate review worklist' });
    expect(region.className).toContain('table-region');
    expect(region.getAttribute('tabindex')).toBe('0');
    expect(region.tabIndex).toBe(0);

    const table = within(region).getByRole('table', { name: 'Duplicate review worklist' });
    expect(table.getAttribute('data-breakpoint')).toBe('compact');
    expect(
      within(table)
        .getAllByRole('columnheader')
        .map((cell) => cell.textContent),
    ).toEqual(['Surviving identity', 'Duplicate identity', 'Action']);
    for (const cell of [
      ...within(table).getAllByRole('columnheader'),
      ...within(table).getAllByRole('cell'),
    ]) {
      const style = cell.style;
      expect(style.overflowWrap).toBe('anywhere');
      expect(style.whiteSpace).toBe('normal');
      expect(style.display).not.toBe('none');
    }

    const arabicName = screen.getByText(LONG_ARABIC);
    expect(arabicName.tagName).toBe('BDI');
    expect(arabicName.getAttribute('lang')).toBe('ar');
    const review = screen.getByRole<HTMLButtonElement>('button', {
      name: 'Review pair SYN-SURVIVOR-LONG and SYN-DUPLICATE-LONG',
    });
    expect(review.className).toContain('touch');
    expect(review.style.minHeight).not.toBe('32px');
    expect(focusableTags(region)).toEqual(['BUTTON']);
    assertLogicalOnly(region);
  });

  it('drops the scroll region and keeps the full table on medium and expanded', () => {
    for (const band of ['medium', 'expanded'] as const) {
      installMatchMedia(band, false);
      const view = wrap(
        <WorklistScreen pairs={[syntheticPair]} locale="en" onSelect={() => undefined} />,
      );
      const table = screen.getByRole('table', { name: 'Duplicate review worklist' });
      expect(table.getAttribute('data-breakpoint')).toBe(band);
      expect(screen.queryByRole('region', { name: 'Duplicate review worklist' })).toBeNull();
      expect(within(table).getAllByRole('columnheader')).toHaveLength(3);
      for (const cell of within(table).getAllByRole('cell')) {
        expect(cell.style.display).not.toBe('none');
      }
      expect(screen.getByText('patient-survivor')).toBeTruthy();
      view.unmount();
    }
  });

  it('stacks the identities and labels conflicts in text on compact phones', () => {
    installMatchMedia('compact', true);
    const { container } = wrap(<ReviewScreen detail={longDetail} locale="en" />);
    const root = container.firstElementChild as HTMLElement;
    expect(root.getAttribute('data-breakpoint')).toBe('compact');

    const identities = screen.getByRole('region', { name: 'Side-by-side identities' });
    const cards = identities.querySelector<HTMLElement>('[data-layout]');
    expect(cards?.className).toContain('stack');
    expect(cards?.getAttribute('data-layout')).toBe('stacked');
    expect(screen.getByRole('region', { name: 'Surviving record' })).toBeTruthy();
    expect(screen.getByRole('region', { name: 'Duplicate record' })).toBeTruthy();

    const conflictRegion = must(
      screen
        .getAllByRole('region', { name: 'Conflicting demographics' })
        .find((element) => element.className.includes('table-region')),
    );
    expect(conflictRegion).toBeTruthy();
    expect(conflictRegion.className).toContain('table-region');
    expect(conflictRegion.tabIndex).toBe(0);
    const conflictTable = within(conflictRegion).getByRole('table', {
      name: 'Conflicting demographics',
    });
    expect(
      within(conflictTable)
        .getAllByRole('columnheader')
        .map((cell) => cell.textContent),
    ).toEqual(['Field', 'Surviving value', 'Duplicate value']);
    const longCell = within(conflictTable).getByText(LONG_ARABIC);
    expect(longCell.tagName).toBe('BDI');
    expect(must(longCell.parentElement).style.overflowWrap).toBe('anywhere');
    expect(screen.getByText(/Dry run: move 1 chart visibility association/).style.whiteSpace).toBe(
      'pre-wrap',
    );
    expect(screen.getByText('Not disclosed')).toBeTruthy();
    assertLogicalOnly(root);
  });

  it('places the identities side by side from medium without hiding a field', () => {
    for (const band of ['medium', 'expanded'] as const) {
      installMatchMedia(band, false);
      const view = wrap(<ReviewScreen detail={syntheticDetail} locale="en" />);
      const root = view.container.firstElementChild as HTMLElement;
      expect(root.getAttribute('data-breakpoint')).toBe(band);
      const identities = screen.getByRole('region', { name: 'Side-by-side identities' });
      const cards = identities.querySelector<HTMLElement>('[data-layout]');
      expect(cards?.className).toContain('grid-2');
      expect(cards?.getAttribute('data-layout')).toBe('side-by-side');
      expect(root.querySelector('.table-region')).toBeNull();
      expect(screen.getByRole('table', { name: 'Conflicting demographics' })).toBeTruthy();
      expect(screen.getByText('MRN-1001@SYNTHETIC')).toBeTruthy();
      expect(screen.getByText('MRN-1002@SYNTHETIC')).toBeTruthy();
      for (const element of [root, ...Array.from(root.querySelectorAll<HTMLElement>('*'))]) {
        expect(element.style.display).not.toBe('none');
      }
      assertLogicalOnly(root);
      // Testing Library cleans up per test, so keep one mounted tree per band.
      view.unmount();
    }
  });

  it('keeps keyboard order, 16px fields and touch-size checkboxes on the compact approval form', () => {
    installMatchMedia('compact', true);
    const { container } = wrap(<ApprovalPanel {...panelProps()} />);
    const root = container.firstElementChild as HTMLElement;
    expect(root.getAttribute('data-breakpoint')).toBe('compact');

    const reason = screen.getByLabelText<HTMLInputElement>('Reason for consolidation');
    expect(reason.className).toContain('field');
    expect(reason.className).toContain('touch');
    for (const reference of ['MRN-1001@SYNTHETIC', 'MRN-1002@SYNTHETIC']) {
      const checkbox = screen.getByLabelText<HTMLInputElement>(reference);
      expect(checkbox.className).toContain('touch');
      expect(checkbox.type).toBe('checkbox');
    }
    const alternate = screen.getByLabelText<HTMLInputElement>('Approved alternate-process code');
    expect(alternate.className).toContain('field');

    const form = screen.getByRole('form', { name: 'Second-reviewer approval' });
    expect(form.querySelector('[data-part="fields"]')?.className).toContain('stack');
    expect(focusableTags(form)).toEqual(['INPUT', 'INPUT', 'INPUT', 'INPUT', 'BUTTON']);
    for (const element of [root, ...Array.from(root.querySelectorAll<HTMLElement>('*'))]) {
      expect(element.style.display).not.toBe('none');
    }
    assertLogicalOnly(root);
  });

  it('mirrors structure under RTL without a direction override or a hidden run', () => {
    for (const band of ['compact', 'expanded'] as const) {
      installMatchMedia(band, true);
      const view = wrap(
        <WorklistScreen pairs={[longPair]} locale="ar" onSelect={() => undefined} />,
        'ar',
      );
      const container = view.container;
      expect(document.documentElement.dir).toBe('rtl');
      expect(document.documentElement.lang).toBe('ar');
      const table = screen.getByRole('table', { name: 'قائمة مراجعة التكرارات' });
      expect(table.getAttribute('data-breakpoint')).toBe(band);
      if (band === 'compact') {
        expect(screen.getByRole('region', { name: 'قائمة مراجعة التكرارات' })).toBeTruthy();
      } else {
        expect(screen.queryByRole('region', { name: 'قائمة مراجعة التكرارات' })).toBeNull();
      }
      const arabicName = screen.getByText(LONG_ARABIC);
      expect(arabicName.getAttribute('dir')).toBe('auto');
      expect(arabicName.getAttribute('lang')).toBe('ar');
      const identifier = screen.getByText('SYN-SURVIVOR-LONG');
      expect(identifier.getAttribute('dir')).toBe('ltr');
      assertLogicalOnly(container.firstElementChild as HTMLElement);
      view.unmount();
    }
  });

  it('keeps the review detail and approval form right-to-left at both bands', () => {
    for (const band of ['compact', 'expanded'] as const) {
      installMatchMedia(band, true);
      const detailView = wrap(<ReviewScreen detail={longDetail} locale="ar" />, 'ar');
      expect(document.documentElement.dir).toBe('rtl');
      expect(document.documentElement.lang).toBe('ar');
      const detailRoot = detailView.container.firstElementChild as HTMLElement;
      expect(detailRoot.getAttribute('data-breakpoint')).toBe(band);
      const identities = screen.getByRole('region', { name: 'الهويات جنبًا إلى جنب' });
      const cards = identities.querySelector<HTMLElement>('[data-layout]');
      expect(cards?.className).toContain(band === 'compact' ? 'stack' : 'grid-2');
      expect(cards?.getAttribute('data-layout')).toBe(
        band === 'compact' ? 'stacked' : 'side-by-side',
      );
      const arabicConflict = screen.getByText(LONG_ARABIC);
      expect(arabicConflict.tagName).toBe('BDI');
      expect(arabicConflict.getAttribute('dir')).toBe('auto');
      expect(arabicConflict.getAttribute('lang')).toBe('ar');
      for (const code of Array.from(detailRoot.querySelectorAll('code'))) {
        expect(code.getAttribute('dir')).toBe('ltr');
      }
      assertLogicalOnly(detailRoot);
      detailView.unmount();

      const panelView = wrap(<ApprovalPanel {...panelProps({ locale: 'ar' })} />, 'ar');
      const panelRoot = panelView.container.firstElementChild as HTMLElement;
      expect(panelRoot.getAttribute('data-breakpoint')).toBe(band);
      expect(screen.getByRole('button', { name: 'الموافقة على الدمج' })).toBeTruthy();
      expect(screen.getByLabelText('سبب الدمج').className).toContain('field');
      for (const reference of ['MRN-1001@SYNTHETIC', 'MRN-1002@SYNTHETIC']) {
        const checkbox = screen.getByLabelText<HTMLInputElement>(reference);
        expect(checkbox.getAttribute('type')).toBe('checkbox');
        expect(checkbox.className).toContain('touch');
      }
      assertLogicalOnly(panelRoot);
      panelView.unmount();
    }
  });

  it('reflows when the viewport crosses a breakpoint without remounting the screen', () => {
    const media = installMatchMedia('compact', false);
    const { container } = wrap(
      <>
        <WorklistScreen pairs={[syntheticPair]} locale="en" onSelect={() => undefined} />
        <ReviewScreen detail={syntheticDetail} locale="en" />
      </>,
    );
    expect(screen.getByRole('region', { name: 'Duplicate review worklist' })).toBeTruthy();
    const identities = screen.getByRole('region', { name: 'Side-by-side identities' });
    expect(identities.querySelector('[data-layout]')?.getAttribute('data-layout')).toBe('stacked');

    act(() => media.fire(BREAKPOINT_MEDIA_QUERIES.medium, true));
    expect(screen.queryByRole('region', { name: 'Duplicate review worklist' })).toBeNull();
    expect(identities.querySelector('[data-layout]')?.getAttribute('data-layout')).toBe(
      'side-by-side',
    );
    expect(container.querySelector('[data-breakpoint="medium"]')).toBeTruthy();
  });
});
