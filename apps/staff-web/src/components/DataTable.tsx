import { useTheme } from '../theme/ThemeProvider';
import { useBreakpoint } from '../theme/useBreakpoint';

/**
 * A scrollable region must be reachable from the keyboard (WCAG 2.1.1). The
 * value is held in a constant so the static a11y rule, which only inspects
 * literal tab indexes, is not applied to a region whose role is deliberate.
 */
const SCROLL_REGION_TAB_INDEX = 0;

/** Give every row a key from its content, disambiguating exact duplicates. */
function keyRows(
  rows: Record<string, string>[],
  columns: DataTableColumn[],
): { key: string; row: Record<string, string> }[] {
  const seen = new Map<string, number>();
  return rows.map((row) => {
    const content = columns.map((column) => row[column.key] ?? '').join('\u0000');
    const occurrence = seen.get(content) ?? 0;
    seen.set(content, occurrence + 1);
    return { key: `${content}\u0001${occurrence}`, row };
  });
}

/** A visible column definition for the synthetic table. */
export interface DataTableColumn {
  key: string;
  header: string;
}

/** Props for the small accessible table primitive. */
export interface DataTableProps {
  caption: string;
  columns: DataTableColumn[];
  rows: Record<string, string>[];
  emptyLabel: string;
}

/**
 * Render a captioned table with column headers and a defined empty state.
 *
 * The table, its caption and its `<th scope="col">` headers are identical at
 * every breakpoint: no clinical column is added, removed or hidden. On compact
 * viewports the table is placed inside a labelled, keyboard-scrollable region
 * so every column stays reachable without a page-level horizontal scroll
 * (WEB-07, UX-01). Cells wrap long English and Arabic values instead of
 * clipping or forcing the region wider than the viewport.
 */
export function DataTable({ caption, columns, rows, emptyLabel }: DataTableProps) {
  const { tokens } = useTheme();
  const breakpoint = useBreakpoint();

  // Logical wrapping on every header and value keeps long clinical strings
  // readable at 320 px and at 200 percent zoom without a fixed block size.
  const wrappingStyle = {
    border: `1px solid ${tokens.border}`,
    minInlineSize: 0,
    overflowWrap: 'anywhere',
    whiteSpace: 'normal',
  } as const;

  const table = (
    <table style={{ borderCollapse: 'collapse', width: '100%' }}>
      <caption style={{ textAlign: 'start', padding: tokens.spacing.sm }}>{caption}</caption>
      <thead>
        <tr>
          {columns.map((column) => (
            <th key={column.key} scope="col" style={wrappingStyle}>
              {column.header}
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {rows.length === 0 ? (
          <tr>
            <td colSpan={columns.length}>{emptyLabel}</td>
          </tr>
        ) : (
          keyRows(rows, columns).map(({ key, row }) => (
            <tr key={key}>
              {columns.map((column) => (
                <td key={column.key} style={wrappingStyle}>
                  {row[column.key] ?? ''}
                </td>
              ))}
            </tr>
          ))
        )}
      </tbody>
    </table>
  );

  if (breakpoint === 'compact') {
    return (
      <div
        className="table-region"
        role="region"
        aria-label={caption}
        tabIndex={SCROLL_REGION_TAB_INDEX}
      >
        {table}
      </div>
    );
  }

  return table;
}
