import type { ReactElement } from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { Locale } from '../../i18n/messages';
import { I18nProvider } from '../../i18n/I18nProvider';
import { ThemeProvider } from '../../theme/ThemeProvider';
import { AddendumBanner } from './AddendumBanner';
import { ImagingReportCard } from './ImagingReportCard';
import type { AddendumAcknowledgment, SignedReport } from './domain';
import { REPORT_SECTION_KEYS } from './domain';
import {
  alphaOrganizationId,
  syntheticAddenda,
  syntheticOrderId,
  syntheticSignedReport,
} from './fixtures';

/** The open alpha addendum shown by the banner tests. */
const openAddendum = required(syntheticAddenda[0]);
/** The acknowledged alpha addendum used by the negative test. */
const acknowledgedAddendum = required(syntheticAddenda[1]);
/** An open addendum from another tenant and another order. */
const foreignAddenda = [required(syntheticAddenda[2]), required(syntheticAddenda[3])];

/** Mount a component with the theme and locale providers the chart uses. */
function wrap(ui: ReactElement, locale: Locale = 'en') {
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>{ui}</I18nProvider>
    </ThemeProvider>,
  );
}

/** Build a banner with one set of receipts for the transition test. */
function banner(
  addenda: readonly AddendumAcknowledgment[],
  onAcknowledge: (addendum: AddendumAcknowledgment) => void,
  locale: Locale = 'en',
): ReactElement {
  return (
    <AddendumBanner
      addenda={addenda}
      organizationId={alphaOrganizationId}
      orderId={syntheticOrderId}
      locale={locale}
      onAcknowledge={onAcknowledge}
    />
  );
}

describe('ImagingReportCard', () => {
  it('renders the signed report sections in canonical clinical order', () => {
    wrap(<ImagingReportCard report={syntheticSignedReport} locale="en" />);

    const card = screen.getByTestId('imaging-report-card');
    expect(card.getAttribute('aria-label')).toBe('Imaging report');
    const sections = screen.getByTestId('report-sections');
    const rendered = Array.from(sections.querySelectorAll('[data-section]')).map((node) =>
      node.getAttribute('data-section'),
    );
    expect(rendered).toEqual([...REPORT_SECTION_KEYS]);
    expect(sections.textContent).toContain(syntheticSignedReport.technique);
    expect(sections.textContent).toContain(syntheticSignedReport.impression);
    expect(screen.getByTestId('report-signed-by').textContent).toContain(
      syntheticSignedReport.signer,
    );
    expect(screen.getByTestId('report-version').textContent).toContain(
      String(syntheticSignedReport.version),
    );
    expect(screen.getByTestId('report-signed-at').textContent).toContain(
      syntheticSignedReport.signedAt,
    );
  });

  it('keeps mixed-script clinical text isolated in an Arabic right-to-left interface', () => {
    const mixedReport: SignedReport = {
      ...syntheticSignedReport,
      impression: 'لا يوجد خلل حاد · No acute abnormality',
    };
    wrap(<ImagingReportCard report={mixedReport} locale="ar" />, 'ar');

    expect(document.documentElement.lang).toBe('ar');
    expect(document.documentElement.dir).toBe('rtl');
    expect(screen.getByTestId('imaging-report-card').getAttribute('aria-label')).toBe(
      'تقرير الأشعة',
    );
    expect(screen.getByTestId('report-signed-by').textContent).toContain('موقّع بواسطة');
    expect(screen.getByTestId('report-sections').textContent).toContain('النتائج');

    const bodies = screen.getByTestId('report-sections').querySelectorAll('dd bdi');
    expect(bodies.length).toBe(REPORT_SECTION_KEYS.length);
    for (const body of bodies) {
      expect(body.tagName).toBe('BDI');
      expect(body.getAttribute('dir')).toBe('auto');
    }
    expect(required(bodies[3]).textContent).toBe(mixedReport.impression);
    expect(screen.getByTestId('report-signed-by').querySelector('bdi')?.getAttribute('dir')).toBe(
      'auto',
    );
  });
});

describe('AddendumBanner', () => {
  it('shows a banner with an acknowledge action until the addendum is acknowledged', () => {
    const onAcknowledge = vi.fn<(addendum: AddendumAcknowledgment) => void>();
    wrap(banner([openAddendum], onAcknowledge));

    const bannerNode = screen.getByTestId('addendum-banner');
    expect(bannerNode.getAttribute('aria-label')).toBe('Unacknowledged addendum');
    expect(bannerNode.textContent).toContain(String(openAddendum.version));
    fireEvent.click(
      screen.getByRole('button', { name: `Acknowledge version ${openAddendum.version}` }),
    );
    expect(onAcknowledge).toHaveBeenCalledExactlyOnceWith(openAddendum);
  });

  it('closes the acknowledgment loop when the receipt becomes acknowledged', () => {
    const onAcknowledge = vi.fn<(addendum: AddendumAcknowledgment) => void>();
    const { rerender } = wrap(banner([openAddendum], onAcknowledge));
    expect(screen.getByTestId('addendum-banner')).toBeTruthy();

    fireEvent.click(
      screen.getByRole('button', { name: `Acknowledge version ${openAddendum.version}` }),
    );
    expect(onAcknowledge).toHaveBeenCalledExactlyOnceWith(openAddendum);

    const acknowledged: AddendumAcknowledgment = {
      ...openAddendum,
      state: 'acknowledged',
      acknowledgedAt: '2026-03-09T14:00:00Z',
      acknowledgedBy: 'synthetic.referrer',
    };
    rerender(
      <ThemeProvider>
        <I18nProvider initialLocale="en">{banner([acknowledged], onAcknowledge)}</I18nProvider>
      </ThemeProvider>,
    );
    expect(screen.queryByTestId('addendum-banner')).toBeNull();
  });

  it('gives each of several open addenda its own version-named action', () => {
    const onAcknowledge = vi.fn<(addendum: AddendumAcknowledgment) => void>();
    const second: AddendumAcknowledgment = { ...openAddendum, version: openAddendum.version + 1 };
    wrap(banner([openAddendum, second], onAcknowledge));

    fireEvent.click(
      screen.getByRole('button', { name: `Acknowledge version ${openAddendum.version}` }),
    );
    fireEvent.click(screen.getByRole('button', { name: `Acknowledge version ${second.version}` }));
    expect(onAcknowledge).toHaveBeenCalledTimes(2);
    expect(onAcknowledge).toHaveBeenNthCalledWith(1, openAddendum);
    expect(onAcknowledge).toHaveBeenNthCalledWith(2, second);
  });

  it('shows no banner once the addendum is acknowledged', () => {
    wrap(banner([acknowledgedAddendum], vi.fn<(addendum: AddendumAcknowledgment) => void>()));
    expect(screen.queryByTestId('addendum-banner')).toBeNull();
  });

  it('ignores addenda from another tenant or another order', () => {
    wrap(banner(foreignAddenda, vi.fn<(addendum: AddendumAcknowledgment) => void>()));
    expect(screen.queryByTestId('addendum-banner')).toBeNull();
  });

  it('isolates the version numeral and names the action in Arabic under RTL', () => {
    wrap(banner([openAddendum], vi.fn<(addendum: AddendumAcknowledgment) => void>(), 'ar'), 'ar');

    expect(document.documentElement.dir).toBe('rtl');
    const bannerNode = screen.getByTestId('addendum-banner');
    expect(bannerNode.textContent).toContain('إقرار الاستلام');
    const versionRun = bannerNode.querySelector('p bdi');
    expect(versionRun?.getAttribute('dir')).toBe('ltr');
    expect(versionRun?.textContent).toBe(String(openAddendum.version));
    expect(
      screen.getByRole('button', { name: `إقرار استلام الإصدار ${openAddendum.version}` }),
    ).toBeTruthy();
  });
});

function required<T>(value: T | null | undefined): T {
  if (value === null || value === undefined) {
    throw new Error('Expected value to be present');
  }
  return value;
}
