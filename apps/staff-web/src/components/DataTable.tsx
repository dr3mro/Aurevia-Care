import { useTheme } from '../theme/ThemeProvider';

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

/** Render a captioned table with column headers and a defined empty state. */
export function DataTable({ caption, columns, rows, emptyLabel }: DataTableProps) {
  const { tokens } = useTheme();
  return (
    <table style={{ borderCollapse: 'collapse', width: '100%' }}>
      <caption style={{ textAlign: 'start', padding: tokens.spacing.sm }}>{caption}</caption>
      <thead>
        <tr>
          {columns.map((column) => (
            <th key={column.key} scope="col" style={{ border: `1px solid ${tokens.border}` }}>
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
                <td key={column.key} style={{ border: `1px solid ${tokens.border}` }}>
                  {row[column.key] ?? ''}
                </td>
              ))}
            </tr>
          ))
        )}
      </tbody>
    </table>
  );
}
