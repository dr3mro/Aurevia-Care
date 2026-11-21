import { MixedScriptText } from '../../components/MixedScriptText';
import { formatInstant } from '../../i18n/format';
import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
import type { PrescriptionEvent } from './domain';
import { prescriptionEventMessageKey, prescriptionText } from './messages';

/** Props for the append-only prescription status timeline. */
export interface StatusTimelineProps {
  events: readonly PrescriptionEvent[];
  submissionId: string | null;
}

/**
 * Render the distinct transmission and pharmacy events the API returned. The
 * interface never infers an event from a colour or from the current channel.
 */
export function StatusTimeline({ events, submissionId }: StatusTimelineProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  return (
    <section
      aria-label={prescriptionText(locale, 'statusTimeline')}
      style={{ display: 'grid', gap: tokens.spacing.sm }}
    >
      <h3>{prescriptionText(locale, 'statusTimeline')}</h3>
      {events.length === 0 ? (
        <p role="status" data-testid="rx-empty-timeline">
          {prescriptionText(locale, 'emptyTimeline')}
        </p>
      ) : (
        <>
          {submissionId !== null ? (
            <p>
              {prescriptionText(locale, 'submissionId')}: <code dir="ltr">{submissionId}</code>
            </p>
          ) : null}
          <ol style={{ display: 'grid', gap: tokens.spacing.sm }}>
            {events.map((event) => (
              <li key={`${event.submissionId}-${event.kind}-${event.occurredAt}`} data-kind={event.kind}>
                <MixedScriptText
                  text={prescriptionText(locale, prescriptionEventMessageKey[event.kind])}
                  lang={locale}
                />
                <time dateTime={event.occurredAt}>
                  {' '}
                  {formatInstant(event.occurredAt, 'UTC', locale)}
                </time>
              </li>
            ))}
          </ol>
        </>
      )}
    </section>
  );
}
