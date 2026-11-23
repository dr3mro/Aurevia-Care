import { useState } from 'react';
import type { CSSProperties } from 'react';
import { Button } from '../../components/Button';
import { Dialog } from '../../components/Dialog';
import { FormField } from '../../components/FormField';
import { useI18n } from '../../i18n/I18nProvider';
import type { Locale } from '../../i18n/messages';
import { useTheme } from '../../theme/ThemeProvider';
import type { ThemeTokens } from '../../theme/tokens';
import { useBreakpoint } from '../../theme/useBreakpoint';
import { formatQueueCount, queueTone, toneWordKey } from './domain';
import { operationsText, queueActionKey } from './labels';
import { QUEUE_ACTIONS } from './types';
import type { QueueAction, QueueDraft, QueueRow, QueueTone, QueuesView } from './types';

/** Props for the presentational operations screen. */
export interface QueuesScreenProps {
  /** The queue view loaded for the active tenant. */
  readonly view: QueuesView;
  /** The in-progress reason draft, owned by the page so errors never lose it. */
  readonly draft: QueueDraft | undefined;
  /** Validation message for a missing reason, supplied by the page. */
  readonly reasonError: string | undefined;
  readonly onDraftChange: (draft: QueueDraft | undefined) => void;
  readonly onSubmit: (draft: QueueDraft) => void;
}

/**
 * Style one count pill from the active theme. The word beside the role is the
 * accessible signal; the fill only reinforces it (WCAG 1.4.1). Urgent uses the
 * accent fill, waiting an outlined control and calm the neutral surface.
 */
function pillStyle(tokens: ThemeTokens, tone: QueueTone): CSSProperties {
  if (tone === 'urgent') {
    return {
      background: tokens.accent,
      color: tokens.background,
      border: `1px solid ${tokens.accent}`,
    };
  }
  if (tone === 'waiting') {
    return { background: 'transparent', color: tokens.foreground, border: `2px solid ${tokens.border}` };
  }
  return { background: tokens.background, color: tokens.foreground, border: `1px solid ${tokens.border}` };
}

/** A native, keyboard-activatable pill that opens the reason dialog for one row. */
function CountPill({
  label,
  tone,
  onManage,
}: {
  readonly label: string;
  readonly tone: QueueTone;
  readonly onManage: () => void;
}) {
  const { tokens } = useTheme();
  return (
    <button
      type="button"
      className="touch"
      data-tone={tone}
      aria-label={label}
      onClick={onManage}
      style={{
        ...pillStyle(tokens, tone),
        borderRadius: tokens.spacing.sm,
        paddingBlock: tokens.spacing.sm,
        paddingInline: tokens.spacing.md,
        textAlign: 'center',
        overflowWrap: 'anywhere',
      }}
    >
      {label}
    </button>
  );
}

/** Props for the reason dialog fields. */
interface ReasonFieldsProps {
  readonly draft: QueueDraft;
  readonly locale: Locale;
  readonly reasonError: string | undefined;
  readonly onDraftChange: (draft: QueueDraft) => void;
  readonly onSubmit: (draft: QueueDraft) => void;
}

/** Action choice, mandatory reason and confirm control for one queue command. */
function ReasonFields({
  draft,
  locale,
  reasonError,
  onDraftChange,
  onSubmit,
}: ReasonFieldsProps) {
  const { tokens } = useTheme();
  const actionLabel = operationsText(locale, 'actionLabel');
  return (
    <div className="stack" style={{ gap: tokens.spacing.md }}>
      <label htmlFor="operations-action">{actionLabel}</label>
      <select
        id="operations-action"
        aria-label={actionLabel}
        className="field touch"
        value={draft.action}
        onChange={(event) =>
          onDraftChange({ ...draft, action: event.target.value as QueueAction })
        }
      >
        {QUEUE_ACTIONS.map((action) => (
          <option key={action} value={action}>
            {operationsText(locale, queueActionKey[action])}
          </option>
        ))}
      </select>
      <FormField
        id="operations-reason"
        label={operationsText(locale, 'reasonLabel')}
        value={draft.reason}
        onChange={(reason) => onDraftChange({ ...draft, reason })}
        {...(reasonError !== undefined ? { error: reasonError } : {})}
      />
      <Button onClick={() => onSubmit(draft)}>{operationsText(locale, 'recordAction')}</Button>
    </div>
  );
}

/**
 * Compose the ADM-02 queue card and the missing-coverage card. Data, validation
 * and commands belong to the page; this component only renders them and reports
 * the reason draft. Layout uses logical properties and a single reading column
 * that stacks on a phone; no colour literal or nested direction override.
 */
export function QueuesScreen({
  view,
  draft,
  reasonError,
  onDraftChange,
  onSubmit,
}: QueuesScreenProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const breakpoint = useBreakpoint();
  const [announcement, setAnnouncement] = useState('');

  const queueName = (row: QueueRow): string => operationsText(locale, row.id);
  const open = (row: QueueRow, action: QueueAction): void => {
    onDraftChange({ queueId: row.id, action, reason: '', version: row.version });
  };
  const submit = (next: QueueDraft): void => {
    setAnnouncement(`${operationsText(locale, next.queueId)}: ${operationsText(locale, 'recordAction')}`);
    onSubmit(next);
  };

  return (
    <section
      className="page"
      data-breakpoint={breakpoint}
      data-operations-layout={breakpoint === 'compact' ? 'stacked' : 'columns'}
      aria-label={operationsText(locale, 'title')}
      style={{
        minInlineSize: breakpoint === 'compact' ? 0 : '320px',
        maxInlineSize: '460px',
        display: 'grid',
        gap: tokens.spacing.lg,
      }}
    >
      <h2 style={{ overflowWrap: 'anywhere' }}>{operationsText(locale, 'title')}</h2>
      <p style={{ overflowWrap: 'anywhere' }}>{operationsText(locale, 'subtitle')}</p>
      <p role="status" style={{ overflowWrap: 'anywhere' }}>
        {announcement}
      </p>
      <table style={{ borderCollapse: 'collapse', width: '100%' }}>
        <caption style={{ textAlign: 'start', paddingBlock: tokens.spacing.sm }}>
          {operationsText(locale, 'queueCardLabel')}
        </caption>
        <tbody>
          {view.rows.map((row) => {
            const tone = queueTone(row);
            const word = operationsText(locale, toneWordKey(tone));
            const pillLabel = `${queueName(row)}: ${word} ${formatQueueCount(row.count, locale)}`;
            return (
              <tr key={row.id}>
                <th scope="row" style={{ textAlign: 'start', paddingBlock: tokens.spacing.sm }}>
                  <strong>{queueName(row)}</strong>
                  <p style={{ overflowWrap: 'anywhere' }}>{operationsText(locale, row.noteKey)}</p>
                </th>
                <td style={{ textAlign: 'end', paddingInlineStart: tokens.spacing.md }}>
                  <CountPill label={pillLabel} tone={tone} onManage={() => open(row, 'assign')} />
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
      <section
        aria-label={operationsText(locale, 'coverageRegion')}
        style={{
          display: 'grid',
          gap: tokens.spacing.md,
          border: `1px solid ${tokens.border}`,
          padding: tokens.spacing.md,
        }}
      >
        <h3 style={{ overflowWrap: 'anywhere' }}>
          {operationsText(locale, 'coverageTitle').replace('{unit}', view.missingCoverage.unit)}
        </h3>
        <p style={{ overflowWrap: 'anywhere' }}>
          {operationsText(locale, 'coverageExplanation').replace(
            '{window}',
            view.missingCoverage.window,
          )}
        </p>
        <Button
          onClick={() =>
            open(
              { id: 'missing-coverage', count: 1, noteKey: 'noteSunday', version: view.missingCoverage.version },
              'assign',
            )
          }
        >
          {operationsText(locale, 'assignBackup')}
        </Button>
      </section>
      <Dialog
        open={draft !== undefined}
        title={operationsText(locale, 'actionDialogTitle').replace(
          '{queue}',
          draft === undefined ? '' : operationsText(locale, draft.queueId),
        )}
        closeLabel={operationsText(locale, 'cancel')}
        onClose={() => onDraftChange(undefined)}
      >
        {draft !== undefined ? (
          <ReasonFields
            draft={draft}
            locale={locale}
            reasonError={reasonError}
            onDraftChange={onDraftChange}
            onSubmit={submit}
          />
        ) : null}
      </Dialog>
    </section>
  );
}
