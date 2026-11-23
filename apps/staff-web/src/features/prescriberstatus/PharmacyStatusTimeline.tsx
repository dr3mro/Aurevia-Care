import type { StatusViewResponse } from '../pharmacyinbox/types';
import { MixedScriptText } from '../../components/MixedScriptText';
import { formatInstant } from '../../i18n/format';
import type { Locale } from '../../i18n/messages';
import { useTheme } from '../../theme/ThemeProvider';
import { timelineRows } from './domain';
import { prescriberText } from './labels';

/** Props for the prescriber's read-only pharmacy status timeline. */
export interface PharmacyStatusTimelineProps {
  status: StatusViewResponse;
  locale: Locale;
  timeZone: string;
}

/**
 * Present one fact per row so submitted, delivery-confirmed, accepted or
 * rejected, clarification and dispensing never collapse into a single state.
 * Dispensing evidence stays on the dispensing row; acceptance never implies it.
 */
export function PharmacyStatusTimeline({ status, locale, timeZone }: PharmacyStatusTimelineProps) {
  const { tokens } = useTheme();
  const rows = timelineRows(status.events);

  return (
    <section
      aria-label={prescriberText(locale, 'timelineTitle')}
      style={{ display: 'grid', gap: tokens.spacing.md }}
    >
      <h2>{prescriberText(locale, 'timelineTitle')}</h2>
      {rows.length === 0 ? (
        <p>{prescriberText(locale, 'noEvents')}</p>
      ) : (
        <table aria-label={prescriberText(locale, 'timelineTitle')}>
          <thead>
            <tr>
              <th scope="col">{prescriberText(locale, 'factId')}</th>
              <th scope="col">{prescriberText(locale, 'factType')}</th>
              <th scope="col">{prescriberText(locale, 'factDetail')}</th>
              <th scope="col">{prescriberText(locale, 'factActor')}</th>
              <th scope="col">{prescriberText(locale, 'factOccurredAt')}</th>
              <th scope="col">{prescriberText(locale, 'dispensingEvidence')}</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={row.id} data-testid={`timeline-row-${row.id}`}>
                <td>
                  <code dir="ltr">{row.id}</code>
                </td>
                <th scope="row">{prescriberText(locale, row.labelKey)}</th>
                <td>
                  {row.detail === null ? null : <MixedScriptText text={row.detail} lang="en" />}
                </td>
                <td>
                  <MixedScriptText text={row.actor} lang="en" />
                </td>
                <td>
                  <time dateTime={row.occurredAt}>
                    {formatInstant(row.occurredAt, timeZone, locale)}
                  </time>
                </td>
                <td>
                  <div>
                    {prescriberText(locale, 'dispensedBy')}:{' '}
                    <code dir="ltr">
                      {row.dispensedBy ?? prescriberText(locale, 'notRecorded')}
                    </code>
                  </div>
                  <div>
                    {prescriberText(locale, 'dispensedAt')}:{' '}
                    <code dir="ltr">
                      {row.dispensedAt ?? prescriberText(locale, 'notRecorded')}
                    </code>
                  </div>
                  <div>
                    {prescriberText(locale, 'handedOver')}:{' '}
                    <code dir="ltr">{row.handedOver ?? prescriberText(locale, 'notRecorded')}</code>
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
