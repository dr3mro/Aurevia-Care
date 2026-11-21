import type { ReactElement } from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { Locale } from '../../i18n/messages';
import { I18nProvider } from '../../i18n/I18nProvider';
import { ThemeProvider } from '../../theme/ThemeProvider';
import { AddendumBanner } from './AddendumBanner';
import { ImagingReportCard } from './ImagingReportCard';
import { REPORT_SECTION_KEYS } from './domain';
import {
  alphaOrganizationId,
  syntheticAddenda,
  syntheticOrderId,
  syntheticSignedReport,
} from './fixtures';

/** The open alpha addendum shown by the banner tests. */
const openAddendum = syntheticAddenda[0]!;
/** The acknowledged alpha addendum used by the negative test. */
const acknowledgedAddendum = syntheticAddenda[1]!;
/** An open addendum from another tenant and another order. */
const foreignAddenda = [syntheticAddenda[2]!, syntheticAddenda[3]!];

/** Mount a component with the theme and locale providers the chart uses. */
function wrap(ui: ReactElement, locale: Locale = 'en') {
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>{ui}</I18nProvider>
    </ThemeProvider>,
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

  it('renders Arabic labels with right-to-left document direction', () => {
    wrap(<ImagingReportCard report={syntheticSignedReport} locale="ar" />, 'ar');

    expect(document.documentElement.lang).toBe('ar');
    expect(document.documentElement.dir).toBe('rtl');
    expect(screen.getByTestId('imaging-report-card').getAttribute('aria-label')).toBe('تقرير الأشعة');
    expect(screen.getByTestId('report-signed-by').textContent).toContain('موقّع بواسطة');
    expect(screen.getByTestId('report-sections').textContent).toContain('النتائج');
  });
});

describe('AddendumBanner', () => {
  it('shows a banner with an acknowledge action until the addendum is acknowledged', () => {
    const onAcknowledge = vi.fn<(version: number) => void>();
    wrap(
      <AddendumBanner
        addenda={[openAddendum]}
        organizationId={alphaOrganizationId}
        orderId={syntheticOrderId}
        locale="en"
        onAcknowledge={onAcknowledge}
      />,
    );

    const banner = screen.getByTestId('addendum-banner');
    expect(banner.getAttribute('aria-label')).toBe('Unacknowledged addendum');
    expect(banner.textContent).toContain(String(openAddendum.version));
    fireEvent.click(screen.getByRole('button', { name: 'Acknowledge' }));
    expect(onAcknowledge).toHaveBeenCalledExactlyOnceWith(openAddendum.version);
  });

  it('shows no banner once the addendum is acknowledged', () => {
    wrap(
      <AddendumBanner
        addenda={[acknowledgedAddendum]}
        organizationId={alphaOrganizationId}
        orderId={syntheticOrderId}
        locale="en"
        onAcknowledge={vi.fn<(version: number) => void>()}
      />,
    );
    expect(screen.queryByTestId('addendum-banner')).toBeNull();
  });

  it('ignores addenda from another tenant or another order', () => {
    wrap(
      <AddendumBanner
        addenda={foreignAddenda}
        organizationId={alphaOrganizationId}
        orderId={syntheticOrderId}
        locale="en"
        onAcknowledge={vi.fn<(version: number) => void>()}
      />,
    );
    expect(screen.queryByTestId('addendum-banner')).toBeNull();
  });

  it('renders the banner and its action in Arabic with right-to-left direction', () => {
    wrap(
      <AddendumBanner
        addenda={[openAddendum]}
        organizationId={alphaOrganizationId}
        orderId={syntheticOrderId}
        locale="ar"
        onAcknowledge={vi.fn<(version: number) => void>()}
      />,
      'ar',
    );

    expect(document.documentElement.dir).toBe('rtl');
    expect(screen.getByTestId('addendum-banner').textContent).toContain('إقرار الاستلام');
    expect(screen.getByRole('button', { name: 'إقرار الاستلام' })).toBeTruthy();
  });
});
