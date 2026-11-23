import type { ReactElement } from 'react';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { I18nProvider } from '../../i18n/I18nProvider';
import type { Locale } from '../../i18n/messages';
import { ThemeProvider } from '../../theme/ThemeProvider';
import type { ImagingWorklistOrder } from './domain';
import {
  alphaOrganizationId,
  betaOrganizationId,
  syntheticOrders,
  syntheticPerformingSite,
} from './fixtures';
import { RadWorklistScreen } from './RadWorklistScreen';

/** Render the screen with inert callbacks and the supplied locale. */
function wrap(
  orders: readonly ImagingWorklistOrder[],
  locale: Locale = 'en',
  overrides: Partial<Parameters<typeof RadWorklistScreen>[0]> = {},
) {
  const props: Parameters<typeof RadWorklistScreen>[0] = {
    orders,
    organizationId: alphaOrganizationId,
    site: syntheticPerformingSite,
    filter: 'all',
    onSiteChange: vi.fn(),
    onFilterChange: vi.fn(),
    onSchedule: vi.fn(),
    onLink: vi.fn(),
    onHandoff: vi.fn(),
    ...overrides,
  };
  const ui: ReactElement = (
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <RadWorklistScreen {...props} />
      </I18nProvider>
    </ThemeProvider>
  );
  return { ...render(ui), props };
}

/** Select the row whose order id is supplied. */
function openRow(orderId: string) {
  const row = required(
    screen
      .getByText(required(syntheticOrders.find((order) => order.orderId === orderId)).orderCode)
      .closest('tr'),
  );
  fireEvent.click(required(within(row).getAllByRole('button')[0]));
}

describe('RadWorklistScreen worklist', () => {
  it('renders all scoped rows with their worklist status in words', () => {
    wrap(syntheticOrders.slice(0, 4));
    const table = screen.getByRole('table');
    expect(
      within(table)
        .getAllByTestId('worklist-status')
        .map((badge) => badge.textContent),
    ).toEqual(['Received', 'Scheduled', 'Performed', 'Reported']);
    expect(within(table).getAllByText('Not recorded').length).toBeGreaterThan(0);
    expect(within(table).getByText('MRI')).toBeTruthy();
  });

  it('shows a plain empty state when the filter has no orders', () => {
    wrap([]);
    expect(screen.getByText('No orders for this site and status.')).toBeTruthy();
    expect(screen.getByText('Select an order to see its actions.')).toBeTruthy();
  });

  it('reports filter and site changes through callbacks', () => {
    const { props } = wrap(syntheticOrders.slice(0, 4), 'en', { filter: 'all' });
    fireEvent.click(screen.getByRole('button', { name: 'Scheduled' }));
    expect(props.onFilterChange).toHaveBeenCalledWith('scheduled');
    expect(screen.getByRole('button', { name: 'All statuses' }).getAttribute('aria-pressed')).toBe(
      'true',
    );

    fireEvent.change(screen.getByLabelText('Performing site'), {
      target: { value: 'site-rad-other' },
    });
    expect(props.onSiteChange).toHaveBeenCalledWith('site-rad-other');
  });

  it('resets the open order when the tenant changes', () => {
    const { rerender } = wrap(syntheticOrders.slice(0, 4));
    openRow('rad-003');
    expect(screen.getByRole('button', { name: 'Hand off as reported' })).toBeTruthy();

    rerender(
      <ThemeProvider>
        <I18nProvider initialLocale="en">
          <RadWorklistScreen
            orders={syntheticOrders.slice(0, 4)}
            organizationId={betaOrganizationId}
            site={syntheticPerformingSite}
            filter="all"
            onSiteChange={vi.fn()}
            onFilterChange={vi.fn()}
            onSchedule={vi.fn()}
            onLink={vi.fn()}
            onHandoff={vi.fn()}
          />
        </I18nProvider>
      </ThemeProvider>,
    );
    // The open row resets to the first order in the incoming projection.
    expect(screen.getByRole('heading', { name: 'ORD-001' })).toBeTruthy();
    expect(screen.queryByRole('heading', { name: 'ORD-003' })).toBeNull();
  });
});

describe('RadWorklistScreen order actions', () => {
  it('offers an accession and modality form for a received order', () => {
    wrap(syntheticOrders.slice(0, 4));
    expect(screen.getByRole('form', { name: 'Schedule the study' })).toBeTruthy();
    expect(screen.queryByRole('form', { name: 'Link the external study' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Hand off as reported' })).toBeNull();
  });

  it('offers a verified study link for a scheduled order', () => {
    wrap(syntheticOrders.slice(0, 4));
    openRow('rad-002');
    expect(screen.getByRole('form', { name: 'Link the external study' })).toBeTruthy();
    expect(screen.getByTestId('label-check').getAttribute('data-verdict')).toBe('pending');
    expect(screen.queryByRole('form', { name: 'Schedule the study' })).toBeNull();
  });

  it('offers a handoff action for a performed order', () => {
    const { props } = wrap(syntheticOrders.slice(0, 4));
    openRow('rad-003');
    const handoff = screen.getByRole('button', { name: 'Hand off as reported' });
    fireEvent.click(handoff);
    expect(props.onHandoff).toHaveBeenCalledWith(syntheticOrders[2]);
  });

  it('offers no action for a reported order', () => {
    wrap(syntheticOrders.slice(0, 4));
    openRow('rad-004');
    expect(screen.queryByRole('form', { name: 'Schedule the study' })).toBeNull();
    expect(screen.queryByRole('form', { name: 'Link the external study' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Hand off as reported' })).toBeNull();
  });

  it('forwards a complete schedule draft with its order', () => {
    const { props } = wrap(syntheticOrders.slice(0, 4));
    fireEvent.change(screen.getByLabelText('Scheduled instant (UTC ISO)'), {
      target: { value: '2026-03-09T09:00:00Z' },
    });
    fireEvent.change(screen.getByLabelText('Accession'), { target: { value: 'ACC-001' } });
    fireEvent.change(screen.getByLabelText('Accession issuer'), { target: { value: 'SYN-ORG' } });
    fireEvent.change(screen.getByLabelText('Modality'), { target: { value: 'ct' } });
    fireEvent.click(screen.getByRole('button', { name: 'Record schedule' }));
    expect(props.onSchedule).toHaveBeenCalledWith(syntheticOrders[0], {
      scheduledAt: '2026-03-09T09:00:00Z',
      modality: 'ct',
      accession: 'ACC-001',
      accessionIssuer: 'SYN-ORG',
    });
  });

  it('blocks a wrong-patient link through the screen', () => {
    const { props } = wrap(syntheticOrders.slice(0, 4));
    openRow('rad-002');
    fireEvent.change(screen.getByLabelText('Patient label from workstation'), {
      target: { value: 'wrong-patient' },
    });
    fireEvent.change(screen.getByLabelText('Accession label from workstation'), {
      target: { value: 'ACC-002' },
    });
    fireEvent.change(screen.getByLabelText('Study Instance UID'), { target: { value: '1.2.826' } });
    fireEvent.change(screen.getByLabelText('Diagnostic workstation reference'), {
      target: { value: 'workstation-1' },
    });
    const link = screen.getByRole<HTMLButtonElement>('button', { name: 'Link study' });
    expect(link.disabled).toBe(true);
    fireEvent.click(link);
    expect(props.onLink).not.toHaveBeenCalled();
  });
});

describe('RadWorklistScreen localization', () => {
  it('renders Arabic labels and document direction while keeping the table', () => {
    wrap(syntheticOrders.slice(0, 4), 'ar');
    expect(document.documentElement.dir).toBe('rtl');
    expect(document.documentElement.lang).toBe('ar');
    expect(screen.getByRole('table')).toBeTruthy();
    expect(screen.getAllByText('قائمة أعمال الأشعة').length).toBeGreaterThan(0);
    expect(screen.getAllByText('تم إجراء الفحص').length).toBeGreaterThan(0);
    openRow('rad-002');
    const check = screen.getByTestId('label-check');
    expect(check.textContent).toContain('أدخل كلا المعرّفين');
  });
});

function required<T>(value: T | null | undefined): T {
  if (value === null || value === undefined) {
    throw new Error('Expected value to be present');
  }
  return value;
}
