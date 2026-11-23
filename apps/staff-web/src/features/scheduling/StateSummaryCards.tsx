import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
import type { Appointment, VisitState } from './domain';
import { receptionNow } from './fixtures';
import { schedulingText, schedulingTextWithValue } from './messages';
import type { SchedulingMessageKey } from './messages';

/** The five states summarized by the reception Today cards. */
const cardStates = ['booked', 'checked-in', 'in-progress', 'completed', 'no-show'] as const;

/** Label and note keys for one card. */
const cardLabels: Record<(typeof cardStates)[number], { label: SchedulingMessageKey; note: SchedulingMessageKey }> = {
  booked: { label: 'cardBooked', note: 'noteNeedCheckIn' },
  'checked-in': { label: 'cardCheckedIn', note: 'noteLongestWait' },
  'in-progress': { label: 'cardInProgress', note: 'noteRoomsInUse' },
  completed: { label: 'cardCompleted', note: 'noteNotesUnsigned' },
  'no-show': { label: 'cardNoShow', note: 'noteReasonRecorded' },
};

/** Whole minutes between a synthetic instant and the fixed reception clock. */
function minutesSince(instant: string): number {
  return Math.max(0, Math.round((Date.parse(receptionNow) - Date.parse(instant)) / 60000));
}

/** Props for the five state count cards. */
export interface StateSummaryCardsProps {
  appointments: readonly Appointment[];
}

/**
 * Summarize the active tenant's worklist by state. Counts are derived from the
 * rows the caller already scoped to the tenant; no second tenant boundary and
 * no live clock are introduced, so the display is deterministic (CLN-02).
 */
export function StateSummaryCards({ appointments }: StateSummaryCardsProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const counts: Record<VisitState, number> = {
    booked: 0,
    'checked-in': 0,
    'in-progress': 0,
    completed: 0,
    cancelled: 0,
    'no-show': 0,
  };
  let longestWait = 0;
  for (const appointment of appointments) {
    counts[appointment.state] += 1;
    longestWait = appointment.state === 'checked-in'
      ? Math.max(longestWait, minutesSince(appointment.scheduledAt))
      : longestWait;
  }
  const noteValues: Record<(typeof cardStates)[number], number> = {
    booked: counts.booked,
    'checked-in': longestWait,
    'in-progress': counts['in-progress'],
    completed: counts.completed,
    'no-show': counts['no-show'],
  };

  return (
    <section aria-label={schedulingText(locale, 'stateSummary')} style={{ inlineSize: '100%', minInlineSize: 0 }}>
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))',
          gap: tokens.spacing.md,
          inlineSize: '100%',
        }}
      >
        {cardStates.map((state) => (
          <div
            key={state}
            data-testid="state-card"
            data-state={state}
            style={{
              display: 'grid',
              gap: tokens.spacing.sm,
              paddingBlock: tokens.spacing.lg, paddingInline: tokens.spacing.lg,
              background: tokens.background,
              color: tokens.foreground,
              border: `1px solid ${tokens.border}`,
              borderRadius: tokens.spacing.sm,
              minInlineSize: 0,
              overflowWrap: 'anywhere',
            }}
          >
            <span>{schedulingText(locale, cardLabels[state].label)}</span>
            <strong data-testid={`state-card-${state}`}>{counts[state]}</strong>
            <span>
              {state === 'no-show'
                ? schedulingText(locale, cardLabels[state].note)
                : schedulingTextWithValue(locale, cardLabels[state].note, noteValues[state])}
            </span>
          </div>
        ))}
      </div>
    </section>
  );
}
