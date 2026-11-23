import { useState } from 'react';

import { Button } from '../../components/Button';
import { MixedScriptText } from '../../components/MixedScriptText';
import { annotatedName } from '../../i18n/format';
import type { Locale } from '../../i18n/messages';
import { useTheme } from '../../theme/ThemeProvider';
import { isOverdue, overdueMinutes, readBackError, shouldAnnounceCritical } from './domain';
import {
  formatClockTime,
  resultsMessage,
  resultsText,
  type ResultsMessageKey,
} from './resultsMessages';
import type { CriticalFinding } from './types';

/** Props for one critical finding card. */
export interface FindingCardProps {
  finding: CriticalFinding;
  locale: Locale;
  now: string;
  timeZone: string;
  onReadBack: (id: string) => void;
  onAcknowledge: (id: string) => void;
  onEscalate: (id: string) => void;
}

/**
 * Render one verified critical finding with its full escalation trail.
 *
 * The panel is a persistent in-flow region: nothing here acknowledges the
 * finding. Criticality and overdue state are carried by words, distinct icons
 * and data attributes, never by colour alone, because the available theme
 * tokens have no critical-status group yet.
 */
export function FindingCard({
  finding,
  locale,
  now,
  timeZone,
  onReadBack,
  onAcknowledge,
  onEscalate,
}: FindingCardProps) {
  const { tokens } = useTheme();
  const [errorKey, setErrorKey] = useState<ResultsMessageKey | undefined>(undefined);
  const nameRun = annotatedName(finding.patient, locale);
  const overdue = isOverdue(finding, now);
  const minutesLate = overdueMinutes(finding, now);
  const deadlineText = !overdue
    ? resultsText(locale, 'onTime')
    : minutesLate === 0
      ? resultsText(locale, 'dueNow')
      : resultsMessage(locale, 'overdue', { minutes: String(minutesLate) });

  // A read-back is a separate clinical record. Fail closed when the
  // authenticated finding does not carry a confirmed recipient.
  function handleReadBack() {
    const error = readBackError(finding.readBackRecipient);
    setErrorKey(error);
    if (error === undefined) {
      onReadBack(finding.id);
    }
  }

  return (
    <li
      data-testid="finding-card"
      data-critical="true"
      data-overdue={overdue}
      style={{
        border: `2px solid ${overdue ? tokens.foreground : tokens.border}`,
        borderRadius: tokens.spacing.sm,
        padding: tokens.spacing.lg,
        display: 'grid',
        gap: tokens.spacing.sm,
        listStyle: 'none',
        minInlineSize: 0,
        overflowWrap: 'anywhere',
      }}
    >
      <div className="cluster" style={{ justifyContent: 'space-between' }}>
        <span className="cluster">
          <span aria-hidden="true">▲</span>
          <span>{resultsText(locale, 'criticalIconLabel')}</span>
        </span>
        <span className="cluster">
          <span>{resultsText(locale, 'dueLabel')}</span>
          {overdue ? (
            <span role="img" aria-label={resultsText(locale, 'overdueIconLabel')}>
              ⚠
            </span>
          ) : null}
          <time dateTime={finding.dueAt} style={overdue ? { fontWeight: 700 } : undefined}>
            {formatClockTime(finding.dueAt, timeZone, locale)}
          </time>
          <strong>{deadlineText}</strong>
        </span>
      </div>
      <p>
        <strong>{finding.testName}</strong>: <span>{finding.value}</span>
      </p>
      <p style={{ color: tokens.foreground }}>
        <MixedScriptText text={nameRun.text} lang={nameRun.lang} />
        {' · '}
        <span>{finding.mrn}</span>
        {' · '}
        <code dir="ltr">{finding.accession}</code>
        {' · '}
        <span>
          {resultsText(locale, 'eventVerified')} <time dateTime={finding.verifiedAt}>
            {formatClockTime(finding.verifiedAt, timeZone, locale)}
          </time>
        </span>
      </p>
      <ul
        style={{
          listStyle: 'none',
          margin: 0,
          padding: 0,
          display: 'grid',
          gap: tokens.spacing.sm,
        }}
      >
        {finding.events.map((event) => (
          <li key={event.id} data-testid="finding-event">
            <span aria-hidden="true">•</span>{' '}
            <time dateTime={event.at}>
              <strong>{formatClockTime(event.at, timeZone, locale)}</strong>
            </time>{' '}
            <span>{resultsMessage(locale, event.descriptionKey, event.values)}</span>
          </li>
        ))}
      </ul>
      <p>
        {resultsText(locale, 'ownerLabel')}: {finding.owner ?? resultsText(locale, 'noOwner')} ·{' '}
        {resultsText(locale, 'coverageLabel')}: {finding.coverage}
      </p>
      {errorKey === undefined ? null : (
        <p role="alert" style={{ color: tokens.foreground }}>
          {resultsText(locale, errorKey)}
        </p>
      )}
      <div className="cluster">
        <Button onClick={handleReadBack}>{resultsText(locale, 'actionReadBack')}</Button>
        <Button variant="secondary" onClick={() => onAcknowledge(finding.id)}>
          {resultsText(locale, 'actionAcknowledge')}
        </Button>
        <Button variant="secondary" onClick={() => onEscalate(finding.id)}>
          {resultsText(locale, 'actionEscalate')}
        </Button>
      </div>
    </li>
  );
}

/** Props for the full critical-findings panel. */
export interface CriticalFindingsProps extends Omit<FindingCardProps, 'finding'> {
  findings: readonly CriticalFinding[];
}

/** Render the persistent critical queue with its count and escalation policy. */
export function CriticalFindings({ findings, locale, now, timeZone, onReadBack, onAcknowledge, onEscalate }: CriticalFindingsProps) {
  const { tokens } = useTheme();
  const heading = resultsText(locale, 'criticalTitle');
  const count = resultsMessage(locale, 'criticalCount', { count: String(findings.length) });

  // One polite announcement of the same visible text on load, never a focus
  // move. Typing in a field takes priority, so the announcement is suppressed.
  // This is derived, not stored: an effect that wrote state on mount caused a
  // cascading render, and React's compiler rules forbid that pattern.
  const announcement = shouldAnnounceCritical(document.activeElement)
    ? `${heading}: ${count}`
    : '';

  return (
    <section
      aria-label={resultsText(locale, 'criticalTitle')}
      data-testid="critical-panel"
      style={{
        border: `2px solid ${tokens.foreground}`,
        borderRadius: tokens.spacing.sm,
        padding: tokens.spacing.lg,
        display: 'grid',
        gap: tokens.spacing.md,
        minInlineSize: 0,
        overflowWrap: 'anywhere',
      }}
    >
      <p
        role="status"
        style={{
          position: 'absolute',
          inlineSize: '1px',
          blockSize: '1px',
          overflow: 'hidden',
          clipPath: 'inset(50%)',
          whiteSpace: 'nowrap',
        }}
      >
        {announcement}
      </p>
      <div className="cluster" style={{ justifyContent: 'space-between' }}>
        <h3 className="cluster">
          <span aria-hidden="true">◆</span>
          {heading}
        </h3>
        <span data-testid="critical-count">{count}</span>
        <p>{resultsText(locale, 'escalationPolicy')}</p>
      </div>
      {findings.length === 0 ? (
        <p>{resultsText(locale, 'noFindings')}</p>
      ) : (
        <ul
          className="grid-2"
          style={{ listStyle: 'none', margin: 0, padding: 0 }}
        >
          {findings.map((finding) => (
            <FindingCard
              key={finding.id}
              finding={finding}
              locale={locale}
              now={now}
              timeZone={timeZone}
              onReadBack={onReadBack}
              onAcknowledge={onAcknowledge}
              onEscalate={onEscalate}
            />
          ))}
        </ul>
      )}
    </section>
  );
}
