import { MixedScriptText } from '../../components/MixedScriptText';
import { formatInstant } from '../../i18n/format';
import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
import { AdminTableRegion } from './AdminTableRegion';
import type { StaffHistoryEvent } from './domain';
import { currentRoles, historyFor, isDeactivated } from './domain';
import { adminText, staffEventMessageKey } from './messages';
import { StateBadge } from './StateBadge';

/** Shape cues for the three append-only staff event kinds. */
const eventGlyph: Record<StaffHistoryEvent['kind'], string> = {
  invited: '○',
  role_granted: '＋',
  deactivated: '×',
};

/** Props for one subject's append-only membership history. */
export interface StaffHistoryViewProps {
  events: readonly StaffHistoryEvent[];
  organizationId: string;
  subject: string;
}

/** Show grantor, effective date and deactivation as immutable events. */
export function StaffHistoryView({ events, organizationId, subject }: StaffHistoryViewProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const text = (key: Parameters<typeof adminText>[1]) => adminText(locale, key);
  const history = historyFor(events, organizationId, subject);
  const deactivated = isDeactivated(history);
  const roles = currentRoles(history);

  return (
    <section aria-label={text('staffHistory')} style={{ display: 'grid', gap: tokens.spacing.md }}>
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
      <AdminTableRegion label={text('staffHistory')}>
        <table style={{ borderCollapse: 'collapse', width: '100%' }}>
          <caption style={{ textAlign: 'start', paddingBlock: tokens.spacing.sm }}>
            {text('staffHistory')}
          </caption>
          <thead>
            <tr>
              <th scope="col">{text('sequence')}</th>
              <th scope="col">{text('subject')}</th>
              <th scope="col">{text('event')}</th>
              <th scope="col">{text('actor')}</th>
              <th scope="col">{text('grantor')}</th>
              <th scope="col">{text('role')}</th>
              <th scope="col">{text('effectiveAtUtc')}</th>
              <th scope="col">{text('deactivationReason')}</th>
            </tr>
          </thead>
          <tbody>
            {history.length === 0 ? (
              <tr>
                <td colSpan={8}>{text('noHistory')}</td>
              </tr>
            ) : (
              history.map((event) => (
                <tr key={`${event.organizationId}:${event.subject}:${event.sequence}`}>
                  <th scope="row">{event.sequence}</th>
                  <td>
                    <MixedScriptText text={event.subject} lang="en" />
                  </td>
                  <td>
                    <StateBadge
                      glyph={eventGlyph[event.kind]}
                      label={adminText(locale, staffEventMessageKey[event.kind])}
                      tone={event.kind}
                    />
                  </td>
                  <td>
                    <MixedScriptText text={event.actor} lang="en" />
                  </td>
                  <td>
                    <MixedScriptText
                      text={event.kind === 'role_granted' ? event.actor : text('notApplicable')}
                      lang={event.kind === 'role_granted' ? 'en' : locale}
                    />
                  </td>
                  <td>
                    <MixedScriptText
                      text={event.kind === 'role_granted' ? event.role : text('notApplicable')}
                      lang={event.kind === 'role_granted' ? 'en' : locale}
                    />
                  </td>
                  <td>
                    <time dateTime={event.effectiveFrom}>
                      {formatInstant(event.effectiveFrom, 'UTC', locale)}
                    </time>
                  </td>
                  <td>
                    <MixedScriptText
                      text={event.kind === 'deactivated' ? event.reason : text('notApplicable')}
                      lang={event.kind === 'deactivated' ? 'en' : locale}
                    />
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </AdminTableRegion>
    </section>
  );
}
