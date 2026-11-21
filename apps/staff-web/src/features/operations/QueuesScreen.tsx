import { useState } from 'react';
import { TransportError } from '../../api/transport';
import { Button } from '../../components/Button';
import { EmptyState, StateView } from '../../components/StateViews';
import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
import { ageMs, actionsFor, formatAge, oldestOccurrence, queueCounts } from './domain';
import { actionKey, queuesText, queueTabKey } from './messages';
import type { QueueAction, QueueCollection, QueueId, QueueRow } from './types';
import { queueIds } from './types';

/** The row and action a reason dialog is open for. */
interface PendingAction {
  row: QueueRow;
  action: QueueAction;
}

/** Props for the presentational queue screen. It never contacts the network. */
export interface QueuesScreenProps {
  collection: QueueCollection;
  active: QueueId;
  onActiveChange: (queue: QueueId) => void;
  /** Injected clock so oldest-age tests are deterministic. */
  nowMs: number;
  /** Resolves once when the command succeeds; rejects with the failure. */
  onAction: (
    queue: QueueId,
    row: QueueRow,
    action: QueueAction,
    reason: string,
  ) => Promise<void>;
  onReload: () => void;
}

/**
 * Render the five operational-queue tabs as a worklist. Tabs, tables and the
 * reason dialog are presentational; the page owns loading and the single-send
 * command.
 */
export function QueuesScreen({
  collection,
  active,
  onActiveChange,
  nowMs,
  onAction,
  onReload,
}: QueuesScreenProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const [pending, setPending] = useState<PendingAction | undefined>(undefined);
  const [reason, setReason] = useState('');
  const [reasonInvalid, setReasonInvalid] = useState(false);
  const [actionError, setActionError] = useState<TransportError | undefined>(undefined);
  const [sent, setSent] = useState(false);
  const [sending, setSending] = useState(false);

  const counts = queueCounts(collection);
  const load = collection[active];
  const rows = load.status === 'ready' ? load.rows : [];
  const oldest = oldestOccurrence(rows);
  const oldestLabel =
    oldest === undefined
      ? queuesText(locale, 'notAvailable')
      : `${queuesText(locale, 'oldest')} ${formatAge(ageMs(oldest, nowMs), locale)}`;

  function openAction(row: QueueRow, action: QueueAction): void {
    setPending({ row, action });
    setReason('');
    setReasonInvalid(false);
    setActionError(undefined);
    setSent(false);
  }

  function cancelAction(): void {
    setPending(undefined);
  }

  async function confirmAction(): Promise<void> {
    const target = pending!;
    if (reason.trim() === '') {
      setReasonInvalid(true);
      return;
    }
    setSending(true);
    setReasonInvalid(false);
    try {
      await onAction(active, target.row, target.action, reason.trim());
      setPending(undefined);
      setSent(true);
    } catch (failure: unknown) {
      setActionError(
        failure instanceof TransportError
          ? failure
          : new TransportError('server', {
              correlationId: undefined,
              code: undefined,
              retryAfterSeconds: undefined,
            }),
      );
      setPending(undefined);
    } finally {
      setSending(false);
    }
  }

  return (
    <section
      aria-label={queuesText(locale, 'queuesTitle')}
      style={{ display: 'grid', gap: tokens.spacing.lg }}
    >
      <h2>{queuesText(locale, 'queuesTitle')}</h2>
      <p>{queuesText(locale, 'syntheticNotice')}</p>
      <p role="status" data-testid="operations-status">
        {sent ? queuesText(locale, 'sent') : ''}
      </p>
      {actionError !== undefined && (
        <div data-testid="operations-action-error">{StateView.forTransportError(actionError)}</div>
      )}
      <div role="group" aria-label={queuesText(locale, 'queuesTitle')}>
        {queueIds.map((queue) => (
          <Button
            key={queue}
            variant="secondary"
            pressed={queue === active}
            onClick={() => onActiveChange(queue)}
          >
            {`${queuesText(locale, queueTabKey[queue])} (${counts[queue]})`}
          </Button>
        ))}
      </div>
      {load.status === 'error' ? (
        <div>
          {StateView.forTransportError(load.error)}
          <Button onClick={onReload}>{queuesText(locale, 'retryQueue')}</Button>
        </div>
      ) : rows.length === 0 ? (
        <EmptyState />
      ) : (
        <>
          <p data-testid="operations-oldest">{oldestLabel}</p>
          <table style={{ borderCollapse: 'collapse', width: '100%' }}>
            <caption style={{ textAlign: 'start', padding: tokens.spacing.sm }}>
              {queuesText(locale, queueTabKey[active])}
            </caption>
            <thead>
              <tr>
                <th scope="col">{queuesText(locale, 'colItem')}</th>
                <th scope="col">{queuesText(locale, 'colDetail')}</th>
                <th scope="col">{queuesText(locale, 'colState')}</th>
                <th scope="col">{queuesText(locale, 'colActions')}</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr key={row.id}>
                  <th scope="row">{row.primary}</th>
                  <td>{row.detail}</td>
                  <td>{row.state}</td>
                  <td>
                    {actionsFor(active).map((action) => (
                      <Button
                        key={action}
                        variant="secondary"
                        onClick={() => openAction(row, action)}
                      >
                        {`${queuesText(locale, actionKey[action])} ${row.primary}`}
                      </Button>
                    ))}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
      {pending !== undefined && (
        <div
          role="dialog"
          aria-modal="true"
          aria-labelledby="operations-reason-title"
          data-testid="operations-reason-dialog"
        >
          <h3 id="operations-reason-title">
            {queuesText(locale, actionKey[pending.action])}
          </h3>
          <label htmlFor="operations-reason">{queuesText(locale, 'reason')}</label>
          <textarea
            id="operations-reason"
            value={reason}
            aria-invalid={reasonInvalid}
            aria-describedby={reasonInvalid ? 'operations-reason-error' : undefined}
            onChange={(event) => setReason(event.target.value)}
          />
          {reasonInvalid && (
            <p id="operations-reason-error">{queuesText(locale, 'reasonRequired')}</p>
          )}
          <Button variant="secondary" onClick={cancelAction}>
            {queuesText(locale, 'cancel')}
          </Button>
          <Button onClick={confirmAction} disabled={sending}>
            {queuesText(locale, 'confirm')}
          </Button>
        </div>
      )}
    </section>
  );
}
