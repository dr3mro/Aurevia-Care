import { useEffect, useState } from 'react';
import { useActiveOrganization } from '../../api/activeOrganization';
import { useApi } from '../../api/useApi';
import { Link } from '../../app/router';
import { Button } from '../../components/Button';
import { MixedScriptText } from '../../components/MixedScriptText';
import { EmptyState, LoadingState, StateView } from '../../components/StateViews';
import { formatInstant } from '../../i18n/format';
import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
import { listInvoices } from './api';
import type { InvoicePage as InvoicePageData, InvoiceView, InvoiceState } from './domain';
import { invoiceStates } from './domain';
import { financeText, invoiceStateMessageKey } from './messages';
import { formatMoney } from './money';

/**
 * List self-pay invoices with an exact status filter and cursor paging. Rows
 * are accumulated across pages but cleared synchronously when the tenant or
 * the filter changes, so a previous selection is never painted (ARC-02).
 */
export function InvoicesPage() {
  const organizationId = useActiveOrganization();
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const [status, setStatus] = useState<InvoiceState | 'all'>('all');
  const [cursor, setCursor] = useState<string | undefined>(undefined);
  const [accumulated, setAccumulated] = useState<readonly InvoiceView[]>([]);
  const [nextCursor, setNextCursor] = useState<string | undefined>(undefined);
  const [renderedOrganization, setRenderedOrganization] = useState(organizationId);

  // Reset during render, before a response from the previous tenant can paint.
  if (organizationId !== renderedOrganization) {
    setRenderedOrganization(organizationId);
    setAccumulated([]);
    setCursor(undefined);
    setNextCursor(undefined);
  }

  const result = useApi<InvoicePageData>(
    () =>
      listInvoices(
        { status: status === 'all' ? undefined : status, cursor },
        organizationId,
      ),
    [status, cursor],
  );

  useEffect(() => {
    if (result.status !== 'ready') {
      return;
    }
    const page = result.data;
    setAccumulated((current) => [...current, ...page.items]);
    setNextCursor(page.nextCursor ?? undefined);
  }, [result.status, result.data]);

  function handleStatusChange(next: InvoiceState | 'all') {
    setStatus(next);
    setCursor(undefined);
    setAccumulated([]);
    setNextCursor(undefined);
  }

  function handleLoadMore() {
    setCursor(nextCursor);
  }

  const loaded = accumulated.map((view) => view.invoice);

  return (
    <section
      aria-label={financeText(locale, 'finance')}
      style={{ display: 'grid', gap: tokens.spacing.lg }}
    >
      <h2>{financeText(locale, 'invoices')}</h2>
      <p>{financeText(locale, 'syntheticNotice')}</p>
      <div style={{ display: 'flex', gap: tokens.spacing.md }}>
        <label htmlFor="finance-status">{financeText(locale, 'status')}</label>
        <select
          id="finance-status"
          value={status}
          onChange={(event) => handleStatusChange(event.target.value as InvoiceState | 'all')}
        >
          <option value="all">{financeText(locale, 'statusAll')}</option>
          {invoiceStates.map((state) => (
            <option key={state} value={state}>
              {financeText(locale, invoiceStateMessageKey[state])}
            </option>
          ))}
        </select>
      </div>

      {result.status === 'loading' && loaded.length === 0 ? (
        <LoadingState />
      ) : result.status === 'error' && loaded.length === 0 ? (
        StateView.forTransportError(result.error)
      ) : loaded.length === 0 ? (
        <EmptyState />
      ) : (
        <>
          <table style={{ borderCollapse: 'collapse', width: '100%' }}>
            <caption style={{ textAlign: 'start' }}>{financeText(locale, 'invoices')}</caption>
            <thead>
              <tr>
                <th scope="col">{financeText(locale, 'invoice')}</th>
                <th scope="col">{financeText(locale, 'patient')}</th>
                <th scope="col">{financeText(locale, 'state')}</th>
                <th scope="col">{financeText(locale, 'total')}</th>
                <th scope="col">{financeText(locale, 'balance')}</th>
                <th scope="col">{financeText(locale, 'created')}</th>
                <th scope="col">{financeText(locale, 'actions')}</th>
              </tr>
            </thead>
            <tbody>
              {loaded.map((invoice) => (
                <tr key={invoice.id}>
                  <th scope="row">{invoice.id}</th>
                  <td>
                    <MixedScriptText text={invoice.patientId} lang="en" />
                  </td>
                  <td>{financeText(locale, invoiceStateMessageKey[invoice.state])}</td>
                  <td>{formatMoney(invoice.totalMinor, invoice.currency, locale)}</td>
                  <td>{formatMoney(invoice.balanceMinor, invoice.currency, locale)}</td>
                  {/* Finance rows carry no facility zone; show the UTC instant explicitly. */}
                  <td>{formatInstant(invoice.createdAt, 'UTC', locale)}</td>
                  <td>
                    {/* The shared Link forwards only children, so the row
                        identity is part of its visible (and accessible) name. */}
                    <Link to={`/finance/${encodeURIComponent(invoice.id)}`}>
                      {financeText(locale, 'open')} {invoice.id}
                    </Link>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {nextCursor !== undefined && (
            <Button onClick={handleLoadMore} disabled={result.status === 'loading'}>
              {financeText(locale, 'loadMore')}
            </Button>
          )}
        </>
      )}
    </section>
  );
}
