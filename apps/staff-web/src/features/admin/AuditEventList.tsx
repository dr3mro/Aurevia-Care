import { useState } from 'react';
import type { ReactNode } from 'react';
import { MixedScriptText } from '../../components/MixedScriptText';
import { formatInstant } from '../../i18n/format';
import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
import { useBreakpoint } from '../../theme/useBreakpoint';
import { AdminTableRegion } from './AdminTableRegion';
import type { AuditEvent } from './domain';
import { adminChainFor, verifyChain } from './domain';
import { adminText, outcomeMessageKey } from './messages';
import { StateBadge } from './StateBadge';

/** Keyboard-focusable scroll regions join the natural tab order. */
const FOCUSABLE_TAB_INDEX = 0;

/** Shape cues for the machine-readable outcomes. */
const outcomeGlyph: Record<AuditEvent['outcome'], string> = {
  SUCCESS: '✓',
  DENIED: '×',
  NOT_FOUND: '?',
  CONFLICT: '!',
};

/** Props for the tenant-scoped audit list and its optional chain-integrity badge. */
export interface AuditEventListProps {
  events: readonly AuditEvent[];
  organizationId: string;
  /** Show the synthetic hash-chain verdict; real API pages have no chain hash. */
  showChain?: boolean;
  /** IANA zone for the visible instant; the UTC instant stays on hover or focus. */
  timeZone?: string;
}

/** Show actor, purpose, scope, correlation, outcome and optional chain status. */
export function AuditEventList({
  events,
  organizationId,
  showChain = true,
  timeZone = 'UTC',
}: AuditEventListProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const breakpoint = useBreakpoint();
  const [focusedKey, setFocusedKey] = useState<string | undefined>(undefined);
  const text = (key: Parameters<typeof adminText>[1]) => adminText(locale, key);
  const chain = adminChainFor(events, organizationId);
  const verified = verifyChain(chain);

  /**
   * Render the occurrence instant once for both the table and the card layout.
   * The UTC value stays reachable on keyboard focus, never hover alone, and an
   * absent instant reads as "unknown" rather than an invented time.
   */
  function renderInstant(event: AuditEvent, key: string): ReactNode {
    const utc =
      event.occurredAt === '' ? text('unknown') : formatInstant(event.occurredAt, 'UTC', locale);
    const visible =
      event.occurredAt === '' ? text('unknown') : formatInstant(event.occurredAt, timeZone, locale);
    const focused = focusedKey === key;
    const focusProps = {
      onFocus: () => setFocusedKey(key),
      onBlur: () => setFocusedKey(undefined),
    };
    return (
      <>
        <time
          {...focusProps}
          dateTime={event.occurredAt === '' ? undefined : event.occurredAt}
          tabIndex={FOCUSABLE_TAB_INDEX}
          title={`${text('utcLabel')}: ${utc}`}
          aria-describedby={focused ? `audit-utc-${event.sequence}` : undefined}
        >
          {visible}
        </time>
        {focused ? (
          <span id={`audit-utc-${event.sequence}`}>
            {text('utcLabel')}: {utc}
          </span>
        ) : null}
      </>
    );
  }

  /**
   * Build the labelled field/value pairs of one event card. Every API column
   * appears in the card, so reflowing to a phone never hides a clinical value
   * (UX-01); the label and its value are separate elements in the reading order.
   */
  function cardFields(event: AuditEvent, key: string): { label: string; content: ReactNode }[] {
    return [
      { label: text('sequence'), content: event.sequence },
      { label: text('actor'), content: <MixedScriptText text={event.actor} lang="en" /> },
      { label: text('purpose'), content: <MixedScriptText text={event.purpose} lang="en" /> },
      { label: text('scope'), content: <MixedScriptText text={event.scope} lang="en" /> },
      {
        label: text('correlationId'),
        content: <MixedScriptText text={event.correlationId} lang="en" />,
      },
      {
        label: text('outcome'),
        content: (
          <StateBadge
            glyph={outcomeGlyph[event.outcome]}
            label={adminText(locale, outcomeMessageKey[event.outcome])}
            tone={event.outcome === 'SUCCESS' ? 'success' : 'denied'}
          />
        ),
      },
      { label: text('action'), content: <MixedScriptText text={event.action} lang="en" /> },
      { label: text('resource'), content: <MixedScriptText text={event.resourceId} lang="en" /> },
      { label: text('occurredAt'), content: renderInstant(event, key) },
    ];
  }

  return (
    <section aria-label={text('auditEvents')} style={{ display: 'grid', gap: tokens.spacing.md }}>
      <h3>{text('auditEvents')}</h3>
      {showChain ? <ChainStatus chain={chain} verified={verified} text={text} /> : null}
      {breakpoint === 'compact' ? (
        <AuditCardList
          chain={chain}
          organizationId={organizationId}
          text={text}
          cardFields={cardFields}
        />
      ) : (
        <AuditTable
          chain={chain}
          organizationId={organizationId}
          text={text}
          renderInstant={renderInstant}
        />
      )}
    </section>
  );
}

type TextFn = (key: Parameters<typeof adminText>[1]) => string;
type AuditChain = ReturnType<typeof adminChainFor>;

/** The hash-chain verdict badge and its broken-chain hint. */
function ChainStatus({
  chain,
  verified,
  text,
}: {
  chain: AuditChain;
  verified: boolean;
  text: TextFn;
}) {
  return (
    <>
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
    </>
  );
}

/** Props shared by the two audit layouts. */
interface AuditLayoutProps {
  chain: AuditChain;
  organizationId: string;
  text: TextFn;
}

/** Phone layout: one definition-list card per event. */
function AuditCardList(
  props: AuditLayoutProps & {
    cardFields: (event: AuditEvent, key: string) => { label: string; content: ReactNode }[];
  },
) {
  const { chain, organizationId, text, cardFields } = props;
  const { tokens } = useTheme();
  return (
    <ol
      className="card-list"
      aria-label={text('auditEvents')}
      style={{ listStyle: 'none', margin: 0, paddingInlineStart: 0 }}
    >
      {props.chain.length === 0 ? (
        <li>{text('noEvents')}</li>
      ) : (
        chain.map((event) => {
          const key = `${organizationId}:${event.sequence}`;
          return (
            <li key={key}>
              <dl style={{ display: 'grid', gap: tokens.spacing.sm, margin: 0 }}>
                {cardFields(event, key).map((field) => (
                  <div
                    key={field.label}
                    style={{ display: 'grid', gap: tokens.spacing.sm, minInlineSize: 0 }}
                  >
                    <dt>{field.label}</dt>
                    <dd style={{ margin: 0, overflowWrap: 'anywhere', whiteSpace: 'normal' }}>
                      {field.content}
                    </dd>
                  </div>
                ))}
              </dl>
            </li>
          );
        })
      )}
    </ol>
  );
}

/** Table layout: one row per event. */
function AuditTable(
  props: AuditLayoutProps & {
    renderInstant: (event: AuditEvent, key: string) => ReactNode;
  },
) {
  const { chain, organizationId, text, renderInstant } = props;
  const { locale } = useI18n();
  const { tokens } = useTheme();
  return (
    <AdminTableRegion label={text('auditEvents')}>
      <table style={{ borderCollapse: 'collapse', inlineSize: '100%' }}>
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
            chain.map((event) => {
              const key = `${organizationId}:${event.sequence}`;
              return (
                <tr key={key}>
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
                  <td>{renderInstant(event, key)}</td>
                </tr>
              );
            })
          )}
        </tbody>
      </table>
    </AdminTableRegion>
  );
}
