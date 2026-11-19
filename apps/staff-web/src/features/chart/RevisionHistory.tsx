import type { Locale } from '../../i18n/messages';
import { useTheme } from '../../theme/ThemeProvider';
import type { NoteRevision } from './domain';
import { chartText } from './messages';

/** Props for the append-only revision history shown beside the active note. */
export interface RevisionHistoryProps {
  revisions: readonly NoteRevision[];
  locale: Locale;
}

/** List preserved revisions with their state and entered-in-error marker. */
export function RevisionHistory({ revisions, locale }: RevisionHistoryProps) {
  const { tokens } = useTheme();
  if (revisions.length === 0) {
    return <p>{chartText(locale, 'noEntries')}</p>;
  }
  return (
    <ol aria-label={chartText(locale, 'history')} style={{ display: 'grid', gap: tokens.spacing.sm }}>
      {revisions.map((revision) => (
        <li key={`${revision.organizationId}:${revision.id}`} data-revision-id={revision.id}>
          {`${revision.kind} · v${revision.version} · ${
            revision.state === 'draft'
              ? chartText(locale, 'noteDraft')
              : chartText(locale, 'noteSigned')
          }`}
          {revision.enteredInError ? (
            <span style={{ paddingInline: tokens.spacing.sm }}>
              {chartText(locale, 'enteredInError')}
            </span>
          ) : null}
        </li>
      ))}
    </ol>
  );
}
