import type { Locale } from '../../i18n/messages';
import { useTheme } from '../../theme/ThemeProvider';
import { useBreakpoint } from '../../theme/useBreakpoint';
import type { NoteRevision } from './domain';
import { chartText } from './messages';

/** Props for the append-only revision history shown beside the active note. */
export interface RevisionHistoryProps {
  revisions: readonly NoteRevision[];
  locale: Locale;
}

/** Logical wrapping keeps a long clinical value readable without clipping it. */
const WRAPPING_STYLE = {
  minInlineSize: 0,
  overflowWrap: 'anywhere',
  whiteSpace: 'normal',
} as const;

/**
 * List preserved revisions with their state and entered-in-error marker.
 *
 * On a compact viewport the revisions render as cards using the shared
 * `.card-list` class, so no field needs a horizontal scroll; from the medium
 * breakpoint upward the original grid list is kept. The DOM, labels and
 * revision identity are identical at every breakpoint and in both directions:
 * width changes presentation only, never the recorded clinical data (WEB-07,
 * UX-01). No clinical field is hidden with `display: none`.
 */
export function RevisionHistory({ revisions, locale }: RevisionHistoryProps) {
  const { tokens } = useTheme();
  const breakpoint = useBreakpoint();

  if (revisions.length === 0) {
    return (
      <p data-breakpoint={breakpoint} style={WRAPPING_STYLE}>
        {chartText(locale, 'noEntries')}
      </p>
    );
  }

  // One layout decision keeps the responsive structure explicit and gives a
  // single branch to prove: cards on compact, the original grid list otherwise.
  const layout =
    breakpoint === 'compact'
      ? ({ view: 'cards', className: 'card-list', style: undefined } as const)
      : ({
          view: 'list',
          className: undefined,
          style: { display: 'grid', gap: tokens.spacing.sm },
        } as const);

  return (
    <ol
      aria-label={chartText(locale, 'history')}
      className={layout.className}
      data-breakpoint={breakpoint}
      data-revision-view={layout.view}
      style={layout.style}
    >
      {revisions.map((revision) => (
        <li
          key={`${revision.organizationId}:${revision.id}`}
          data-revision-id={revision.id}
          style={WRAPPING_STYLE}
        >
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
