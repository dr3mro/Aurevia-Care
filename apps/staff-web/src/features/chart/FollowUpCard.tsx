import { useState } from 'react';
import { Button } from '../../components/Button';
import { formatCivilDate } from '../../i18n/format';
import type { Locale } from '../../i18n/messages';
import { useTheme } from '../../theme/ThemeProvider';
import type { FollowUpSuggestion } from './domain';
import { chartText } from './messages';

/** Props for the synthetic follow-up suggestion card (CLN-06). */
export interface FollowUpCardProps {
  suggestion: FollowUpSuggestion;
  locale: Locale;
  /** Called only after the explicit booking confirmation is activated. */
  onConfirmBook: () => void;
  /** Called with the typed reason only after an explicit override confirm. */
  onOverride: (reason: string) => void;
}

/** Which confirmation surface is open, if any. */
type FollowUpMode = 'idle' | 'book' | 'override';

/**
 * Present a follow-up suggestion with its rule and two separate commands.
 * Neither command commits on the first click: booking needs an explicit
 * confirmation, and an override needs a non-blank reason typed before its
 * confirm becomes available (CLN-06, WCAG 3.3.4). The suggestion itself never
 * changes when a confirmation is cancelled.
 */
export function FollowUpCard({ suggestion, locale, onConfirmBook, onOverride }: FollowUpCardProps) {
  const { tokens } = useTheme();
  const [mode, setMode] = useState<FollowUpMode>('idle');
  const [reason, setReason] = useState('');
  const dialogStyle = {
    display: 'grid',
    gap: tokens.spacing.sm,
    border: `2px solid ${tokens.border}`,
    padding: tokens.spacing.md,
    minInlineSize: 0,
    overflowWrap: 'anywhere',
  } as const;

  function close(): void {
    setMode('idle');
    setReason('');
  }

  return (
    <section
      data-part="follow-up-card"
      style={{
        display: 'grid',
        gap: tokens.spacing.sm,
        border: `1px solid ${tokens.border}`,
        padding: tokens.spacing.md,
        minInlineSize: 0,
        overflowWrap: 'anywhere',
      }}
    >
      <h4 style={{ marginBlock: 0 }}>{chartText(locale, 'followUpSuggestion')}</h4>
      <time
        dateTime={suggestion.suggestedOn}
        style={{ fontSize: 'var(--sc-font-size-numeric-lg, 1.5rem)' }}
      >
        {formatCivilDate(suggestion.suggestedOn, locale)}
      </time>
      <p style={{ marginBlock: 0 }}>
        <b>{chartText(locale, 'followUpRule')}</b> <span>{suggestion.rule}</span>
      </p>
      <div className="cluster" data-part="follow-up-actions">
        <Button onClick={() => setMode('book')}>{chartText(locale, 'confirmAndBook')}</Button>
        <Button variant="secondary" onClick={() => setMode('override')}>
          {chartText(locale, 'overrideWithReason')}
        </Button>
      </div>
      {mode === 'book' ? (
        <section role="dialog" aria-label={chartText(locale, 'bookingConfirm')} style={dialogStyle}>
          <p style={{ marginBlock: 0 }}>{chartText(locale, 'bookingConfirm')}</p>
          <div className="cluster">
            <Button
              onClick={() => {
                onConfirmBook();
                close();
              }}
            >
              {chartText(locale, 'confirmBooking')}
            </Button>
            <Button variant="secondary" onClick={close}>
              {chartText(locale, 'cancel')}
            </Button>
          </div>
        </section>
      ) : null}
      {mode === 'override' ? (
        <section role="dialog" aria-label={chartText(locale, 'overrideReason')} style={dialogStyle}>
          <label htmlFor="follow-up-override-reason">{chartText(locale, 'overrideReason')}</label>
          <input
            id="follow-up-override-reason"
            className="field touch"
            value={reason}
            onChange={(event) => setReason(event.target.value)}
          />
          {reason.trim().length === 0 ? (
            <p>{chartText(locale, 'overrideReasonRequired')}</p>
          ) : null}
          <div className="cluster">
            <Button
              disabled={reason.trim().length === 0}
              onClick={() => {
                onOverride(reason);
                close();
              }}
            >
              {chartText(locale, 'confirmOverride')}
            </Button>
            <Button variant="secondary" onClick={close}>
              {chartText(locale, 'cancel')}
            </Button>
          </div>
        </section>
      ) : null}
    </section>
  );
}
