import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { I18nProvider } from '../../i18n/I18nProvider';
import { ThemeProvider } from '../../theme/ThemeProvider';
import { ReconciliationPanel } from './ReconciliationPanel';
import { matchedReconciliation, mismatchedReconciliation } from './fixtures';
import type { ReconciliationCase } from './types';

/** Optional mount inputs for the reconciliation panel. */
interface PanelOptions {
  readonly onAskLab?: () => void;
  readonly onAssignOwner?: () => void;
  readonly onLink?: () => void;
  readonly locale?: 'en' | 'ar';
}

/** Mount the panel with injectable callbacks. */
function renderPanel(reconciliation: ReconciliationCase | undefined, options: PanelOptions = {}) {
  const onAskLab = options.onAskLab ?? vi.fn();
  const onAssignOwner = options.onAssignOwner ?? vi.fn();
  const onLink = options.onLink ?? vi.fn();
  const locale = options.locale ?? 'en';
  const view = render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <ReconciliationPanel
          reconciliation={reconciliation}
          locale={locale}
          onAskLab={onAskLab}
          onAssignOwner={onAssignOwner}
          onLink={onLink}
        />
      </I18nProvider>
    </ThemeProvider>,
  );
  return { ...view, onAskLab, onAssignOwner, onLink };
}

describe('ReconciliationPanel', () => {
  it('shows report and order values plus the differing-field warning', () => {
    renderPanel(mismatchedReconciliation);
    expect(screen.getByText('Reconciliation: unmatched report')).toBeTruthy();
    expect(screen.getByText('Report says')).toBeTruthy();
    expect(screen.getByText('Closest order')).toBeTruthy();
    expect(screen.getByText('Lina Farouk')).toBeTruthy();
    expect(screen.getByText('Lina Farouq')).toBeTruthy();
    expect(screen.getByText('Name differs. Do not link until the lab confirms.')).toBeTruthy();
  });

  it('never offers a link control when the identifiers differ', () => {
    renderPanel(mismatchedReconciliation);
    expect(screen.queryByRole('button', { name: 'Link report to order' })).toBeNull();
  });

  it('offers Ask the lab and Assign owner as callable actions', () => {
    const { onAskLab, onAssignOwner } = renderPanel(mismatchedReconciliation);
    fireEvent.click(screen.getByRole('button', { name: 'Ask the lab' }));
    fireEvent.click(screen.getByRole('button', { name: 'Assign owner' }));
    expect(onAskLab).toHaveBeenCalledTimes(1);
    expect(onAssignOwner).toHaveBeenCalledTimes(1);
  });

  it('offers a callable link only when every identifier matched', () => {
    const { onLink } = renderPanel(matchedReconciliation);
    expect(screen.getByText('Identifiers match. Linking is allowed.')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Link report to order' }));
    expect(onLink).toHaveBeenCalledTimes(1);
  });

  it('shows both name forms with correct language tags in Arabic', () => {
    renderPanel(mismatchedReconciliation, { locale: 'ar' });
    const latin = screen.getAllByText(/Lina Farouk|Lina Farouq/);
    expect(latin.length).toBeGreaterThan(0);
    expect(latin.every((run) => run.getAttribute('lang') === 'en')).toBe(true);
    expect(screen.getAllByText('لينا فاروق').length).toBeGreaterThan(0);
    expect(screen.getAllByText('لينا فاروق')[0]?.getAttribute('lang')).toBe('ar');
    expect(screen.getByText('الاسم مختلف. لا تربط قبل تأكيد المختبر.')).toBeTruthy();
  });

  it('tags a Latin-only identity run as English under Arabic', () => {
    const withoutLocal: ReconciliationCase = {
      ...mismatchedReconciliation,
      report: {
        ...mismatchedReconciliation.report,
        patient: { latinName: 'Synthetic LatinOnly' },
      },
    };
    renderPanel(withoutLocal, { locale: 'ar' });
    expect(screen.getByText('Synthetic LatinOnly').getAttribute('lang')).toBe('en');
  });

  it('renders nothing when there is no reconciliation case', () => {
    const { container } = renderPanel(undefined);
    expect(container.firstChild).toBeNull();
  });
});
