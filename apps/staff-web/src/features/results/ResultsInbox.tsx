import { DataTable } from '../../components/DataTable';
import type { DataTableColumn } from '../../components/DataTable';
import { displayLocalName, formatCivilDate } from '../../i18n/format';
import type { Locale } from '../../i18n/messages';
import { useTheme } from '../../theme/ThemeProvider';
import {
  kindMarkers,
  kindMessageKeys,
  resultsText,
  reviewMessageKeys,
} from './resultsMessages';
import type { InboxRow } from './types';

/** Props for the inbox card. */
export interface ResultsInboxProps {
  rows: readonly InboxRow[];
  locale: Locale;
}

/**
 * Render the results inbox as a real captioned table.
 *
 * `DataTable` cells are strings, so the kind marker is a word plus a
 * shape-distinct glyph rather than a coloured pill. No clinical column is
 * hidden at any width; on compact viewports `DataTable` supplies its own
 * labelled scroll region.
 */
export function ResultsInbox({ rows, locale }: ResultsInboxProps) {
  const { tokens } = useTheme();
  const columns: DataTableColumn[] = [
    { key: 'patient', header: resultsText(locale, 'colPatient') },
    { key: 'report', header: resultsText(locale, 'colReport') },
    { key: 'source', header: resultsText(locale, 'colSource') },
    { key: 'kind', header: resultsText(locale, 'colKind') },
    { key: 'review', header: resultsText(locale, 'colReview') },
    { key: 'owner', header: resultsText(locale, 'colOwner') },
  ];
  const tableRows = rows.map((row) => ({
    patient: `${displayLocalName(row.patient, locale)} · ${row.mrn}`,
    report: `${row.reportName} · ${formatCivilDate(row.reportDate, locale)}`,
    source: row.source,
    kind: `${kindMarkers[row.kind]} ${resultsText(locale, kindMessageKeys[row.kind])}`,
    review: resultsText(locale, reviewMessageKeys[row.reviewState]),
    owner: row.owner,
  }));

  return (
    <section
      aria-label={resultsText(locale, 'inboxTitle')}
      style={{
        border: `1px solid ${tokens.border}`,
        borderRadius: tokens.spacing.sm,
        padding: tokens.spacing.lg,
        display: 'grid',
        gap: tokens.spacing.sm,
        minInlineSize: 0,
        overflowWrap: 'anywhere',
      }}
    >
      <h3>{resultsText(locale, 'inboxTitle')}</h3>
      <p>{resultsText(locale, 'reviewSeparate')}</p>
      <DataTable
        caption={resultsText(locale, 'inboxTitle')}
        columns={columns}
        rows={tableRows}
        emptyLabel={resultsText(locale, 'emptyRows')}
      />
    </section>
  );
}
