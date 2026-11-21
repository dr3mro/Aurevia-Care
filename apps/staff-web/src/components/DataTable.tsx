import { useTheme } from '../theme/ThemeProvider';
import { useBreakpoint } from '../theme/useBreakpoint';

/** A visible column definition for the synthetic table. */
export interface DataTableColumn {
  key: string;
  header: string;
}

/** Props for the small accessible table primitive. */
export interface DataTableProps {
  caption: string;
  columns: DataTableColumn[];
  rows: Array<Record<string, string>>;
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
          rows.map((row, rowIndex) => (
            <tr key={rowIndex}>
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
      <div className="table-region" role="region" aria-label={caption} tabIndex={0}>
        {table}
      </div>
    );
  }

  return table;
}
