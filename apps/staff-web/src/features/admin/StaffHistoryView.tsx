import type { ReactNode } from 'react';
import { MixedScriptText } from '../../components/MixedScriptText';
import { formatInstant } from '../../i18n/format';
import { useI18n } from '../../i18n/I18nProvider';
import type { Locale } from '../../i18n/messages';
import { useTheme } from '../../theme/ThemeProvider';
import { useBreakpoint } from '../../theme/useBreakpoint';
import { AdminTableRegion } from './AdminTableRegion';
import type { StaffHistoryEvent } from './domain';
import { currentRoles, historyFor, isDeactivated } from './domain';
import { adminText, staffEventMessageKey } from './messages';
import type { AdminMessageKey } from './messages';
import { StateBadge } from './StateBadge';

/** Shape cues for the three append-only staff event kinds. */
const eventGlyph: Record<StaffHistoryEvent['kind'], string> = {
  invited: '○',
  role_granted: '＋',
  deactivated: '×',
};

/** Every clinical column, in the fixed order shared by the table and the cards. */
const HISTORY_COLUMNS = [
  'sequence',
  'subject',
  'event',
  'actor',
  'grantor',
  'role',
  'effectiveAtUtc',
  'deactivationReason',
] as const satisfies readonly AdminMessageKey[];

/** Props for one subject's append-only membership history. */
export interface StaffHistoryViewProps {
  events: readonly StaffHistoryEvent[];
  organizationId: string;
  subject: string;
  /** Language of free-text deactivation reasons; fixtures stay English by default. */
  reasonLang?: Locale;
}

/**
 * Build the eight clinical values of one append-only event in column order.
 *
 * A single source of truth keeps the wide table and the phone cards showing
 * exactly the same fields: no clinical column is added, removed or hidden when
 * the layout band changes (WEB-07, UX-01, decision 0004).
 */
function eventValues(
  event: StaffHistoryEvent,
  locale: Locale,
  reasonLang: Locale,
  text: (key: AdminMessageKey) => string,
): ReactNode[] {
  return [
    event.sequence,
    <MixedScriptText key="subject" text={event.subject} lang="en" />,
    <StateBadge
      key="event"
      glyph={eventGlyph[event.kind]}
      label={adminText(locale, staffEventMessageKey[event.kind])}
      tone={event.kind}
    />,
    <MixedScriptText key="actor" text={event.actor} lang="en" />,
    <MixedScriptText
      key="grantor"
      text={event.kind === 'role_granted' ? event.actor : text('notApplicable')}
      lang={event.kind === 'role_granted' ? 'en' : locale}
    />,
    <MixedScriptText
      key="role"
      text={event.kind === 'role_granted' ? event.role : text('notApplicable')}
      lang={event.kind === 'role_granted' ? 'en' : locale}
    />,
    <time key="effectiveAtUtc" dateTime={event.effectiveFrom}>
      {formatInstant(event.effectiveFrom, 'UTC', locale)}
    </time>,
    <MixedScriptText
      key="deactivationReason"
      text={event.kind === 'deactivated' ? event.reason : text('notApplicable')}
      lang={event.kind === 'deactivated' ? reasonLang : locale}
    />,
  ];
}

/** Show grantor, effective date and deactivation as immutable events. */
export function StaffHistoryView({
  events,
  organizationId,
  subject,
  reasonLang = 'en',
}: StaffHistoryViewProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const breakpoint = useBreakpoint();
  const compact = breakpoint === 'compact';
  const text = (key: Parameters<typeof adminText>[1]) => adminText(locale, key);
  const history = historyFor(events, organizationId, subject);
  const deactivated = isDeactivated(history);
  const roles = currentRoles(history);

  // Long English and Arabic values wrap instead of clipping or forcing a
  // page-level scroll. Only logical properties are used so RTL needs no
  // second rule set; responsive.css has no dedicated clinical-cell class.
  const cellStyle = {
    minInlineSize: 0,
    overflowWrap: 'anywhere',
    whiteSpace: 'normal',
  } as const;

  const wideTable = (
    <AdminTableRegion label={text('staffHistory')}>
      <table style={{ borderCollapse: 'collapse', inlineSize: '100%' }}>
        <caption style={{ textAlign: 'start', paddingBlock: tokens.spacing.sm }}>
          {text('staffHistory')}
        </caption>
        <thead>
          <tr>
            {HISTORY_COLUMNS.map((key) => (
              <th key={key} scope="col" style={cellStyle}>
                {text(key)}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {history.length === 0 ? (
            <tr>
              <td colSpan={HISTORY_COLUMNS.length}>{text('noHistory')}</td>
            </tr>
          ) : (
            history.map((event) => {
              const values = eventValues(event, locale, reasonLang, text);
              return (
                <tr key={`${event.organizationId}:${event.subject}:${event.sequence}`}>
                  {HISTORY_COLUMNS.map((key, index) =>
                    index === 0 ? (
                      <th key={key} scope="row" style={cellStyle}>
                        {values[index]}
                      </th>
                    ) : (
                      <td key={key} style={cellStyle}>
                        {values[index]}
                      </td>
                    ),
                  )}
                </tr>
              );
            })
          )}
        </tbody>
      </table>
    </AdminTableRegion>
  );

  // On phones each event becomes its own labelled card table. Real table,
  // caption and th scope semantics plus the full column set are preserved, as
  // decision 0004 requires for a card rendering of tabular clinical data.
  const phoneCards = (
    <div className="card-list" role="region" aria-label={text('staffHistory')} tabIndex={0}>
      {history.length === 0 ? (
        <p>{text('noHistory')}</p>
      ) : (
        history.map((event) => {
          const values = eventValues(event, locale, reasonLang, text);
          return (
            <table
              key={`${event.organizationId}:${event.subject}:${event.sequence}`}
              style={{ borderCollapse: 'collapse', inlineSize: '100%' }}
            >
              <caption style={{ textAlign: 'start', paddingBlock: tokens.spacing.sm }}>
                {`${text('sequence')} ${event.sequence}: ${adminText(locale, staffEventMessageKey[event.kind])}`}
              </caption>
              <tbody>
                {HISTORY_COLUMNS.map((key, index) => (
                  <tr key={key}>
                    <th scope="row" style={cellStyle}>
                      {text(key)}
                    </th>
                    <td style={cellStyle}>{values[index]}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          );
        })
      )}
    </div>
  );

  return (
    <section
      aria-label={text('staffHistory')}
      className="page"
      data-breakpoint={breakpoint}
      style={{
        display: 'grid',
        gap: tokens.spacing.md,
        minInlineSize: 0,
        overflowWrap: 'anywhere',
      }}
    >
      <h3>{text('staffHistory')}</h3>
      <p>
        {text('subject')}: <MixedScriptText text={subject} lang="en" />
      </p>
      {deactivated ? (
        <p role="status">
          <StateBadge glyph="×" label={text('eventDeactivated')} tone="deactivated" />
        </p>
      ) : null}
      <p>
        {text('currentRoles')}:{' '}
        {roles.length > 0 ? (
          <MixedScriptText text={roles.join(', ')} lang="en" />
        ) : (
          <MixedScriptText text={text('noRoles')} lang={locale} />
        )}
      </p>
      {compact ? phoneCards : wideTable}
    </section>
  );
}
