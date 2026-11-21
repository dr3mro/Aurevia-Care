import { useState } from 'react';
import { useActiveOrganization } from '../../api/activeOrganization';
import { TransportError } from '../../api/transport';
import { useApi } from '../../api/useApi';
import { useRoute } from '../../app/router';
import { Button } from '../../components/Button';
import { MixedScriptText } from '../../components/MixedScriptText';
import { LoadingState, StateView } from '../../components/StateViews';
import { formatInstant } from '../../i18n/format';
import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
import { getInvoice, issueInvoice, postEntry, voidInvoice } from './api';
import type { EntryBody, EntryKind, VoidBody } from './api';
import { availableActions } from './domain';
import type { InvoiceView } from './domain';
import { entryTypeMessageKey, financeText, invoiceStateMessageKey } from './messages';
import { formatMoney } from './money';
import { PaymentForm } from './PaymentForm';
import type { PaymentDraft } from './PaymentForm';
import { ReversalForm } from './ReversalForm';
import type { ReversalDraft } from './ReversalForm';
import { VoidForm } from './VoidForm';
import type { VoidDraft } from './VoidForm';

/** Normalise an unexpected command rejection to a typed transport failure. */
function toTransportError(failure: unknown): TransportError {
  if (failure instanceof TransportError) {
    return failure;
  }
  return new TransportError('server', {
    correlationId: undefined,
    code: undefined,
    retryAfterSeconds: undefined,
  });
}

/**
 * Read one invoice with its lines and ledger, then dispatch issue, payment,
 * refund, credit and void commands. Typed work is kept when a command fails;
 * a conflict is shown by the shared state view without discarding the form.
 */
export function InvoiceDetailPage() {
  const { params } = useRoute();
  const id = params.id ?? '';
  const organizationId = useActiveOrganization();
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const [commandError, setCommandError] = useState<TransportError | undefined>(undefined);
  const result = useApi<InvoiceView>(() => getInvoice(id, organizationId), [id, organizationId]);

  async function run(command: () => Promise<unknown>): Promise<boolean> {
    try {
      await command();
      setCommandError(undefined);
      result.reload();
      return true;
    } catch (failure: unknown) {
      setCommandError(toTransportError(failure));
      return false;
    }
  }

  function handleIssue(version: number): Promise<boolean> {
    return run(() => issueInvoice(id, version, organizationId));
  }

  function handlePayment(
    version: number,
    draft: PaymentDraft,
    idempotencyKey: string,
  ): Promise<boolean> {
    const body: EntryBody = {
      amountMinor: draft.amountMinor,
      reference: draft.reference,
      reason: draft.reason,
      approverSubject: null,
    };
    return run(() => postEntry(id, 'payments', version, body, organizationId, idempotencyKey));
  }

  function handleReversal(
    kind: Extract<EntryKind, 'refunds' | 'credits'>,
    version: number,
    draft: ReversalDraft,
    idempotencyKey: string,
  ): Promise<boolean> {
    const body: EntryBody = {
      amountMinor: draft.amountMinor,
      reference: draft.reference,
      reason: draft.reason,
      approverSubject: draft.approverSubject,
    };
    return run(() => postEntry(id, kind, version, body, organizationId, idempotencyKey));
  }

  function handleVoid(version: number, draft: VoidDraft): Promise<boolean> {
    const body: VoidBody = { reason: draft.reason, approverSubject: draft.approverSubject };
    return run(() => voidInvoice(id, version, body, organizationId));
  }

  if (result.status === 'loading') {
    return <LoadingState />;
  }
  if (result.status === 'error') {
    return <>{StateView.forTransportError(result.error)}</>;
  }

  const { invoice, items, entries } = result.data;
  const actions = availableActions(invoice);
  // The server refuses to void an invoice that already has ledger entries.
  // A fully refunded invoice returns to `issued` but keeps its trail, so the
  // lifecycle state alone is not enough to offer this irreversible command.
  const canVoid = actions.void && entries.length === 0;

  return (
    <section
      aria-label={financeText(locale, 'finance')}
      style={{ display: 'grid', gap: tokens.spacing.lg }}
    >
      <h2>
        {financeText(locale, 'invoice')} {invoice.id}
      </h2>
      <p>
        {financeText(locale, 'patient')}: <MixedScriptText text={invoice.patientId} lang="en" />
      </p>
      <p>
        {financeText(locale, 'state')}:{' '}
        <span data-testid="finance-state">
          {financeText(locale, invoiceStateMessageKey[invoice.state])}
        </span>
      </p>
      {commandError !== undefined && (
        <div data-testid="finance-command-error">{StateView.forTransportError(commandError)}</div>
      )}
      <dl>
        <dt>{financeText(locale, 'total')}</dt>
        <dd>{formatMoney(invoice.totalMinor, invoice.currency, locale)}</dd>
        <dt>{financeText(locale, 'paid')}</dt>
        <dd>{formatMoney(invoice.paidMinor, invoice.currency, locale)}</dd>
        <dt>{financeText(locale, 'refunded')}</dt>
        <dd>{formatMoney(invoice.refundedMinor, invoice.currency, locale)}</dd>
        <dt>{financeText(locale, 'credited')}</dt>
        <dd>{formatMoney(invoice.creditedMinor, invoice.currency, locale)}</dd>
        <dt>{financeText(locale, 'balance')}</dt>
        <dd>{formatMoney(invoice.balanceMinor, invoice.currency, locale)}</dd>
      </dl>

      <table style={{ borderCollapse: 'collapse', width: '100%' }}>
        <caption style={{ textAlign: 'start' }}>{financeText(locale, 'lines')}</caption>
        <thead>
          <tr>
            <th scope="col">{financeText(locale, 'lineNo')}</th>
            <th scope="col">{financeText(locale, 'description')}</th>
            <th scope="col">{financeText(locale, 'quantity')}</th>
            <th scope="col">{financeText(locale, 'unitAmount')}</th>
            <th scope="col">{financeText(locale, 'amount')}</th>
          </tr>
        </thead>
        <tbody>
          {items.map((item) => (
            <tr key={item.id}>
              <th scope="row">{item.lineNo}</th>
              <td>{item.description}</td>
              <td>{item.quantity}</td>
              <td>{formatMoney(item.unitAmountMinor, invoice.currency, locale)}</td>
              <td>{formatMoney(item.amountMinor, invoice.currency, locale)}</td>
            </tr>
          ))}
        </tbody>
      </table>

      <table style={{ borderCollapse: 'collapse', width: '100%' }}>
        <caption style={{ textAlign: 'start' }}>{financeText(locale, 'ledger')}</caption>
        <thead>
          <tr>
            <th scope="col">{financeText(locale, 'type')}</th>
            <th scope="col">{financeText(locale, 'amount')}</th>
            <th scope="col">{financeText(locale, 'reference')}</th>
            <th scope="col">{financeText(locale, 'reason')}</th>
            <th scope="col">{financeText(locale, 'approver')}</th>
            <th scope="col">{financeText(locale, 'receipt')}</th>
            <th scope="col">{financeText(locale, 'occurred')}</th>
          </tr>
        </thead>
        <tbody>
          {entries.map((entry) => (
            <tr key={entry.id}>
              <th scope="row">{financeText(locale, entryTypeMessageKey[entry.type])}</th>
              <td>{formatMoney(entry.amountMinor, entry.currency, locale)}</td>
              <td>{entry.reference}</td>
              <td>{entry.reason ?? ''}</td>
              <td>{entry.approverSubject ?? ''}</td>
              <td>{entry.receiptNo}</td>
              {/* Finance responses carry no facility zone; label the UTC instant explicitly. */}
              <td>{formatInstant(entry.occurredAt, 'UTC', locale)}</td>
            </tr>
          ))}
        </tbody>
      </table>

      {actions.issue && (
        <Button onClick={() => void handleIssue(invoice.version)}>
          {financeText(locale, 'issueInvoice')}
        </Button>
      )}
      {actions.payment && (
        <PaymentForm
          currency={invoice.currency}
          onSubmit={(draft, idempotencyKey) =>
            handlePayment(invoice.version, draft, idempotencyKey)
          }
        />
      )}
      {actions.refund && (
        <ReversalForm
          mode="refund"
          currency={invoice.currency}
          onSubmit={(draft, idempotencyKey) =>
            handleReversal('refunds', invoice.version, draft, idempotencyKey)
          }
        />
      )}
      {actions.credit && (
        <ReversalForm
          mode="credit"
          currency={invoice.currency}
          onSubmit={(draft, idempotencyKey) =>
            handleReversal('credits', invoice.version, draft, idempotencyKey)
          }
        />
      )}
      {canVoid && (
        <VoidForm onSubmit={(draft) => handleVoid(invoice.version, draft)} />
      )}
    </section>
  );
}
