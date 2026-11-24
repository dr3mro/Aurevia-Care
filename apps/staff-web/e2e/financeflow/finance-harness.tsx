import { StrictMode, useState } from 'react';
import type { ReactElement } from 'react';
import { createRoot } from 'react-dom/client';
import { ActiveOrganizationProvider, useActiveOrganization } from '../../src/api/activeOrganization';
import { syntheticFacility } from '../../src/fixtures/synthetic';
import { I18nProvider, useI18n } from '../../src/i18n/I18nProvider';
import type { Locale } from '../../src/i18n/messages';
import { ThemeProvider } from '../../src/theme/ThemeProvider';
import { reconciliationDate } from './synthetic-finance-api';

/**
 * Dev-only daily-reconciliation workstation (WEB-10, FIN-01).
 *
 * The navigation rail declares `/finance` but no screen is implemented yet.
 * This mounts a small typed component under the real providers and calls the
 * synthetic boundary with the selected role. It is not a production entry
 * point: in the portal the role comes from the authenticated session, never
 * from a query parameter.
 */

/** Visible outcome of one reconciliation read. */
type ReadStatus = 'pending' | 'shown' | 'forbidden';

/** One table of harness words with identical keys in both locales. */
interface HarnessWords {
  heading: string;
  intro: string;
  roleLabel: string;
  financeRole: string;
  receptionRole: string;
  run: string;
  pending: string;
  totalLabel: string;
  entriesLabel: string;
  forbidden: string;
}

const words: Record<Locale, HarnessWords> = {
  en: {
    heading: 'Daily reconciliation',
    intro: 'Synthetic totals only: no clinical narrative is shown to finance roles.',
    roleLabel: 'Workspace role',
    financeRole: 'finance',
    receptionRole: 'reception',
    run: 'Read reconciliation',
    pending: 'No reconciliation has been read.',
    totalLabel: 'Total in minor units',
    entriesLabel: 'Posted entries',
    forbidden: 'You do not have access to reconciliation.',
  },
  ar: {
    heading: 'التسوية اليومية',
    intro: 'إجماليات توضيحية فقط: لا تُعرض أي سردية سريرية للأدوار المالية.',
    roleLabel: 'دور مساحة العمل',
    financeRole: 'المالية',
    receptionRole: 'الاستقبال',
    run: 'قراءة التسوية',
    pending: 'لم تُقرأ أي تسوية بعد.',
    totalLabel: 'الإجمالي بوحدات صغيرة',
    entriesLabel: 'القيود المُرحّلة',
    forbidden: 'لا تملك صلاحية الوصول إلى التسوية.',
  },
};

/** Stable tenant object; hoisted so React sees one identity per mount. */
const tenant = { organizationId: syntheticFacility.organizationId };

/** The workstation under one persistent, visible locale switch. */
function Workstation(): ReactElement {
  const { locale, toggleLocale } = useI18n();
  const organizationId = useActiveOrganization();
  const text = words[locale];
  const isReception = new URLSearchParams(window.location.search).get('role') === 'reception';
  const role = isReception ? 'reception' : 'finance';
  const [status, setStatus] = useState<ReadStatus>('pending');
  const [total, setTotal] = useState<number | null>(null);
  const [entries, setEntries] = useState<number | null>(null);

  async function read(): Promise<void> {
    const response = await fetch(
      `/api/v1/finance/reconciliation?date=${encodeURIComponent(reconciliationDate)}`,
      {
        headers: {
          'X-Active-Organization': organizationId,
          'X-Correlation-Id': 'synthetic-correlation-finance',
          'X-XSRF-TOKEN': 'synthetic-csrf-token',
          'X-Active-Role': role,
        },
      },
    );
    if (!response.ok) {
      setStatus('forbidden');
      return;
    }
    const body: unknown = await response.json();
    if (
      typeof body === 'object' &&
      body !== null &&
      'totalMinorUnits' in body &&
      'postedEntries' in body
    ) {
      const reportedTotal: unknown = Reflect.get(body, 'totalMinorUnits');
      const reportedEntries: unknown = Reflect.get(body, 'postedEntries');
      if (typeof reportedTotal === 'number' && typeof reportedEntries === 'number') {
        setTotal(reportedTotal);
        setEntries(reportedEntries);
        setStatus('shown');
      }
    }
  }

  return (
    <div style={{ display: 'grid', gap: '1rem' }}>
      <button type="button" onClick={toggleLocale}>
        {locale === 'en' ? 'العربية' : 'English'}
      </button>
      <h2>{text.heading}</h2>
      <p>{text.intro}</p>
      <p data-testid="finance-role">{text.roleLabel}: {isReception ? text.receptionRole : text.financeRole}</p>
      <button type="button" onClick={() => void read()}>
        {text.run}
      </button>
      <p role="status" data-testid="finance-status" data-status={status}>
        {status === 'pending' ? text.pending : ''}
        {status === 'forbidden' ? text.forbidden : ''}
        {status === 'shown' ? `${text.totalLabel}: ${total ?? 0}; ${text.entriesLabel}: ${entries ?? 0}` : ''}
      </p>
    </div>
  );
}

/** Mount the harness tree with the same providers the staff portal uses. */
function Harness(): ReactElement {
  return (
    <StrictMode>
      <ThemeProvider>
        <I18nProvider>
          <ActiveOrganizationProvider value={tenant}>
            <Workstation />
          </ActiveOrganizationProvider>
        </I18nProvider>
      </ThemeProvider>
    </StrictMode>
  );
}

const container = document.getElementById('finance-harness');
if (container === null) {
  throw new Error('finance-harness container is required');
}
createRoot(container).render(<Harness />);
