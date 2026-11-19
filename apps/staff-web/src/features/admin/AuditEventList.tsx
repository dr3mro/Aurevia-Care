import { MixedScriptText } from '../../components/MixedScriptText';
import { formatInstant } from '../../i18n/format';
import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
import type { AuditEvent } from './domain';
import { adminChainFor, verifyChain } from './domain';
import { adminText, outcomeMessageKey } from './messages';
import { StateBadge } from './StateBadge';

/** Shape cues for the two machine-readable outcomes. */
const outcomeGlyph: Record<AuditEvent['outcome'], string> = {
  SUCCESS: '✓',
  DENIED: '×',
};

/** Props for the tenant-scoped audit list and its chain-integrity badge. */
export interface AuditEventListProps {
  events: readonly AuditEvent[];
  organizationId: string;
}

/** Show actor, purpose, scope, correlation, outcome and chain status. */
export function AuditEventList({ events, organizationId }: AuditEventListProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const text = (key: Parameters<typeof adminText>[1]) => adminText(locale, key);
  const chain = adminChainFor(events, organizationId);
  const verified = verifyChain(chain);

  return (
    <section aria-label={text('auditEvents')} style={{ display: 'grid', gap: tokens.spacing.md }}>
      <h3>{text('auditEvents')}</h3>
      {chain.length > 0 ? (
        <StateBadge
          glyph={verified ? '✓' : '!'}
          label={text(verified ? 'chainVerified' : 'chainNotVerified')}
          tone={verified ? 'verified' : 'broken'}
          testId="chain-badge"
        />
      ) : (
        <StateBadge
          glyph="—"
          label={text('chainNotApplicable')}
          tone="not-applicable"
          testId="chain-badge"
        />
      )}
      {!verified && chain.length > 0 ? <p role="status">{text('chainBrokenHint')}</p> : null}
      <table style={{ borderCollapse: 'collapse', width: '100%' }}>
        <caption style={{ textAlign: 'start', paddingBlock: tokens.spacing.sm }}>
          {text('auditEvents')}
        </caption>
        <thead>
          <tr>
            <th scope="col">{text('sequence')}</th>
            <th scope="col">{text('actor')}</th>
            <th scope="col">{text('purpose')}</th>
            <th scope="col">{text('scope')}</th>
            <th scope="col">{text('correlationId')}</th>
            <th scope="col">{text('outcome')}</th>
            <th scope="col">{text('action')}</th>
            <th scope="col">{text('resource')}</th>
            <th scope="col">{text('occurredAt')}</th>
          </tr>
        </thead>
        <tbody>
          {chain.length === 0 ? (
            <tr>
              <td colSpan={9}>{text('noEvents')}</td>
            </tr>
          ) : (
            chain.map((event) => (
              <tr key={`${event.organizationId}:${event.sequence}`}>
                <th scope="row">{event.sequence}</th>
                <td>
                  <MixedScriptText text={event.actor} lang="en" />
                </td>
                <td>
                  <MixedScriptText text={event.purpose} lang="en" />
                </td>
                <td>
                  <MixedScriptText text={event.scope} lang="en" />
                </td>
                <td>
                  <MixedScriptText text={event.correlationId} lang="en" />
                </td>
                <td>
                  <StateBadge
                    glyph={outcomeGlyph[event.outcome]}
                    label={adminText(locale, outcomeMessageKey[event.outcome])}
                    tone={event.outcome === 'SUCCESS' ? 'success' : 'denied'}
                  />
                </td>
                <td>
                  <MixedScriptText text={event.action} lang="en" />
                </td>
                <td>
                  <MixedScriptText text={event.resourceId} lang="en" />
                </td>
                <td>
                  <time dateTime={event.occurredAt}>
                    {formatInstant(event.occurredAt, 'UTC', locale)}
                  </time>
                </td>
              </tr>
            ))
          )}
        </tbody>
      </table>
    </section>
  );
}
